package ke.co.technopick.mfi.core.lending.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import ke.co.technopick.mfi.canonical.Money;

/** Shared test fixtures matching the Phase 0 design examples (section 5). */
public final class LoanFixtures {

    public static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    public static final UUID LOAN = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");

    public static final LoanGlMapping GL = new LoanGlMapping(
            "1200-LOANS-PRINCIPAL", "1210-INTEREST-RECEIVABLE", "4000-INTEREST-INCOME",
            "1220-FEE-RECEIVABLE", "4100-FEE-PROCESSING", "1230-PENALTY-RECEIVABLE", "4300-PENALTY-INCOME",
            "2400-LOAN-OVERPAYMENTS", "5100-LOAN-WRITE-OFFS");

    private LoanFixtures() {
    }

    public static Money kes(String v) {
        return Money.of(v, "KES");
    }

    /** Biashara loan v1: 24% p.a. reducing balance, 2% upfront fee, KES 5,000 - 500,000, 3-24 months. */
    public static LoanProductVersion biasharaV1() {
        return new LoanProductVersion(UUID.fromString("cccccccc-0000-0000-0000-000000000001"), "BIASHARA", 1,
                Currency.getInstance("KES"), LoanProductVersion.InterestMethod.REDUCING_BALANCE,
                new BigDecimal("0.24"), RoundingMode.HALF_EVEN, kes("5000"), kes("500000"), 3, 24,
                new BigDecimal("0.02"), RepaymentAllocator.Strategy.COMPONENT_FIRST,
                List.of(DueComponent.values()), GL);
    }

    public static LoanPostingRules.LoanContext context() {
        return new LoanPostingRules.LoanContext(TENANT, LOAN, "NBO01", biasharaV1());
    }
}
