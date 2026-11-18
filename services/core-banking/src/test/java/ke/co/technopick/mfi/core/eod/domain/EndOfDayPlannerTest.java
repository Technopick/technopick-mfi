package ke.co.technopick.mfi.core.eod.domain;

import static ke.co.technopick.mfi.core.lending.domain.LoanFixtures.kes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ke.co.technopick.mfi.canonical.Money;
import ke.co.technopick.mfi.core.accounting.domain.JournalEntry;
import ke.co.technopick.mfi.core.collections.domain.ArrearsCalculator;
import ke.co.technopick.mfi.core.eod.domain.EndOfDayPlanner.InstallmentState;
import ke.co.technopick.mfi.core.eod.domain.EndOfDayPlanner.Plan;
import ke.co.technopick.mfi.core.lending.domain.DueComponent;
import ke.co.technopick.mfi.core.lending.domain.InstallmentDue;
import ke.co.technopick.mfi.core.lending.domain.LoanFixtures;
import ke.co.technopick.mfi.core.lending.domain.PenaltyRule;
import org.junit.jupiter.api.Test;

/** First two installments of the design example loan, disbursed 2026-11-18, no payments made. */
class EndOfDayPlannerTest {

    private static final LocalDate DISBURSED = LocalDate.of(2026, 11, 18);
    private static final LocalDate DUE1 = LocalDate.of(2026, 12, 18);
    private static final LocalDate DUE2 = LocalDate.of(2027, 1, 18);
    private static final PenaltyRule FLAT_200 = new PenaltyRule(PenaltyRule.Method.FLAT_ONCE, 3, kes("200"), null,
            null, RoundingMode.HALF_EVEN);

    private static List<InstallmentState> state(Map<Integer, Money> accrued, Map<Integer, Money> penalties) {
        return List.of(
                new InstallmentState(new InstallmentDue(1, DUE1, Map.of(DueComponent.PRINCIPAL, kes("7455.96"),
                        DueComponent.INTEREST, kes("2000.00"))), DISBURSED, kes("2000.00"),
                        accrued.getOrDefault(1, kes("0")), penalties.getOrDefault(1, kes("0"))),
                new InstallmentState(new InstallmentDue(2, DUE2, Map.of(DueComponent.PRINCIPAL, kes("7605.08"),
                        DueComponent.INTEREST, kes("1850.88"))), DUE1, kes("1850.88"),
                        accrued.getOrDefault(2, kes("0")), penalties.getOrDefault(2, kes("0"))));
    }

    @Test
    void firstDayAccruesOnlyTheCurrentPeriod() {
        Plan p = EndOfDayPlanner.plan(LoanFixtures.context(), DISBURSED.plusDays(1), state(Map.of(), Map.of()),
                FLAT_200, kes("0"), "eod-2026-11-19");
        assertEquals(1, p.accruals().size());
        assertEquals(kes("66.67"), p.accruals().get(0).amount());
        assertEquals("loan:" + LoanFixtures.LOAN + ":ACCRUE:2026-11-19", p.accrualEntry().idempotencyKey());
        assertTrue(p.penaltyEntries().isEmpty());
        assertEquals(0, p.arrears().daysPastDue());
    }

    /** Runs EOD every day from disbursement to 2027-01-20, feeding each plan back as state. */
    @Test
    void sixtyThreeDaysOfEodWithoutPayment() {
        Map<Integer, Money> accrued = new HashMap<>();
        Map<Integer, Money> penalties = new HashMap<>();
        Set<String> keys = new HashSet<>();
        List<LocalDate> penaltyDates = new ArrayList<>();
        Plan last = null;
        for (LocalDate d = DISBURSED.plusDays(1); !d.isAfter(LocalDate.of(2027, 1, 20)); d = d.plusDays(1)) {
            last = EndOfDayPlanner.plan(LoanFixtures.context(), d, state(accrued, penalties), FLAT_200, kes("0"), "eod");
            last.accruals().forEach(a -> accrued.merge(a.installmentNumber(), a.amount(), Money::plus));
            for (EndOfDayPlanner.InstallmentAmount pen : last.penalties()) {
                penalties.merge(pen.installmentNumber(), pen.amount(), Money::plus);
            }
            if (!last.penalties().isEmpty()) {
                penaltyDates.add(d);
            }
            if (last.accrualEntry() != null) {
                assertTrue(keys.add(last.accrualEntry().idempotencyKey()), "unique accrual key per day");
            }
            for (JournalEntry e : last.penaltyEntries()) {
                assertTrue(keys.add(e.idempotencyKey()), "unique penalty key");
            }
        }
        assertEquals(kes("2000.00"), accrued.get(1));
        assertEquals(kes("1850.88"), accrued.get(2));
        assertEquals(kes("200"), penalties.get(1));
        assertNull(penalties.get(2)); // due 2027-01-18, grace runs to 2027-01-21
        assertEquals(List.of(LocalDate.of(2026, 12, 22)), penaltyDates);
        assertEquals(33, last.arrears().daysPastDue());
        assertEquals(ArrearsCalculator.Bucket.DPD_31_60, last.arrears().bucket());
        assertEquals(kes("18911.92"), last.arrears().arrearsAmount());
    }

    @Test
    void replanningTheSameDateAfterPostingIsANoOp() {
        LocalDate d = DISBURSED.plusDays(10);
        Plan first = EndOfDayPlanner.plan(LoanFixtures.context(), d, state(Map.of(), Map.of()), FLAT_200, kes("0"), "c");
        Plan again = EndOfDayPlanner.plan(LoanFixtures.context(), d,
                state(Map.of(1, first.accruals().get(0).amount()), Map.of()), FLAT_200, kes("0"), "c");
        assertNull(again.accrualEntry());
        assertTrue(again.accruals().isEmpty());
    }
}
