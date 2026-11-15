package skillexchange.exception;

/** Thrown when a learner tries to book a slot someone else already holds. */
public class SlotAlreadyBookedException extends BookingException {
    private static final long serialVersionUID = 1L;

    private final String slotId;

    public SlotAlreadyBookedException(String slotId) {
        super("Slot " + slotId + " is already booked");
        this.slotId = slotId;
    }

    public String getSlotId() { return slotId; }
}
