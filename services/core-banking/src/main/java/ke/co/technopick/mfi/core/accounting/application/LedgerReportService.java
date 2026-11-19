package ke.co.technopick.mfi.core.accounting.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ke.co.technopick.mfi.core.common.NotFoundException;
import ke.co.technopick.mfi.core.tenancy.persistence.BusinessDates;
import ke.co.technopick.mfi.core.tenancy.persistence.TenantSession;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only views of the general ledger for finance (ADR-0023): chart of accounts, trial balance
 * as of a business date, account activity with opening and running balances, and one entry with
 * all its lines. Everything is computed from journal lines; nothing here can change the ledger.
 * Balances are shown with the account's natural sign: debit-positive for assets and expenses,
 * credit-positive for liabilities, equity and income.
 */
@Service
public class LedgerReportService {

    static final int MAX_DAYS = 366;
    static final int MAX_LINES = 2000;

    public record GlAccount(String accountCode, String name, String accountClass, String currency, boolean postable,
                            String status, boolean subledger) { }

    public record TrialBalanceLine(String accountCode, String name, String accountClass, String currency, String debit,
                                   String credit, String balance) { }

    public record CurrencyTotal(String currency, String debit, String credit, String difference, boolean balanced) { }

    public record TrialBalance(LocalDate asOf, String branchCode, List<TrialBalanceLine> accounts, List<CurrencyTotal> totals) { }

    public record ActivityLine(LocalDate businessDate, OffsetDateTime postedAt, UUID entryId, String source, String reference,
                               String narrative, String branchCode, String depositAccount, String debit, String credit,
                               String balance) { }

    public record AccountActivity(GlAccount account, LocalDate from, LocalDate to, String branchCode, String openingBalance,
                                  String totalDebit, String totalCredit, String closingBalance, List<ActivityLine> lines,
                                  boolean truncated) { }

    public record EntryLine(int lineNo, String accountCode, String accountName, String side, String amount, String currency,
                            String branchCode, String narrative, String depositAccount) { }

    public record EntryView(UUID entryId, LocalDate businessDate, OffsetDateTime postedAt, String source, String reference,
                            String correlationId, String switchReference, UUID reversesEntryId, UUID reversedByEntryId,
                            String actor, List<EntryLine> lines) { }

    private final JdbcClient jdbc;
    private final TenantSession session;
    private final BusinessDates businessDates;

    public LedgerReportService(JdbcClient jdbc, TenantSession session, BusinessDates businessDates) {
        this.jdbc = jdbc;
        this.session = session;
        this.businessDates = businessDates;
    }

    @Transactional(readOnly = true)
    public List<GlAccount> accounts(UUID tenantId) {
        session.bind(tenantId);
        return jdbc.sql("""
                SELECT account_code, name, account_class, currency, postable, status, requires_subledger
                  FROM ledger.gl_account ORDER BY account_code
                """)
            .query((rs, i) -> new GlAccount(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getBoolean(5), rs.getString(6), rs.getBoolean(7)))
            .list();
    }

