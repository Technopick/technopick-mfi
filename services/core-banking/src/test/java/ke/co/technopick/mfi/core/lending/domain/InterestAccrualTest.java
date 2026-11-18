package ke.co.technopick.mfi.core.lending.domain;

import static ke.co.technopick.mfi.core.lending.domain.LoanFixtures.kes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Random;
import ke.co.technopick.mfi.canonical.Money;
import org.junit.jupiter.api.Test;

class InterestAccrualTest {

    private static final RoundingMode HE = RoundingMode.HALF_EVEN;
    private static final LocalDate START = LocalDate.of(2026, 11, 18);
    private static final LocalDate DUE = LocalDate.of(2026, 12, 18); // 30 days

    @Test
    void workedExampleFirstInstallment() {
        // KES 2,000.00 over 30 days: 66.67 on day 1, cumulative rounding thereafter.
        assertEquals(kes("66.67"), InterestAccrual.accruedToDate(kes("2000.00"), START, DUE, START.plusDays(1), HE));
        assertEquals(kes("133.33"), InterestAccrual.accruedToDate(kes("2000.00"), START, DUE, START.plusDays(2), HE));
        assertEquals(kes("2000.00"), InterestAccrual.accruedToDate(kes("2000.00"), START, DUE, DUE, HE));
        assertEquals(kes("2000.00"), InterestAccrual.accruedToDate(kes("2000.00"), START, DUE, DUE.plusDays(10), HE));
        assertEquals(kes("0.00"), InterestAccrual.accruedToDate(kes("2000.00"), START, DUE, START, HE));
    }

    @Test
    void dailyAmountsSumExactlyToPeriodInterest() {
        Money posted = kes("0");
        for (LocalDate d = START.plusDays(1); !d.isAfter(DUE); d = d.plusDays(1)) {
            Money today = InterestAccrual.accrualDue(kes("1850.88"), START, DUE, d, posted, HE);
            assertTrue(!today.isNegative());
            posted = posted.plus(today);
        }
        assertEquals(kes("1850.88"), posted);
    }

    @Test
    void rerunSameDayAccruesNothing() {
        Money first = InterestAccrual.accrualDue(kes("2000.00"), START, DUE, START.plusDays(5), kes("0"), HE);
        assertEquals(kes("0.00"), InterestAccrual.accrualDue(kes("2000.00"), START, DUE, START.plusDays(5), first, HE));
    }

    @Test
    void missedEodIsCaughtUp() {
        Money day1 = InterestAccrual.accrualDue(kes("2000.00"), START, DUE, START.plusDays(1), kes("0"), HE);
        // Days 2 and 3 missed; day 4 run posts the three days together.
        Money day4 = InterestAccrual.accrualDue(kes("2000.00"), START, DUE, START.plusDays(4), day1, HE);
        assertEquals(kes("266.67"), day1.plus(day4));
    }

    @Test
    void overAccrualIsDetected() {
        assertThrows(IllegalStateException.class,
                () -> InterestAccrual.accrualDue(kes("2000.00"), START, DUE, START.plusDays(1), kes("100.00"), HE));
    }

    @Test
    void propertyRandomPeriodsSumExactly() {
        Random rnd = new Random(7L);
        for (int i = 0; i < 2000; i++) {
            Money interest = Money.of(BigDecimal.valueOf(rnd.nextInt(10_000_000), 2), java.util.Currency.getInstance("KES"));
            LocalDate start = LocalDate.of(2026, 1, 1).plusDays(rnd.nextInt(365));
            LocalDate due = start.plusDays(1 + rnd.nextInt(92));
            Money posted = kes("0");
            Money previousTarget = kes("0");
            for (LocalDate d = start.plusDays(1); !d.isAfter(due); d = d.plusDays(1 + (rnd.nextInt(10) == 0 ? 2 : 0))) {
                Money target = InterestAccrual.accruedToDate(interest, start, due, d, HE);
                assertTrue(target.compareTo(previousTarget) >= 0, "monotonic, case " + i);
                previousTarget = target;
                posted = posted.plus(InterestAccrual.accrualDue(interest, start, due, d, posted, HE));
            }
            posted = posted.plus(InterestAccrual.accrualDue(interest, start, due, due, posted, HE));
            assertEquals(interest, posted, "sum, case " + i);
        }
    }
}
