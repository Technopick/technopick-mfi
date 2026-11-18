package ke.co.technopick.mfi.core.lending.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.Objects;
import ke.co.technopick.mfi.canonical.Money;

/**
 * Late-payment penalty rule of a product version (penalty/v1).
 *
 * <ul>
 *   <li>Nothing is charged until {@code graceDays} have passed after the due date.</li>
 *   <li>{@link Method#FLAT_ONCE}: one flat charge per late installment, on the first day after grace.</li>
 *   <li>{@link Method#DAILY_RATE}: each business day after grace, {@code dailyRate} x the
 *       installment's unpaid principal + interest on that day (so partial payments reduce it).</li>
 *   <li>{@code capPerInstallment} limits the total penalty on one installment.</li>
 * </ul>
 * The EOD batch runs every business date in sequence (V4 forbids skipping dates), so the
 * daily method never needs to reconstruct past balances.
 *
 * <p>LEGAL REVIEW REQUIRED: Kenyan rules limiting interest and charges on non-performing
 * loans (for example the in duplum rule for banks and microfinance banks) and the NDTCP
 * Regulations 2026 must be checked per licence type; caps here are configuration, not advice.
 */
public record PenaltyRule(Method method, int graceDays, Money flatAmount, BigDecimal dailyRate,
                          Money capPerInstallment, RoundingMode rounding) {

    public enum Method { NONE, FLAT_ONCE, DAILY_RATE }

    public PenaltyRule {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(rounding, "rounding");
        if (graceDays < 0) {
            throw new IllegalArgumentException("graceDays must not be negative");
        }
        if (method == Method.FLAT_ONCE && (flatAmount == null || !flatAmount.isPositive())) {
            throw new IllegalArgumentException("FLAT_ONCE needs a positive flatAmount");
        }
        if (method == Method.DAILY_RATE && (dailyRate == null || dailyRate.signum() <= 0
                || dailyRate.compareTo(new BigDecimal("0.01")) > 0)) {
            throw new IllegalArgumentException("DAILY_RATE needs 0 < dailyRate <= 0.01");
        }
        if (capPerInstallment != null && capPerInstallment.isNegative()) {
            throw new IllegalArgumentException("cap must not be negative");
        }
    }

    public static PenaltyRule none() {
        return new PenaltyRule(Method.NONE, 0, null, null, null, RoundingMode.HALF_EVEN);
    }

    /**
     * Penalty to charge on {@code businessDate} for one installment.
     *
     * @param unpaidPrincipalAndInterest the installment's unpaid principal + interest today
     * @param alreadyCharged             penalty already charged on this installment
     */
    public Money chargeFor(LocalDate dueDate, LocalDate businessDate, Money unpaidPrincipalAndInterest,
                           Money alreadyCharged) {
        Objects.requireNonNull(dueDate, "dueDate");
        Objects.requireNonNull(businessDate, "businessDate");
        Objects.requireNonNull(unpaidPrincipalAndInterest, "unpaid");
        Objects.requireNonNull(alreadyCharged, "alreadyCharged");
        Currency ccy = unpaidPrincipalAndInterest.currency();
        Money zero = Money.zero(ccy);
        long daysLate = ChronoUnit.DAYS.between(dueDate, businessDate);
        if (method == Method.NONE || daysLate <= graceDays || !unpaidPrincipalAndInterest.isPositive()) {
            return zero;
        }
        Money charge = switch (method) {
            case FLAT_ONCE -> alreadyCharged.isZero() ? flatAmount : zero;
            case DAILY_RATE -> unpaidPrincipalAndInterest.times(dailyRate, rounding);
            case NONE -> zero;
        };
        if (capPerInstallment != null) {
            Money room = capPerInstallment.minus(alreadyCharged);
            if (!room.isPositive()) {
                return zero;
            }
            if (charge.compareTo(room) > 0) {
                charge = room;
            }
        }
        return charge;
    }
}
