package skillexchange.exception;

/** A value typed by the user is not acceptable. Carries the field name so a form can point at it. */
public class ValidationException extends SkillExchangeException {
    private static final long serialVersionUID = 1L;

    private final String field;

    public ValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() { return field; }
}
