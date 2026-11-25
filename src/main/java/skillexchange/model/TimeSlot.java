package skillexchange.model;

import java.io.Serializable;
import java.time.LocalDateTime;

/** One bookable time window for a listing. The end time comes from the listing's duration. */
public final class TimeSlot implements Serializable {
    private static final long serialVersionUID = 2L;

    private final long id;
    private final Listing listing;
    private final LocalDateTime start;
    private final SessionMode mode;
    private final String place;     // null when hidden from someone who has not booked the slot
    private final boolean booked;

    public TimeSlot(long id, Listing listing, LocalDateTime start, SessionMode mode, String place, boolean booked) {
        if (listing == null) throw new IllegalArgumentException("Listing is required");
        if (start == null) throw new IllegalArgumentException("Start time is required");
        if (mode == null) throw new IllegalArgumentException("Session mode is required");
        this.id = id;
        this.listing = listing;
        this.start = start;
        this.mode = mode;
        this.place = place;
        this.booked = booked;
    }

    /** A copy without the meeting link or room. Sent to students who are only browsing. */
    public TimeSlot withoutPlace() {
        return new TimeSlot(id, listing, start, mode, null, booked);
    }

    public long getId() { return id; }
    public Listing getListing() { return listing; }
    public Student getTutor() { return listing.getTutor(); }
    public LocalDateTime getStart() { return start; }
    public LocalDateTime getEnd() { return start.plusMinutes(listing.getDurationMinutes()); }
    public SessionMode getMode() { return mode; }
    public String getPlace() { return place; }
    public boolean isBooked() { return booked; }

    /** True if the two slots' time ranges intersect. */
    public boolean overlaps(TimeSlot other) {
        return start.isBefore(other.getEnd()) && other.getStart().isBefore(getEnd());
    }

    @Override
    public String toString() {
        return listing.getSkill() + " at " + start + (booked ? " (booked)" : " (open)");
    }
}
