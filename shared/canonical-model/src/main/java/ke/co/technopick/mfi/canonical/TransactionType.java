package ke.co.technopick.mfi.canonical;

/**
 * Business meaning of a transaction, independent of any message standard.
 * ISO 8583 processing codes and ISO 20022 purposes map onto these values at the edge.
 */
public enum TransactionType {
    BALANCE_ENQUIRY(false),
    MINI_STATEMENT(false),
    CASH_WITHDRAWAL(true),
    CASH_DEPOSIT(true),
    PURCHASE(true),
    FUNDS_TRANSFER(true),
    BILL_PAYMENT(true),
    PRE_AUTHORIZATION(true),
    PRE_AUTHORIZATION_COMPLETION(true),
    REFUND(true),
    LOAN_REPAYMENT(true),
    LOAN_DISBURSEMENT(true);

    private final boolean financial;

    TransactionType(boolean financial) {
        this.financial = financial;
    }

    /** True when the transaction moves value and therefore needs an amount and, on approval, postings. */
    public boolean isFinancial() {
        return financial;
    }
}
