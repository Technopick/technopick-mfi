package ke.co.technopick.mfi.core.lending.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import ke.co.technopick.mfi.canonical.Money;

/**
 * Immutable terms of a loan product at one version. A loan pins the version it was created
 * with; changing any term means activating a new version (maker-checker, V3 migration), so
 * existing approved or disbursed loans never change silently.
 *
 * <p>Phase 3 scope: monthly repayment frequency, 30/360 periodic rate. Other frequencies and
 * day-count bases are added as new {@code scheduleMethodVersion}s.
 */
public record LoanProductVersion(
        UUID versionId,
        String productCode,
        int version,
        Currency currency,
        InterestMethod interestMethod,
        BigDecimal annualRate,
        RoundingMode rounding,
        Money minPrincipal,
        Money maxPrincipal,
        int minTermMonths,
        int maxTermMonths,
        BigDecimal upfrontFeeRate,
        RepaymentAllocator.Strategy allocationStrategy,
        List<DueComponent> allocationOrder,
        LoanGlMapping gl) {

    public enum InterestMethod { REDUCING_BALANCE, FLAT }

    /** Scale used to express the periodic rate, e.g. 25% p.a. monthly = 0.0208333333. */
    public static final int PERIODIC_RATE_SCALE = 10;

    public LoanProductVersion {
        Objects.requireNonNull(versionId, "versionId");
        if (productCode == null || productCode.isBlank()) {
            throw new IllegalArgumentException("productCode is required");
        }
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1");
        }
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(interestMethod, "interestMethod");
        Objects.requireNonNull(annualRate, "annualRate");
        Objects.requireNonNull(rounding, "rounding");
        Objects.requireNonNull(minPrincipal, "minPrincipal");
        Objects.requireNonNull(maxPrincipal, "maxPrincipal");
        Objects.requireNonNull(upfrontFeeRate, "upfrontFeeRate");
        Objects.requireNonNull(allocationStrategy, "allocationStrategy");
        Objects.requireNonNull(allocationOrder, "allocationOrder");
        Objects.requireNonNull(gl, "gl");
        if (annualRate.signum() < 0 || annualRate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("annualRate must be between 0 and 1 (0% to 100%)");
        }
        if (upfrontFeeRate.signum() < 0 || upfrontFeeRate.compareTo(new BigDecimal("0.20")) > 0) {
            throw new IllegalArgumentException("upfrontFeeRate must be between 0 and 0.20");
        }
        if (!minPrincipal.currency().equals(currency) || !maxPrincipal.currency().equals(currency)) {
            throw new IllegalArgumentException("principal limits must be in " + currency);
        }
        if (!minPrincipal.isPositive() || minPrincipal.compareTo(maxPrincipal) > 0) {
            throw new IllegalArgumentException("0 < minPrincipal <= maxPrincipal required");
        }
        if (minTermMonths < 1 || minTermMonths > maxTermMonths || maxTermMonths > 600) {
            throw new IllegalArgumentException("1 <= minTerm <= maxTerm <= 600 required");
        }
        allocationOrder = List.copyOf(allocationOrder);
        // Validates the order (all components, once each).
        new RepaymentAllocator(allocationStrategy, allocationOrder);
    }

    /** Monthly periodic rate on a 30/360 basis. */
    public BigDecimal periodicRate() {
        return annualRate.divide(BigDecimal.valueOf(12), PERIODIC_RATE_SCALE, RoundingMode.HALF_EVEN);
    }

    /** Checks an application's amount and term against this version's limits. */
    public void validateRequest(Money principal, int termMonths) {
        Objects.requireNonNull(principal, "principal");
        if (!principal.currency().equals(currency)) {
            throw new IllegalArgumentException("principal must be in " + currency);
        }
        if (principal.compareTo(minPrincipal) < 0 || principal.compareTo(maxPrincipal) > 0) {
            throw new IllegalArgumentException("principal " + principal + " outside " + minPrincipal + " - " + maxPrincipal);
        }
        if (termMonths < minTermMonths || termMonths > maxTermMonths) {
            throw new IllegalArgumentException("term " + termMonths + " outside " + minTermMonths + " - " + maxTermMonths);
        }
    }

    public List<Installment> schedule(Money principal, int termMonths) {
        validateRequest(principal, termMonths);
        return switch (interestMethod) {
            case REDUCING_BALANCE -> ScheduleCalculator.reducingBalance(principal, periodicRate(), termMonths, rounding);
            case FLAT -> ScheduleCalculator.flat(principal, periodicRate(), termMonths, rounding);
        };
    }

    /** Upfront fee deducted at disbursement. */
    public Money upfrontFee(Money principal) {
        return principal.times(upfrontFeeRate, rounding);
    }

    public RepaymentAllocator allocator() {
        return new RepaymentAllocator(allocationStrategy, allocationOrder);
    }
}
