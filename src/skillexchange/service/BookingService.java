package skillexchange.service;

import skillexchange.exception.*;
import skillexchange.model.*;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Keeps all students, listings, slots and bookings in memory and enforces the booking rules.
 * Unknown ids are programming errors (IllegalArgumentException). Broken booking rules throw
 * checked BookingException subclasses.
 *
 * Thread safety: every public method is synchronized on this service, so a booking's
 * check-then-act ("is the slot free? then mark it booked") runs as one step. Two threads
 * can never both see a slot as free. Getters return copies, so callers can iterate
 * without holding the lock. Lock order is always service, then ledger, which rules out deadlock.
 *
 * The whole service is Serializable, so saving it writes every student, listing, slot,
 * booking and ledger entry in one go. Shared references (the same Student object inside
 * a listing, a booking and the ledger) come back as one object after loading.
 */
public class BookingService implements Serializable {
    private static final long serialVersionUID = 1L;

    // LinkedHashMap keeps insertion order, so items print in the order they were added.
    private final Map<String, Student> students = new LinkedHashMap<>();
    private final Map<String, Listing> listings = new LinkedHashMap<>();
    private final Map<String, TimeSlot> slots = new LinkedHashMap<>();
    private final Map<String, Booking> bookings = new LinkedHashMap<>();
    private final CreditLedger ledger = new CreditLedger();

    private int nextSlotNo = 1;
    private int nextBookingNo = 1;
    private int nextListingNo = 1;   // never goes down, so a deleted listing's id is not handed out again

    // Every rule that depends on the time ("has this slot started?") asks this clock, never the system
    // directly. Normally it is the real clock; DemoData and tests can swap it to build history in the past.
    // Not serializable and not part of the saved state, so it is transient and restored in readObject.
    private transient Clock clock = Clock.systemDefaultZone();

    /** Replaces the clock the rules use. Meant for tests and for seeding demo history. */
    public synchronized void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    // ---------- Students ----------

    public synchronized void registerStudent(Student student, int initialCredits) {
        if (students.containsKey(student.getId()))
            throw new IllegalArgumentException("Student id already exists: " + student.getId());
        students.put(student.getId(), student);
        ledger.openAccount(student, initialCredits, now());
    }

    /** The ledger is thread-safe on its own; the field is final, so no lock is needed here. */
    public CreditLedger getLedger() { return ledger; }

    public synchronized List<Student> getAllStudents() {
        return new ArrayList<>(students.values());
    }

    public synchronized List<Booking> getAllBookings() {
        return new ArrayList<>(bookings.values());
    }

    /** Returns null if there is no such student. */
    public synchronized Student findStudent(String id) {
        return students.get(id);
    }

    /** Returns null if there is no such booking. */
    public synchronized Booking findBooking(String id) {
        return bookings.get(id);
    }

    /** Next unused id such as "S4". Gaps left by hand-picked ids are skipped. */
    public synchronized String nextStudentId() { return nextFreeId("S", students); }

    public synchronized String nextListingId() {
        int n = Math.max(nextListingNo, listings.size() + 1);
        while (listings.containsKey("L" + n)) n++;
        return "L" + n;
    }

    private static String nextFreeId(String prefix, Map<String, ?> map) {
        int n = map.size() + 1;
        while (map.containsKey(prefix + n)) n++;
        return prefix + n;
    }

    // ---------- Listings ----------

    public synchronized void addListing(Listing listing) {
        requireRegistered(listing.getTutor());
        if (listings.containsKey(listing.getId()))
            throw new IllegalArgumentException("Listing id already exists: " + listing.getId());
        listings.put(listing.getId(), listing);
        // Remember the highest number used, so a later listing never reuses the id of a deleted one.
        if (listing.getId().matches("L\\d{1,8}"))
            nextListingNo = Math.max(nextListingNo, Integer.parseInt(listing.getId().substring(1)) + 1);
    }

    /**
     * Deletes a listing together with its time slots. Only its own tutor may do this, and not while a
     * learner still has a confirmed booking on one of its slots. Past bookings keep their own reference
     * to the listing, so the session history and the ledger are unaffected.
     */
    public synchronized void removeListing(Student tutor, String listingId) throws BookingException {
        Listing listing = listings.get(listingId);
        if (listing == null) throw new IllegalArgumentException("No such listing: " + listingId);
        if (!listing.getTutor().equals(tutor))
            throw new BookingException("Only the tutor who owns listing " + listingId + " can delete it");
        for (Booking b : bookings.values()) {
            if (b.getStatus() == BookingStatus.CONFIRMED && b.getSlot().getListing().getId().equals(listingId))
                throw new BookingException("Listing " + listingId + " still has a confirmed booking ("
                        + b.getId() + "). Cancel it first.");
        }
        slots.values().removeIf(s -> s.getListing().getId().equals(listingId));
        listings.remove(listingId);
    }

