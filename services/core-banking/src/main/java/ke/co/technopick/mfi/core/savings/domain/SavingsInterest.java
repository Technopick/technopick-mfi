package ke.co.technopick.mfi.core.savings.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Savings interest arithmetic (savings-interest/v1, ADR-0019). Must agree exactly with the
 * database checks in V18: ACT/365 fixed, daily amount to 10 decimals, the posted 2-decimal amount
 * is the rounded cumulative accrual of the open period less what that period already posted, and
 * withholding tax is gross x rate rounded to cents. All rounding is half-up (amounts are
 * positive, so this equals PostgreSQL's round()).
 */
public final class SavingsInterest {

    static final BigDecimal DAYS_X_100 = BigDecimal.valueOf(36500);

    private SavingsInterest() {
    }

    /** One day's interest on a positive balance at an annual percentage rate. */
    public static BigDecimal daily(BigDecimal balance, BigDecimal annualRatePercent) {
        if (balance.signum() <= 0 || annualRatePercent.signum() <= 0) {
            throw new IllegalArgumentException("balance and rate must be positive");
        }
        return balance.multiply(annualRatePercent).divide(DAYS_X_100, 10, RoundingMode.HALF_UP);
    }

    /**
     * Amount to post today.
     *
     * @param periodExactSoFar  sum of the open period's previous daily amounts (10 decimals)
     * @param periodPostedSoFar sum of what the open period already posted (cents)
     * @param today             today's daily amount
     */
    public static BigDecimal postToday(BigDecimal periodExactSoFar, BigDecimal periodPostedSoFar, BigDecimal today) {
        return periodExactSoFar.add(today).setScale(2, RoundingMode.HALF_UP).subtract(periodPostedSoFar)
            .setScale(2, RoundingMode.UNNECESSARY);
    }

    /** Withholding tax on gross interest. */
    public static BigDecimal tax(BigDecimal gross, BigDecimal taxRatePercent) {
        return gross.multiply(taxRatePercent).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    /** Interest is credited to accounts at the end of each calendar month. */
    public static boolean isPostingDay(LocalDate businessDate) {
        return businessDate.equals(YearMonth.from(businessDate).atEndOfMonth());
    }
}
