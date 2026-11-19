package ke.co.technopick.mfi.core.lending.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ke.co.technopick.mfi.canonical.Money;
import ke.co.technopick.mfi.core.accounting.application.JournalPosting;
import ke.co.technopick.mfi.core.accounting.domain.JournalEntry;
import ke.co.technopick.mfi.core.accounting.domain.JournalLine;
import ke.co.technopick.mfi.core.common.ConflictException;
import ke.co.technopick.mfi.core.common.NotFoundException;
import ke.co.technopick.mfi.core.lending.domain.LoanPostingRules;
import ke.co.technopick.mfi.core.messaging.persistence.Outbox;
import ke.co.technopick.mfi.core.tenancy.persistence.BusinessDates;
import ke.co.technopick.mfi.core.tenancy.persistence.TenantSession;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loan write-off (ADR-0022). One person requests it for a loan the last end of day showed at
 * least the tenant's minimum days past due; another approves or rejects it. Approval writes off
 * what is owed at that moment (principal, interest receivable, unpaid penalties) and marks the
 * loan WRITTEN_OFF. Money received afterwards is recorded as a recovery to the product's
 * recovery account, never beyond what was written off. The database (V21) re-checks all of it.
 */
@Service
public class LoanWriteOffService {

    public record Recovery(String amount, String channel, String providerRef, OffsetDateTime recordedAt) { }

    public record WriteOffView(UUID writeOffId, String loanNumber, String branchCode, String currency, String status,
                               int daysPastDue, String reason, String requestedBy, OffsetDateTime requestedAt,
                               String decidedBy, String decisionReason, String principalOutstanding, String interestOutstanding,
                               String penaltyOutstanding, String writtenOff, String recovered, List<Recovery> recoveries,
                               boolean replayed) { }

    private final JdbcClient jdbc;
    private final TenantSession session;
    private final BusinessDates businessDates;
    private final JournalPosting journal;
    private final Outbox outbox;

    public LoanWriteOffService(JdbcClient jdbc, TenantSession session, BusinessDates businessDates, JournalPosting journal,
                               Outbox outbox) {
        this.jdbc = jdbc;
        this.session = session;
        this.businessDates = businessDates;
        this.journal = journal;
        this.outbox = outbox;
    }

