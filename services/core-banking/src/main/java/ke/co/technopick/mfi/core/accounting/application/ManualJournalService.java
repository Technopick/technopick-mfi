package ke.co.technopick.mfi.core.accounting.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ke.co.technopick.mfi.canonical.Money;
import ke.co.technopick.mfi.core.accounting.domain.JournalEntry;
import ke.co.technopick.mfi.core.accounting.domain.JournalLine;
import ke.co.technopick.mfi.core.accounting.domain.Side;
import ke.co.technopick.mfi.core.common.ConflictException;
import ke.co.technopick.mfi.core.common.NotFoundException;
import ke.co.technopick.mfi.core.messaging.persistence.Outbox;
import ke.co.technopick.mfi.core.tenancy.persistence.BusinessDates;
import ke.co.technopick.mfi.core.tenancy.persistence.TenantSession;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manual journals (ADR-0025): finance proposes a balanced journal on non-control accounts; a
 * different person posts or rejects it. Control accounts (customer subledgers, loan receivables
 * and overpayments, savings interest payable, till cash) are refused so they change only through
 * their business flows and reconciliation stays meaningful. The database (V23) re-checks all of it.
 */
@Service
public class ManualJournalService {

    public enum LineSide { DEBIT, CREDIT }

    public record LineInput(String accountCode, LineSide side, BigDecimal amount, String branchCode, String narrative) { }

    public record ManualJournalLine(int lineNo, String accountCode, String accountName, String side, String amount, String currency,
                       String branchCode, String narrative) { }

    public record ManualJournalView(UUID journalId, LocalDate businessDate, String narrative, String status,
                                    String requestedBy, OffsetDateTime requestedAt, String decidedBy, String decisionReason,
                                    UUID entryId, String totalDebit, List<ManualJournalLine> lines, boolean replayed) { }

    static final int MAX_LINES = 50;

    private final JdbcClient jdbc;
    private final TenantSession session;
    private final BusinessDates businessDates;
    private final JournalPosting journal;
    private final Outbox outbox;

    public ManualJournalService(JdbcClient jdbc, TenantSession session, BusinessDates businessDates, JournalPosting journal,
                                Outbox outbox) {
        this.jdbc = jdbc;
        this.session = session;
        this.businessDates = businessDates;
        this.journal = journal;
        this.outbox = outbox;
    }

