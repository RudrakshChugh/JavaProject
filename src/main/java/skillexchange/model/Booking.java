package skillexchange.model;

import java.io.Serializable;
import java.time.LocalDateTime;

/** A learner's reservation of one tutor time slot. */
public final class Booking implements Serializable {
    private static final long serialVersionUID = 2L;

    private final long id;
    private final Student learner;
    private final TimeSlot slot;
    private final BookingStatus status;
    private final int heldCredits;
    private final LocalDateTime bookedAt;

    public Booking(long id, Student learner, TimeSlot slot, BookingStatus status, int heldCredits, LocalDateTime bookedAt) {
        if (learner == null || slot == null) throw new IllegalArgumentException("Learner and slot are required");
        this.id = id;
        this.learner = learner;
        this.slot = slot;
        this.status = status;
        this.heldCredits = heldCredits;
        this.bookedAt = bookedAt;
    }

    public long getId() { return id; }
    public Student getLearner() { return learner; }
    public TimeSlot getSlot() { return slot; }
    public Student getTutor() { return slot.getTutor(); }
    public BookingStatus getStatus() { return status; }
    public int getHeldCredits() { return heldCredits; }
    public LocalDateTime getBookedAt() { return bookedAt; }

    public boolean isLearner(Student s) { return learner.equals(s); }
    public boolean involves(Student s) { return learner.equals(s) || getTutor().equals(s); }

    /** The other person in the session, from the point of view of {@code me}. */
    public Student otherParty(Student me) {
        return isLearner(me) ? getTutor() : learner;
    }

    @Override
    public String toString() {
        return "Booking " + id + ": " + learner.getName() + " -> " + slot.getListing().getSkill() + " [" + status + "]";
    }
}
