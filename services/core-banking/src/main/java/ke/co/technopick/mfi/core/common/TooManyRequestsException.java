package ke.co.technopick.mfi.core.common;

/** The caller has asked too often; they may try again after {@link #retryAfterSeconds()}. */
public class TooManyRequestsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final long retryAfterSeconds;

    public TooManyRequestsException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
