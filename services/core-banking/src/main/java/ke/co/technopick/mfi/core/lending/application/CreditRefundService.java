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
import ke.co.technopick.mfi.core.accounting.domain.Side;
import ke.co.technopick.mfi.core.common.ConflictException;
import ke.co.technopick.mfi.core.common.NotFoundException;
import ke.co.technopick.mfi.core.tenancy.persistence.BusinessDates;
import ke.co.technopick.mfi.core.tenancy.persistence.TenantSession;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refunds of credit left on closed loans (ADR-0032). One person asks for the refund to one of the
 * member's own savings accounts; another approves it. Approval moves the money from the loan's
 * overpayment account to the savings account and reduces the loan's credit. The database
 * re-checks ownership, the closed loan, the amount against the credit and the posting (V29).
 */
@Service
public class CreditRefundService {

    public record RefundView(UUID refundId, String loanNumber, String amount, String currency, String method,
                             String accountNumber, String reason, String status, String requestedBy, OffsetDateTime requestedAt,
                             String decidedBy, OffsetDateTime decidedAt, String decisionReason, LocalDate businessDate,
                             UUID entryId, String creditRemaining) { }

    private final JdbcClient jdbc;
    private final TenantSession session;
    private final BusinessDates businessDates;
    private final JournalPosting journal;

    public CreditRefundService(JdbcClient jdbc, TenantSession session, BusinessDates businessDates, JournalPosting journal) {
        this.jdbc = jdbc;
        this.session = session;
        this.businessDates = businessDates;
        this.journal = journal;
    }

    private static String m(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static final String VIEW = """
            SELECT r.refund_id, l.loan_number, r.amount, l.currency, r.method, a.account_number, r.reason, r.status,
                   r.requested_by, r.requested_at, r.decided_by, r.decided_at, r.decision_reason, r.business_date, r.entry_id,
                   lending.loan_credit(l.tenant_id, l.loan_id) AS credit
              FROM lending.credit_refund r
              JOIN lending.loan l ON l.tenant_id = r.tenant_id AND l.loan_id = r.loan_id
              JOIN savings.deposit_account a ON a.tenant_id = r.tenant_id AND a.account_id = r.deposit_account_id
            """;

    private static RefundView row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RefundView(rs.getObject(1, UUID.class), rs.getString(2), m(rs.getBigDecimal(3)), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getObject(10, OffsetDateTime.class),
                rs.getString(11), rs.getObject(12, OffsetDateTime.class), rs.getString(13), rs.getObject(14, LocalDate.class),
                rs.getObject(15, UUID.class), m(rs.getBigDecimal(16)));
    }

    private RefundView view(UUID refundId) {
        return jdbc.sql(VIEW + " WHERE r.refund_id = :id").param("id", refundId).query((rs, i) -> row(rs)).optional()
            .orElseThrow(() -> new NotFoundException("refund not found"));
    }

