package ke.co.technopick.mfi.core.lending.domain;

import java.util.stream.Stream;

/**
 * GL accounts a loan product version posts to. Part of the product version, so a change of
 * accounting mapping is a new version and never alters existing loans' history.
 */
public record LoanGlMapping(
        String principalReceivable,
        String interestReceivable,
        String interestIncome,
        String feeReceivable,
        String feeIncome,
        String penaltyReceivable,
        String penaltyIncome,
        String overpaymentLiability,
        String writeOffExpense) {

    public LoanGlMapping {
        Stream.of(principalReceivable, interestReceivable, interestIncome, feeReceivable, feeIncome,
                        penaltyReceivable, penaltyIncome, overpaymentLiability, writeOffExpense)
                .forEach(code -> {
                    if (code == null || code.isBlank()) {
                        throw new IllegalArgumentException("every GL mapping entry is required");
                    }
                });
    }

    /** Receivable account cleared when a repayment is allocated to {@code component}. */
    public String receivableFor(DueComponent component) {
        return switch (component) {
            case PRINCIPAL -> principalReceivable;
            case INTEREST -> interestReceivable;
            case FEE -> feeReceivable;
            case PENALTY -> penaltyReceivable;
        };
    }
}
