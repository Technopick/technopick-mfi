package ke.co.technopick.mfi.core.lending.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import ke.co.technopick.mfi.canonical.Money;

/**
 * Allocates a repayment across due installments according to the loan's product version.
 *
 * <p>Specification: docs/architecture/loan-calculations.md, "Repayment allocation".
 * Installments are always paid oldest due date first. Two strategies:
 * <ul>
 *   <li>{@link Strategy#COMPONENT_FIRST}: for each component in the configured order, clear it
 *       across all installments (oldest first) before moving to the next component.
 *       E.g. all penalties, then all fees, then all interest, then all principal.</li>
 *   <li>{@link Strategy#INSTALLMENT_FIRST}: clear each installment completely (components in
 *       the configured order) before moving to the next installment.</li>
 * </ul>
 * Whatever is left after every due amount is cleared is returned as {@code unallocated};
 * the caller applies the product's overpayment rule (prepay future principal or hold as
 * overpayment liability). This class never decides that silently.
 */
public final class RepaymentAllocator {

    public enum Strategy { COMPONENT_FIRST, INSTALLMENT_FIRST }

    public static final String METHOD_VERSION = "allocation/v1";

    /** One allocation line; their sum plus {@code unallocated} equals the payment exactly. */
    public record Allocation(int installmentNumber, DueComponent component, Money amount) { }

    public record Result(List<Allocation> allocations, Money unallocated) {
        public Result {
            allocations = List.copyOf(allocations);
        }

        public Money totalAllocated(Currency currency) {
            return allocations.stream().map(Allocation::amount).reduce(Money.zero(currency), Money::plus);
        }
    }

    private final Strategy strategy;
    private final List<DueComponent> order;

    public RepaymentAllocator(Strategy strategy, List<DueComponent> order) {
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(order, "order");
        if (order.size() != DueComponent.values().length || order.stream().distinct().count() != order.size()) {
            throw new IllegalArgumentException("order must list every DueComponent exactly once");
        }
        this.order = List.copyOf(order);
    }

    /** Default: penalties, fees, interest, principal; component-first. */
    public static RepaymentAllocator standard() {
        return new RepaymentAllocator(Strategy.COMPONENT_FIRST, List.of(DueComponent.values()));
    }

    public Result allocate(Money payment, List<InstallmentDue> dues) {
        Objects.requireNonNull(payment, "payment");
        Objects.requireNonNull(dues, "dues");
        if (!payment.isPositive()) {
            throw new IllegalArgumentException("payment must be positive");
        }
        Currency ccy = payment.currency();
        for (InstallmentDue due : dues) {
            due.outstanding().values().forEach(m -> {
                if (!m.currency().equals(ccy)) {
                    throw new IllegalArgumentException("Due currency " + m.currency() + " differs from payment " + ccy);
                }
            });
        }
        List<InstallmentDue> sorted = dues.stream()
                .sorted(Comparator.comparing(InstallmentDue::dueDate, LocalDate::compareTo)
                        .thenComparingInt(InstallmentDue::installmentNumber))
                .toList();

        List<Allocation> out = new ArrayList<>();
        Money remaining = payment;
        if (strategy == Strategy.COMPONENT_FIRST) {
            for (DueComponent component : order) {
                for (InstallmentDue due : sorted) {
                    remaining = take(due, component, remaining, ccy, out);
                }
            }
        } else {
            for (InstallmentDue due : sorted) {
                for (DueComponent component : order) {
                    remaining = take(due, component, remaining, ccy, out);
                }
            }
        }
        return new Result(out, remaining);
    }

    private static Money take(InstallmentDue due, DueComponent component, Money remaining, Currency ccy,
                              List<Allocation> out) {
        if (remaining.isZero()) {
            return remaining;
        }
        Money owed = due.outstanding(component, ccy);
        if (owed.isZero()) {
            return remaining;
        }
        Money paid = owed.compareTo(remaining) <= 0 ? owed : remaining;
        out.add(new Allocation(due.installmentNumber(), component, paid));
        return remaining.minus(paid);
    }
}
