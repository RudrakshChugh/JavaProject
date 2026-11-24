package skillexchange.service;

import skillexchange.exception.*;
import skillexchange.model.*;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
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

    // ---------- Students ----------

    public synchronized void registerStudent(Student student, int initialCredits) {
        if (students.containsKey(student.getId()))
            throw new IllegalArgumentException("Student id already exists: " + student.getId());
        students.put(student.getId(), student);
        ledger.openAccount(student, initialCredits);
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

    /** Next unused id such as "S4". Gaps left by hand-picked ids are skipped. */
    public synchronized String nextStudentId() { return nextFreeId("S", students); }

    public synchronized String nextListingId() { return nextFreeId("L", listings); }

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

    /** Open slots, soonest first. */
    public synchronized List<TimeSlot> getAvailableSlots() {
        List<TimeSlot> open = new ArrayList<>();
        for (TimeSlot s : slots.values()) {
            if (!s.isBooked()) open.add(s);
        }
        open.sort(Comparator.comparing(TimeSlot::getStart));
        return open;
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
        checkLearnerIsFree(learner, slot);

        String bookingId = "B" + nextBookingNo;
        int cost = slot.getListing().getCreditCost();   // polymorphic: 0 for paid and free listings
        if (cost > 0) ledger.hold(learner, cost, bookingId);  // throws if not enough credits

        nextBookingNo++;
        slot.markBooked();
        Booking booking = new Booking(bookingId, learner, slot);
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
        ledger.refund(bookingId, b.getLearner());
    }

    /**
     * The learner confirms that the session took place, which releases the held credits to the tutor.
     * Only the learner can do this, because they are the one paying.
     */
    public synchronized void completeSession(Student actor, String bookingId) throws BookingException {
        Booking b = requireConfirmed(bookingId);
        if (!b.getLearner().equals(actor))
            throw new BookingException("Only the learner can confirm booking " + bookingId);
        b.setStatus(BookingStatus.COMPLETED);
        ledger.release(bookingId, b.getSlot().getTutor());
    }

    /** Bookings where the student is either the learner or the tutor. */
    public synchronized List<Booking> getBookingsInvolving(Student student) {
        List<Booking> result = new ArrayList<>();
        for (Booking b : bookings.values()) {
            if (b.getLearner().equals(student) || b.getSlot().getTutor().equals(student)) result.add(b);
        }
        return result;
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