    @Transactional
    public WriteOffView request(UUID tenantId, String idempotencyKey, String loanNumber, String reason, String actor) {
        session.bind(tenantId);
        Optional<UUID> existing = jdbc.sql("SELECT write_off_id FROM lending.write_off WHERE idempotency_key = :k")
            .param("k", idempotencyKey).query(UUID.class).optional();
        if (existing.isPresent()) {
            WriteOffView v = view(existing.get(), true);
            if (!v.loanNumber().equals(loanNumber.strip().toUpperCase(java.util.Locale.ROOT))) {
                throw new ConflictException("idempotency key already used for another loan");
            }
            return v;
        }
        record L(UUID id, String status) { }
        L loan = jdbc.sql("SELECT loan_id, status FROM lending.loan WHERE loan_number = :n")
            .param("n", loanNumber.strip().toUpperCase(java.util.Locale.ROOT))
            .query((rs, i) -> new L(rs.getObject(1, UUID.class), rs.getString(2))).optional()
            .orElseThrow(() -> new NotFoundException("loan " + loanNumber + " not found"));
        if (!"ACTIVE".equals(loan.status())) {
            throw new IllegalArgumentException("loan " + loanNumber + " is " + loan.status());
        }
        if (jdbc.sql("SELECT count(*) FROM lending.write_off WHERE loan_id = :l AND status <> 'REJECTED'")
                .param("l", loan.id()).query(Integer.class).single() > 0) {
            throw new ConflictException("a write-off is already pending or approved for " + loanNumber);
        }
        Integer dpd = jdbc.sql("SELECT days_past_due FROM lending.arrears_snapshot WHERE loan_id = :l ORDER BY as_of DESC LIMIT 1")
            .param("l", loan.id()).query(Integer.class).optional().orElse(0);
        Integer min = jdbc.sql("SELECT min_days_past_due FROM lending.write_off_policy").query(Integer.class).optional()
            .orElseThrow(() -> new IllegalArgumentException("no write-off policy is configured for this institution"));
        if (dpd < min) {
            throw new IllegalArgumentException(loanNumber + " was " + dpd + " days past due at the last end of day; write-off needs "
                    + min);
        }
        if (jdbc.sql("SELECT lending.loan_credit(tenant_id, loan_id) FROM lending.loan WHERE loan_id = :l").param("l", loan.id())
                .query(BigDecimal.class).single().signum() != 0) {
            throw new IllegalArgumentException(loanNumber + " holds overpaid money; apply or refund it before writing off");
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO lending.write_off (tenant_id, write_off_id, idempotency_key, loan_id, reason, days_past_due, requested_by)
                VALUES (:t, :id, :k, :l, :r, :dpd, :a)
                """)
            .param("t", tenantId).param("id", id).param("k", idempotencyKey).param("l", loan.id()).param("r", reason.strip())
            .param("dpd", dpd).param("a", actor).update();
        return view(id, false);
    }

    @Transactional(readOnly = true)
    public List<WriteOffView> list(UUID tenantId, String status) {
        session.bind(tenantId);
        return jdbc.sql("""
                SELECT write_off_id FROM lending.write_off
                 WHERE (CAST(:s AS varchar) IS NULL OR status = :s)
                 ORDER BY requested_at DESC LIMIT 200
                """)
            .param("s", status).query(UUID.class).list().stream().map(id -> view(id, false)).toList();
    }

    @Transactional(readOnly = true)
    public WriteOffView ofLoan(UUID tenantId, String loanNumber) {
        session.bind(tenantId);
        return jdbc.sql("""
                SELECT w.write_off_id FROM lending.write_off w JOIN lending.loan l ON l.tenant_id = w.tenant_id AND l.loan_id = w.loan_id
                 WHERE l.loan_number = :n ORDER BY w.requested_at DESC LIMIT 1
                """)
            .param("n", loanNumber.strip().toUpperCase(java.util.Locale.ROOT)).query(UUID.class).optional()
            .map(id -> view(id, false)).orElseThrow(() -> new NotFoundException("no write-off for " + loanNumber));
    }

    @Transactional
    public WriteOffView decide(UUID tenantId, UUID writeOffId, boolean approve, String reason, String actor) {
        session.bind(tenantId);
        record W(UUID loan, String status, String requestedBy) { }
        W w = jdbc.sql("SELECT loan_id, status, requested_by FROM lending.write_off WHERE write_off_id = :id FOR UPDATE")
            .param("id", writeOffId)
            .query((rs, i) -> new W(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3))).optional()
            .orElseThrow(() -> new NotFoundException("write-off not found"));
        if (!"PENDING".equals(w.status())) {
            throw new ConflictException("write-off is already " + w.status());
        }
        if (w.requestedBy().equals(actor)) {
            throw new AccessDeniedException("the person who asked for the write-off cannot decide it");
        }
        if (!approve) {
            if (reason == null || reason.strip().length() < 3) {
                throw new IllegalArgumentException("a rejection needs a reason");
            }
            jdbc.sql("UPDATE lending.write_off SET status = 'REJECTED', decided_by = :a, decision_reason = :r WHERE write_off_id = :id")
                .param("a", actor).param("r", reason.strip()).param("id", writeOffId).update();
            return view(writeOffId, false);
        }
        LoanRepaymentService.Loaded loan = loadLoan(w.loan());
        if (!"ACTIVE".equals(loan.status())) {
            throw new ConflictException("loan is " + loan.status());
        }
        if (loan.product().gl().writeOffExpense() == null) {
            throw new IllegalStateException("product has no write-off account");
        }
        record Owed(BigDecimal principal, BigDecimal interest, BigDecimal penalty) { }
        Owed owed = jdbc.sql("""
                SELECT lending.loan_principal_outstanding(tenant_id, loan_id), lending.loan_interest_receivable(tenant_id, loan_id),
                       lending.loan_penalty_unpaid(tenant_id, loan_id)
                  FROM lending.loan WHERE loan_id = :l
                """)
            .param("l", w.loan())
            .query((rs, i) -> new Owed(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3))).single();
        Currency ccy = loan.product().currency();
        LoanPostingRules.LoanContext ctx = new LoanPostingRules.LoanContext(tenantId, w.loan(), loan.branchCode(), loan.product());
        JournalEntry entry = LoanPostingRules.writeOff(ctx, money(owed.principal(), ccy), money(owed.interest(), ccy),
                money(owed.penalty(), ccy), businessDates.current(), writeOffId.toString(), "write-off:" + writeOffId);
        journal.post(entry, actor);
        jdbc.sql("""
                UPDATE lending.write_off SET status = 'APPROVED', decided_by = :a, decision_reason = :r, principal = :p,
                       interest = :i, penalty = :x, entry_id = :e
                 WHERE write_off_id = :id
                """)
            .param("a", actor).param("r", reason == null || reason.isBlank() ? null : reason.strip())
            .param("p", owed.principal()).param("i", owed.interest()).param("x", owed.penalty()).param("e", entry.entryId())
            .param("id", writeOffId).update();
        jdbc.sql("UPDATE lending.loan SET status = 'WRITTEN_OFF' WHERE loan_id = :l").param("l", w.loan()).update();
        BigDecimal total = owed.principal().add(owed.interest()).add(owed.penalty());
        outbox.record(tenantId, "LoanWrittenOff", "loan", w.loan().toString(),
                "{\"loanNumber\":\"%s\",\"writtenOff\":\"%s\",\"currency\":\"%s\"}"
                        .formatted(loan.loanNumber(), plain(total), ccy.getCurrencyCode()),
                "write-off:" + writeOffId);
        return view(writeOffId, false);
    }

    /** Remaining amount that can still be recovered on a written-off loan; empty if not written off. */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<BigDecimal> recoverable(UUID loanId) {
        return jdbc.sql("""
                SELECT w.principal + w.interest + w.penalty
                       - COALESCE((SELECT sum(amount) FROM lending.write_off_recovery r
                                    WHERE r.tenant_id = w.tenant_id AND r.loan_id = w.loan_id), 0)
                  FROM lending.write_off w JOIN lending.loan l ON l.tenant_id = w.tenant_id AND l.loan_id = w.loan_id
                 WHERE w.loan_id = :l AND w.status = 'APPROVED' AND l.status = 'WRITTEN_OFF'
                """)
            .param("l", loanId).query(BigDecimal.class).optional();
    }

    /** Records money received for a written-off loan (inside the receiving transaction). */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID recordRecovery(UUID tenantId, UUID loanId, Money amount, String channel, String providerRef,
                               String debitAccount, LocalDate businessDate, String correlationId, String actor) {
        record L(String number, String branch, String recoveryGl) { }
        L l = jdbc.sql("""
                SELECT l.loan_number, l.branch_code, v.gl_write_off_recovery FROM lending.loan l
                  JOIN lending.loan_product_version v ON v.tenant_id = l.tenant_id AND v.version_id = l.product_version_id
                 WHERE l.loan_id = :l
                """)
            .param("l", loanId).query((rs, i) -> new L(rs.getString(1), rs.getString(2), rs.getString(3))).single();
        String key = "recovery:" + channel + ":" + providerRef;
        UUID entryId = UUID.nameUUIDFromBytes((tenantId + "|" + key).getBytes(StandardCharsets.UTF_8));
        journal.post(new JournalEntry(entryId, tenantId, key, businessDate, "LOAN", correlationId, providerRef, null, List.of(
                JournalLine.debit(debitAccount, amount, l.branch()), JournalLine.credit(l.recoveryGl(), amount, l.branch()))), actor);
        UUID id = UUID.nameUUIDFromBytes((tenantId + "|recovery|" + channel + "|" + providerRef).getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO lending.write_off_recovery (tenant_id, recovery_id, loan_id, amount, channel, provider_ref, entry_id,
                    recorded_by)
                VALUES (:t, :id, :l, :amount, :ch, :ref, :e, :a)
                """)
            .param("t", tenantId).param("id", id).param("l", loanId).param("amount", amount.amount()).param("ch", channel)
            .param("ref", providerRef).param("e", entryId).param("a", actor).update();
        outbox.record(tenantId, "WrittenOffLoanRecovery", "write_off_recovery", id.toString(),
                "{\"loanNumber\":\"%s\",\"amount\":\"%s\",\"currency\":\"%s\"}"
                        .formatted(l.number(), plain(amount.amount()), amount.currency().getCurrencyCode()),
                correlationId);
        return entryId;
    }

