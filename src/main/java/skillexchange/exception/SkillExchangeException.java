package skillexchange.exception;

/**
 * Root of every checked exception the application throws on purpose.
 * The JavaFX client catches this one type to show the message of any rule that was broken.
 */
public class SkillExchangeException extends Exception {
    private static final long serialVersionUID = 1L;

    public SkillExchangeException(String message) {
        super(message);
    }

    public SkillExchangeException(String message, Throwable cause) {
        super(message, cause);
    }
}