    @Transactional
    public ManualJournalView propose(UUID tenantId, String idempotencyKey, LocalDate date, String narrative,
                                     List<LineInput> lines, String actor) {
        session.bind(tenantId);
        Optional<UUID> existing = jdbc.sql("SELECT journal_id FROM ledger.manual_journal WHERE idempotency_key = :k")
            .param("k", idempotencyKey).query(UUID.class).optional();
        if (existing.isPresent()) {
            ManualJournalView v = view(existing.get(), true);
            if (!v.narrative().equals(narrative.strip()) || v.lines().size() != lines.size()) {
                throw new ConflictException("idempotency key already used for a different journal");
            }
            return v;
        }
        LocalDate today = businessDates.current();
        LocalDate businessDate = date == null ? today : date;
        if (businessDate.isAfter(today)) {
            throw new IllegalArgumentException("a journal cannot be dated after the business date " + today);
        }
        if (jdbc.sql("""
                SELECT count(*) FROM ledger.accounting_period WHERE status = 'OPEN' AND :d BETWEEN start_date AND end_date
                """).param("d", businessDate).query(Integer.class).single() == 0) {
            throw new IllegalArgumentException(businessDate + " is not in an open accounting period");
        }
        if (lines == null || lines.size() < 2 || lines.size() > MAX_LINES) {
            throw new IllegalArgumentException("a journal has between 2 and " + MAX_LINES + " lines");
        }
        record Acc(String currency, boolean postable, String status, boolean control) { }
        Map<String, BigDecimal> balance = new HashMap<>();
        List<String> currencies = new ArrayList<>();
        for (LineInput l : lines) {
            Acc a = jdbc.sql("""
                    SELECT currency, postable, status, ledger.is_control_account(tenant_id, account_code) AS control
                      FROM ledger.gl_account WHERE account_code = :c
                    """)
                .param("c", l.accountCode())
                .query((rs, i) -> new Acc(rs.getString(1), rs.getBoolean(2), rs.getString(3), rs.getBoolean(4))).optional()
                .orElseThrow(() -> new IllegalArgumentException("GL account " + l.accountCode() + " not found"));
            if (!a.postable() || !"ACTIVE".equals(a.status())) {
                throw new IllegalArgumentException(l.accountCode() + " is not an active postable account");
            }
            if (a.control()) {
                throw new IllegalArgumentException(l.accountCode()
                        + " is a control account; it changes only through its business flows (reverse or correct the transaction instead)");
            }
            Currency ccy = Currency.getInstance(a.currency());
            if (l.amount() == null || l.amount().signum() <= 0
                    || l.amount().stripTrailingZeros().scale() > ccy.getDefaultFractionDigits()) {
                throw new IllegalArgumentException("line amounts must be positive with at most "
                        + ccy.getDefaultFractionDigits() + " decimals");
            }
            balance.merge(a.currency(), l.side() == LineSide.DEBIT ? l.amount() : l.amount().negate(), BigDecimal::add);
            currencies.add(a.currency());
        }
        balance.forEach((c, b) -> {
            if (b.signum() != 0) {
                throw new IllegalArgumentException("debits and credits differ by " + plain(b.abs()) + " " + c);
            }
        });
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO ledger.manual_journal (tenant_id, journal_id, idempotency_key, business_date, narrative, requested_by)
                VALUES (:t, :id, :k, :d, :n, :a)
                """)
            .param("t", tenantId).param("id", id).param("k", idempotencyKey).param("d", businessDate)
            .param("n", narrative.strip()).param("a", actor).update();
        for (int i = 0; i < lines.size(); i++) {
            LineInput l = lines.get(i);
            jdbc.sql("""
                    INSERT INTO ledger.manual_journal_line (tenant_id, journal_id, line_no, account_code, side, amount, currency,
                        branch_code, narrative)
                    VALUES (:t, :id, :no, :acc, :side, :amount, :ccy, :branch, :narrative)
                    """)
                .param("t", tenantId).param("id", id).param("no", i + 1).param("acc", l.accountCode())
                .param("side", l.side() == LineSide.DEBIT ? "D" : "C").param("amount", l.amount()).param("ccy", currencies.get(i))
                .param("branch", l.branchCode()).param("narrative", l.narrative() == null || l.narrative().isBlank() ? null
                        : l.narrative().strip())
                .update();
        }
        return view(id, false);
    }

    @Transactional(readOnly = true)
    public List<ManualJournalView> list(UUID tenantId, String status) {
        session.bind(tenantId);
        return jdbc.sql("""
                SELECT journal_id FROM ledger.manual_journal WHERE (CAST(:s AS varchar) IS NULL OR status = :s)
                 ORDER BY requested_at DESC LIMIT 200
                """)
            .param("s", status).query(UUID.class).list().stream().map(id -> view(id, false)).toList();
    }

    @Transactional
    public ManualJournalView decide(UUID tenantId, UUID journalId, boolean post, String reason, String actor) {
        session.bind(tenantId);
        record J(LocalDate date, String status, String requestedBy, String narrative) { }
        J j = jdbc.sql("SELECT business_date, status, requested_by, narrative FROM ledger.manual_journal WHERE journal_id = :id FOR UPDATE")
            .param("id", journalId)
            .query((rs, i) -> new J(rs.getObject(1, LocalDate.class), rs.getString(2), rs.getString(3), rs.getString(4)))
            .optional().orElseThrow(() -> new NotFoundException("manual journal not found"));
        if (!"PENDING".equals(j.status())) {
            throw new ConflictException("journal is already " + j.status());
        }
        if (j.requestedBy().equals(actor)) {
            throw new AccessDeniedException("the person who proposed the journal cannot decide it");
        }
        if (!post) {
            if (reason == null || reason.strip().length() < 3) {
                throw new IllegalArgumentException("a rejection needs a reason");
            }
            jdbc.sql("UPDATE ledger.manual_journal SET status = 'REJECTED', decided_by = :a, decision_reason = :r WHERE journal_id = :id")
                .param("a", actor).param("r", reason.strip()).param("id", journalId).update();
            return view(journalId, false);
        }
        List<JournalLine> lines = jdbc.sql("""
                SELECT account_code, side, amount, currency, branch_code, narrative FROM ledger.manual_journal_line
                 WHERE journal_id = :id ORDER BY line_no
                """)
            .param("id", journalId)
            .query((rs, i) -> {
                Currency c = Currency.getInstance(rs.getString(4));
                Money m = Money.of(rs.getBigDecimal(3).setScale(c.getDefaultFractionDigits(), RoundingMode.UNNECESSARY), c);
                return new JournalLine(rs.getString(1), "D".equals(rs.getString(2)) ? Side.DEBIT : Side.CREDIT, m,
                        rs.getString(5), rs.getString(6));
            })
            .list();
        String key = "manual:" + journalId;
        UUID entryId = UUID.nameUUIDFromBytes((tenantId + "|" + key).getBytes(StandardCharsets.UTF_8));
        journal.post(new JournalEntry(entryId, tenantId, key, j.date(), "MANUAL", "manual:" + journalId, null, null, lines), actor);
        jdbc.sql("""
                UPDATE ledger.manual_journal SET status = 'POSTED', decided_by = :a, decision_reason = :r, entry_id = :e
                 WHERE journal_id = :id
                """)
            .param("a", actor).param("r", reason == null || reason.isBlank() ? null : reason.strip()).param("e", entryId)
            .param("id", journalId).update();
        outbox.record(tenantId, "ManualJournalPosted", "manual_journal", journalId.toString(),
                "{\"journalId\":\"%s\",\"businessDate\":\"%s\",\"entryId\":\"%s\"}".formatted(journalId, j.date(), entryId),
                "manual:" + journalId);
        return view(journalId, false);
    }

    private ManualJournalView view(UUID id, boolean replayed) {
        List<ManualJournalLine> lines = jdbc.sql("""
                SELECT l.line_no, l.account_code, a.name, l.side, l.amount, l.currency, l.branch_code, l.narrative
                  FROM ledger.manual_journal_line l
                  JOIN ledger.gl_account a ON a.tenant_id = l.tenant_id AND a.account_code = l.account_code
                 WHERE l.journal_id = :id ORDER BY l.line_no
                """)
            .param("id", id)
            .query((rs, i) -> new ManualJournalLine(rs.getInt(1), rs.getString(2), rs.getString(3), "D".equals(rs.getString(4)) ? "DEBIT"
                    : "CREDIT", plain(rs.getBigDecimal(5)), rs.getString(6), rs.getString(7), rs.getString(8)))
            .list();
        BigDecimal debit = lines.stream().filter(l -> "DEBIT".equals(l.side())).map(l -> new BigDecimal(l.amount()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        return jdbc.sql("""
                SELECT journal_id, business_date, narrative, status, requested_by, requested_at, decided_by, decision_reason, entry_id
                  FROM ledger.manual_journal WHERE journal_id = :id
                """)
            .param("id", id)
            .query((rs, i) -> new ManualJournalView(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class),
                    rs.getString(3), rs.getString(4), rs.getString(5), rs.getObject(6, OffsetDateTime.class), rs.getString(7),
                    rs.getString(8), rs.getObject(9, UUID.class), plain(debit), lines, replayed))
            .optional().orElseThrow(() -> new NotFoundException("manual journal not found"));
    }

    private static String plain(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }
}
