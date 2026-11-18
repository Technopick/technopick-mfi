package ke.co.technopick.mfi.core.lending.domain;

import static ke.co.technopick.mfi.core.lending.domain.LoanFixtures.kes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LoanProductVersionTest {

    @Test
    void periodicRateAndScheduleMatchDesignExample() {
        LoanProductVersion v = LoanFixtures.biasharaV1();
        assertEquals(new BigDecimal("0.0200000000"), v.periodicRate());
        List<Installment> s = v.schedule(kes("100000"), 12);
        assertEquals(kes("9455.96"), s.get(0).total());
        assertEquals(kes("9455.97"), s.get(11).total());
        assertEquals(kes("2000.00"), v.upfrontFee(kes("100000")));
    }

    @Test
    void nonTerminatingMonthlyRateIsScaled() {
        LoanProductVersion v = new LoanProductVersion(UUID.randomUUID(), "P", 1, Currency.getInstance("KES"),
                LoanProductVersion.InterestMethod.REDUCING_BALANCE, new BigDecimal("0.25"), RoundingMode.HALF_EVEN,
                kes("1"), kes("10"), 1, 12, BigDecimal.ZERO, RepaymentAllocator.Strategy.COMPONENT_FIRST,
                List.of(DueComponent.values()), LoanFixtures.GL);
        assertEquals(new BigDecimal("0.0208333333"), v.periodicRate());
    }

    @Test
    void requestLimitsAreEnforced() {
        LoanProductVersion v = LoanFixtures.biasharaV1();
        assertThrows(IllegalArgumentException.class, () -> v.schedule(kes("4999.99"), 12));
        assertThrows(IllegalArgumentException.class, () -> v.schedule(kes("500000.01"), 12));
        assertThrows(IllegalArgumentException.class, () -> v.schedule(kes("10000"), 2));
        assertThrows(IllegalArgumentException.class, () -> v.schedule(kes("10000"), 25));
        assertThrows(IllegalArgumentException.class,
                () -> v.validateRequest(ke.co.technopick.mfi.canonical.Money.of("10000", "USD"), 12));
    }

    @Test
    void invalidTermsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LoanProductVersion(UUID.randomUUID(), "P", 1,
                Currency.getInstance("KES"), LoanProductVersion.InterestMethod.FLAT, new BigDecimal("1.5"),
                RoundingMode.HALF_EVEN, kes("1"), kes("10"), 1, 12, BigDecimal.ZERO,
                RepaymentAllocator.Strategy.COMPONENT_FIRST, List.of(DueComponent.values()), LoanFixtures.GL));
        assertThrows(IllegalArgumentException.class, () -> new LoanProductVersion(UUID.randomUUID(), "P", 1,
                Currency.getInstance("KES"), LoanProductVersion.InterestMethod.FLAT, new BigDecimal("0.2"),
                RoundingMode.HALF_EVEN, kes("10"), kes("1"), 1, 12, BigDecimal.ZERO,
                RepaymentAllocator.Strategy.COMPONENT_FIRST, List.of(DueComponent.values()), LoanFixtures.GL));
        assertThrows(IllegalArgumentException.class, () -> new LoanProductVersion(UUID.randomUUID(), "P", 1,
                Currency.getInstance("KES"), LoanProductVersion.InterestMethod.FLAT, new BigDecimal("0.2"),
                RoundingMode.HALF_EVEN, kes("1"), kes("10"), 1, 12, BigDecimal.ZERO,
                RepaymentAllocator.Strategy.COMPONENT_FIRST, List.of(DueComponent.PRINCIPAL), LoanFixtures.GL));
    }
}