    @Transactional
    public RefundView request(UUID tenantId, String loanNumber, String idempotencyKey, String accountNumber, String amount,
                              String reason, String actor) {
        session.bind(tenantId);
        Optional<UUID> replay = jdbc.sql("SELECT refund_id FROM lending.credit_refund WHERE idempotency_key = :k")
            .param("k", idempotencyKey).query(UUID.class).optional();
        if (replay.isPresent()) {
            return view(replay.get());
        }
        if (amount == null || !amount.matches("^\\d{1,13}(\\.\\d{1,2})?$") || new BigDecimal(amount).signum() <= 0) {
            throw new IllegalArgumentException("amount must be a positive amount with at most two decimals");
        }
        record L(UUID id, String status, BigDecimal credit) { }
        L l = jdbc.sql("""
                SELECT loan_id, status, lending.loan_credit(tenant_id, loan_id) FROM lending.loan WHERE loan_number = :n FOR UPDATE
                """).param("n", loanNumber)
            .query((rs, i) -> new L(rs.getObject(1, UUID.class), rs.getString(2), rs.getBigDecimal(3))).optional()
            .orElseThrow(() -> new NotFoundException("loan " + loanNumber + " not found"));
        if (!"CLOSED".equals(l.status())) {
            throw new ConflictException("only credit on a closed loan is refunded; loan " + loanNumber + " is " + l.status());
        }
        BigDecimal value = new BigDecimal(amount);
        if (value.compareTo(l.credit()) > 0) {
            throw new ConflictException("the loan holds " + m(l.credit()) + " of credit; " + amount + " cannot be refunded");
        }
        UUID account = jdbc.sql("SELECT account_id FROM savings.deposit_account WHERE account_number = :a")
            .param("a", accountNumber).query(UUID.class).optional()
            .orElseThrow(() -> new IllegalArgumentException("savings account " + accountNumber + " not found"));
        UUID id = UUID.nameUUIDFromBytes((tenantId + "|refund|" + idempotencyKey).getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO lending.credit_refund (tenant_id, refund_id, idempotency_key, loan_id, amount, method, deposit_account_id,
                    reason, requested_by)
                VALUES (:t, :id, :k, :loan, :amount, 'SAVINGS', :account, :reason, :by)
                """).param("t", tenantId).param("id", id).param("k", idempotencyKey).param("loan", l.id()).param("amount", value)
            .param("account", account).param("reason", reason == null ? null : reason.strip()).param("by", actor).update();
        return view(id);
    }

    @Transactional
    public RefundView decide(UUID tenantId, UUID refundId, boolean approve, String reason, String actor) {
        session.bind(tenantId);
        record R(UUID loan, String status, String requestedBy, BigDecimal amount, UUID account) { }
        R r = jdbc.sql("SELECT loan_id, status, requested_by, amount, deposit_account_id FROM lending.credit_refund WHERE refund_id = :id FOR UPDATE")
            .param("id", refundId)
            .query((rs, i) -> new R(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getBigDecimal(4),
                    rs.getObject(5, UUID.class)))
            .optional().orElseThrow(() -> new NotFoundException("refund not found"));
        if (!"REQUESTED".equals(r.status())) {
            throw new ConflictException("refund is already " + r.status());
        }
        if (r.requestedBy().equals(actor)) {
            throw new AccessDeniedException("the person who asked for the refund cannot decide it");
        }
        String why = reason == null || reason.isBlank() ? null : reason.strip();
        if (!approve) {
            if (why == null || why.length() < 3) {
                throw new IllegalArgumentException("a rejection needs a reason");
            }
            jdbc.sql("""
                    UPDATE lending.credit_refund SET status = 'REJECTED', decided_by = :a, decided_at = clock_timestamp(),
                           decision_reason = :r WHERE refund_id = :id
                    """).param("a", actor).param("r", why).param("id", refundId).update();
            return view(refundId);
        }
        jdbc.sql("SELECT 1 FROM lending.loan WHERE loan_id = :l FOR UPDATE").param("l", r.loan()).query(Integer.class).single();
        record P(String number, String loanBranch, String currency, String overpaymentGl, String control, String accountBranch) { }
        P p = jdbc.sql("""
                SELECT l.loan_number, l.branch_code, l.currency, v.gl_overpayment, a.gl_control_account, a.branch_code
                  FROM lending.loan l
                  JOIN lending.loan_product_version v ON v.tenant_id = l.tenant_id AND v.version_id = l.product_version_id
                  JOIN savings.deposit_account a ON a.tenant_id = l.tenant_id AND a.account_id = :account
                 WHERE l.loan_id = :loan
                """).param("loan", r.loan()).param("account", r.account())
            .query((rs, i) -> new P(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)))
            .single();
        BigDecimal credit = jdbc.sql("SELECT lending.loan_credit(tenant_id, loan_id) FROM lending.loan WHERE loan_id = :l")
            .param("l", r.loan()).query(BigDecimal.class).single();
        if (r.amount().compareTo(credit) > 0) {
            throw new ConflictException("the loan now holds " + m(credit) + " of credit; the refund of " + m(r.amount()) + " no longer fits");
        }
        Currency ccy = Currency.getInstance(p.currency());
        Money amount = Money.of(r.amount().setScale(ccy.getDefaultFractionDigits(), RoundingMode.UNNECESSARY), ccy);
        LocalDate today = businessDates.current();
        String key = "loan:" + r.loan() + ":CREDIT_REFUND:" + refundId;
        UUID entryId = UUID.nameUUIDFromBytes((tenantId + "|" + key).getBytes(StandardCharsets.UTF_8));
        journal.post(new JournalEntry(entryId, tenantId, key, today, "LOAN", "refund:" + p.number(), null, null, List.of(
                JournalLine.debit(p.overpaymentGl(), amount, p.loanBranch()),
                JournalLine.onAccount(p.control(), Side.CREDIT, amount, p.accountBranch(), r.account()))), actor);
        jdbc.sql("""
                UPDATE lending.credit_refund SET status = 'APPROVED', decided_by = :a, decided_at = clock_timestamp(),
                       decision_reason = :r, business_date = :d, entry_id = :e WHERE refund_id = :id
                """).param("a", actor).param("r", why).param("d", today).param("e", entryId).param("id", refundId).update();
        return view(refundId);
    }

    @Transactional(readOnly = true)
    public List<RefundView> list(UUID tenantId, String status, String loanNumber) {
        session.bind(tenantId);
        return jdbc.sql(VIEW + """
                 WHERE (CAST(:s AS text) IS NULL OR r.status = :s) AND (CAST(:n AS text) IS NULL OR l.loan_number = :n)
                 ORDER BY r.requested_at DESC LIMIT 200
                """).param("s", status).param("n", loanNumber).query((rs, i) -> row(rs)).list();
    }
}
