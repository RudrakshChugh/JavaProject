package skillexchange.model;

import java.io.Serializable;
import java.time.LocalDateTime;

/** A learner's reservation of one tutor time slot. */
public class Booking implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String id;
    private final Student learner;
    private final TimeSlot slot;
    private final LocalDateTime bookedAt;
    private volatile BookingStatus status;

    public Booking(String id, Student learner, TimeSlot slot, LocalDateTime bookedAt) {
        if (learner == null || slot == null) throw new IllegalArgumentException("Learner and slot are required");
        this.id = id;
        this.learner = learner;
        this.slot = slot;
        this.bookedAt = bookedAt;
        this.status = BookingStatus.CONFIRMED;
    }

    public String getId() { return id; }
    public Student getLearner() { return learner; }
    public TimeSlot getSlot() { return slot; }
    public LocalDateTime getBookedAt() { return bookedAt; }
    public BookingStatus getStatus() { return status; }

    public void setStatus(BookingStatus status) { this.status = status; }

    @Override
    public String toString() {
        return String.format("Booking %s: %s -> %s on %s [%s]",
                id, learner.getName(), slot.getListing().getSkill(),
                slot.getStart().toLocalDate(), status);
    }
}
