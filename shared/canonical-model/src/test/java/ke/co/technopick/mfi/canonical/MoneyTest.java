package ke.co.technopick.mfi.canonical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class MoneyTest {

    private static final Currency KES = Currency.getInstance("KES");

    @Test
    void normalisesToCurrencyScale() {
        assertEquals("KES 100.00", Money.of("100", "KES").toString());
        assertEquals(Money.of("100.0", "KES"), Money.of("100.00", "KES"));
    }

    @Test
    void rejectsSubMinorUnitAmountsWithoutExplicitRounding() {
        assertThrows(IllegalArgumentException.class, () -> Money.of("10.005", "KES"));
    }

    @Test
    void roundsHalfEvenWhenAsked() {
        assertEquals(Money.of("10.00", "KES"), Money.of(new BigDecimal("10.005"), KES, RoundingMode.HALF_EVEN));
        assertEquals(Money.of("10.02", "KES"), Money.of(new BigDecimal("10.015"), KES, RoundingMode.HALF_EVEN));
    }

    @Test
    void rejectsCurrencyMixing() {
        assertThrows(IllegalArgumentException.class, () -> Money.of("1", "KES").plus(Money.of("1", "USD")));
    }

    @Test
    void additionIsExact() {
        Money sum = Money.zero(KES);
        for (int i = 0; i < 10; i++) {
            sum = sum.plus(Money.of("0.10", "KES"));
        }
        assertEquals(Money.of("1.00", "KES"), sum);
    }

    @Test
    void timesRoundsToMinorUnits() {
        assertEquals(Money.of("1850.88", "KES"), Money.of("92544.04", "KES").times(new BigDecimal("0.02"), RoundingMode.HALF_EVEN));
    }

    @Test
    void signHelpers() {
        assertTrue(Money.of("-1", "KES").isNegative());
        assertTrue(Money.zero("KES").isZero());
        assertTrue(Money.of("0.01", "KES").isPositive());
    }
}
