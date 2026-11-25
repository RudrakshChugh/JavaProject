package skillexchange.exception;

/** Signing in failed, or the request needs a signed-in student and there is none. */
public class AuthException extends SkillExchangeException {
    private static final long serialVersionUID = 1L;

    public AuthException(String message) {
        super(message);
    }
}
