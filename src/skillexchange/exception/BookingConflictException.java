package skillexchange.exception;

/**
 * Thrown when a booking or slot would clash with the person's schedule:
 * overlapping times, or a tutor booking their own session.
 */
public class BookingConflictException extends BookingException {
    private static final long serialVersionUID = 1L;

    public BookingConflictException(String message) {
        super(message);
    }
}
