package ke.co.technopick.mfi.core.collections.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import ke.co.technopick.mfi.canonical.Money;
import ke.co.technopick.mfi.core.lending.domain.DueComponent;
import ke.co.technopick.mfi.core.lending.domain.InstallmentDue;

/**
 * Days past due (DPD), arrears amount and ageing bucket for one loan.
 *
 * <p>Definition (arrears/v1, docs/architecture/portfolio-metrics.md):
 * <ul>
 *   <li>An installment is overdue on {@code asOf} when its due date is before {@code asOf}
 *       and its unpaid principal + interest exceeds the tolerance. Penalties and fees do not
 *       make an installment overdue on their own.</li>
 *   <li>DPD = days from the oldest overdue installment's due date to {@code asOf}
 *       (an installment due yesterday and unpaid is 1 DPD).</li>
 *   <li>Arrears amount = unpaid principal + interest of all overdue installments.</li>
 * </ul>
 */
public final class ArrearsCalculator {

    public static final String METHOD_VERSION = "arrears/v1";

    /** Operational ageing bucket used in collections work queues and PAR reports. */
    public enum Bucket { CURRENT, DPD_1_30, DPD_31_60, DPD_61_90, DPD_91_180, DPD_OVER_180 }

    /**
     * Prudential-style classification. Thresholds follow the commonly applied CBK risk
     * classification (Normal / Watch / Substandard / Doubtful / Loss) and are configuration
     * that MUST be confirmed by compliance for each tenant's regulator before use in returns.
     */
    public enum Classification { NORMAL, WATCH, SUBSTANDARD, DOUBTFUL, LOSS }

    public record Position(long daysPastDue, Bucket bucket, Classification classification, Money arrearsAmount,
                           LocalDate oldestOverdueDueDate) { }

    private ArrearsCalculator() {
    }

    public static Position position(List<InstallmentDue> installments, LocalDate asOf, Money tolerance) {
        Objects.requireNonNull(installments, "installments");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(tolerance, "tolerance");
        Currency ccy = tolerance.currency();
        Money arrears = Money.zero(ccy);
        LocalDate oldest = null;
        for (InstallmentDue due : installments) {
            if (!due.dueDate().isBefore(asOf)) {
                continue;
            }
            Money unpaid = due.outstanding(DueComponent.PRINCIPAL, ccy).plus(due.outstanding(DueComponent.INTEREST, ccy));
            if (unpaid.compareTo(tolerance) > 0) {
                arrears = arrears.plus(unpaid);
                if (oldest == null || due.dueDate().isBefore(oldest)) {
                    oldest = due.dueDate();
                }
            }
        }
        long dpd = oldest == null ? 0 : ChronoUnit.DAYS.between(oldest, asOf);
        return new Position(dpd, bucket(dpd), classification(dpd), arrears, oldest);
    }

    public static Bucket bucket(long dpd) {
        if (dpd <= 0) {
            return Bucket.CURRENT;
        } else if (dpd <= 30) {
            return Bucket.DPD_1_30;
        } else if (dpd <= 60) {
            return Bucket.DPD_31_60;
        } else if (dpd <= 90) {
            return Bucket.DPD_61_90;
        } else if (dpd <= 180) {
            return Bucket.DPD_91_180;
        }
        return Bucket.DPD_OVER_180;
    }

    public static Classification classification(long dpd) {
        if (dpd <= 30) {
            return Classification.NORMAL;
        } else if (dpd <= 90) {
            return Classification.WATCH;
        } else if (dpd <= 180) {
            return Classification.SUBSTANDARD;
        } else if (dpd <= 360) {
            return Classification.DOUBTFUL;
        }
        return Classification.LOSS;
    }
}
