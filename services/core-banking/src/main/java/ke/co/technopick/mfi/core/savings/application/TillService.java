package ke.co.technopick.mfi.core.savings.application;

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
import ke.co.technopick.mfi.core.messaging.persistence.Outbox;
import ke.co.technopick.mfi.core.tenancy.persistence.BusinessDates;
import ke.co.technopick.mfi.core.tenancy.persistence.TenantSession;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Teller tills (ADR-0018). A teller opens a till session for the business day; their cash
 * transactions post to the till's own cash GL account. Cash moves between the branch vault and the
 * till only when a different person approves the transfer. At close the teller counts the cash;
 * the system compares it with the till's ledger balance, and a difference is posted to teller
 * shortages or overages only after a supervisor approves it (or sends the teller back to recount).
 */
@Service
public class TillService {

    public enum Direction { VAULT_TO_TILL, TILL_TO_VAULT }

    public record TillView(String tillCode, String branchCode, String currency, boolean active, String openedBy,
                           String sessionStatus) { }

    public record TillSessionView(UUID sessionId, String tillCode, String branchCode, String currency, String teller,
                                  LocalDate businessDate, String status, OffsetDateTime openedAt, String expectedCash,
                                  String countedAmount, String variance, int pendingTransactions, int pendingTransfers,
                                  String varianceApprovedBy, String varianceReason) { }

    public record CashTransferView(UUID transferId, UUID sessionId, String tillCode, String teller, Direction direction,
                                   String amount, String currency, String status, String requestedBy,
                                   OffsetDateTime requestedAt, String decidedBy, String decisionReason, boolean replayed) { }

    /** What the teller service needs to post against a session. */
    public record OpenTill(UUID sessionId, String tillCode, String branchCode, String currency, String glCash) { }

    private record Session(UUID id, UUID tillId, String tillCode, String branch, String currency, String glCash,
                           String teller, String status, BigDecimal counted, BigDecimal expected) { }

    private final JdbcClient jdbc;
    private final TenantSession session;
    private final BusinessDates businessDates;
    private final JournalPosting journal;
    private final Outbox outbox;

    public TillService(JdbcClient jdbc, TenantSession session, BusinessDates businessDates, JournalPosting journal,
                       Outbox outbox) {
        this.jdbc = jdbc;
        this.session = session;
        this.businessDates = businessDates;
        this.journal = journal;
        this.outbox = outbox;
    }

    @Transactional(readOnly = true)
    public List<TillView> tills(UUID tenantId) {
        session.bind(tenantId);
        return jdbc.sql("""
                SELECT t.till_code, t.branch_code, t.currency, t.active, s.teller, s.status
                  FROM savings.till t
                  LEFT JOIN savings.till_session s ON s.tenant_id = t.tenant_id AND s.till_id = t.till_id AND s.status <> 'CLOSED'
                 ORDER BY t.branch_code, t.till_code
                """)
            .query((rs, i) -> new TillView(rs.getString("till_code"), rs.getString("branch_code"), rs.getString("currency"),
                    rs.getBoolean("active"), rs.getString("teller"), rs.getString("status")))
            .list();
    }

