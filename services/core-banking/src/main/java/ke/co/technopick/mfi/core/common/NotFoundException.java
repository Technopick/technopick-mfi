package ke.co.technopick.mfi.core.common;

/** The requested resource does not exist for the caller's tenant (never says whether it exists elsewhere). */
public class NotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NotFoundException(String message) {
        super(message);
    }
}
