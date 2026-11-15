package skillexchange.model;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * One bookable time window for a listing. The end time comes from the listing's duration.
 */
public class TimeSlot implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm");

    private final String id;
    private final Listing listing;
    private final LocalDateTime start;
    private volatile boolean booked;   // volatile: other threads always see the latest value

    public TimeSlot(String id, Listing listing, LocalDateTime start) {
        if (listing == null) throw new IllegalArgumentException("Listing is required");
        if (start == null) throw new IllegalArgumentException("Start time is required");
        this.id = id;
        this.listing = listing;
        this.start = start;
    }

    public String getId() { return id; }
    public Listing getListing() { return listing; }
    public Student getTutor() { return listing.getTutor(); }
    public LocalDateTime getStart() { return start; }
    public LocalDateTime getEnd() { return start.plusMinutes(listing.getDurationMinutes()); }
    public boolean isBooked() { return booked; }

    public void markBooked() { booked = true; }
    public void markAvailable() { booked = false; }

    /** True if the two slots' time ranges intersect. */
    public boolean overlaps(TimeSlot other) {
        return start.isBefore(other.getEnd()) && other.getStart().isBefore(getEnd());
    }

    @Override
    public String toString() {
        return String.format("[%s] %s with %s, %s-%s (%s) %s",
                id, listing.getSkill(), getTutor().getName(),
                start.format(FMT), getEnd().toLocalTime(), listing.getPriceDescription(),
                booked ? "BOOKED" : "OPEN");
    }
}