    @Transactional
    public TillSessionView open(UUID tenantId, String tillCode, String actor) {
        session.bind(tenantId);
        if (byTeller(actor).isPresent()) {
            throw new ConflictException("you already have a till open; close it first");
        }
        record Till(UUID id, boolean active) { }
        Till till = jdbc.sql("SELECT till_id, active FROM savings.till WHERE till_code = :c").param("c", tillCode.strip())
            .query((rs, i) -> new Till(rs.getObject("till_id", UUID.class), rs.getBoolean("active"))).optional()
            .orElseThrow(() -> new IllegalArgumentException("till " + tillCode + " not found"));
        if (!till.active()) {
            throw new IllegalArgumentException("till " + tillCode + " is not in service");
        }
        Integer busy = jdbc.sql("SELECT count(*) FROM savings.till_session WHERE till_id = :t AND status <> 'CLOSED'")
            .param("t", till.id()).query(Integer.class).single();
        if (busy > 0) {
            throw new ConflictException("till " + tillCode + " is in use by another teller");
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO savings.till_session (tenant_id, session_id, till_id, teller, business_date)
                VALUES (:tenant, :id, :till, :teller, :date)
                """)
            .param("tenant", tenantId).param("id", id).param("till", till.id()).param("teller", actor)
            .param("date", businessDates.current()).update();
        return view(id);
    }

    /** The caller's current (open or closing) session, if any. */
    @Transactional(readOnly = true)
    public Optional<TillSessionView> mine(UUID tenantId, String actor) {
        session.bind(tenantId);
        return byTeller(actor).map(s -> view(s.id()));
    }

    /** For the teller service: the actor's OPEN session, inside the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<OpenTill> openTillOf(String actor) {
        return byTeller(actor).filter(s -> "OPEN".equals(s.status()))
            .map(s -> new OpenTill(s.id(), s.tillCode(), s.branch(), s.currency(), s.glCash()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String tillGlOfSession(UUID sessionId) {
        return lock(sessionId).glCash();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal tillCash(String glCash) {
        return jdbc.sql("""
                SELECT COALESCE(sum(CASE side WHEN 'D' THEN amount ELSE -amount END), 0) FROM ledger.journal_line
                 WHERE account_code = :gl
                """)
            .param("gl", glCash).query(BigDecimal.class).single();
    }

    @Transactional
    public CashTransferView requestTransfer(UUID tenantId, UUID sessionId, String idempotencyKey, Direction direction,
                                            BigDecimal amountValue, String actor) {
        session.bind(tenantId);
        Optional<UUID> existing = jdbc.sql("SELECT transfer_id FROM savings.cash_transfer WHERE idempotency_key = :k")
            .param("k", idempotencyKey).query(UUID.class).optional();
        if (existing.isPresent()) {
            CashTransferView v = transfer(existing.get(), true);
            if (v.direction() != direction || new BigDecimal(v.amount()).compareTo(amountValue) != 0) {
                throw new ConflictException("idempotency key already used for a different transfer");
            }
            return v;
        }
        Session s = lock(sessionId);
        if (!s.teller().equals(actor) || !"OPEN".equals(s.status())) {
            throw new AccessDeniedException("transfers are requested on your own open till");
        }
        Money amount = money(amountValue, Currency.getInstance(s.currency()));
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (direction == Direction.TILL_TO_VAULT && tillCash(s.glCash()).compareTo(amount.amount()) < 0) {
            throw new IllegalArgumentException("the till holds only " + plain(tillCash(s.glCash())));
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO savings.cash_transfer (tenant_id, transfer_id, idempotency_key, session_id, direction, amount,
                    currency, requested_by)
                VALUES (:tenant, :id, :key, :session, :direction, :amount, :ccy, :actor)
                """)
            .param("tenant", tenantId).param("id", id).param("key", idempotencyKey).param("session", sessionId)
            .param("direction", direction.name()).param("amount", amount.amount()).param("ccy", s.currency())
            .param("actor", actor).update();
        return transfer(id, false);
    }

    @Transactional(readOnly = true)
    public List<CashTransferView> pendingTransfers(UUID tenantId) {
        session.bind(tenantId);
        return jdbc.sql("SELECT transfer_id FROM savings.cash_transfer WHERE status = 'PENDING' ORDER BY requested_at")
            .query(UUID.class).list().stream().map(id -> transfer(id, false)).toList();
    }

