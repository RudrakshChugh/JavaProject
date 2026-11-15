package skillexchange.exception;

/**
 * Base class for all booking-rule violations.
 * It is a checked exception, so callers must handle a failed booking explicitly.
 */
public class BookingException extends Exception {
    private static final long serialVersionUID = 1L;

    public BookingException(String message) {
        super(message);
    }
}
