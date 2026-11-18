package ke.co.technopick.mfi.core.accounting.domain;

/** Debit or credit side of a journal line. */
public enum Side {
    DEBIT,
    CREDIT;

    public Side opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
