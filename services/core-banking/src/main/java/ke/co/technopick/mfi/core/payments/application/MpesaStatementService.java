package ke.co.technopick.mfi.core.payments.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ke.co.technopick.mfi.core.common.ConflictException;
import ke.co.technopick.mfi.core.common.NotFoundException;
import ke.co.technopick.mfi.core.payments.domain.MpesaStatement;
import ke.co.technopick.mfi.core.payments.domain.MpesaStatement.AccountKind;
import ke.co.technopick.mfi.core.payments.domain.MpesaStatement.Line;
import ke.co.technopick.mfi.core.payments.domain.MpesaStatement.Parsed;
import ke.co.technopick.mfi.core.tenancy.persistence.TenantSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M-Pesa statement reconciliation (ADR-0038). Finance uploads the portal statement of a paybill or
 * B2C short code for one day. Each completed line is matched by receipt number to the payment or
 * disbursement the platform recorded; everything else is an exception that finance resolves by
 * linking the ledger entries that account for it (charges, transfers to the bank) or by a written
 * explanation. A second person signs the day off once nothing is open; the database re-derives the
 * ledger figures at sign-off.
 */
@Service
public class MpesaStatementService {

    public record StatementAccount(String accountKind, String shortCode, String clearingAccount, String branchCode) { }

    public record StatementUploadView(UUID uploadId, String accountKind, String shortCode, String clearingAccount,
                                      LocalDate statementDate, String fileName, int lineCount, String openingBalance,
                                      String closingBalance, String totalPaidIn, String totalWithdrawn, boolean balanceChainOk,
                                      String status, int matched, int exceptions, int resolved, int ignored, String uploadedBy,
                                      OffsetDateTime uploadedAt, String signedOffBy, OffsetDateTime signedOffAt,
                                      String ledgerBalance, String difference, Integer ledgerOnlyCount, String ledgerOnlyAmount,
                                      String note, String withdrawnBy, OffsetDateTime withdrawnAt) { }

    public record StatementLineView(int lineNo, String receiptNo, OffsetDateTime completedAt, String details,
                                    String transactionStatus, String paidIn, String withdrawn, String balance, String reasonType,
                                    String otherParty, String accountRef, String category, String status, String exceptionCode,
                                    String matchedReference, UUID paymentId, UUID disbursementId, UUID journalEntryId,
                                    String resolution, String resolutionNote, String resolvedBy) { }

    public record StatementLedgerItem(UUID entryId, LocalDate businessDate, String source, String reference, String amount) { }

    /** The ledger side now (open statements) or as signed off. */
    public record StatementCheck(boolean live, String ledgerBalance, String difference, int ledgerOnlyCount,
                                 String ledgerOnlyAmount, List<StatementLedgerItem> ledgerOnly) { }

    public record StatementDetail(StatementUploadView upload, StatementCheck check) { }

    public record StatementRematch(int matched, int stillOpen) { }

    private static final int LEDGER_ITEMS_SHOWN = 200;

    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final TenantSession session;

    public MpesaStatementService(JdbcClient jdbc, JdbcTemplate template, TenantSession session) {
        this.jdbc = jdbc;
        this.template = template;
        this.session = session;
    }

