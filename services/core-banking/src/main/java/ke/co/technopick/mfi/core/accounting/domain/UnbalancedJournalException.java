package ke.co.technopick.mfi.core.accounting.domain;

/** Raised when a journal entry violates double-entry rules. Never caught to "fix up" an entry. */
public final class UnbalancedJournalException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public UnbalancedJournalException(String message) {
        super(message);
    }
}
