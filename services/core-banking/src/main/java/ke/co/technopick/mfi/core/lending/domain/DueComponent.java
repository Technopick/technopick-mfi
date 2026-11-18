package ke.co.technopick.mfi.core.lending.domain;

/** Kinds of amount that can be due on a loan installment, in the default waterfall order. */
public enum DueComponent {
    PENALTY,
    FEE,
    INTEREST,
    PRINCIPAL
}
