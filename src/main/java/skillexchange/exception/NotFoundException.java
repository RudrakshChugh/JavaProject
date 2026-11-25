package skillexchange.exception;

/** The id in the request does not belong to anything in the database. */
public class NotFoundException extends SkillExchangeException {
    private static final long serialVersionUID = 1L;

    public NotFoundException(String what, long id) {
        super("No " + what + " with id " + id);
    }
}
