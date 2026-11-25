package skillexchange.service;

import skillexchange.exception.BookingConflictException;
import skillexchange.exception.BookingException;
import skillexchange.exception.SkillExchangeException;
import skillexchange.exception.SlotAlreadyBookedException;
import skillexchange.model.Activity;
import skillexchange.model.Booking;
import skillexchange.model.BookingStatus;
import skillexchange.model.CreditSummary;
import skillexchange.model.Listing;
import skillexchange.model.ListingType;
import skillexchange.model.SessionMode;
import skillexchange.model.Stats;
import skillexchange.model.Student;
import skillexchange.model.TimeSlot;
import skillexchange.model.TutorProfile;
import skillexchange.persistence.BookingDao;
import skillexchange.persistence.Database;
import skillexchange.persistence.LedgerDao;
import skillexchange.persistence.ListingDao;
import skillexchange.persistence.SlotDao;
import skillexchange.persistence.SqlWork;
import skillexchange.persistence.StudentDao;
import skillexchange.validation.Validator;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Logger;

/**
 * Listings, time slots and bookings, and every rule about them.
 *
 * Three things together keep a slot from being booked twice:
 *
 * 1. A lock in Java. Every method that changes data takes the write lock, so "is the slot free?"
 *    and "mark it booked" run as one step. Two threads can never both see the slot as free.
 * 2. A database transaction. All the rows a booking touches (the booking, the slot, the credit
 *    balance, the ledger) are committed together. If any check fails halfway, everything is rolled back.
 * 3. A UNIQUE index in the database that refuses a second confirmed booking for the same slot.
 *
 * Broken rules throw checked BookingException subclasses; an id that does not exist throws
 * NotFoundException.
 */
public final class BookingService {
    private static final Logger LOG = Logger.getLogger(BookingService.class.getName());
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM, HH:mm");

    private final Database db;
    private final AppClock clock;
    private final StudentDao students;
    private final ListingDao listings;
    private final SlotDao slots;
    private final BookingDao bookings;
    private final LedgerDao ledgerDao;
    private final CreditLedger ledger;

    // Many threads may read at once, but a writer works alone.
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    BookingService(Database db, AppClock clock, StudentDao students, ListingDao listings, SlotDao slots,
                   BookingDao bookings, LedgerDao ledgerDao, CreditLedger ledger) {
        this.db = db;
        this.clock = clock;
        this.students = students;
        this.listings = listings;
        this.slots = slots;
        this.bookings = bookings;
        this.ledgerDao = ledgerDao;
        this.ledger = ledger;
    }

    /** Runs the work alone (write lock) and as one database transaction. */
    private <T> T change(SqlWork<T> work) throws SkillExchangeException {
        lock.writeLock().lock();
        try {
            return db.inTransaction(work);
        } finally {
            lock.writeLock().unlock();     // always released, even when the work throws
        }
    }

    // ---------- Listings ----------

    public Listing createListing(Student tutor, ListingType type, String skill, String description,
                                 int durationMinutes, double fee, int creditCost) throws SkillExchangeException {
        if (type == null) throw new BookingException("Choose a listing type");
        String cleanSkill = Validator.skill(skill);
        String cleanDescription = Validator.description(description);
        int minutes = Validator.duration(durationMinutes);
        // Only the value that belongs to the chosen type is checked; the other one is ignored.
        double cleanFee = type == ListingType.PAID ? Validator.fee(fee) : 0;
        int cleanCost = type == ListingType.SWAP ? Validator.creditCost(creditCost) : 0;
        Listing draft = Listing.of(type, 0, tutor, cleanSkill, cleanDescription, minutes, cleanFee, cleanCost);
        Listing saved = change(c -> listings.require(c, listings.insert(c, draft, clock.now())));
        LOG.info(() -> tutor.getName() + " created listing " + saved.getId() + ": " + saved);
        return saved;
    }

    /**
     * Deletes a listing together with its open time slots. Only its own tutor may do this, and not
     * while a learner still has a confirmed booking on it. Past sessions keep their history.
     */
    public void deleteListing(Student tutor, long listingId) throws SkillExchangeException {
        change(c -> {
            Listing listing = listings.require(c, listingId);
            requireOwner(listing, tutor);
            Optional<Booking> confirmed = bookings.findConfirmedOfListing(c, listingId);
            if (confirmed.isPresent())
                throw new BookingException(confirmed.get().getLearner().getName()
                        + " still has a booking on this listing. Cancel it first.");
            slots.removeOpenOfListing(c, listingId);
            listings.deactivate(c, listingId);
            return null;
        });
        LOG.info(() -> tutor.getName() + " deleted listing " + listingId);
    }