    @Transactional
    public CashTransferView decideTransfer(UUID tenantId, UUID transferId, boolean approve, String reason, String actor) {
        session.bind(tenantId);
        record T(UUID session, Direction direction, BigDecimal amount, String currency, String status, String requestedBy) { }
        T t = jdbc.sql("""
                SELECT session_id, direction, amount, currency, status, requested_by FROM savings.cash_transfer
                 WHERE transfer_id = :id FOR UPDATE
                """)
            .param("id", transferId)
            .query((rs, i) -> new T(rs.getObject("session_id", UUID.class), Direction.valueOf(rs.getString("direction")),
                    rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("status"), rs.getString("requested_by")))
            .optional().orElseThrow(() -> new NotFoundException("transfer not found"));
        if (!"PENDING".equals(t.status())) {
            throw new ConflictException("transfer is already " + t.status());
        }
        if (t.requestedBy().equals(actor)) {
            throw new AccessDeniedException("the teller who asked for the transfer cannot approve it");
        }
        if (!approve) {
            requireReason(reason, "a rejection needs a reason");
            jdbc.sql("UPDATE savings.cash_transfer SET status = 'REJECTED', decided_by = :a, decision_reason = :r WHERE transfer_id = :id")
                .param("a", actor).param("r", reason.strip()).param("id", transferId).update();
            return transfer(transferId, false);
        }
        Session s = lock(t.session());
        Money amount = money(t.amount(), Currency.getInstance(t.currency()));
        if (t.direction() == Direction.TILL_TO_VAULT && tillCash(s.glCash()).compareTo(amount.amount()) < 0) {
            throw new IllegalArgumentException("the till holds only " + plain(tillCash(s.glCash())));
        }
        String vault = vaultGl(s.branch(), s.currency());
        UUID entry = post(s, "cash-transfer:" + transferId, t.direction() == Direction.VAULT_TO_TILL
                ? List.of(JournalLine.debit(s.glCash(), amount, s.branch()), JournalLine.credit(vault, amount, s.branch()))
                : List.of(JournalLine.debit(vault, amount, s.branch()), JournalLine.credit(s.glCash(), amount, s.branch())), actor);
        jdbc.sql("""
                UPDATE savings.cash_transfer SET status = 'POSTED', decided_by = :a, decision_reason = :r, journal_entry_id = :e
                 WHERE transfer_id = :id
                """)
            .param("a", actor).param("r", reason == null || reason.isBlank() ? null : reason.strip()).param("e", entry)
            .param("id", transferId).update();
        return transfer(transferId, false);
    }

    /** Teller's closing count. Balanced: closed at once. Otherwise waits for a supervisor. */
    @Transactional
    public TillSessionView close(UUID tenantId, UUID sessionId, BigDecimal countedValue, String actor) {
        session.bind(tenantId);
        Session s = lock(sessionId);
        if (!s.teller().equals(actor)) {
            throw new AccessDeniedException("only the teller who opened the till can close it");
        }
        if (!"OPEN".equals(s.status())) {
            throw new ConflictException("till session is " + s.status());
        }
        Money counted = money(countedValue, Currency.getInstance(s.currency()));
        BigDecimal expected = tillCash(s.glCash());
        boolean balanced = counted.amount().compareTo(expected) == 0;
        jdbc.sql("""
                UPDATE savings.till_session SET status = :status, counted_amount = :counted, expected_amount = :expected
                 WHERE session_id = :id
                """)
            .param("status", balanced ? "CLOSED" : "CLOSING").param("counted", counted.amount()).param("expected", expected)
            .param("id", sessionId).update();
        if (balanced) {
            outbox.record(tenantId, "TillClosed", "till_session", sessionId.toString(),
                    "{\"sessionId\":\"%s\",\"variance\":\"0.00\"}".formatted(sessionId), "till:" + sessionId);
        }
        return view(sessionId);
    }

    @Transactional(readOnly = true)
    public List<TillSessionView> awaitingVarianceApproval(UUID tenantId) {
        session.bind(tenantId);
        return jdbc.sql("SELECT session_id FROM savings.till_session WHERE status = 'CLOSING' ORDER BY close_requested_at")
            .query(UUID.class).list().stream().map(this::view).toList();
    }