    public synchronized List<Listing> getAllListings() {
        return new ArrayList<>(listings.values());
    }

    public synchronized List<Listing> getListingsByTutor(Student tutor) {
        List<Listing> result = new ArrayList<>();
        for (Listing l : listings.values()) {
            if (l.getTutor().equals(tutor)) result.add(l);
        }
        return result;
    }

    /** Case-insensitive partial match on the skill name. */
    public synchronized List<Listing> searchBySkill(String keyword) {
        String k = keyword.toLowerCase();
        List<Listing> result = new ArrayList<>();
        for (Listing l : listings.values()) {
            if (l.getSkill().toLowerCase().contains(k)) result.add(l);
        }
        return result;
    }

    // ---------- Slots ----------

    /** Tutor publishes a time for a listing. A tutor's slots may not overlap each other. */
    public synchronized TimeSlot addSlot(String listingId, LocalDateTime start) throws BookingConflictException {
        Listing listing = listings.get(listingId);
        if (listing == null) throw new IllegalArgumentException("No such listing: " + listingId);

        TimeSlot candidate = new TimeSlot("T" + nextSlotNo, listing, start);
        for (TimeSlot existing : slots.values()) {
            if (existing.getTutor().equals(listing.getTutor()) && existing.overlaps(candidate)) {
                throw new BookingConflictException(listing.getTutor().getName()
                        + " already has a slot at that time: " + existing.getId());
            }
        }
        nextSlotNo++;
        slots.put(candidate.getId(), candidate);
        return candidate;
    }

    /** Like addSlot(listingId, start), but only the listing's own tutor may add a slot to it. */
    public synchronized TimeSlot addSlot(Student tutor, String listingId, LocalDateTime start) throws BookingException {
        Listing listing = listings.get(listingId);
        if (listing == null) throw new IllegalArgumentException("No such listing: " + listingId);
        if (!listing.getTutor().equals(tutor))
            throw new BookingException("Only the tutor who owns listing " + listingId + " can add slots to it");
        return addSlot(listingId, start);
    }

    /**
     * Takes back a time slot that nobody has booked. Only the tutor who published it may do this.
     * A booked slot cannot be removed: the booking has to be cancelled first, which refunds the learner.
     */
    public synchronized void removeSlot(Student tutor, String slotId) throws BookingException {
        TimeSlot slot = slots.get(slotId);
        if (slot == null) throw new IllegalArgumentException("No such slot: " + slotId);
        if (!slot.getTutor().equals(tutor))
            throw new BookingException("Only the tutor who published slot " + slotId + " can remove it");
        if (slot.isBooked())
            throw new SlotAlreadyBookedException(slotId);
        slots.remove(slotId);
    }

    /** Open slots, soonest first. */
    public synchronized List<TimeSlot> getAvailableSlots() {
        List<TimeSlot> open = new ArrayList<>();
        for (TimeSlot s : slots.values()) {
            if (!s.isBooked()) open.add(s);
        }
        open.sort(Comparator.comparing(TimeSlot::getStart));
        return open;
    }

    /**
     * Open slots a learner can still book, soonest first: they have not started yet,
     * and their tutor is not attending someone else's session at that time.
     */
    public synchronized List<TimeSlot> getUpcomingAvailableSlots() {
        LocalDateTime now = now();
        List<TimeSlot> upcoming = getAvailableSlots();
        upcoming.removeIf(s -> !s.getStart().isAfter(now) || tutorClash(s) != null);
        return upcoming;
    }

    /** True if the slot's tutor has booked someone else's session that overlaps it, so the tutor cannot teach it. */
    public synchronized boolean isTutorBusy(TimeSlot slot) {
        return tutorClash(slot) != null;
    }

    // ---------- Bookings ----------

    /**
     * Books a slot. Every check runs before anything changes, so a failed booking
     * leaves no partial state behind. Synchronized, so the checks and the commit are atomic.
     */
    public synchronized Booking book(Student learner, String slotId) throws BookingException {
        requireRegistered(learner);
        TimeSlot slot = slots.get(slotId);
        if (slot == null) throw new IllegalArgumentException("No such slot: " + slotId);

        if (slot.getTutor().equals(learner))
            throw new BookingConflictException("You cannot book your own session");
        if (slot.isBooked())
            throw new SlotAlreadyBookedException(slotId);
        if (!slot.getStart().isAfter(now()))
            throw new BookingException("Slot " + slotId + " has already started and can no longer be booked");
        checkLearnerIsFree(learner, slot);
        if (tutorClash(slot) != null)
            throw new BookingConflictException(slot.getTutor().getName() + " is attending another session at that time");

        String bookingId = "B" + nextBookingNo;
        int cost = slot.getListing().getCreditCost();   // polymorphic: 0 for paid and free listings
        if (cost > 0) ledger.hold(learner, cost, bookingId, now());  // throws if not enough credits

        nextBookingNo++;
        slot.markBooked();
        Booking booking = new Booking(bookingId, learner, slot, now());
        bookings.put(bookingId, booking);
        return booking;
    }