    public List<Listing> listingsOf(Student tutor) {
        return db.query(c -> listings.findByTutor(c, tutor.getId()));
    }

    // ---------- Slots ----------

    /** A tutor publishes a time for a listing. A tutor's slots may not overlap each other. */
    public TimeSlot addSlot(Student tutor, long listingId, LocalDateTime start, SessionMode mode, String place)
            throws SkillExchangeException {
        String cleanPlace = Validator.place(mode, place);
        return change(c -> {
            Listing listing = listings.require(c, listingId);
            requireOwner(listing, tutor);
            LocalDateTime from = Validator.slotStart(start, clock.now());
            LocalDateTime to = from.plusMinutes(listing.getDurationMinutes());
            Optional<TimeSlot> clash = slots.findTutorOverlap(c, tutor.getId(), from, to);
            if (clash.isPresent())
                throw new BookingConflictException("You already have a slot at that time: " + describe(clash.get()));
            return slots.require(c, slots.insert(c, listingId, from, to, mode, cleanPlace));
        });
    }

    /**
     * Takes back a time slot nobody has booked. A booked slot cannot be removed: the booking has
     * to be cancelled first, which refunds the learner.
     */
    public void removeSlot(Student tutor, long slotId) throws SkillExchangeException {
        change(c -> {
            TimeSlot slot = slots.require(c, slotId);
            requireOwner(slot.getListing(), tutor);
            if (slot.isBooked())
                throw new BookingException("This slot is booked. Cancel the booking first, which refunds the learner.");
            slots.remove(c, slotId);
            return null;
        });
    }

    public List<TimeSlot> slotsOf(Student tutor) {
        return db.query(c -> slots.findByTutor(c, tutor.getId()));
    }

    /** Open sessions a learner can still book, soonest first. */
    public List<TimeSlot> openSlots(String text, ListingType type) {
        return db.query(c -> slots.findOpenUpcoming(c, clock.now(), text, type));
    }

    // ---------- Bookings ----------

    /**
     * Books a slot. Every check runs inside the transaction and before anything is committed, so a
     * failed booking leaves nothing behind.
     */
    public Booking book(Student learner, long slotId) throws SkillExchangeException {
        Booking booking = change(c -> {
            TimeSlot slot = slots.require(c, slotId);
            LocalDateTime now = clock.now();

            if (slot.getTutor().equals(learner))
                throw new BookingConflictException("You cannot book your own session");
            if (slot.isBooked())
                throw new SlotAlreadyBookedException(slotId);
            if (!slot.getStart().isAfter(now))
                throw new BookingException("This session has already started and can no longer be booked");

            // A learner cannot take two sessions at once, or attend one while teaching another.
            Optional<Booking> mine = bookings.findLearnerOverlap(c, learner.getId(), slot.getStart(), slot.getEnd());
            if (mine.isPresent())
                throw new BookingConflictException("You already have a booking at that time: " + describe(mine.get().getSlot()));
            Optional<TimeSlot> teaching = slots.findTeachingOverlap(c, learner.getId(), slot.getStart(), slot.getEnd());
            if (teaching.isPresent())
                throw new BookingConflictException("You are teaching at that time: " + describe(teaching.get()));
            // The other side of the same rule: the tutor may be attending someone else's session then.
            if (bookings.findLearnerOverlap(c, slot.getTutor().getId(), slot.getStart(), slot.getEnd()).isPresent())
                throw new BookingConflictException(slot.getTutor().getName() + " is attending another session at that time");

            int cost = slot.getListing().getCreditCost();    // polymorphic: 0 for paid and free listings
            long bookingId = bookings.insert(c, slotId, learner.getId(), cost, now);
            // If the learner cannot afford it, hold() throws and the INSERT above is rolled back.
            if (cost > 0) ledger.hold(c, learner, cost, bookingId, now);
            slots.setBooked(c, slotId, true);
            return bookings.require(c, bookingId);
        });
        LOG.info(() -> learner.getName() + " booked " + describe(booking.getSlot()) + " (booking " + booking.getId() + ")");
        return booking;
    }

