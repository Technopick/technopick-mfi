package ke.co.technopick.mfi.core.collections.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import ke.co.technopick.mfi.canonical.Money;

/**
 * Portfolio at risk (PAR) - metric definition par/v1 (docs/architecture/portfolio-metrics.md):
 *
 * <pre>
 *   PAR_n = sum(outstanding principal of loans with DPD > n)
 *         / sum(outstanding principal of all loans)
 * </pre>
 * <ul>
 *   <li>Population: ACTIVE loans as of the snapshot date; written-off and closed loans excluded
 *       from numerator and denominator.</li>
 *   <li>The whole outstanding principal of a late loan counts, not only the overdue part.</li>
 *   <li>Single currency per calculation; ratio at 4 decimal places, HALF_EVEN.</li>
 *   <li>No loans (denominator zero): no ratio, never a fake 0%.</li>
 * </ul>
 */
public final class PortfolioAtRisk {

    public static final String METRIC_VERSION = "par/v1";

    public record LoanPosition(UUID loanId, String branchCode, String officer, Money outstandingPrincipal,
                               long daysPastDue, boolean writtenOff) {
        public LoanPosition {
            Objects.requireNonNull(loanId, "loanId");
            Objects.requireNonNull(outstandingPrincipal, "outstandingPrincipal");
            if (outstandingPrincipal.isNegative() || daysPastDue < 0) {
                throw new IllegalArgumentException("outstanding and DPD must not be negative");
            }
        }
    }

    public record Result(int thresholdDays, Money numerator, Money denominator, Optional<BigDecimal> ratio,
                         int loansAtRisk, int loansInPortfolio) { }

    private PortfolioAtRisk() {
    }

    public static Result par(Collection<LoanPosition> loans, int thresholdDays, Currency currency) {
        Objects.requireNonNull(loans, "loans");
        Objects.requireNonNull(currency, "currency");
        if (thresholdDays < 0) {
            throw new IllegalArgumentException("thresholdDays must not be negative");
        }
        Money numerator = Money.zero(currency);
        Money denominator = Money.zero(currency);
        int atRisk = 0;
        int inPortfolio = 0;
        for (LoanPosition loan : loans) {
            if (loan.writtenOff()) {
                continue;
            }
            if (!loan.outstandingPrincipal().currency().equals(currency)) {
                throw new IllegalArgumentException("loan " + loan.loanId() + " is not in " + currency);
            }
            inPortfolio++;
            denominator = denominator.plus(loan.outstandingPrincipal());
            if (loan.daysPastDue() > thresholdDays) {
                atRisk++;
                numerator = numerator.plus(loan.outstandingPrincipal());
            }
        }
        Optional<BigDecimal> ratio = denominator.isZero()
                ? Optional.empty()
                : Optional.of(numerator.amount().divide(denominator.amount(), 4, RoundingMode.HALF_EVEN));
        return new Result(thresholdDays, numerator, denominator, ratio, atRisk, inPortfolio);
    }
}
