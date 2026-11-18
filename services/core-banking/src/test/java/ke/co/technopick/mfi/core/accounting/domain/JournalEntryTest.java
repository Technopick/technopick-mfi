package ke.co.technopick.mfi.core.accounting.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import ke.co.technopick.mfi.canonical.Money;
import org.junit.jupiter.api.Test;

/** Worked examples from the Phase 0 design, section 5.4. */
class JournalEntryTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 11, 18);
    private static final String BR = "NBO01";

    private static Money kes(String v) {
        return Money.of(v, "KES");
    }

    private static JournalEntry entry(String key, List<JournalLine> lines) {
        return new JournalEntry(UUID.randomUUID(), TENANT, key, BUSINESS_DATE, "TEST", "corr", null, null, lines);
    }

    @Test
    void feeDeductedDisbursementBalances() {
        JournalEntry e = entry("disb-1", List.of(
                JournalLine.debit("1200-LOANS-PRINCIPAL", kes("100000.00"), BR),
                JournalLine.credit("1110-MPESA-B2C-CLEARING", kes("98000.00"), BR),
                JournalLine.credit("4100-FEE-PROCESSING", kes("2000.00"), BR)));
        assertEquals(kes("100000.00"), e.totalDebits(Currency.getInstance("KES")));
    }

    @Test
    void misPostedNetDisbursementIsRejected() {
        // Debiting the receivable with the net cash amount leaves the entry 2,000 short.
        assertThrows(UnbalancedJournalException.class, () -> entry("disb-bad", List.of(
                JournalLine.debit("1200-LOANS-PRINCIPAL", kes("98000.00"), BR),
                JournalLine.credit("1110-MPESA-B2C-CLEARING", kes("98000.00"), BR),
                JournalLine.credit("4100-FEE-PROCESSING", kes("2000.00"), BR))));
    }

    @Test
    void switchedWithdrawalCompletionAndReversalNetToZero() {
        JournalEntry completion = new JournalEntry(UUID.randomUUID(), TENANT, "sw-1", BUSINESS_DATE, "SWITCH",
                "corr", "RRN632207000001", null, List.of(
                JournalLine.debit("2100-CUSTOMER-DEPOSITS", kes("5050.00"), BR),
                JournalLine.credit("2300-AGENT-FLOAT-PAYABLE", kes("5000.00"), BR),
                JournalLine.credit("4200-FEE-WITHDRAWAL", kes("50.00"), BR)));
        JournalEntry reversal = completion.reversal(UUID.randomUUID(), "sw-1-rev", BUSINESS_DATE, "corr-rev");

        assertEquals(completion.entryId(), reversal.reversesEntryId());
        assertEquals("RRN632207000001", reversal.switchReference());
        for (int i = 0; i < completion.lines().size(); i++) {
            JournalLine original = completion.lines().get(i);
            JournalLine reversed = reversal.lines().get(i);
            assertEquals(original.amount(), reversed.amount());
            assertEquals(original.side().opposite(), reversed.side());
        }
    }

    @Test
    void reversalOfReversalIsRefused() {
        JournalEntry original = entry("k1", List.of(
                JournalLine.debit("A", kes("1.00"), BR), JournalLine.credit("B", kes("1.00"), BR)));
        JournalEntry reversal = original.reversal(UUID.randomUUID(), "k1-r", BUSINESS_DATE, "c");
        assertThrows(IllegalStateException.class,
                () -> reversal.reversal(UUID.randomUUID(), "k1-r-r", BUSINESS_DATE, "c"));
    }

    @Test
    void mustBalancePerBranch() {
        assertThrows(UnbalancedJournalException.class, () -> entry("k2", List.of(
                JournalLine.debit("A", kes("10.00"), "NBO01"),
                JournalLine.credit("B", kes("10.00"), "MSA01"))));
    }

    @Test
    void mustBalancePerCurrency() {
        assertThrows(UnbalancedJournalException.class, () -> entry("k3", List.of(
                JournalLine.debit("A", kes("10.00"), BR),
                JournalLine.credit("B", Money.of("10.00", "USD"), BR))));
    }

    @Test
    void singleLineAndNonPositiveLinesRejected() {
        assertThrows(UnbalancedJournalException.class,
                () -> entry("k4", List.of(JournalLine.debit("A", kes("10.00"), BR))));
        assertThrows(IllegalArgumentException.class, () -> JournalLine.debit("A", kes("0.00"), BR));
        assertThrows(IllegalArgumentException.class, () -> JournalLine.debit("A", kes("-1.00"), BR));
    }

    @Test
    void idempotencyKeyRequired() {
        assertThrows(IllegalArgumentException.class, () -> entry(" ", List.of(
                JournalLine.debit("A", kes("1.00"), BR), JournalLine.credit("B", kes("1.00"), BR))));
    }

    @Test
    void linesAreDefensivelyCopied() {
        JournalEntry e = entry("k5", new java.util.ArrayList<>(List.of(
                JournalLine.debit("A", kes("1.00"), BR), JournalLine.credit("B", kes("1.00"), BR))));
        assertThrows(UnsupportedOperationException.class, () -> e.lines().clear());
        assertTrue(e.lines().size() == 2);
    }
}
