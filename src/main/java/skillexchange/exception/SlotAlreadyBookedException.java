package skillexchange.exception;

/** Thrown when a learner tries to book a slot someone else already holds. */
public class SlotAlreadyBookedException extends BookingException {
    private static final long serialVersionUID = 1L;

    private final long slotId;

    public SlotAlreadyBookedException(long slotId) {
        super("That session has just been booked by someone else");
        this.slotId = slotId;
    }

    public long getSlotId() { return slotId; }
}
