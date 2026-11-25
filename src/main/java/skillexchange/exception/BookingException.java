package skillexchange.exception;

/** A booking rule was broken. Checked, so every caller has to deal with a failed booking. */
public class BookingException extends SkillExchangeException {
    private static final long serialVersionUID = 1L;

    public BookingException(String message) {
        super(message);
    }
}
