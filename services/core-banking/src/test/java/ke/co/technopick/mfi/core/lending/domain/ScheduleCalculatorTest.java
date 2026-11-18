package ke.co.technopick.mfi.core.lending.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Random;
import ke.co.technopick.mfi.canonical.Money;
import org.junit.jupiter.api.Test;

/** Worked examples from the Phase 0 design, sections 5.1 and 5.2. */
class ScheduleCalculatorTest {

    private static final RoundingMode HE = RoundingMode.HALF_EVEN;

    private static Money kes(String v) {
        return Money.of(v, "KES");
    }

    @Test
    void reducingBalanceWorkedExample() {
        List<Installment> s = ScheduleCalculator.reducingBalance(kes("100000"), new BigDecimal("0.02"), 12, HE);

        assertEquals(12, s.size());
        assertEquals(kes("9455.96"), s.get(0).total());
        assertEquals(kes("7455.96"), s.get(0).principal());
        assertEquals(kes("2000.00"), s.get(0).interest());
        assertEquals(kes("92544.04"), s.get(0).closingBalance());

        assertEquals(kes("7605.08"), s.get(1).principal());
        assertEquals(kes("1850.88"), s.get(1).interest());
        assertEquals(kes("84938.96"), s.get(1).closingBalance());

        Installment last = s.get(11);
        assertEquals(kes("9455.97"), last.total());
        assertEquals(kes("9270.56"), last.principal());
        assertEquals(kes("185.41"), last.interest());
        assertTrue(last.closingBalance().isZero());

        assertEquals(kes("13471.53"), sum(s, false));
        assertEquals(kes("100000.00"), sum(s, true));
    }

    @Test
    void flatWorkedExample() {
        List<Installment> s = ScheduleCalculator.flat(kes("100000"), new BigDecimal("0.02"), 12, HE);
        assertEquals(kes("10333.33"), s.get(0).total());
        assertEquals(kes("10333.37"), s.get(11).total());
        assertEquals(kes("24000.00"), sum(s, false));
        assertEquals(kes("100000.00"), sum(s, true));
    }

    @Test
    void zeroRateFallsBackToEqualPrincipal() {
        List<Installment> s = ScheduleCalculator.reducingBalance(kes("1000"), BigDecimal.ZERO, 3, HE);
        assertEquals(kes("333.33"), s.get(0).total());
        assertEquals(kes("333.34"), s.get(2).total());
        assertEquals(kes("0.00"), sum(s, false));
    }

    /**
     * Property (seeded, deterministic): for any principal, rate and term, principal components sum
     * exactly to the loan amount, the balance ends at zero and never goes negative.
     * Moves to jqwik @Property once the networked build is available.
     */
    @Test
    void invariantsHoldForRandomLoans() {
        Random random = new Random(20261118L);
        for (int i = 0; i < 2000; i++) {
            Money principal = Money.of(BigDecimal.valueOf(100 + random.nextInt(5_000_000), 2).movePointRight(2)
                    .add(BigDecimal.valueOf(random.nextInt(100), 2)), java.util.Currency.getInstance("KES"));
            BigDecimal rate = BigDecimal.valueOf(random.nextInt(800), 4); // 0% - 7.99% per period
            int periods = 1 + random.nextInt(60);
            for (List<Installment> s : List.of(
                    ScheduleCalculator.reducingBalance(principal, rate, periods, HE),
                    ScheduleCalculator.flat(principal, rate, periods, HE))) {
                assertEquals(principal, sum(s, true), "principal sum, case " + i);
                assertTrue(s.get(s.size() - 1).closingBalance().isZero(), "zero close, case " + i);
                for (Installment in : s) {
                    assertTrue(!in.closingBalance().isNegative() && !in.principal().isNegative()
                            && !in.interest().isNegative(), "no negatives, case " + i);
                }
            }
        }
    }

    @Test
    void rejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class,
                () -> ScheduleCalculator.reducingBalance(kes("0"), new BigDecimal("0.02"), 12, HE));
        assertThrows(IllegalArgumentException.class,
                () -> ScheduleCalculator.reducingBalance(kes("100"), new BigDecimal("-0.01"), 12, HE));
        assertThrows(IllegalArgumentException.class,
                () -> ScheduleCalculator.flat(kes("100"), new BigDecimal("0.01"), 0, HE));
    }

    private static Money sum(List<Installment> s, boolean principal) {
        Money total = Money.zero("KES");
        for (Installment i : s) {
            total = total.plus(principal ? i.principal() : i.interest());
        }
        return total;
    }
}
