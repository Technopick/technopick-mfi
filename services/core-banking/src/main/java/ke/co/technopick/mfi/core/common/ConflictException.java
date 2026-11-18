package ke.co.technopick.mfi.core.common;

/** The request is valid but conflicts with the resource's current state (e.g. completing a released hold). */
public class ConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConflictException(String message) {
        super(message);
    }
}
