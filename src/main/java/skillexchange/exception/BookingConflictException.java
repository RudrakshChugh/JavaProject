package skillexchange.exception;

/** Thrown when a booking or a new slot would put one person in two places at the same time. */
public class BookingConflictException extends BookingException {
    private static final long serialVersionUID = 1L;

    public BookingConflictException(String message) {
        super(message);
    }
}