    /**
     * Cancels a confirmed booking, reopens the slot and refunds any held credits.
     * Either the learner or the tutor may cancel.
     */
    public synchronized void cancel(Student actor, String bookingId) throws BookingException {
        Booking b = requireConfirmed(bookingId);
        if (!b.getLearner().equals(actor) && !b.getSlot().getTutor().equals(actor))
            throw new BookingException("Only the learner or the tutor can cancel booking " + bookingId);
        b.setStatus(BookingStatus.CANCELLED);
        b.getSlot().markAvailable();
        ledger.refund(bookingId, b.getLearner(), now());
    }

    /**
     * The learner confirms that the session took place, which releases the held credits to the tutor.
     * Only the learner can do this, because they are the one paying, and only once the session has
     * started: a session that has not begun cannot have taken place.
     */
    public synchronized void completeSession(Student actor, String bookingId) throws BookingException {
        Booking b = requireConfirmed(bookingId);
        if (!b.getLearner().equals(actor))
            throw new BookingException("Only the learner can confirm booking " + bookingId);
        if (now().isBefore(b.getSlot().getStart()))
            throw new BookingException("Booking " + bookingId + " cannot be confirmed before the session starts");
        b.setStatus(BookingStatus.COMPLETED);
        ledger.release(bookingId, b.getSlot().getTutor(), now());
    }

    /** Bookings where the student is either the learner or the tutor. */
    public synchronized List<Booking> getBookingsInvolving(Student student) {
        List<Booking> result = new ArrayList<>();
        for (Booking b : bookings.values()) {
            if (b.getLearner().equals(student) || b.getSlot().getTutor().equals(student)) result.add(b);
        }
        return result;
    }

    /**
     * Credits currently held in escrow for the learner's confirmed swap bookings.
     * A hold exists exactly while a swap booking is CONFIRMED: completing releases it and cancelling refunds it.
     */
    public synchronized int getHeldCredits(Student learner) {
        int held = 0;
        for (Booking b : bookings.values()) {
            if (b.getStatus() == BookingStatus.CONFIRMED && b.getLearner().equals(learner)) {
                held += b.getSlot().getListing().getCreditCost();
            }
        }
        return held;
    }

    public synchronized List<TimeSlot> getSlotsForTutor(Student tutor) {
        List<TimeSlot> result = new ArrayList<>();
        for (TimeSlot s : slots.values()) {
            if (s.getTutor().equals(tutor)) result.add(s);
        }
        result.sort(Comparator.comparing(TimeSlot::getStart));
        return result;
    }

    // ---------- Serialisation ----------

    /** Holds the lock while saving, so a snapshot can't capture a half-finished booking. */
    private synchronized void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
    }

    /** The clock is not saved, so a loaded service gets the real one back. */
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        clock = Clock.systemDefaultZone();
        if (nextListingNo < 1) nextListingNo = 1;   // data saved before this counter existed
    }

    // ---------- Helpers (always called with the lock held) ----------

    /** A learner can't take two sessions at once, or attend one while their own slot is booked. */
    private void checkLearnerIsFree(Student learner, TimeSlot wanted) throws BookingConflictException {
        for (Booking b : bookings.values()) {
            if (b.getStatus() == BookingStatus.CONFIRMED
                    && b.getLearner().equals(learner)
                    && b.getSlot().overlaps(wanted)) {
                throw new BookingConflictException(learner.getName()
                        + " already has booking " + b.getId() + " at that time");
            }
        }
        for (TimeSlot s : slots.values()) {
            if (s.isBooked() && s.getTutor().equals(learner) && s.overlaps(wanted)) {
                throw new BookingConflictException(learner.getName()
                        + " is teaching slot " + s.getId() + " at that time");
            }
        }
    }

    /**
     * The other side of checkLearnerIsFree: the confirmed booking, if any, in which this slot's tutor is the
     * learner at an overlapping time. Without this check a student who books a session over one of their own
     * open slots could later have that slot booked too, and be expected in two places at once.
     */
    private Booking tutorClash(TimeSlot slot) {
        Student tutor = slot.getTutor();
        for (Booking b : bookings.values()) {
            if (b.getStatus() == BookingStatus.CONFIRMED && b.getLearner().equals(tutor) && b.getSlot().overlaps(slot)) {
                return b;
            }
        }
        return null;
    }

    private Booking requireConfirmed(String bookingId) throws BookingException {
        Booking b = bookings.get(bookingId);
        if (b == null) throw new IllegalArgumentException("No such booking: " + bookingId);
        if (b.getStatus() != BookingStatus.CONFIRMED)
            throw new BookingException("Booking " + bookingId + " is already " + b.getStatus());
        return b;
    }

    private void requireRegistered(Student s) {
        if (!students.containsKey(s.getId()))
            throw new IllegalArgumentException("Student not registered: " + s);
    }
}
