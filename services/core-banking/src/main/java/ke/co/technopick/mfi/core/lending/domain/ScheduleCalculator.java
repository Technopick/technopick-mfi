package ke.co.technopick.mfi.core.lending.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import ke.co.technopick.mfi.canonical.Money;

/**
 * Generates equal-installment repayment schedules.
 *
 * <p>Specification: docs/architecture/loan-calculations.md (version 1). Summary:
 * <ul>
 *   <li>Periodic rate {@code r} is supplied already converted from the annual rate by the
 *       product's accrual basis (e.g. 24% p.a., monthly, 30/360 gives r = 0.02).</li>
 *   <li>Intermediate arithmetic uses {@link MathContext#DECIMAL128}; each installment
 *       component is rounded to minor units with the product's rounding mode.</li>
 *   <li>The final installment absorbs every rounding difference so that principal sums
 *       exactly to the loan amount and the closing balance is exactly zero.</li>
 * </ul>
 * Grace periods, moratoria, holidays and irregular first periods are added in Phase 3 as
 * new, separately versioned methods; this class never changes behaviour for existing loans.
 */
public final class ScheduleCalculator {

    public static final String METHOD_VERSION = "equal-installment/v1";

    private static final MathContext MC = MathContext.DECIMAL128;

    private ScheduleCalculator() {
    }

    /** Reducing (declining) balance with equal total installments (annuity / EMI). */
    public static List<Installment> reducingBalance(Money principal, BigDecimal periodicRate, int periods,
                                                    RoundingMode rounding) {
        validate(principal, periodicRate, periods, rounding);
        if (periodicRate.signum() == 0) {
            return flat(principal, BigDecimal.ZERO, periods, rounding);
        }
        BigDecimal onePlusR = BigDecimal.ONE.add(periodicRate, MC);
        BigDecimal discount = BigDecimal.ONE.divide(onePlusR.pow(periods, MC), MC);
        BigDecimal emiExact = principal.amount().multiply(periodicRate, MC)
                .divide(BigDecimal.ONE.subtract(discount, MC), MC);
        Money emi = Money.of(emiExact, principal.currency(), rounding);

        List<Installment> schedule = new ArrayList<>(periods);
        Money balance = principal;
        for (int n = 1; n <= periods; n++) {
            Money interest = balance.times(periodicRate, rounding);
            Money principalPart = (n == periods) ? balance : emi.minus(interest);
            if (principalPart.isNegative()) {
                throw new IllegalArgumentException("Installment does not cover interest at period " + n);
            }
            balance = balance.minus(principalPart);
            schedule.add(new Installment(n, principalPart, interest, balance));
        }
        return List.copyOf(schedule);
    }

    /**
     * Flat-rate schedule: interest = principal x periodic rate x periods, spread evenly.
     * Callers must disclose the effective rate beside any flat rate (consumer protection).
     */
    public static List<Installment> flat(Money principal, BigDecimal periodicRate, int periods,
                                         RoundingMode rounding) {
        validate(principal, periodicRate, periods, rounding);
        BigDecimal n = BigDecimal.valueOf(periods);
        Money totalInterest = principal.times(periodicRate.multiply(n, MC), rounding);

        // Non-final installments are rounded DOWN so their sum can never exceed the total;
        // the final installment takes the (non-negative) remainder. The product rounding
        // mode applies to the total interest only.
        Money principalEach = Money.of(principal.amount().divide(n, MC), principal.currency(), RoundingMode.DOWN);
        Money interestEach = Money.of(totalInterest.amount().divide(n, MC), principal.currency(), RoundingMode.DOWN);

        List<Installment> schedule = new ArrayList<>(periods);
        Money balance = principal;
        Money interestSoFar = Money.zero(principal.currency());
        for (int i = 1; i <= periods; i++) {
            boolean last = i == periods;
            Money p = last ? balance : principalEach;
            Money in = last ? totalInterest.minus(interestSoFar) : interestEach;
            balance = balance.minus(p);
            interestSoFar = interestSoFar.plus(in);
            schedule.add(new Installment(i, p, in, balance));
        }
        return List.copyOf(schedule);
    }

    private static void validate(Money principal, BigDecimal rate, int periods, RoundingMode rounding) {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(rate, "periodicRate");
        Objects.requireNonNull(rounding, "rounding");
        if (!principal.isPositive()) {
            throw new IllegalArgumentException("principal must be positive");
        }
        if (rate.signum() < 0) {
            throw new IllegalArgumentException("periodicRate must not be negative");
        }
        if (periods < 1 || periods > 600) {
            throw new IllegalArgumentException("periods must be between 1 and 600");
        }
    }
}