    @Transactional(readOnly = true)
    public TrialBalance trialBalance(UUID tenantId, LocalDate asOf, String branchCode) {
        session.bind(tenantId);
        LocalDate date = asOf == null ? businessDates.current() : asOf;
        String branch = blankToNull(branchCode);
        List<TrialBalanceLine> lines = jdbc.sql("""
                SELECT a.account_code, a.name, a.account_class, a.currency,
                       sum(CASE l.side WHEN 'D' THEN l.amount ELSE -l.amount END) AS net
                  FROM ledger.journal_line l
                  JOIN ledger.journal_entry e ON e.tenant_id = l.tenant_id AND e.entry_id = l.entry_id
                  JOIN ledger.gl_account a ON a.tenant_id = l.tenant_id AND a.account_code = l.account_code
                 WHERE e.business_date <= :d AND (CAST(:b AS varchar) IS NULL OR l.branch_code = :b)
                 GROUP BY a.account_code, a.name, a.account_class, a.currency
                HAVING sum(CASE l.side WHEN 'D' THEN l.amount ELSE -l.amount END) <> 0
                 ORDER BY a.account_code
                """)
            .param("d", date).param("b", branch)
            .query((rs, i) -> {
                BigDecimal net = rs.getBigDecimal("net");
                String cls = rs.getString("account_class");
                return new TrialBalanceLine(rs.getString("account_code"), rs.getString("name"), cls, rs.getString("currency"),
                        net.signum() > 0 ? plain(net) : "0.00", net.signum() < 0 ? plain(net.negate()) : "0.00",
                        plain(natural(cls, net)));
            })
            .list();
        Map<String, BigDecimal[]> byCurrency = new LinkedHashMap<>();
        for (TrialBalanceLine l : lines) {
            BigDecimal[] t = byCurrency.computeIfAbsent(l.currency(), c -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            t[0] = t[0].add(new BigDecimal(l.debit()));
            t[1] = t[1].add(new BigDecimal(l.credit()));
        }
        List<CurrencyTotal> totals = new ArrayList<>();
        byCurrency.forEach((c, t) -> totals.add(new CurrencyTotal(c, plain(t[0]), plain(t[1]), plain(t[0].subtract(t[1])),
                t[0].compareTo(t[1]) == 0)));
        return new TrialBalance(date, branch, lines, totals);
    }

    @Transactional(readOnly = true)
    public AccountActivity activity(UUID tenantId, String accountCode, LocalDate from, LocalDate to, String branchCode) {
        session.bind(tenantId);
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("give a from and to date, from not after to");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_DAYS) {
            throw new IllegalArgumentException("at most " + MAX_DAYS + " days at a time");
        }
        GlAccount account = jdbc.sql("""
                SELECT account_code, name, account_class, currency, postable, status, requires_subledger
                  FROM ledger.gl_account WHERE account_code = :c
                """)
            .param("c", accountCode)
            .query((rs, i) -> new GlAccount(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getBoolean(5), rs.getString(6), rs.getBoolean(7)))
            .optional().orElseThrow(() -> new NotFoundException("GL account " + accountCode + " not found"));
        String branch = blankToNull(branchCode);
        BigDecimal opening = jdbc.sql("""
                SELECT COALESCE(sum(CASE l.side WHEN 'D' THEN l.amount ELSE -l.amount END), 0)
                  FROM ledger.journal_line l
                  JOIN ledger.journal_entry e ON e.tenant_id = l.tenant_id AND e.entry_id = l.entry_id
                 WHERE l.account_code = :c AND e.business_date < :from AND (CAST(:b AS varchar) IS NULL OR l.branch_code = :b)
                """)
            .param("c", accountCode).param("from", from).param("b", branch).query(BigDecimal.class).single();
        record Raw(LocalDate date, OffsetDateTime postedAt, UUID entryId, String source, String reference, String narrative,
                   String branch, String deposit, String side, BigDecimal amount) { }
        List<Raw> raw = jdbc.sql("""
                SELECT e.business_date, e.posted_at, e.entry_id, e.source, e.idempotency_key, l.narrative, l.branch_code,
                       d.account_number, l.side, l.amount
                  FROM ledger.journal_line l
                  JOIN ledger.journal_entry e ON e.tenant_id = l.tenant_id AND e.entry_id = l.entry_id
                  LEFT JOIN savings.deposit_account d ON d.tenant_id = l.tenant_id AND d.account_id = l.subledger_account_id
                 WHERE l.account_code = :c AND e.business_date BETWEEN :from AND :to
                   AND (CAST(:b AS varchar) IS NULL OR l.branch_code = :b)
                 ORDER BY e.business_date, e.posted_at, e.entry_id, l.line_no
                 LIMIT :limit
                """)
            .param("c", accountCode).param("from", from).param("to", to).param("b", branch).param("limit", MAX_LINES + 1)
            .query((rs, i) -> new Raw(rs.getObject(1, LocalDate.class), rs.getObject(2, OffsetDateTime.class),
                    rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                    rs.getString(8), rs.getString(9), rs.getBigDecimal(10)))
            .list();
        boolean truncated = raw.size() > MAX_LINES;
        if (truncated) {
            raw = raw.subList(0, MAX_LINES);
        }
        BigDecimal running = opening;
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        List<ActivityLine> lines = new ArrayList<>();
        for (Raw r : raw) {
            boolean isDebit = "D".equals(r.side());
            running = isDebit ? running.add(r.amount()) : running.subtract(r.amount());
            debit = isDebit ? debit.add(r.amount()) : debit;
            credit = isDebit ? credit : credit.add(r.amount());
            lines.add(new ActivityLine(r.date(), r.postedAt(), r.entryId(), r.source(), r.reference(), r.narrative(), r.branch(),
                    r.deposit(), isDebit ? plain(r.amount()) : null, isDebit ? null : plain(r.amount()),
                    plain(natural(account.accountClass(), running))));
        }
        return new AccountActivity(account, from, to, branch, plain(natural(account.accountClass(), opening)), plain(debit),
                plain(credit), plain(natural(account.accountClass(), running)), lines, truncated);
    }

    @Transactional(readOnly = true)
    public EntryView entry(UUID tenantId, UUID entryId) {
        session.bind(tenantId);
        record H(LocalDate date, OffsetDateTime postedAt, String source, String reference, String correlation, String switchRef,
                 UUID reverses, String actor) { }
        H h = jdbc.sql("""
                SELECT business_date, posted_at, source, idempotency_key, correlation_id, switch_reference, reverses_entry_id, actor
                  FROM ledger.journal_entry WHERE entry_id = :id
                """)
            .param("id", entryId)
            .query((rs, i) -> new H(rs.getObject(1, LocalDate.class), rs.getObject(2, OffsetDateTime.class), rs.getString(3),
                    rs.getString(4), rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class), rs.getString(8)))
            .optional().orElseThrow(() -> new NotFoundException("journal entry not found"));
        UUID reversedBy = jdbc.sql("SELECT entry_id FROM ledger.journal_entry WHERE reverses_entry_id = :id")
            .param("id", entryId).query(UUID.class).optional().orElse(null);
        List<EntryLine> lines = jdbc.sql("""
                SELECT l.line_no, l.account_code, a.name, l.side, l.amount, l.currency, l.branch_code, l.narrative, d.account_number
                  FROM ledger.journal_line l
                  JOIN ledger.gl_account a ON a.tenant_id = l.tenant_id AND a.account_code = l.account_code
                  LEFT JOIN savings.deposit_account d ON d.tenant_id = l.tenant_id AND d.account_id = l.subledger_account_id
                 WHERE l.entry_id = :id ORDER BY l.line_no
                """)
            .param("id", entryId)
            .query((rs, i) -> new EntryLine(rs.getInt(1), rs.getString(2), rs.getString(3), "D".equals(rs.getString(4)) ? "DEBIT"
                    : "CREDIT", plain(rs.getBigDecimal(5)), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9)))
            .list();
        return new EntryView(entryId, h.date(), h.postedAt(), h.source(), h.reference(), h.correlation(), h.switchRef(),
                h.reverses(), reversedBy, h.actor(), lines);
    }

    /** Debit-positive balance turned into the account's natural sign. */
    static BigDecimal natural(String accountClass, BigDecimal debitPositive) {
        return "ASSET".equals(accountClass) || "EXPENSE".equals(accountClass) ? debitPositive : debitPositive.negate();
    }

    private static String plain(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