    /** Supervisor accepts the variance: posts it to shortages / overages and closes the session. */
    @Transactional
    public TillSessionView approveVariance(UUID tenantId, UUID sessionId, String reason, String actor) {
        session.bind(tenantId);
        Session s = lock(sessionId);
        checkSupervisor(s, actor);
        requireReason(reason, "record why the variance is accepted");
        BigDecimal variance = s.counted().subtract(s.expected());
        Money abs = money(variance.abs(), Currency.getInstance(s.currency()));
        record Gl(String shortage, String overage) { }
        Gl gl = jdbc.sql("SELECT gl_shortage, gl_overage FROM savings.branch_cash_account WHERE branch_code = :b AND currency = :c")
            .param("b", s.branch()).param("c", s.currency())
            .query((rs, i) -> new Gl(rs.getString("gl_shortage"), rs.getString("gl_overage"))).single();
        String varianceGl = variance.signum() < 0 ? gl.shortage() : gl.overage();
        if (varianceGl == null) {
            throw new IllegalStateException("no teller " + (variance.signum() < 0 ? "shortage" : "overage")
                    + " account configured for branch " + s.branch());
        }
        UUID entry = post(s, "till-variance:" + sessionId, variance.signum() < 0
                ? List.of(JournalLine.debit(varianceGl, abs, s.branch()), JournalLine.credit(s.glCash(), abs, s.branch()))
                : List.of(JournalLine.debit(s.glCash(), abs, s.branch()), JournalLine.credit(varianceGl, abs, s.branch())), actor);
        jdbc.sql("""
                UPDATE savings.till_session SET status = 'CLOSED', variance_approved_by = :a, variance_reason = :r,
                       variance_entry_id = :e
                 WHERE session_id = :id
                """)
            .param("a", actor).param("r", reason.strip()).param("e", entry).param("id", sessionId).update();
        outbox.record(tenantId, "TillClosed", "till_session", sessionId.toString(),
                "{\"sessionId\":\"%s\",\"variance\":\"%s\"}".formatted(sessionId, plain(variance)), "till:" + sessionId);
        return view(sessionId);
    }

    /** Supervisor sends the teller back to count again: the session reopens with no count. */
    @Transactional
    public TillSessionView recount(UUID tenantId, UUID sessionId, String reason, String actor) {
        session.bind(tenantId);
        Session s = lock(sessionId);
        checkSupervisor(s, actor);
        requireReason(reason, "say why a recount is needed");
        jdbc.sql("""
                UPDATE savings.till_session SET status = 'OPEN', counted_amount = NULL, expected_amount = NULL,
                       close_requested_at = NULL
                 WHERE session_id = :id
                """)
            .param("id", sessionId).update();
        return view(sessionId);
    }

    // ------------------------------------------------------------------ helpers

    private void checkSupervisor(Session s, String actor) {
        if (!"CLOSING".equals(s.status())) {
            throw new ConflictException("till session is " + s.status());
        }
        if (s.teller().equals(actor)) {
            throw new AccessDeniedException("the teller cannot approve their own till variance");
        }
    }

    private UUID post(Session s, String key, List<JournalLine> lines, String actor) {
        UUID tenant = jdbc.sql("SELECT tenancy.current_tenant()").query(UUID.class).single();
        UUID entryId = UUID.nameUUIDFromBytes((tenant + "|" + key).getBytes(StandardCharsets.UTF_8));
        journal.post(new JournalEntry(entryId, tenant, key, businessDates.current(), "TELLER", "till:" + s.id(), null, null,
                lines), actor);
        return entryId;
    }

    private String vaultGl(String branch, String currency) {
        return jdbc.sql("SELECT gl_cash FROM savings.branch_cash_account WHERE branch_code = :b AND currency = :c")
            .param("b", branch).param("c", currency).query(String.class).single();
    }

    private static final String SESSION_SELECT = """
            SELECT s.session_id, s.till_id, t.till_code, t.branch_code, t.currency, t.gl_cash, s.teller, s.status,
                   s.counted_amount, s.expected_amount
              FROM savings.till_session s JOIN savings.till t ON t.tenant_id = s.tenant_id AND t.till_id = s.till_id""";

    private static Session session(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Session(rs.getObject("session_id", UUID.class), rs.getObject("till_id", UUID.class),
                rs.getString("till_code"), rs.getString("branch_code"), rs.getString("currency"), rs.getString("gl_cash"),
                rs.getString("teller"), rs.getString("status"), rs.getBigDecimal("counted_amount"),
                rs.getBigDecimal("expected_amount"));
    }

