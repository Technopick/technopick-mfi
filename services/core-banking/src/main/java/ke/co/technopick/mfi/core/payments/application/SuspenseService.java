package ke.co.technopick.mfi.core.payments.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import ke.co.technopick.mfi.canonical.Money;
import ke.co.technopick.mfi.core.accounting.domain.JournalEntry;
import ke.co.technopick.mfi.core.accounting.domain.JournalLine;
import ke.co.technopick.mfi.core.accounting.domain.Side;
import ke.co.technopick.mfi.core.accounting.persistence.JdbcJournal;
import ke.co.technopick.mfi.core.common.ConflictException;
import ke.co.technopick.mfi.core.common.NotFoundException;
import ke.co.technopick.mfi.core.lending.application.LoanRepaymentService;
import ke.co.technopick.mfi.core.messaging.persistence.Outbox;
import ke.co.technopick.mfi.core.savings.persistence.JdbcDepositAccounts;
import ke.co.technopick.mfi.core.tenancy.persistence.BusinessDates;
import ke.co.technopick.mfi.core.tenancy.persistence.TenantSession;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Clears unmatched receipts out of suspense under maker-checker: one person requests the move to
 * a deposit account or loan, a different person approves (which posts Dr suspense / Cr target) or
 * rejects. The database enforces the same rules (V9).
 */
@Service
public class SuspenseService {

    public enum TargetType { DEPOSIT_ACCOUNT, LOAN }

    public record OpenItem(UUID paymentId, String providerRef, String amount, String currency, String billRef,
                           String msisdnMasked, int ageDays, UUID pendingResolutionId) { }

    public record Decision(UUID resolutionId, String status, UUID journalEntryId) { }

    private record Resolution(UUID paymentId, String targetType, String targetReference, String status,
                              String requestedBy) { }

    private record Receipt(BigDecimal amount, String currency, String providerRef, String glSuspense, String branch) { }

    private final JdbcClient jdbc;
    private final TenantSession session;
    private final BusinessDates businessDates;
    private final JdbcDepositAccounts accounts;
    private final LoanRepaymentService loans;
    private final JdbcJournal journal;
    private final Outbox outbox;

    public SuspenseService(JdbcClient jdbc, TenantSession session, BusinessDates businessDates,
                           JdbcDepositAccounts accounts, LoanRepaymentService loans, JdbcJournal journal, Outbox outbox) {
        this.jdbc = jdbc;
        this.session = session;
        this.businessDates = businessDates;
        this.accounts = accounts;
        this.loans = loans;
        this.journal = journal;
        this.outbox = outbox;
    }

    @Transactional(readOnly = true)
    public List<OpenItem> openItems(UUID tenantId) {
        session.bind(tenantId);
        return jdbc.sql("""
                SELECT payment_id, provider_ref, amount, currency, bill_ref, msisdn_masked, age_days, pending_resolution_id
                  FROM payments.open_suspense_item ORDER BY received_at
                """)
            .query((rs, i) -> new OpenItem(rs.getObject("payment_id", UUID.class), rs.getString("provider_ref"),
                    rs.getBigDecimal("amount").setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                    rs.getString("currency"), rs.getString("bill_ref"), rs.getString("msisdn_masked"),
                    rs.getInt("age_days"), rs.getObject("pending_resolution_id", UUID.class)))
            .list();
    }

