package ke.co.technopick.mfi.core.lending.application;

import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import ke.co.technopick.mfi.canonical.Money;
import ke.co.technopick.mfi.core.accounting.application.JournalPosting;
import ke.co.technopick.mfi.core.accounting.domain.JournalEntry;
import ke.co.technopick.mfi.core.lending.domain.LoanPostingRules;
import ke.co.technopick.mfi.core.messaging.persistence.Outbox;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loan disbursement for payment channels: what would be paid out (principal net of the upfront
 * fee), and activation once the money has actually left (posting + APPROVED -> ACTIVE in the
 * caller's transaction; the database checks the entry belongs to this loan and debits principal).
 */
@Service
public class LoanDisbursementService {

    public record Disbursable(UUID loanId, String loanNumber, String status, Money principal, Money netAmount,
                              Money fee) { }

    private final JdbcClient jdbc;
    private final LoanRepaymentService loans;
    private final JournalPosting journal;
    private final Outbox outbox;

    public LoanDisbursementService(JdbcClient jdbc, LoanRepaymentService loans, JournalPosting journal, Outbox outbox) {
        this.jdbc = jdbc;
        this.loans = loans;
        this.journal = journal;
        this.outbox = outbox;
    }

    /** Locks the loan and returns its disbursement figures (the tenant must already be bound). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Disbursable> disbursable(String loanNumber) {
        return loans.findByNumber(loanNumber).map(ref -> {
            LoanRepaymentService.Loaded l = loans.load(ref.loanId());
            Money principal = Money.of(l.principal().setScale(l.product().currency().getDefaultFractionDigits(),
                    RoundingMode.UNNECESSARY), l.product().currency());
            Money fee = l.product().upfrontFee(principal);
            return new Disbursable(ref.loanId(), l.loanNumber(), l.status(), principal, principal.minus(fee), fee);
        });
    }

    /**
     * Posts the disbursement (Dr principal receivable, Cr channel clearing with the net amount,
     * Cr fee income) and activates the loan. Returns the journal entry id.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID activate(UUID tenantId, UUID loanId, String channelClearingAccount, String disbursementRef,
                         LocalDate businessDate, String correlationId, String actor) {
        LoanRepaymentService.Loaded l = loans.load(loanId);
        if (!"APPROVED".equals(l.status())) {
            throw new IllegalStateException("loan " + l.loanNumber() + " is " + l.status() + ", not APPROVED");
        }
        Money principal = Money.of(l.principal().setScale(l.product().currency().getDefaultFractionDigits(),
                RoundingMode.UNNECESSARY), l.product().currency());
        JournalEntry entry = LoanPostingRules.disbursement(
                new LoanPostingRules.LoanContext(tenantId, loanId, l.branchCode(), l.product()), principal,
                channelClearingAccount, businessDate, disbursementRef, correlationId);
        journal.post(entry, actor);
        jdbc.sql("UPDATE lending.loan SET status = 'ACTIVE', disbursement_entry_id = :entry WHERE loan_id = :loan")
            .param("entry", entry.entryId()).param("loan", loanId).update();
        outbox.record(tenantId, "LoanDisbursed", "loan", loanId.toString(),
                "{\"loanId\":\"%s\",\"loanNumber\":\"%s\",\"principal\":\"%s\",\"currency\":\"%s\",\"entryId\":\"%s\"}"
                        .formatted(loanId, l.loanNumber(), principal.amount().toPlainString(),
                                principal.currency().getCurrencyCode(), entry.entryId()),
                correlationId);
        return entry.entryId();
    }
}