    /**
     * Cancels a confirmed booking, reopens the slot and refunds any held credits.
     * Either the learner or the tutor may cancel.
     */
    public void cancel(Student actor, long bookingId) throws SkillExchangeException {
        change(c -> {
            Booking b = requireConfirmed(bookings.require(c, bookingId));
            if (!b.involves(actor))
                throw new BookingException("Only the learner or the tutor can cancel this booking");
            bookings.setStatus(c, bookingId, BookingStatus.CANCELLED);
            slots.setBooked(c, b.getSlot().getId(), false);
            ledger.refund(c, b, clock.now());
            return null;
        });
        LOG.info(() -> actor.getName() + " cancelled booking " + bookingId);
    }

    /**
     * The learner confirms that the session took place, which releases the held credits to the tutor.
     * Only the learner can do this, because they are the one paying, and only once the session has
     * started: a session that has not begun cannot have taken place.
     */
    public void complete(Student actor, long bookingId) throws SkillExchangeException {
        change(c -> {
            Booking b = requireConfirmed(bookings.require(c, bookingId));
            if (!b.isLearner(actor))
                throw new BookingException("Only the learner can confirm that the session took place");
            if (clock.now().isBefore(b.getSlot().getStart()))
                throw new BookingException("You can confirm the session once it has started");
            bookings.setStatus(c, bookingId, BookingStatus.COMPLETED);
            ledger.release(c, b, clock.now());
            return null;
        });
        LOG.info(() -> actor.getName() + " confirmed booking " + bookingId + " took place");
    }

    /** Bookings where the student is the learner or the tutor. */
    public List<Booking> bookingsOf(Student student) {
        return db.query(c -> bookings.findInvolving(c, student.getId()));
    }

    public Optional<Booking> findBooking(long bookingId) {
        return db.query(c -> bookings.findById(c, bookingId));
    }

    // ---------- Credits and numbers ----------

    public CreditSummary credits(Student student) {
        // The read lock keeps a writer out, so balance, held credits and history belong to the same moment.
        lock.readLock().lock();
        try {
            return db.query(c -> new CreditSummary(students.credits(c, student.getId()),
                    bookings.heldCredits(c, student.getId()), ledgerDao.history(c, student.getId())));
        } finally {
            lock.readLock().unlock();
        }
    }

    public Stats stats() {
        lock.readLock().lock();
        try {
            return db.query(c -> new Stats(students.count(c), listings.countActive(c),
                    slots.countOpenUpcoming(c, clock.now()), bookings.countWithStatus(c, BookingStatus.COMPLETED)));
        } finally {
            lock.readLock().unlock();
        }
    }

    /** The latest bookings, as "who booked what with whom". */
    public List<Activity> recentActivity() {
        return db.query(c -> bookings.findRecent(c, 6)).stream()
                .map(b -> new Activity(b.getLearner().getName(), b.getSlot().getListing().getSkill(),
                        b.getTutor().getName(), b.getBookedAt()))
                .toList();
    }

    /** A tutor's public profile. */
    public TutorProfile profile(long studentId) throws SkillExchangeException {
        return db.inTransaction(c -> {
            Student tutor = students.require(c, studentId);
            List<TimeSlot> open = slots.findOpenUpcoming(c, clock.now(), "", null).stream()
                    .filter(s -> s.getTutor().equals(tutor))
                    .map(TimeSlot::withoutPlace)
                    .toList();
            return new TutorProfile(tutor, listings.findByTutor(c, studentId), open, bookings.countTaughtBy(c, studentId));
        });
    }

    // ---------- Helpers ----------

    private static void requireOwner(Listing listing, Student tutor) throws BookingException {
        if (!listing.getTutor().equals(tutor))
            throw new BookingException("Only the tutor who owns this listing can change it");
    }

    private static Booking requireConfirmed(Booking b) throws BookingException {
        if (b.getStatus() != BookingStatus.CONFIRMED)
            throw new BookingException("This booking is already " + b.getStatus().name().toLowerCase());
        return b;
    }

    private static String describe(TimeSlot slot) {
        return slot.getListing().getSkill() + ", " + slot.getStart().format(WHEN);
    }
}