    @Transactional(readOnly = true)
    public List<StatementAccount> accounts(UUID tenantId) {
        session.bind(tenantId);
        return jdbc.sql("""
                SELECT 'PAYBILL' AS kind, short_code, gl_clearing, branch_code FROM payments.mpesa_paybill WHERE active
                UNION ALL
                SELECT 'B2C', short_code, gl_clearing, branch_code FROM payments.mpesa_b2c_account WHERE active
                ORDER BY 1, 2
                """)
            .query((rs, n) -> new StatementAccount(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)))
            .list();
    }

    @Transactional
    public StatementDetail upload(UUID tenantId, AccountKind kind, String shortCode, LocalDate statementDate, String fileName,
                                  String csv, String actor) {
        session.bind(tenantId);
        String clearing = jdbc.sql("""
                SELECT gl_clearing FROM payments.mpesa_paybill WHERE short_code = :s AND :k = 'PAYBILL'
                UNION ALL
                SELECT gl_clearing FROM payments.mpesa_b2c_account WHERE short_code = :s AND :k = 'B2C'
                """)
            .param("k", kind.name()).param("s", shortCode).query(String.class).optional()
            .orElseThrow(() -> new IllegalArgumentException("no " + kind + " short code " + shortCode + " is configured"));
        String hash = sha256(csv);
        Optional<UUID> sameFile = jdbc.sql("""
                SELECT upload_id FROM payments.statement_upload WHERE file_sha256 = :h AND status <> 'WITHDRAWN'
                """).param("h", hash).query(UUID.class).optional();
        if (sameFile.isPresent()) {
            throw new ConflictException("this file was already uploaded (statement " + sameFile.get() + ")");
        }
        Optional<UUID> sameDay = jdbc.sql("""
                SELECT upload_id FROM payments.statement_upload
                 WHERE account_kind = :k AND short_code = :s AND statement_date = :d AND status <> 'WITHDRAWN'
                """).param("k", kind.name()).param("s", shortCode).param("d", statementDate).query(UUID.class).optional();
        if (sameDay.isPresent()) {
            throw new ConflictException("a statement for " + shortCode + " on " + statementDate
                    + " is already uploaded; withdraw it first to replace it");
        }
        ZoneId zone = ZoneId.of(jdbc.sql("SELECT time_zone FROM tenancy.tenant").query(String.class).single());
        Parsed parsed = MpesaStatement.parse(csv, kind, shortCode, statementDate, zone);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO payments.statement_upload (tenant_id, upload_id, account_kind, short_code, gl_clearing, statement_date,
                    file_name, file_sha256, line_count, opening_balance, closing_balance, total_paid_in, total_withdrawn,
                    balance_chain_ok, uploaded_by)
                VALUES (tenancy.current_tenant(), :id, :k, :s, :gl, :d, :f, :h, :n, :o, :c, :pi, :w, :chain, :actor)
                """)
            .param("id", id).param("k", kind.name()).param("s", shortCode).param("gl", clearing).param("d", statementDate)
            .param("f", fileName.length() > 200 ? fileName.substring(0, 200) : fileName).param("h", hash)
            .param("n", parsed.lines().size()).param("o", parsed.openingBalance()).param("c", parsed.closingBalance())
            .param("pi", parsed.totalPaidIn()).param("w", parsed.totalWithdrawn()).param("chain", parsed.balanceChainOk())
            .param("actor", actor).update();
        List<Object[]> rows = new ArrayList<>(parsed.lines().size());
        for (Line l : parsed.lines()) {
            rows.add(new Object[] {id, l.lineNo(), l.receiptNo(), l.completedAt(), l.initiatedAt(), l.details(),
                l.status(), l.paidIn(), l.withdrawn(), l.balance(), l.reasonType(), l.otherPartyMasked(), l.linkedTxn(),
                l.accountRef(), l.category().name(), l.completed() ? "EXCEPTION" : "IGNORED",
                l.completed() ? "NOT_IN_LEDGER" : null});
        }
        int[] types = {Types.OTHER, Types.INTEGER, Types.VARCHAR, Types.TIMESTAMP_WITH_TIMEZONE, Types.TIMESTAMP_WITH_TIMEZONE,
            Types.VARCHAR, Types.VARCHAR, Types.NUMERIC, Types.NUMERIC, Types.NUMERIC, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR,
            Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR};
        template.batchUpdate("""
                INSERT INTO payments.statement_line (tenant_id, upload_id, line_no, receipt_no, completed_at, initiated_at, details,
                    txn_status, paid_in, withdrawn, balance, reason_type, other_party_masked, linked_txn, account_ref, category,
                    status, exception_code)
                VALUES (tenancy.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rows, types);
        match(id, kind);
        return detail(id);
    }

    @Transactional
    public StatementRematch rematch(UUID tenantId, UUID uploadId) {
        session.bind(tenantId);
        StatementUploadView u = lockOpen(uploadId);
        int matched = match(uploadId, AccountKind.valueOf(u.accountKind()));
        int open = jdbc.sql("SELECT count(*) FROM payments.statement_line WHERE upload_id = :id AND active AND status = 'EXCEPTION'")
            .param("id", uploadId).query(Integer.class).single();
        return new StatementRematch(matched, open);
    }

    /**
     * Matches open receipt (paybill) or payment (B2C) lines to what the platform recorded under the
     * same receipt number and short code. Only an equal amount that no other live line has matched
     * is a match; anything else stays an exception with the reason.
     */
    private int match(UUID uploadId, AccountKind kind) {
        String candidates = kind == AccountKind.PAYBILL
                ? """
                  SELECT s.line_no, p.payment_id AS target, (p.amount = s.paid_in) AS same_amount, true AS usable,
                         EXISTS (SELECT 1 FROM payments.statement_line o WHERE o.tenant_id = s.tenant_id AND o.active
                                    AND o.payment_id = p.payment_id) AS taken,
                         row_number() OVER (PARTITION BY p.payment_id ORDER BY s.line_no) AS rn
                    FROM payments.statement_line s
                    JOIN payments.statement_upload u ON u.tenant_id = s.tenant_id AND u.upload_id = s.upload_id
                    JOIN payments.provider_payment p ON p.tenant_id = s.tenant_id AND p.provider_ref = s.receipt_no
                                                    AND p.short_code = u.short_code
                   WHERE s.upload_id = :id AND s.active AND s.status = 'EXCEPTION' AND s.category = 'RECEIPT'
                  """
                : """
                  SELECT s.line_no, d.disbursement_id AS target, (d.amount = s.withdrawn) AS same_amount,
                         (d.status = 'COMPLETED' AND d.attention IS NULL) AS usable,
                         EXISTS (SELECT 1 FROM payments.statement_line o WHERE o.tenant_id = s.tenant_id AND o.active
                                    AND o.disbursement_id = d.disbursement_id) AS taken,
                         row_number() OVER (PARTITION BY d.disbursement_id ORDER BY s.line_no) AS rn
                    FROM payments.statement_line s
                    JOIN payments.statement_upload u ON u.tenant_id = s.tenant_id AND u.upload_id = s.upload_id
                    JOIN payments.b2c_disbursement d ON d.tenant_id = s.tenant_id AND d.transaction_id = s.receipt_no
                                                    AND d.short_code = u.short_code
                   WHERE s.upload_id = :id AND s.active AND s.status = 'EXCEPTION' AND s.category = 'PAYMENT'
                  """;
        String column = kind == AccountKind.PAYBILL ? "payment_id" : "disbursement_id";
        // Lines inserted in this transaction have no statistics yet, so the planner expects one row
        // and picks nested loops that re-run the candidates for every line (minutes for a busy day).
        // The candidates are materialised once and joined by hash instead.
        jdbc.sql("SET LOCAL enable_nestloop = off").update();
        jdbc.sql("""
                WITH c AS MATERIALIZED (%s)
                UPDATE payments.statement_line s
                   SET status = CASE WHEN c.usable AND c.same_amount AND NOT c.taken AND c.rn = 1 THEN 'MATCHED' ELSE 'EXCEPTION' END,
                       exception_code = CASE WHEN c.usable AND c.same_amount AND NOT c.taken AND c.rn = 1 THEN NULL
                                             WHEN NOT c.usable THEN 'DISBURSEMENT_ATTENTION'
                                             WHEN NOT c.same_amount THEN 'AMOUNT_MISMATCH'
                                             ELSE 'ALREADY_MATCHED' END,
                       %s = CASE WHEN c.usable AND c.same_amount AND NOT c.taken AND c.rn = 1 THEN c.target END
                  FROM c
                 WHERE s.tenant_id = tenancy.current_tenant() AND s.upload_id = :id AND s.line_no = c.line_no
                """.formatted(candidates, column))
            .param("id", uploadId).update();
        jdbc.sql("SET LOCAL enable_nestloop = on").update();
        return jdbc.sql("SELECT count(*) FROM payments.statement_line WHERE upload_id = :id AND status = 'MATCHED'")
            .param("id", uploadId).query(Integer.class).single();
    }

    @Transactional
    public StatementDetail explain(UUID tenantId, UUID uploadId, List<Integer> lineNos, String note, String actor) {
        session.bind(tenantId);
        lockOpen(uploadId);
        resolve(uploadId, lineNos, null, note, actor);
        return detail(uploadId);
    }

    /** Links exception lines to the ledger entry that accounts for them; the database checks the totals agree. */
    @Transactional
    public StatementDetail link(UUID tenantId, UUID uploadId, List<Integer> lineNos, UUID journalEntryId, String note,
                                String actor) {
        session.bind(tenantId);
        lockOpen(uploadId);
        boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM ledger.journal_entry WHERE entry_id = :e)")
            .param("e", journalEntryId).query(Boolean.class).single();
        if (!exists) {
            throw new NotFoundException("journal entry " + journalEntryId + " not found");
        }
        resolve(uploadId, lineNos, journalEntryId, note, actor);
        // Surface the total check (a deferred constraint) now, as a business-rule error, not at commit.
        jdbc.sql("SET CONSTRAINTS payments.statement_line_link IMMEDIATE").update();
        return detail(uploadId);
    }

    private void resolve(UUID uploadId, List<Integer> lineNos, UUID entry, String note, String actor) {
        List<Integer> distinct = lineNos.stream().distinct().toList();
        int open = jdbc.sql("""
                SELECT count(*) FROM payments.statement_line
                 WHERE upload_id = :id AND line_no IN (:lines) AND active AND status = 'EXCEPTION'
                """).param("id", uploadId).param("lines", distinct).query(Integer.class).single();
        if (open != distinct.size()) {
            throw new ConflictException("only open exceptions can be resolved; check the selected lines");
        }
        jdbc.sql("""
                UPDATE payments.statement_line
                   SET status = 'RESOLVED', resolution = :r, journal_entry_id = :e, resolution_note = :note,
                       resolved_by = :actor, resolved_at = clock_timestamp()
                 WHERE upload_id = :id AND line_no IN (:lines)
                """)
            .param("r", entry == null ? "EXPLAINED" : "LINKED_JOURNAL").param("e", entry).param("note", note.strip())
            .param("actor", actor).param("id", uploadId).param("lines", distinct).update();
    }

    @Transactional
    public StatementDetail signOff(UUID tenantId, UUID uploadId, String note, String actor) {
        session.bind(tenantId);
        StatementUploadView u = lockOpen(uploadId);
        if (u.uploadedBy().equals(actor)) {
            throw new ConflictException("the person who uploaded the statement cannot sign it off");
        }
        StatementCheck c = check(uploadId, u.statementDate(), u.clearingAccount(), u.closingBalance());
        jdbc.sql("""
                UPDATE payments.statement_upload
                   SET status = 'SIGNED_OFF', signed_off_by = :actor, signed_off_at = clock_timestamp(), ledger_balance = :lb,
                       difference = :diff, ledger_only_count = :n, ledger_only_amount = :amt, note = :note
                 WHERE upload_id = :id
                """)
            .param("actor", actor).param("lb", new BigDecimal(c.ledgerBalance())).param("diff", new BigDecimal(c.difference()))
            .param("n", c.ledgerOnlyCount()).param("amt", new BigDecimal(c.ledgerOnlyAmount()))
            .param("note", note == null || note.isBlank() ? null : note.strip()).param("id", uploadId).update();
        return detail(uploadId);
    }

    /** A wrongly uploaded statement is withdrawn: its lines release their matches and the day can be uploaded again. */
    @Transactional
    public StatementDetail withdraw(UUID tenantId, UUID uploadId, String reason, String actor) {
        session.bind(tenantId);
        lockOpen(uploadId);
        jdbc.sql("UPDATE payments.statement_line SET active = false WHERE upload_id = :id AND active").param("id", uploadId).update();
        jdbc.sql("""
                UPDATE payments.statement_upload SET status = 'WITHDRAWN', withdrawn_by = :actor, withdrawn_at = clock_timestamp(),
                       note = :reason
                 WHERE upload_id = :id
                """).param("actor", actor).param("reason", reason.strip()).param("id", uploadId).update();
        return detail(uploadId);
    }

    @Transactional(readOnly = true)
    public List<StatementUploadView> list(UUID tenantId, String status) {
        session.bind(tenantId);
        return jdbc.sql(VIEW + " WHERE (CAST(:status AS varchar) IS NULL OR u.status = :status) "
                + GROUP + " ORDER BY u.statement_date DESC, u.account_kind, u.short_code LIMIT 200")
            .param("status", status).query(MpesaStatementService::upload).list();
    }

    @Transactional(readOnly = true)
    public StatementDetail get(UUID tenantId, UUID uploadId) {
        session.bind(tenantId);
        return detail(uploadId);
    }

    @Transactional(readOnly = true)
    public List<StatementLineView> lines(UUID tenantId, UUID uploadId, String status, int limit) {
        session.bind(tenantId);
        view(uploadId);
        return jdbc.sql("""
                SELECT s.*, COALESCE(a.account_number, pl.loan_number, dl.loan_number) AS matched_reference
                  FROM payments.statement_line s
                  LEFT JOIN payments.provider_payment p ON p.tenant_id = s.tenant_id AND p.payment_id = s.payment_id
                  LEFT JOIN savings.deposit_account a ON a.tenant_id = p.tenant_id AND a.account_id = p.deposit_account_id
                  LEFT JOIN lending.loan pl ON pl.tenant_id = p.tenant_id AND pl.loan_id = p.loan_id
                  LEFT JOIN payments.b2c_disbursement d ON d.tenant_id = s.tenant_id AND d.disbursement_id = s.disbursement_id
                  LEFT JOIN lending.loan dl ON dl.tenant_id = d.tenant_id AND dl.loan_id = d.loan_id
                 WHERE s.upload_id = :id AND (CAST(:status AS varchar) IS NULL OR s.status = :status)
                 ORDER BY s.line_no LIMIT :limit
                """)
            .param("id", uploadId).param("status", status).param("limit", limit)
            .query((rs, n) -> new StatementLineView(rs.getInt("line_no"), rs.getString("receipt_no"),
                    rs.getObject("completed_at", OffsetDateTime.class), rs.getString("details"), rs.getString("txn_status"),
                    m(rs.getBigDecimal("paid_in")), m(rs.getBigDecimal("withdrawn")), m(rs.getBigDecimal("balance")),
                    rs.getString("reason_type"), rs.getString("other_party_masked"), rs.getString("account_ref"),
                    rs.getString("category"), rs.getString("status"), rs.getString("exception_code"),
                    rs.getString("matched_reference"), rs.getObject("payment_id", UUID.class),
                    rs.getObject("disbursement_id", UUID.class), rs.getObject("journal_entry_id", UUID.class),
                    rs.getString("resolution"), rs.getString("resolution_note"), rs.getString("resolved_by")))
            .list();
    }

    private StatementDetail detail(UUID uploadId) {
        StatementUploadView u = view(uploadId);
        StatementCheck c = switch (u.status()) {
            case "OPEN" -> check(uploadId, u.statementDate(), u.clearingAccount(), u.closingBalance());
            case "SIGNED_OFF" -> new StatementCheck(false, u.ledgerBalance(), u.difference(), u.ledgerOnlyCount(),
                    u.ledgerOnlyAmount(), ledgerOnly(uploadId));
            default -> null;
        };
        return new StatementDetail(u, c);
    }

    private StatementCheck check(UUID uploadId, LocalDate date, String clearing, String closing) {
        BigDecimal ledger = jdbc.sql("SELECT payments.clearing_balance(tenancy.current_tenant(), :gl, :d)")
            .param("gl", clearing).param("d", date).query(BigDecimal.class).single();
        record Totals(int count, BigDecimal amount) { }
        Totals t = jdbc.sql("""
                SELECT count(*), COALESCE(sum(net), 0) FROM payments.statement_ledger_only(tenancy.current_tenant(), :id)
                """).param("id", uploadId).query((rs, n) -> new Totals(rs.getInt(1), rs.getBigDecimal(2))).single();
        return new StatementCheck(true, m(ledger), m(new BigDecimal(closing).subtract(ledger)), t.count(), m(t.amount()),
                ledgerOnly(uploadId));
    }

    private List<StatementLedgerItem> ledgerOnly(UUID uploadId) {
        return jdbc.sql("""
                SELECT entry_id, business_date, source, reference, net
                  FROM payments.statement_ledger_only(tenancy.current_tenant(), :id)
                 ORDER BY posted_at, entry_id LIMIT :max
                """).param("id", uploadId).param("max", LEDGER_ITEMS_SHOWN)
            .query((rs, n) -> new StatementLedgerItem(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class),
                    rs.getString(3), rs.getString(4), m(rs.getBigDecimal(5))))
            .list();
    }

    private StatementUploadView lockOpen(UUID uploadId) {
        jdbc.sql("SELECT 1 FROM payments.statement_upload WHERE upload_id = :id FOR UPDATE").param("id", uploadId)
            .query(Integer.class).optional()
            .orElseThrow(() -> new NotFoundException("statement " + uploadId + " not found"));
        StatementUploadView u = view(uploadId);
        if (!"OPEN".equals(u.status())) {
            throw new ConflictException("statement is " + u.status());
        }
        return u;
    }

    private StatementUploadView view(UUID uploadId) {
        return jdbc.sql(VIEW + " WHERE u.upload_id = :id " + GROUP).param("id", uploadId)
            .query(MpesaStatementService::upload).optional()
            .orElseThrow(() -> new NotFoundException("statement " + uploadId + " not found"));
    }

    private static final String VIEW = """
            SELECT u.*, count(*) FILTER (WHERE s.status = 'MATCHED' AND s.active) AS matched,
                   count(*) FILTER (WHERE s.status = 'EXCEPTION' AND s.active) AS exceptions,
                   count(*) FILTER (WHERE s.status = 'RESOLVED' AND s.active) AS resolved,
                   count(*) FILTER (WHERE s.status = 'IGNORED') AS ignored
              FROM payments.statement_upload u
              LEFT JOIN payments.statement_line s ON s.tenant_id = u.tenant_id AND s.upload_id = u.upload_id
            """;

    private static final String GROUP = " GROUP BY u.tenant_id, u.upload_id";

    private static StatementUploadView upload(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new StatementUploadView(rs.getObject("upload_id", UUID.class), rs.getString("account_kind"),
                rs.getString("short_code"), rs.getString("gl_clearing"), rs.getObject("statement_date", LocalDate.class),
                rs.getString("file_name"), rs.getInt("line_count"), m(rs.getBigDecimal("opening_balance")),
                m(rs.getBigDecimal("closing_balance")), m(rs.getBigDecimal("total_paid_in")), m(rs.getBigDecimal("total_withdrawn")),
                rs.getBoolean("balance_chain_ok"), rs.getString("status"), rs.getInt("matched"), rs.getInt("exceptions"),
                rs.getInt("resolved"), rs.getInt("ignored"), rs.getString("uploaded_by"),
                rs.getObject("uploaded_at", OffsetDateTime.class), rs.getString("signed_off_by"),
                rs.getObject("signed_off_at", OffsetDateTime.class), m(rs.getBigDecimal("ledger_balance")),
                m(rs.getBigDecimal("difference")), rs.getObject("ledger_only_count", Integer.class), m(rs.getBigDecimal("ledger_only_amount")),
                rs.getString("note"), rs.getString("withdrawn_by"), rs.getObject("withdrawn_at", OffsetDateTime.class));
    }

    private static String m(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
