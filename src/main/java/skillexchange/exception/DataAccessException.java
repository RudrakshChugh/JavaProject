package skillexchange.exception;

/**
 * The database failed (a SQLException). Unchecked: it is a fault in the system, not something a
 * user did, so callers are not forced to handle it. The original SQLException is kept as the cause.
 */
public class DataAccessException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DataAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