    private Optional<Session> byTeller(String actor) {
        return jdbc.sql(SESSION_SELECT + " WHERE s.teller = :a AND s.status <> 'CLOSED'").param("a", actor)
            .query(TillService::session).optional();
    }

    private Session lock(UUID sessionId) {
        return jdbc.sql(SESSION_SELECT + " WHERE s.session_id = :id FOR UPDATE OF s").param("id", sessionId)
            .query(TillService::session).optional().orElseThrow(() -> new NotFoundException("till session not found"));
    }

    private TillSessionView view(UUID sessionId) {
        Session s = jdbc.sql(SESSION_SELECT + " WHERE s.session_id = :id").param("id", sessionId)
            .query(TillService::session).optional().orElseThrow(() -> new NotFoundException("till session not found"));
        record Extra(LocalDate date, OffsetDateTime openedAt, String approvedBy, String reason) { }
        Extra x = jdbc.sql("""
                SELECT business_date, opened_at, variance_approved_by, variance_reason FROM savings.till_session
                 WHERE session_id = :id
                """)
            .param("id", sessionId)
            .query((rs, i) -> new Extra(rs.getObject("business_date", LocalDate.class),
                    rs.getObject("opened_at", OffsetDateTime.class), rs.getString("variance_approved_by"),
                    rs.getString("variance_reason")))
            .single();
        int pendingTxns = jdbc.sql("SELECT count(*) FROM savings.teller_transaction WHERE session_id = :id AND status = 'PENDING_APPROVAL'")
            .param("id", sessionId).query(Integer.class).single();
        int pendingTransfers = jdbc.sql("SELECT count(*) FROM savings.cash_transfer WHERE session_id = :id AND status = 'PENDING'")
            .param("id", sessionId).query(Integer.class).single();
        return new TillSessionView(s.id(), s.tillCode(), s.branch(), s.currency(), s.teller(), x.date(), s.status(),
                x.openedAt(), plain(tillCash(s.glCash())), s.counted() == null ? null : plain(s.counted()),
                s.counted() == null ? null : plain(s.counted().subtract(s.expected())), pendingTxns, pendingTransfers,
                x.approvedBy(), x.reason());
    }

    private CashTransferView transfer(UUID id, boolean replayed) {
        return jdbc.sql("""
                SELECT c.transfer_id, c.session_id, t.till_code, s.teller, c.direction, c.amount, c.currency, c.status,
                       c.requested_by, c.requested_at, c.decided_by, c.decision_reason
                  FROM savings.cash_transfer c
                  JOIN savings.till_session s ON s.tenant_id = c.tenant_id AND s.session_id = c.session_id
                  JOIN savings.till t ON t.tenant_id = s.tenant_id AND t.till_id = s.till_id
                 WHERE c.transfer_id = :id
                """)
            .param("id", id)
            .query((rs, i) -> new CashTransferView(rs.getObject("transfer_id", UUID.class),
                    rs.getObject("session_id", UUID.class), rs.getString("till_code"), rs.getString("teller"),
                    Direction.valueOf(rs.getString("direction")), plain(rs.getBigDecimal("amount")), rs.getString("currency"),
                    rs.getString("status"), rs.getString("requested_by"), rs.getObject("requested_at", OffsetDateTime.class),
                    rs.getString("decided_by"), rs.getString("decision_reason"), replayed))
            .single();
    }

    private static void requireReason(String reason, String message) {
        if (reason == null || reason.strip().length() < 3) {
            throw new IllegalArgumentException(message);
        }
    }

    private static Money money(BigDecimal value, Currency currency) {
        if (value.signum() < 0) {
            throw new IllegalArgumentException("amount cannot be negative");
        }
        if (value.stripTrailingZeros().scale() > currency.getDefaultFractionDigits()) {
            throw new IllegalArgumentException("amount has too many decimal places");
        }
        return Money.of(value.setScale(currency.getDefaultFractionDigits(), RoundingMode.UNNECESSARY), currency);
    }

    static String plain(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
