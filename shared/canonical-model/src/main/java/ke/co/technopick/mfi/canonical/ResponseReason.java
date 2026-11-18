package ke.co.technopick.mfi.canonical;

/**
 * Internal, standard-neutral outcome of an authorization or payment.
 *
 * <p>Each counterparty has a versioned response-code map (switching.response_code_map)
 * translating these to ISO 8583 field 39, ISO 20022 status reason codes, and the
 * customer-facing message. Never expose these names to customers directly.
 */
public enum ResponseReason {
    APPROVED(true),
    INSUFFICIENT_FUNDS(false),
    ACCOUNT_NOT_FOUND(false),
    ACCOUNT_BLOCKED(false),
    LIMIT_EXCEEDED(false),
    INVALID_AMOUNT(false),
    INVALID_TRANSACTION(false),
    DUPLICATE_TRANSACTION(false),
    ORIGINAL_NOT_FOUND(false),
    SUSPECTED_FRAUD(false),
    SECURITY_VIOLATION(false),
    ISSUER_UNAVAILABLE(false),
    SYSTEM_ERROR(false),
    TIMEOUT(false);

    private final boolean approved;

    ResponseReason(boolean approved) {
        this.approved = approved;
    }

    public boolean isApproved() {
        return approved;
    }
}