    private LoanRepaymentService.Loaded loadLoan(UUID loanId) {
        return jdbc.sql("""
                SELECT l.loan_number, l.status, l.branch_code, l.principal, p.currency, v.*
                  FROM lending.loan l
                  JOIN lending.loan_product_version v ON v.tenant_id = l.tenant_id AND v.version_id = l.product_version_id
                  JOIN lending.loan_product p ON p.tenant_id = v.tenant_id AND p.product_code = v.product_code
                 WHERE l.loan_id = :loan
                   FOR UPDATE OF l
                """)
            .param("loan", loanId)
            .query((rs, i) -> new LoanRepaymentService.Loaded(rs.getString("loan_number"), rs.getString("status"),
                    rs.getString("branch_code"), rs.getBigDecimal("principal"), LoanRepaymentService.product(rs)))
            .single();
    }

    private WriteOffView view(UUID id, boolean replayed) {
        WriteOffView v = jdbc.sql("""
                SELECT w.write_off_id, l.loan_number, l.branch_code, l.currency, w.status, w.days_past_due, w.reason,
                       w.requested_by, w.requested_at, w.decided_by, w.decision_reason,
                       COALESCE(w.principal, lending.loan_principal_outstanding(l.tenant_id, l.loan_id)) AS principal,
                       COALESCE(w.interest, lending.loan_interest_receivable(l.tenant_id, l.loan_id)) AS interest,
                       COALESCE(w.penalty, lending.loan_penalty_unpaid(l.tenant_id, l.loan_id)) AS penalty,
                       w.principal + w.interest + w.penalty AS written_off,
                       COALESCE((SELECT sum(amount) FROM lending.write_off_recovery r
                                  WHERE r.tenant_id = w.tenant_id AND r.loan_id = w.loan_id), 0) AS recovered
                  FROM lending.write_off w JOIN lending.loan l ON l.tenant_id = w.tenant_id AND l.loan_id = w.loan_id
                 WHERE w.write_off_id = :id
                """)
            .param("id", id)
            .query((rs, i) -> new WriteOffView(rs.getObject("write_off_id", UUID.class), rs.getString("loan_number"),
                    rs.getString("branch_code"), rs.getString("currency"), rs.getString("status"), rs.getInt("days_past_due"),
                    rs.getString("reason"), rs.getString("requested_by"), rs.getObject("requested_at", OffsetDateTime.class),
                    rs.getString("decided_by"), rs.getString("decision_reason"), plain(rs.getBigDecimal("principal")),
                    plain(rs.getBigDecimal("interest")), plain(rs.getBigDecimal("penalty")),
                    rs.getBigDecimal("written_off") == null ? null : plain(rs.getBigDecimal("written_off")),
                    plain(rs.getBigDecimal("recovered")), List.of(), replayed))
            .single();
        List<Recovery> recoveries = jdbc.sql("""
                SELECT r.amount, r.channel, r.provider_ref, r.recorded_at FROM lending.write_off_recovery r
                  JOIN lending.write_off w ON w.tenant_id = r.tenant_id AND w.loan_id = r.loan_id
                 WHERE w.write_off_id = :id ORDER BY r.recorded_at
                """)
            .param("id", id)
            .query((rs, i) -> new Recovery(plain(rs.getBigDecimal(1)), rs.getString(2), rs.getString(3),
                    rs.getObject(4, OffsetDateTime.class)))
            .list();
        return new WriteOffView(v.writeOffId(), v.loanNumber(), v.branchCode(), v.currency(), v.status(), v.daysPastDue(),
                v.reason(), v.requestedBy(), v.requestedAt(), v.decidedBy(), v.decisionReason(), v.principalOutstanding(),
                v.interestOutstanding(), v.penaltyOutstanding(), v.writtenOff(), v.recovered(), recoveries, v.replayed());
    }

    private static Money money(BigDecimal v, Currency c) {
        return Money.of(v.setScale(c.getDefaultFractionDigits(), RoundingMode.UNNECESSARY), c);
    }

    private static String plain(BigDecimal v) {
        return v.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
