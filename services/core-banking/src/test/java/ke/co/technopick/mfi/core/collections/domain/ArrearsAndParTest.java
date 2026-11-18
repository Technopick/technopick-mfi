package ke.co.technopick.mfi.core.collections.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ke.co.technopick.mfi.canonical.Money;
import ke.co.technopick.mfi.core.collections.domain.ArrearsCalculator.Bucket;
import ke.co.technopick.mfi.core.collections.domain.ArrearsCalculator.Classification;
import ke.co.technopick.mfi.core.collections.domain.PortfolioAtRisk.LoanPosition;
import ke.co.technopick.mfi.core.lending.domain.DueComponent;
import ke.co.technopick.mfi.core.lending.domain.InstallmentDue;
import org.junit.jupiter.api.Test;

class ArrearsAndParTest {

    private static final Currency KES = Currency.getInstance("KES");
    private static final Money ZERO = Money.zero(KES);

    private static Money kes(String v) {
        return Money.of(v, "KES");
    }

    private static InstallmentDue due(int n, String date, String principal, String interest, String penalty) {
        return new InstallmentDue(n, LocalDate.parse(date), Map.of(
                DueComponent.PRINCIPAL, kes(principal), DueComponent.INTEREST, kes(interest),
                DueComponent.PENALTY, kes(penalty)));
    }

    @Test
    void dueTodayIsNotOverdueDueYesterdayIsOneDay() {
        List<InstallmentDue> s = List.of(due(1, "2026-12-18", "7455.96", "2000.00", "0"));
        assertEquals(0, ArrearsCalculator.position(s, LocalDate.of(2026, 12, 18), ZERO).daysPastDue());
        ArrearsCalculator.Position p = ArrearsCalculator.position(s, LocalDate.of(2026, 12, 19), ZERO);
        assertEquals(1, p.daysPastDue());
        assertEquals(Bucket.DPD_1_30, p.bucket());
        assertEquals(kes("9455.96"), p.arrearsAmount());
    }

    @Test
    void dpdCountsFromOldestUnpaidInstallment() {
        List<InstallmentDue> s = List.of(
                due(1, "2026-12-18", "0", "0", "0"),            // paid
                due(2, "2027-01-18", "100.00", "0", "0"),       // partly unpaid
                due(3, "2027-02-18", "7757.18", "1698.78", "0"));
        ArrearsCalculator.Position p = ArrearsCalculator.position(s, LocalDate.of(2027, 3, 1), ZERO);
        assertEquals(42, p.daysPastDue());
        assertEquals(Bucket.DPD_31_60, p.bucket());
        assertEquals(kes("9555.96"), p.arrearsAmount());
        assertEquals(LocalDate.of(2027, 1, 18), p.oldestOverdueDueDate());
    }

    @Test
    void penaltiesAloneAndAmountsWithinToleranceDoNotAge() {
        List<InstallmentDue> s = List.of(due(1, "2026-12-18", "0", "0.40", "200.00"));
        assertEquals(0, ArrearsCalculator.position(s, LocalDate.of(2027, 2, 1), kes("0.50")).daysPastDue());
        assertEquals(45, ArrearsCalculator.position(s, LocalDate.of(2027, 2, 1), ZERO).daysPastDue());
    }

    @Test
    void bucketAndClassificationBoundaries() {
        assertEquals(Bucket.CURRENT, ArrearsCalculator.bucket(0));
        assertEquals(Bucket.DPD_1_30, ArrearsCalculator.bucket(30));
        assertEquals(Bucket.DPD_31_60, ArrearsCalculator.bucket(31));
        assertEquals(Bucket.DPD_61_90, ArrearsCalculator.bucket(90));
        assertEquals(Bucket.DPD_91_180, ArrearsCalculator.bucket(180));
        assertEquals(Bucket.DPD_OVER_180, ArrearsCalculator.bucket(181));
        assertEquals(Classification.NORMAL, ArrearsCalculator.classification(30));
        assertEquals(Classification.WATCH, ArrearsCalculator.classification(90));
        assertEquals(Classification.SUBSTANDARD, ArrearsCalculator.classification(180));
        assertEquals(Classification.DOUBTFUL, ArrearsCalculator.classification(360));
        assertEquals(Classification.LOSS, ArrearsCalculator.classification(361));
    }

    private static LoanPosition loan(String outstanding, long dpd, boolean writtenOff) {
        return new LoanPosition(UUID.randomUUID(), "NBO01", "officer-1", kes(outstanding), dpd, writtenOff);
    }

    @Test
    void parWorkedExample() {
        List<LoanPosition> book = List.of(
                loan("100000", 0, false),
                loan("50000", 5, false),
                loan("30000", 45, false),
                loan("20000", 120, false),
                loan("99999", 400, true)); // written off: excluded everywhere
        // Denominator 200,000. PAR1 = 100,000; PAR30 = 50,000; PAR60 = 20,000; PAR90 = 20,000.
        PortfolioAtRisk.Result par1 = PortfolioAtRisk.par(book, 1, KES);
        assertEquals(kes("200000"), par1.denominator());
        assertEquals(kes("100000"), par1.numerator());
        assertEquals(new BigDecimal("0.5000"), par1.ratio().orElseThrow());
        assertEquals(3, par1.loansAtRisk());
        assertEquals(4, par1.loansInPortfolio());
        assertEquals(new BigDecimal("0.2500"), PortfolioAtRisk.par(book, 30, KES).ratio().orElseThrow());
        assertEquals(new BigDecimal("0.1000"), PortfolioAtRisk.par(book, 60, KES).ratio().orElseThrow());
        assertEquals(new BigDecimal("0.1000"), PortfolioAtRisk.par(book, 90, KES).ratio().orElseThrow());
    }

    @Test
    void parBoundaryIsStrictlyGreaterThan() {
        assertEquals(new BigDecimal("0.0000"),
                PortfolioAtRisk.par(List.of(loan("1000", 30, false)), 30, KES).ratio().orElseThrow());
        assertEquals(new BigDecimal("1.0000"),
                PortfolioAtRisk.par(List.of(loan("1000", 31, false)), 30, KES).ratio().orElseThrow());
    }

    @Test
    void emptyPortfolioHasNoRatioRatherThanZero() {
        assertTrue(PortfolioAtRisk.par(List.of(), 30, KES).ratio().isEmpty());
        assertTrue(PortfolioAtRisk.par(List.of(loan("5", 400, true)), 30, KES).ratio().isEmpty());
    }

    @Test
    void mixedCurrencyRejected() {
        assertThrows(IllegalArgumentException.class, () -> PortfolioAtRisk.par(List.of(
                new LoanPosition(UUID.randomUUID(), "B", "o", Money.of("1", "USD"), 0, false)), 30, KES));
    }
}
