package skillexchange.exception;

/** Thrown when a learner does not have enough credits for a credit-swap session. */
public class InsufficientCreditsException extends BookingException {
    private static final long serialVersionUID = 1L;

    private final int required;
    private final int available;

    public InsufficientCreditsException(String studentName, int required, int available) {
        super(studentName + " needs " + required + (required == 1 ? " credit" : " credits")
                + " but has only " + available);
        this.required = required;
        this.available = available;
    }

    public int getRequired() { return required; }
    public int getAvailable() { return available; }
}
