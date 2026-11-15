package skillexchange.exception;

/** Saving or loading data failed: an I/O error, or a corrupt or incompatible data file. */
public class PersistenceException extends Exception {
    private static final long serialVersionUID = 1L;

    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
