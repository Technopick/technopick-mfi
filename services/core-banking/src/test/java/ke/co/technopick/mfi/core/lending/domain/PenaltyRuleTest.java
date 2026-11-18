package ke.co.technopick.mfi.core.lending.domain;

import static ke.co.technopick.mfi.core.lending.domain.LoanFixtures.kes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PenaltyRuleTest {

    private static final LocalDate DUE = LocalDate.of(2026, 12, 18);
    private static final PenaltyRule FLAT = new PenaltyRule(PenaltyRule.Method.FLAT_ONCE, 3, kes("200"), null, null,
            RoundingMode.HALF_EVEN);
    private static final PenaltyRule DAILY = new PenaltyRule(PenaltyRule.Method.DAILY_RATE, 0, null,
            new BigDecimal("0.001"), kes("500"), RoundingMode.HALF_EVEN);

    @Test
    void flatChargedOnceAfterGrace() {
        assertEquals(kes("0"), FLAT.chargeFor(DUE, DUE.plusDays(3), kes("9455.96"), kes("0")));
        assertEquals(kes("200"), FLAT.chargeFor(DUE, DUE.plusDays(4), kes("9455.96"), kes("0")));
        assertEquals(kes("0"), FLAT.chargeFor(DUE, DUE.plusDays(5), kes("9455.96"), kes("200")));
    }

    @Test
    void dailyRateOnTodaysUnpaidAmount() {
        assertEquals(kes("0"), DAILY.chargeFor(DUE, DUE, kes("9455.96"), kes("0")));
        assertEquals(kes("9.46"), DAILY.chargeFor(DUE, DUE.plusDays(1), kes("9455.96"), kes("0")));
        // After a partial payment only the remaining unpaid amount attracts the penalty.
        assertEquals(kes("1.00"), DAILY.chargeFor(DUE, DUE.plusDays(2), kes("1000.00"), kes("9.46")));
    }

    @Test
    void capLimitsTotalPerInstallment() {
        assertEquals(kes("4.00"), DAILY.chargeFor(DUE, DUE.plusDays(60), kes("9455.96"), kes("496.00")));
        assertEquals(kes("0"), DAILY.chargeFor(DUE, DUE.plusDays(61), kes("9455.96"), kes("500.00")));
    }

    @Test
    void nothingWhenPaidOrNoRule() {
        assertEquals(kes("0"), FLAT.chargeFor(DUE, DUE.plusDays(10), kes("0"), kes("0")));
        assertEquals(kes("0"), PenaltyRule.none().chargeFor(DUE, DUE.plusDays(10), kes("100"), kes("0")));
    }

    @Test
    void invalidRulesRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PenaltyRule(PenaltyRule.Method.DAILY_RATE, 0, null,
                new BigDecimal("0.05"), null, RoundingMode.HALF_EVEN));
        assertThrows(IllegalArgumentException.class, () -> new PenaltyRule(PenaltyRule.Method.FLAT_ONCE, 0, null,
                null, null, RoundingMode.HALF_EVEN));
        assertThrows(IllegalArgumentException.class, () -> new PenaltyRule(PenaltyRule.Method.NONE, -1, null,
                null, null, RoundingMode.HALF_EVEN));
    }
}
