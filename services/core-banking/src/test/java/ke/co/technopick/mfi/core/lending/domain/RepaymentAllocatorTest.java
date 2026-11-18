package ke.co.technopick.mfi.core.lending.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import ke.co.technopick.mfi.canonical.Money;
import ke.co.technopick.mfi.core.lending.domain.RepaymentAllocator.Allocation;
import ke.co.technopick.mfi.core.lending.domain.RepaymentAllocator.Result;
import ke.co.technopick.mfi.core.lending.domain.RepaymentAllocator.Strategy;
import org.junit.jupiter.api.Test;

class RepaymentAllocatorTest {

    private static final Currency KES = Currency.getInstance("KES");

    private static Money kes(String v) {
        return Money.of(v, "KES");
    }

    /** Two overdue installments of the section 5.1 loan, with a penalty on the first. */
    private static List<InstallmentDue> twoOverdue() {
        return List.of(
                new InstallmentDue(2, LocalDate.of(2026, 10, 18), Map.of(
                        DueComponent.INTEREST, kes("1850.88"), DueComponent.PRINCIPAL, kes("7605.08"))),
                new InstallmentDue(1, LocalDate.of(2026, 9, 18), Map.of(
                        DueComponent.PENALTY, kes("200.00"),
                        DueComponent.INTEREST, kes("2000.00"), DueComponent.PRINCIPAL, kes("7455.96"))));
    }

    @Test
    void exactInstallmentPaymentClearsOldestFirst() {
        Result r = RepaymentAllocator.standard().allocate(kes("9655.96"), twoOverdue());
        // Component-first: penalty (inst 1), interest inst 1, interest inst 2, then principal inst 1, inst 2.
        assertEquals(List.of(
                new Allocation(1, DueComponent.PENALTY, kes("200.00")),
                new Allocation(1, DueComponent.INTEREST, kes("2000.00")),
                new Allocation(2, DueComponent.INTEREST, kes("1850.88")),
                new Allocation(1, DueComponent.PRINCIPAL, kes("5605.08"))), r.allocations());
        assertTrue(r.unallocated().isZero());
    }

    @Test
    void installmentFirstClearsWholeOldestInstallment() {
        RepaymentAllocator a = new RepaymentAllocator(Strategy.INSTALLMENT_FIRST, List.of(DueComponent.values()));
        Result r = a.allocate(kes("9655.96"), twoOverdue());
        assertEquals(List.of(
                new Allocation(1, DueComponent.PENALTY, kes("200.00")),
                new Allocation(1, DueComponent.INTEREST, kes("2000.00")),
                new Allocation(1, DueComponent.PRINCIPAL, kes("7455.96"))), r.allocations());
        assertTrue(r.unallocated().isZero());
    }

    @Test
    void overpaymentIsReturnedNotSilentlyApplied() {
        Result r = RepaymentAllocator.standard().allocate(kes("20000.00"), twoOverdue());
        assertEquals(kes("19111.92"), r.totalAllocated(KES));
        assertEquals(kes("888.08"), r.unallocated());
    }

    @Test
    void customOrderPrincipalBeforeInterest() {
        RepaymentAllocator a = new RepaymentAllocator(Strategy.COMPONENT_FIRST,
                List.of(DueComponent.PRINCIPAL, DueComponent.INTEREST, DueComponent.FEE, DueComponent.PENALTY));
        Result r = a.allocate(kes("100.00"), twoOverdue());
        assertEquals(List.of(new Allocation(1, DueComponent.PRINCIPAL, kes("100.00"))), r.allocations());
    }

    @Test
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class,
                () -> RepaymentAllocator.standard().allocate(kes("0.00"), twoOverdue()));
        assertThrows(IllegalArgumentException.class,
                () -> RepaymentAllocator.standard().allocate(Money.of("10", "USD"), twoOverdue()));
        assertThrows(IllegalArgumentException.class,
                () -> new RepaymentAllocator(Strategy.COMPONENT_FIRST, List.of(DueComponent.PRINCIPAL)));
        assertThrows(IllegalArgumentException.class, () -> new InstallmentDue(1, LocalDate.of(2026, 1, 1),
                Map.of(DueComponent.FEE, kes("-1.00"))));
    }

    /**
     * Seeded property test: for both strategies, allocations + unallocated == payment exactly,
     * no component is over-allocated, and every allocation is positive.
     */
    @Test
    void invariantsHoldForRandomCases() {
        Random rnd = new Random(42L);
        for (int i = 0; i < 3000; i++) {
            List<InstallmentDue> dues = new ArrayList<>();
            int n = 1 + rnd.nextInt(6);
            for (int k = 1; k <= n; k++) {
                Map<DueComponent, Money> m = new EnumMap<>(DueComponent.class);
                for (DueComponent c : DueComponent.values()) {
                    if (rnd.nextInt(3) > 0) {
                        m.put(c, Money.of(java.math.BigDecimal.valueOf(rnd.nextInt(2_000_000), 2), KES));
                    }
                }
                dues.add(new InstallmentDue(k, LocalDate.of(2026, 1, 1).plusMonths(rnd.nextInt(12)), m));
            }
            Money payment = Money.of(java.math.BigDecimal.valueOf(1 + rnd.nextInt(5_000_000), 2), KES);
            for (Strategy s : Strategy.values()) {
                Result r = new RepaymentAllocator(s, List.of(DueComponent.values())).allocate(payment, dues);
                assertEquals(payment, r.totalAllocated(KES).plus(r.unallocated()), "sum, case " + i);
                assertTrue(!r.unallocated().isNegative(), "unallocated >= 0, case " + i);
                for (InstallmentDue d : dues) {
                    for (DueComponent c : DueComponent.values()) {
                        Money allocated = r.allocations().stream()
                                .filter(a -> a.installmentNumber() == d.installmentNumber() && a.component() == c)
                                .map(Allocation::amount).reduce(Money.zero(KES), Money::plus);
                        assertTrue(allocated.compareTo(d.outstanding(c, KES)) <= 0, "over-allocation, case " + i);
                    }
                }
                for (Allocation a : r.allocations()) {
                    assertTrue(a.amount().isPositive(), "positive, case " + i);
                }
            }
        }
    }
}
