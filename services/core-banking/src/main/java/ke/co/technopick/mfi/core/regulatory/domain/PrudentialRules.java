package ke.co.technopick.mfi.core.regulatory.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * The prudential rules of the Sacco Societies (Deposit-taking Sacco Business) Regulations, 2010
 * (LN 95/2010) as configured for a tenant (ADR-0030). Pure: every rule is unit-tested here and
 * the report only gathers the inputs. Money is BigDecimal throughout; nothing is a float.
 */
public final class PrudentialRules {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private PrudentialRules() {
    }

    /** Loan classes of Reg 41(3), from the least to the most at risk. */
    public enum LoanClass { PERFORMING, WATCH, SUBSTANDARD, DOUBTFUL, LOSS;

        public boolean nonPerforming() {
            return this == SUBSTANDARD || this == DOUBTFUL || this == LOSS;
        }
    }

    public record Settings(BigDecimal minCoreCapital, BigDecimal minCoreToAssetsPct, BigDecimal minInstitutionalToAssetsPct,
                           BigDecimal minCoreToDepositsPct, BigDecimal minLiquidityPct, int watchMaxDpd, int substandardMaxDpd,
                           int doubtfulMaxDpd, BigDecimal provisionPerformingPct, BigDecimal provisionWatchPct,
                           BigDecimal provisionSubstandardPct, BigDecimal provisionDoubtfulPct, BigDecimal provisionLossPct,
                           boolean includeCurrentYearSurplus) {
        public Settings {
            Objects.requireNonNull(minCoreCapital, "minCoreCapital");
            if (!(0 < watchMaxDpd && watchMaxDpd < substandardMaxDpd && substandardMaxDpd < doubtfulMaxDpd)) {
                throw new IllegalArgumentException("arrears bands must increase: 0 < watch < substandard < doubtful");
            }
            BigDecimal[] p = {provisionPerformingPct, provisionWatchPct, provisionSubstandardPct, provisionDoubtfulPct, provisionLossPct};
            for (int i = 0; i < p.length; i++) {
                if (p[i] == null || p[i].signum() < 0 || p[i].compareTo(HUNDRED) > 0 || (i > 0 && p[i].compareTo(p[i - 1]) < 0)) {
                    throw new IllegalArgumentException("loan loss allowance rates must be 0-100% and never fall as risk rises");
                }
            }
            for (BigDecimal r : new BigDecimal[] {minCoreToAssetsPct, minInstitutionalToAssetsPct, minCoreToDepositsPct, minLiquidityPct}) {
                if (r == null || r.signum() < 0 || r.compareTo(HUNDRED) > 0) {
                    throw new IllegalArgumentException("minimum ratios must be percentages between 0 and 100");
                }
            }
            if (minCoreCapital.signum() < 0) {
                throw new IllegalArgumentException("minimum core capital cannot be negative");
            }
        }

        /** LN 95/2010: Reg 9 capital, Reg 13 liquidity, Reg 41(3) classes, Reg 44(1) allowances. */
        public static Settings regulationDefaults() {
            return new Settings(new BigDecimal("10000000.00"), new BigDecimal("10.00"), new BigDecimal("8.00"),
                    new BigDecimal("8.00"), new BigDecimal("15.00"), 30, 180, 360, new BigDecimal("1.00"),
                    new BigDecimal("5.00"), new BigDecimal("25.00"), new BigDecimal("50.00"), new BigDecimal("100.00"), true);
        }

        public BigDecimal provisionPct(LoanClass c) {
            return switch (c) {
                case PERFORMING -> provisionPerformingPct;
                case WATCH -> provisionWatchPct;
                case SUBSTANDARD -> provisionSubstandardPct;
                case DOUBTFUL -> provisionDoubtfulPct;
                case LOSS -> provisionLossPct;
            };
        }
    }

    /** Reg 41(3) by days past due, with the tenant's band limits. */
    public static LoanClass classify(int daysPastDue, Settings s) {
        if (daysPastDue < 0) {
            throw new IllegalArgumentException("days past due cannot be negative");
        }
        if (daysPastDue == 0) {
            return LoanClass.PERFORMING;
        }
        if (daysPastDue <= s.watchMaxDpd()) {
            return LoanClass.WATCH;
        }
        if (daysPastDue <= s.substandardMaxDpd()) {
            return LoanClass.SUBSTANDARD;
        }
        if (daysPastDue <= s.doubtfulMaxDpd()) {
            return LoanClass.DOUBTFUL;
        }
        return LoanClass.LOSS;
    }

    /** Reg 44(1): the minimum allowance for one loan, to the cent, rounded half up. */
    public static BigDecimal requiredAllowance(BigDecimal outstandingPrincipal, LoanClass c, Settings s) {
        return outstandingPrincipal.multiply(s.provisionPct(c)).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    /** numerator / denominator as a percentage to two places; null when the denominator is zero. */
    public static BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        if (denominator.signum() == 0) {
            return null;
        }
        return numerator.multiply(HUNDRED).divide(denominator, 2, RoundingMode.HALF_UP);
    }

    /**
     * Whether a ratio meets its minimum. Compared on the exact fraction, not the rounded
     * percentage, so 9.996% never passes a 10% minimum. A zero denominator has nothing to measure.
     */
    public static Boolean meets(BigDecimal numerator, BigDecimal denominator, BigDecimal minimumPct) {
        if (denominator.signum() == 0) {
            return null;
        }
        if (denominator.signum() < 0) {
            return false;
        }
        return numerator.multiply(HUNDRED).compareTo(minimumPct.multiply(denominator)) >= 0;
    }
}
