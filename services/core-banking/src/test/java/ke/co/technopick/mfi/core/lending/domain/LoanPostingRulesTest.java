package ke.co.technopick.mfi.core.lending.domain;

import static ke.co.technopick.mfi.core.lending.domain.LoanFixtures.kes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import ke.co.technopick.mfi.canonical.Money;
import ke.co.technopick.mfi.core.accounting.domain.JournalEntry;
import ke.co.technopick.mfi.core.accounting.domain.JournalLine;
import ke.co.technopick.mfi.core.accounting.domain.Side;
import org.junit.jupiter.api.Test;

class LoanPostingRulesTest {

    private static final Currency KES = Currency.getInstance("KES");
    private static final LocalDate D = LocalDate.of(2026, 11, 18);
    private static final String MPESA_B2C = "1110-MPESA-B2C-CLEARING";
    private static final String MPESA_C2B = "1120-MPESA-C2B-CLEARING";

    private static Set<String> lines(JournalEntry e) {
        return e.lines().stream().map(l -> l.side() + " " + l.glAccountCode() + " " + l.amount().amount().toPlainString())
                .collect(Collectors.toSet());
    }

    @Test
    void disbursementNetOfUpfrontFee() {
        JournalEntry e = LoanPostingRules.disbursement(LoanFixtures.context(), kes("100000"), MPESA_B2C, D, "B2C-1", "c");
        assertEquals(Set.of(
                "DEBIT 1200-LOANS-PRINCIPAL 100000.00",
                "CREDIT 1110-MPESA-B2C-CLEARING 98000.00",
                "CREDIT 4100-FEE-PROCESSING 2000.00"), lines(e));
        assertEquals("loan:" + LoanFixtures.LOAN + ":DISBURSE:B2C-1", e.idempotencyKey());
    }

    @Test
    void replayedEventGetsSameEntryIdAndKey() {
        JournalEntry a = LoanPostingRules.disbursement(LoanFixtures.context(), kes("100000"), MPESA_B2C, D, "B2C-1", "c1");
        JournalEntry b = LoanPostingRules.disbursement(LoanFixtures.context(), kes("100000"), MPESA_B2C, D, "B2C-1", "c2");
        assertEquals(a.entryId(), b.entryId());
        assertEquals(a.idempotencyKey(), b.idempotencyKey());
    }

    @Test
    void repaymentCreditsReceivablesPerComponentAndOverpayment() {
        List<InstallmentDue> dues = List.of(new InstallmentDue(1, LocalDate.of(2026, 12, 18), Map.of(
                DueComponent.PENALTY, kes("200.00"), DueComponent.INTEREST, kes("2000.00"),
                DueComponent.PRINCIPAL, kes("7455.96"))));
        RepaymentAllocator.Result r = LoanFixtures.biasharaV1().allocator().allocate(kes("10000.00"), dues);
        JournalEntry e = LoanPostingRules.repayment(LoanFixtures.context(), kes("10000.00"), r, MPESA_C2B, D, "QK7X1", "c");
        assertEquals(Set.of(
                "DEBIT 1120-MPESA-C2B-CLEARING 10000.00",
                "CREDIT 1230-PENALTY-RECEIVABLE 200.00",
                "CREDIT 1210-INTEREST-RECEIVABLE 2000.00",
                "CREDIT 1200-LOANS-PRINCIPAL 7455.96",
                "CREDIT 2400-LOAN-OVERPAYMENTS 344.04"), lines(e));
    }

    @Test
    void repaymentAcrossInstallmentsAggregatesPerComponent() {
        List<InstallmentDue> dues = List.of(
                new InstallmentDue(1, LocalDate.of(2026, 9, 18), Map.of(DueComponent.INTEREST, kes("2000.00"))),
                new InstallmentDue(2, LocalDate.of(2026, 10, 18), Map.of(DueComponent.INTEREST, kes("1850.88"))));
        RepaymentAllocator.Result r = LoanFixtures.biasharaV1().allocator().allocate(kes("3850.88"), dues);
        JournalEntry e = LoanPostingRules.repayment(LoanFixtures.context(), kes("3850.88"), r, MPESA_C2B, D, "QK7X2", "c");
        assertEquals(2, e.lines().size());
        assertEquals(Set.of("DEBIT 1120-MPESA-C2B-CLEARING 3850.88", "CREDIT 1210-INTEREST-RECEIVABLE 3850.88"), lines(e));
    }

    @Test
    void repaymentRejectsAllocationThatDoesNotAddUp() {
        RepaymentAllocator.Result wrong = new RepaymentAllocator.Result(
                List.of(new RepaymentAllocator.Allocation(1, DueComponent.INTEREST, kes("10.00"))), kes("0"));
        assertThrows(IllegalArgumentException.class, () -> LoanPostingRules.repayment(LoanFixtures.context(),
                kes("20.00"), wrong, MPESA_C2B, D, "X", "c"));
    }

    @Test
    void accrualPenaltyFeeAndWriteOff() {
        var ctx = LoanFixtures.context();
        assertEquals(Set.of("DEBIT 1210-INTEREST-RECEIVABLE 66.67", "CREDIT 4000-INTEREST-INCOME 66.67"),
                lines(LoanPostingRules.interestAccrual(ctx, kes("66.67"), D, "c")));
        assertNull(LoanPostingRules.interestAccrual(ctx, kes("0"), D, "c"));
        assertEquals(Set.of("DEBIT 1230-PENALTY-RECEIVABLE 200.00", "CREDIT 4300-PENALTY-INCOME 200.00"),
                lines(LoanPostingRules.penaltyCharged(ctx, kes("200"), D, "PEN-1", "c")));
        assertEquals(Set.of("DEBIT 1220-FEE-RECEIVABLE 150.00", "CREDIT 4100-FEE-PROCESSING 150.00"),
                lines(LoanPostingRules.feeCharged(ctx, kes("150"), D, "FEE-1", "c")));
        assertEquals(Set.of("DEBIT 5100-LOAN-WRITE-OFFS 50185.41", "CREDIT 1200-LOANS-PRINCIPAL 50000.00",
                        "CREDIT 1210-INTEREST-RECEIVABLE 185.41"),
                lines(LoanPostingRules.writeOff(ctx, kes("50000"), kes("185.41"), D, "WO-APPROVAL-7", "c")));
    }

    @Test
    void everyEntryIsBalanced() {
        var ctx = LoanFixtures.context();
        for (JournalEntry e : List.of(
                LoanPostingRules.disbursement(ctx, kes("37500"), MPESA_B2C, D, "a", "c"),
                LoanPostingRules.writeOff(ctx, kes("1"), kes("0"), D, "b", "c"))) {
            Money dr = Money.zero(KES);
            Money cr = Money.zero(KES);
            for (JournalLine l : e.lines()) {
                if (l.side() == Side.DEBIT) {
                    dr = dr.plus(l.amount());
                } else {
                    cr = cr.plus(l.amount());
                }
            }
            assertEquals(dr, cr);
        }
    }
}
