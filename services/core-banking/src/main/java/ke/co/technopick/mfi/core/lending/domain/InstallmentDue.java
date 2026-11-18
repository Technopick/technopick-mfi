package ke.co.technopick.mfi.core.lending.domain;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import ke.co.technopick.mfi.canonical.Money;

/**
 * Outstanding (unpaid) amounts on one installment at the time a payment is applied.
 *
 * @param installmentNumber 1-based installment sequence
 * @param dueDate           contractual due date (oldest is paid first)
 * @param outstanding       unpaid amount per component; missing components count as zero
 */
public record InstallmentDue(int installmentNumber, LocalDate dueDate, Map<DueComponent, Money> outstanding) {

    public InstallmentDue {
        Objects.requireNonNull(dueDate, "dueDate");
        Objects.requireNonNull(outstanding, "outstanding");
        if (installmentNumber < 1) {
            throw new IllegalArgumentException("installmentNumber must be >= 1");
        }
        outstanding.forEach((component, amount) -> {
            Objects.requireNonNull(component, "component");
            if (amount.isNegative()) {
                throw new IllegalArgumentException("Outstanding " + component + " must not be negative");
            }
        });
        outstanding = Map.copyOf(new EnumMap<>(outstanding));
    }

    public Money outstanding(DueComponent component, java.util.Currency currency) {
        return outstanding.getOrDefault(component, Money.zero(currency));
    }
}