    @Transactional
    public UUID request(UUID tenantId, UUID paymentId, TargetType type, String targetReference, String reason,
                        String actor) {
        session.bind(tenantId);
        String ref = targetReference.strip().toUpperCase(Locale.ROOT);
        boolean targetOk = switch (type) {
            case DEPOSIT_ACCOUNT -> accounts.lockByNumber(ref)
                    .filter(a -> !"CLOSED".equals(a.status()) && !"BLOCKED".equals(a.status())).isPresent();
            case LOAN -> loans.findByNumber(ref).filter(l -> "ACTIVE".equals(l.status())).isPresent();
        };
        if (!targetOk) {
            throw new IllegalArgumentException("target " + type + " " + ref + " does not exist or cannot receive funds");
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO payments.suspense_resolution (tenant_id, resolution_id, payment_id, target_type, target_reference,
                    reason, requested_by)
                VALUES (:tenant, :id, :payment, :type, :ref, :reason, :actor)
                """)
            .param("tenant", tenantId).param("id", id).param("payment", paymentId).param("type", type.name())
            .param("ref", ref).param("reason", reason).param("actor", actor)
            .update();
        return id;
    }

    @Transactional
    public Decision decide(UUID tenantId, UUID resolutionId, boolean approve, String actor, String correlationId) {
        session.bind(tenantId);
        Resolution r = jdbc.sql("""
                SELECT payment_id, target_type, target_reference, status, requested_by
                  FROM payments.suspense_resolution WHERE resolution_id = :id FOR UPDATE
                """)
            .param("id", resolutionId)
            .query((rs, i) -> new Resolution(rs.getObject("payment_id", UUID.class), rs.getString("target_type"),
                    rs.getString("target_reference"), rs.getString("status"), rs.getString("requested_by")))
            .optional()
            .orElseThrow(() -> new NotFoundException("resolution not found"));
        if (!"PENDING".equals(r.status())) {
            throw new ConflictException("resolution is already " + r.status());
        }
        if (r.requestedBy().equals(actor)) {
            throw new AccessDeniedException("the requester cannot decide their own resolution");
        }
        if (!approve) {
            jdbc.sql("UPDATE payments.suspense_resolution SET status = 'REJECTED', decided_by = :actor WHERE resolution_id = :id")
                .param("actor", actor).param("id", resolutionId).update();
            return new Decision(resolutionId, "REJECTED", null);
        }
        Receipt receipt = jdbc.sql("""
                SELECT p.amount, p.currency, p.provider_ref, b.gl_suspense, b.branch_code
                  FROM payments.provider_payment p
                  JOIN payments.mpesa_paybill b ON b.tenant_id = p.tenant_id AND b.short_code = p.short_code
                 WHERE p.payment_id = :payment
                """)
            .param("payment", r.paymentId())
            .query((rs, i) -> new Receipt(rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("provider_ref"),
                    rs.getString("gl_suspense"), rs.getString("branch_code")))
            .single();
        Currency ccy = Currency.getInstance(receipt.currency());
        Money amount = Money.of(receipt.amount().setScale(ccy.getDefaultFractionDigits(), RoundingMode.UNNECESSARY), ccy);
        UUID entryId;
        if (TargetType.valueOf(r.targetType()) == TargetType.DEPOSIT_ACCOUNT) {
            JdbcDepositAccounts.AccountRow account = accounts.lockByNumber(r.targetReference())
                    .orElseThrow(() -> new ConflictException("target account no longer exists"));
            String key = "suspense:" + resolutionId;
            entryId = UUID.nameUUIDFromBytes((tenantId + "|" + key).getBytes(StandardCharsets.UTF_8));
            journal.post(new JournalEntry(entryId, tenantId, key, businessDates.current(), "SUSPENSE", correlationId,
                    receipt.providerRef(), null, List.of(
                        JournalLine.debit(receipt.glSuspense(), amount, account.branchCode()),
                        JournalLine.onAccount(account.glControlAccount(), Side.CREDIT, amount, account.branchCode(),
                                account.accountId()))), actor);
        } else {
            LoanRepaymentService.LoanRef loan = loans.findByNumber(r.targetReference())
                    .orElseThrow(() -> new ConflictException("target loan no longer exists"));
            entryId = loans.apply(tenantId, loan.loanId(), amount, "SUSPENSE", resolutionId.toString(),
                    receipt.glSuspense(), businessDates.current(), correlationId, actor).journalEntryId();
        }
        jdbc.sql("""
                UPDATE payments.suspense_resolution SET status = 'APPROVED', decided_by = :actor, journal_entry_id = :entry
                 WHERE resolution_id = :id
                """)
            .param("actor", actor).param("entry", entryId).param("id", resolutionId).update();
        outbox.record(tenantId, "SuspenseItemResolved", "suspense_resolution", resolutionId.toString(),
                "{\"resolutionId\":\"%s\",\"paymentId\":\"%s\",\"targetType\":\"%s\",\"amount\":\"%s\",\"currency\":\"%s\"}"
                        .formatted(resolutionId, r.paymentId(), r.targetType(), amount.amount().toPlainString(),
                                ccy.getCurrencyCode()),
                correlationId);
        return new Decision(resolutionId, "APPROVED", entryId);
    }
}
