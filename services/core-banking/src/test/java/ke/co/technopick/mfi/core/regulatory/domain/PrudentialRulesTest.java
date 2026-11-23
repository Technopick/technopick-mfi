package ke.co.technopick.mfi.core.regulatory.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import ke.co.technopick.mfi.core.regulatory.domain.PrudentialRules.LoanClass;
import ke.co.technopick.mfi.core.regulatory.domain.PrudentialRules.Settings;
import org.junit.jupiter.api.Test;

class PrudentialRulesTest {

    static final Settings REG = Settings.regulationDefaults();

    static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    @Test
    void classifiesByDaysPastDueAtTheRegulationBoundaries() {
        assertEquals(LoanClass.PERFORMING, PrudentialRules.classify(0, REG));
        assertEquals(LoanClass.WATCH, PrudentialRules.classify(1, REG));
        assertEquals(LoanClass.WATCH, PrudentialRules.classify(30, REG));
        assertEquals(LoanClass.SUBSTANDARD, PrudentialRules.classify(31, REG));
        assertEquals(LoanClass.SUBSTANDARD, PrudentialRules.classify(180, REG));
        assertEquals(LoanClass.DOUBTFUL, PrudentialRules.classify(181, REG));
        assertEquals(LoanClass.DOUBTFUL, PrudentialRules.classify(360, REG));
        assertEquals(LoanClass.LOSS, PrudentialRules.classify(361, REG));
        assertFalse(LoanClass.WATCH.nonPerforming());
        assertTrue(LoanClass.SUBSTANDARD.nonPerforming());
        assertThrows(IllegalArgumentException.class, () -> PrudentialRules.classify(-1, REG));
    }

    @Test
    void requiredAllowanceUsesTheClassRateRoundedToTheCent() {
        assertEquals(d("100.00"), PrudentialRules.requiredAllowance(d("10000.00"), LoanClass.PERFORMING, REG));
        assertEquals(d("500.00"), PrudentialRules.requiredAllowance(d("10000.00"), LoanClass.WATCH, REG));
        assertEquals(d("2500.00"), PrudentialRules.requiredAllowance(d("10000.00"), LoanClass.SUBSTANDARD, REG));
        assertEquals(d("5000.00"), PrudentialRules.requiredAllowance(d("10000.00"), LoanClass.DOUBTFUL, REG));
        assertEquals(d("10000.00"), PrudentialRules.requiredAllowance(d("10000.00"), LoanClass.LOSS, REG));
        assertEquals(d("0.13"), PrudentialRules.requiredAllowance(d("12.50"), LoanClass.PERFORMING, REG)); // 0.125 -> 0.13
        assertEquals(d("0.62"), PrudentialRules.requiredAllowance(d("12.3333"), LoanClass.WATCH, REG));
    }

    @Test
    void ratiosCompareExactlyNotOnTheRoundedFigure() {
        assertEquals(d("10.00"), PrudentialRules.percent(d("1000"), d("10000")));
        assertEquals(d("10.00"), PrudentialRules.percent(d("999.6"), d("10000"))); // shows 10.00 ...
        assertFalse(PrudentialRules.meets(d("999.6"), d("10000"), d("10")));     // ... but does not meet 10%
        assertTrue(PrudentialRules.meets(d("1000"), d("10000"), d("10")));
        assertNull(PrudentialRules.percent(d("5"), BigDecimal.ZERO));
        assertNull(PrudentialRules.meets(d("5"), BigDecimal.ZERO, d("10")));
        assertFalse(PrudentialRules.meets(d("5"), d("-1"), d("10")));
        assertFalse(PrudentialRules.meets(d("-5"), d("100"), d("8"))); // negative capital never meets a minimum
    }

    @Test
    void settingsMustBeCoherent() {
        assertThrows(IllegalArgumentException.class, () -> new Settings(d("0"), d("10"), d("8"), d("8"), d("15"),
                180, 30, 360, d("1"), d("5"), d("25"), d("50"), d("100"), true));
        assertThrows(IllegalArgumentException.class, () -> new Settings(d("0"), d("10"), d("8"), d("8"), d("15"),
                30, 180, 360, d("1"), d("5"), d("25"), d("60"), d("50"), true));
        assertThrows(IllegalArgumentException.class, () -> new Settings(d("0"), d("110"), d("8"), d("8"), d("15"),
                30, 180, 360, d("1"), d("5"), d("25"), d("50"), d("100"), true));
        assertThrows(IllegalArgumentException.class, () -> new Settings(d("-1"), d("10"), d("8"), d("8"), d("15"),
                30, 180, 360, d("1"), d("5"), d("25"), d("50"), d("100"), true));
        // Stricter than the regulations is allowed (Reg 10 lets the Authority require more).
        Settings strict = new Settings(d("20000000"), d("12"), d("10"), d("10"), d("20"), 15, 90, 180, d("2"), d("10"),
                d("30"), d("60"), d("100"), false);
        assertEquals(LoanClass.SUBSTANDARD, PrudentialRules.classify(16, strict));
    }
}
