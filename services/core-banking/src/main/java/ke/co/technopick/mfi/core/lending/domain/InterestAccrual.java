package ke.co.technopick.mfi.core.lending.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import ke.co.technopick.mfi.canonical.Money;

/**
 * Daily accrual of an installment's scheduled interest across its period.
 *
 * <p>Method (accrual/v1): the period runs from the previous due date (exclusive) to the
 * due date (inclusive). Accrued-to-date is computed cumulatively:
 * {@code round(periodInterest * elapsedDays / periodDays)}. Each day's accrual is the
 * difference from what has already been posted. Consequences, all tested:
 * <ul>
 *   <li>the daily amounts of a period sum exactly to the scheduled interest;</li>
 *   <li>no daily amount is negative;</li>
 *   <li>a missed EOD is caught up automatically on the next run, never double-accrued.</li>
 * </ul>
 */
public final class InterestAccrual {

    public static final String METHOD_VERSION = "accrual/v1";

    private InterestAccrual() {
    }

    /** Interest that should have been recognised for the period by the end of {@code asOf}. */
    public static Money accruedToDate(Money periodInterest, LocalDate periodStart, LocalDate dueDate,
                                      LocalDate asOf, RoundingMode rounding) {
        Objects.requireNonNull(periodInterest, "periodInterest");
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(dueDate, "dueDate");
        Objects.requireNonNull(asOf, "asOf");
        if (!dueDate.isAfter(periodStart)) {
            throw new IllegalArgumentException("dueDate must be after periodStart");
        }
        if (periodInterest.isNegative()) {
            throw new IllegalArgumentException("periodInterest must not be negative");
        }
        long periodDays = ChronoUnit.DAYS.between(periodStart, dueDate);
        long elapsed = Math.max(0, Math.min(periodDays, ChronoUnit.DAYS.between(periodStart, asOf)));
        if (elapsed == periodDays) {
            return periodInterest;
        }
        BigDecimal share = periodInterest.amount().multiply(BigDecimal.valueOf(elapsed))
                .divide(BigDecimal.valueOf(periodDays), MathContext.DECIMAL128);
        return Money.of(share, periodInterest.currency(), rounding);
    }

    /**
     * Amount to post in today's EOD run, given what has already been accrued for the period.
     * Idempotent by construction: running it twice for the same day returns zero the second time.
     */
    public static Money accrualDue(Money periodInterest, LocalDate periodStart, LocalDate dueDate,
                                   LocalDate asOf, Money alreadyAccrued, RoundingMode rounding) {
        Money target = accruedToDate(periodInterest, periodStart, dueDate, asOf, rounding);
        Money due = target.minus(alreadyAccrued);
        if (due.isNegative()) {
            throw new IllegalStateException("over-accrued: posted " + alreadyAccrued + ", target " + target);
        }
        return due;
    }
}
