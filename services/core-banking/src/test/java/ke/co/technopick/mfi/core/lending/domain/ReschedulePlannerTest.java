package ke.co.technopick.mfi.core.lending.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import ke.co.technopick.mfi.canonical.Money;
import ke.co.technopick.mfi.core.lending.domain.ReschedulePlanner.Current;
import ke.co.technopick.mfi.core.lending.domain.ReschedulePlanner.Kind;
import ke.co.technopick.mfi.core.lending.domain.ReschedulePlanner.Plan;
import ke.co.technopick.mfi.core.lending.domain.ReschedulePlanner.PrepaymentMode;
import ke.co.technopick.mfi.core.lending.domain.ReschedulePlanner.Request;
import ke.co.technopick.mfi.core.lending.domain.ReschedulePlanner.Row;
import org.junit.jupiter.api.Test;

/** 12,000.00 at 3% a month over 12 instalments from 1 Oct 2026; today is 28 Nov 2026 (instalment 3 is accruing). */
class ReschedulePlannerTest {

    static final Currency KES = Currency.getInstance("KES");
    static final LoanProductVersion PRODUCT = new LoanProductVersion(UUID.randomUUID(), "P", 1, KES,
            LoanProductVersion.InterestMethod.REDUCING_BALANCE, new BigDecimal("0.36"), RoundingMode.HALF_EVEN,
            Money.of(new BigDecimal("1000"), KES), Money.of(new BigDecimal("100000"), KES), 1, 36, BigDecimal.ZERO,
            RepaymentAllocator.Strategy.COMPONENT_FIRST, List.of(DueComponent.values()), LoanFixtures.GL);
    static final LocalDate TODAY = LocalDate.of(2026, 11, 28);
    static final List<Installment> ORIGINAL = ScheduleCalculator.reducingBalance(Money.of(new BigDecimal("12000"), KES),
            new BigDecimal("0.03"), 12, RoundingMode.HALF_EVEN);

    /** The original schedule; the first {@code paid} instalments fully paid; 200.00 accrued on instalment 3. */
    static List<Current> schedule(int paid) {
        List<Current> out = new ArrayList<>();
        for (Installment i : ORIGINAL) {
            boolean isPaid = i.number() <= paid;
            BigDecimal p = i.principal().amount();
            BigDecimal in = i.interest().amount();
            BigDecimal accrued = i.number() <= 2 ? in : i.number() == 3 ? new BigDecimal("200.00") : BigDecimal.ZERO;
            out.add(new Current(i.number(), LocalDate.of(2026, 10, 1).plusMonths(i.number() - 1L), p, in,
                    isPaid ? p : BigDecimal.ZERO, isPaid ? in : BigDecimal.ZERO, accrued, BigDecimal.ZERO));
        }
        return out;
    }

    static BigDecimal principal(List<Row> rows) {
        return rows.stream().map(Row::principal).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static void datesRise(List<Row> rows) {
        for (int i = 1; i < rows.size(); i++) {
            assertEquals(rows.get(i - 1).no() + 1, rows.get(i).no());
            assertTrue(rows.get(i).due().isAfter(rows.get(i - 1).due()));
        }
    }

    @Test
    void restructureMovesOverduePrincipalIntoANewPlanAfterGraceMonths() {
        List<Current> now = schedule(0);
        Plan plan = ReschedulePlanner.plan(PRODUCT, now, TODAY, new Request(Kind.RESTRUCTURE, 12, 2, null, null));
        List<Row> rows = plan.rows();
        assertEquals(16, rows.size());
        assertEquals(0, new BigDecimal("12000").compareTo(principal(rows)));
        datesRise(rows);
        // Overdue instalments keep their date and interest, with no principal left on them.
        for (int i = 0; i < 2; i++) {
            assertEquals(now.get(i).due(), rows.get(i).due());
            assertEquals(now.get(i).interest(), rows.get(i).interest());
            assertEquals(0, rows.get(i).principal().signum());
        }
        // The accruing instalment keeps its date and interest; it and the next are interest-only grace months.
        assertEquals(now.get(2).due(), rows.get(2).due());
        assertEquals(now.get(2).interest(), rows.get(2).interest());
        assertEquals(0, rows.get(2).principal().signum());
        assertEquals(0, rows.get(3).principal().signum());
        assertEquals(new BigDecimal("360.00"), rows.get(3).interest());
        assertEquals(new BigDecimal("1205.55"), plan.instalmentAmount());
        assertEquals(14, plan.instalments());
        assertEquals(now.get(0).interest().add(now.get(1).interest()), plan.stillDue());
        assertEquals(new BigDecimal("12000.00"), plan.outstanding().setScale(2));
    }

    @Test
    void prepaymentShortensTheTermOrLowersTheInstalment() {
        List<Current> now = schedule(2);
        BigDecimal oldInstalment = now.get(3).principal().add(now.get(3).interest());
        Plan shorter = ReschedulePlanner.plan(PRODUCT, now, TODAY,
                new Request(Kind.PREPAYMENT, null, 0, new BigDecimal("3000.00"), PrepaymentMode.SHORTEN_TERM));
        assertEquals(0, new BigDecimal("12000").compareTo(principal(shorter.rows())));
        datesRise(shorter.rows());
        assertEquals(now.get(2).principal().add(new BigDecimal("3000.00")), shorter.rows().get(2).principal());
        assertEquals(now.get(2).interest(), shorter.rows().get(2).interest());
        assertTrue(shorter.rows().size() < 12, "fewer instalments: " + shorter.rows().size());
        assertTrue(shorter.instalmentAmount().compareTo(oldInstalment) <= 0);
        assertTrue(shorter.interestAfter().compareTo(shorter.interestBefore()) < 0);

        Plan lower = ReschedulePlanner.plan(PRODUCT, now, TODAY,
                new Request(Kind.PREPAYMENT, null, 0, new BigDecimal("3000.00"), PrepaymentMode.REDUCE_INSTALMENT));
        assertEquals(12, lower.rows().size());
        assertEquals(0, new BigDecimal("12000").compareTo(principal(lower.rows())));
        assertTrue(lower.instalmentAmount().compareTo(oldInstalment) < 0);
        assertEquals(lower.rows().get(3).due(), LocalDate.of(2027, 1, 1));
    }

    @Test
    void prepaymentNeedsNoArrearsAndLeavesSomethingToPlan() {
        assertThrows(IllegalStateException.class, () -> ReschedulePlanner.plan(PRODUCT, schedule(0), TODAY,
                new Request(Kind.PREPAYMENT, null, 0, new BigDecimal("100.00"), PrepaymentMode.SHORTEN_TERM)));
        assertThrows(IllegalArgumentException.class, () -> ReschedulePlanner.plan(PRODUCT, schedule(2), TODAY,
                new Request(Kind.PREPAYMENT, null, 0, new BigDecimal("50000.00"), PrepaymentMode.SHORTEN_TERM)));
        assertThrows(IllegalArgumentException.class, () -> ReschedulePlanner.plan(PRODUCT, schedule(0), TODAY,
                new Request(Kind.RESTRUCTURE, 0, 0, null, null)));
    }

    @Test
    void laterInstalmentsWithPaymentsCannotBeReplanned() {
        List<Current> now = new ArrayList<>(schedule(0));
        Current five = now.get(4);
        now.set(4, new Current(five.no(), five.due(), five.principal(), five.interest(), BigDecimal.ONE, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO));
        assertThrows(IllegalStateException.class, () -> ReschedulePlanner.plan(PRODUCT, now, TODAY,
                new Request(Kind.RESTRUCTURE, 6, 0, null, null)));
    }
}
