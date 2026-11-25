package skillexchange.exception;

/** The client could not reach the server, or the connection dropped while waiting for an answer. */
public class ConnectionLostException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ConnectionLostException(String message, Throwable cause) {
        super(message, cause);
    }
}
