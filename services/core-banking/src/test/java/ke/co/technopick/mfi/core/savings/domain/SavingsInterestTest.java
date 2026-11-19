package ke.co.technopick.mfi.core.savings.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SavingsInterestTest {

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    @Test
    void dailyIsAct365ToTenDecimals() {
        assertEquals(d("8.0000000000"), SavingsInterest.daily(d("40000"), d("7.3")));
        assertEquals(d("0.1643835616"), SavingsInterest.daily(d("1000"), d("6")));
        assertThrows(IllegalArgumentException.class, () -> SavingsInterest.daily(BigDecimal.ZERO, d("6")));
    }

    @Test
    void cumulativeRoundingNeverDrifts() {
        // 1,000 at 6%: 0.1643835616 a day. Posting each day's rounded amount would give 0.16 x 30 = 4.80;
        // the cumulative method posts 4.93, the rounded 30-day total.
        BigDecimal day = SavingsInterest.daily(d("1000"), d("6"));
        BigDecimal exact = BigDecimal.ZERO;
        BigDecimal posted = BigDecimal.ZERO;
        for (int i = 0; i < 30; i++) {
            BigDecimal today = SavingsInterest.postToday(exact, posted, day);
            assertTrue(today.signum() >= 0);
            exact = exact.add(day);
            posted = posted.add(today);
        }
        assertEquals(d("4.93"), posted);
        assertEquals(exact.setScale(2, java.math.RoundingMode.HALF_UP), posted);
    }

    @Test
    void taxIsRoundedHalfUpToCents() {
        assertEquals(d("1.20"), SavingsInterest.tax(d("8.00"), d("15")));
        assertEquals(d("0.74"), SavingsInterest.tax(d("4.93"), d("15"))); // 0.7395
        assertEquals(d("0.00"), SavingsInterest.tax(d("4.93"), BigDecimal.ZERO));
    }

    @Test
    void postsOnTheLastDayOfTheMonth() {
        assertTrue(SavingsInterest.isPostingDay(LocalDate.of(2026, 11, 30)));
        assertTrue(SavingsInterest.isPostingDay(LocalDate.of(2028, 2, 29)));
        assertFalse(SavingsInterest.isPostingDay(LocalDate.of(2026, 11, 29)));
    }
}
