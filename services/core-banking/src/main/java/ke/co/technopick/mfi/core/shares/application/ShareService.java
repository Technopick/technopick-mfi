package ke.co.technopick.mfi.core.shares.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * Member share capital (ADR-0040): settings, the share register, purchases paid from the member's
 * own savings, transfers between members with maker-checker, and year-end dividend runs with
 * maker-checker. The database re-checks every rule (V37); this service gives clear answers first.
 */
@Service
public class ShareService {

    public record ShareSettingsView(String parValue, String minimumHolding, String shareCapitalAccount, String dividendSourceAccount,
                                    String dividendsPayableAccount, String dividendTaxAccount, String dividendTaxRate,
                                    String updatedBy, OffsetDateTime updatedAt, boolean sharesIssued) { }

    public record ShareTransactionView(UUID txnId, String kind, String amount, long shares, LocalDate businessDate,
                                       String accountNumber, UUID transferId, UUID entryId, String createdBy,
                                       OffsetDateTime createdAt) { }

    public record MemberShares(String customerNumber, String name, String holding, long shares, String minimumHolding,
                               List<ShareTransactionView> transactions) { }

    public record ShareholderRow(String customerNumber, String name, String branchCode, String holding, long shares) { }

    public record ShareRegister(String parValue, String totalHolding, long totalShares, int members, List<ShareholderRow> holders) { }

    public record ShareTransferView(UUID transferId, String fromCustomerNumber, String fromName, String toCustomerNumber,
                                    String toName, String amount, long shares, String reason, String status, String requestedBy,
                                    OffsetDateTime requestedAt, String decidedBy, OffsetDateTime decidedAt, String decisionReason,
                                    LocalDate businessDate, String sellerHolding) { }

    public record DividendLineView(String customerNumber, String name, String sharesAmount, String gross, String tax, String net,
                                   String paidTo) { }

    public record DividendRunView(UUID runId, int financialYear, LocalDate recordDate, String ratePct, String taxRate, int members,
                                  String sharesTotal, String grossTotal, String taxTotal, String netTotal, String toSavingsTotal,
                                  String toPayableTotal, String status, String requestedBy, OffsetDateTime requestedAt,
                                  String decidedBy, OffsetDateTime decidedAt, String decisionReason, LocalDate businessDate,
                                  UUID entryId) { }

    public record DividendPreview(int financialYear, LocalDate recordDate, String ratePct, String taxRate, int members,
                                  String sharesTotal, String grossTotal, String taxTotal, String netTotal, String toSavingsTotal,
                                  String toPayableTotal, List<DividendLineView> lines) { }

    private record Settings(BigDecimal par, BigDecimal minimum, String capital, String source, String payable, String tax,
                            BigDecimal taxRate, String currency) { }

    private record Line(UUID customerId, String customerNumber, String name, BigDecimal shares, BigDecimal gross, BigDecimal tax,
                        BigDecimal net, UUID accountId, String accountNumber, String control, String branch) { }

    private final JdbcClient jdbc;
    private final TenantSession session;
    private final BusinessDates businessDates;
    private final JournalPosting journal;

    public ShareService(JdbcClient jdbc, TenantSession session, BusinessDates businessDates, JournalPosting journal) {
        this.jdbc = jdbc;
        this.session = session;
        this.businessDates = businessDates;
        this.journal = journal;
    }

    // ------------------------------------------------------------------ settings

    @Transactional(readOnly = true)
    public Optional<ShareSettingsView> settings(UUID tenantId) {
        session.bind(tenantId);
        return settingsView();
    }

    @Transactional
    public ShareSettingsView saveSettings(UUID tenantId, BigDecimal parValue, BigDecimal minimumHolding, String shareCapital,
                                         String dividendSource, String dividendsPayable, String dividendTax, BigDecimal taxRate,
                                         String actor) {
        session.bind(tenantId);
        if (minimumHolding.remainder(parValue).signum() != 0) {
            throw new IllegalArgumentException("the minimum holding must be a whole number of shares of " + m(parValue));
        }
        jdbc.sql("""
                INSERT INTO shares.share_settings (tenant_id, par_value, minimum_holding, gl_share_capital, gl_dividend_source,
                    gl_dividends_payable, gl_dividend_tax, dividend_tax_rate, updated_by)
                VALUES (tenancy.current_tenant(), :par, :min, :cap, :src, :pay, :tax, :rate, :actor)
                ON CONFLICT (tenant_id) DO UPDATE
                   SET par_value = EXCLUDED.par_value, minimum_holding = EXCLUDED.minimum_holding,
                       gl_share_capital = EXCLUDED.gl_share_capital, gl_dividend_source = EXCLUDED.gl_dividend_source,
                       gl_dividends_payable = EXCLUDED.gl_dividends_payable, gl_dividend_tax = EXCLUDED.gl_dividend_tax,
                       dividend_tax_rate = EXCLUDED.dividend_tax_rate, updated_by = EXCLUDED.updated_by, updated_at = clock_timestamp()
                """)
            .param("par", parValue).param("min", minimumHolding).param("cap", shareCapital).param("src", dividendSource)
            .param("pay", dividendsPayable).param("tax", dividendTax).param("rate", taxRate).param("actor", actor).update();
        return settingsView().orElseThrow();
    }

    private Optional<ShareSettingsView> settingsView() {
        return jdbc.sql("""
                SELECT s.*, EXISTS (SELECT 1 FROM shares.share_transaction) AS issued FROM shares.share_settings s
                """)
            .query((rs, n) -> new ShareSettingsView(m(rs.getBigDecimal("par_value")), m(rs.getBigDecimal("minimum_holding")),
                    rs.getString("gl_share_capital"), rs.getString("gl_dividend_source"), rs.getString("gl_dividends_payable"),
                    rs.getString("gl_dividend_tax"), rs.getBigDecimal("dividend_tax_rate").setScale(2, RoundingMode.HALF_UP).toPlainString(),
                    rs.getString("updated_by"), rs.getObject("updated_at", OffsetDateTime.class), rs.getBoolean("issued")))
            .optional();
    }

    private Settings requireSettings() {
        return jdbc.sql("""
                SELECT s.par_value, s.minimum_holding, s.gl_share_capital, s.gl_dividend_source, s.gl_dividends_payable,
                       s.gl_dividend_tax, s.dividend_tax_rate, g.currency
                  FROM shares.share_settings s
                  JOIN ledger.gl_account g ON g.tenant_id = s.tenant_id AND g.account_code = s.gl_share_capital
                """)
            .query((rs, n) -> new Settings(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getString(6), rs.getBigDecimal(7), rs.getString(8)))
            .optional().orElseThrow(() -> new ConflictException("share capital is not set up; finance must save the share settings"));
    }

    // ------------------------------------------------------------------ register

    @Transactional(readOnly = true)
    public MemberShares member(UUID tenantId, String customerNumber) {
        session.bind(tenantId);
        Settings s = requireSettings();
        Member c = customer(customerNumber);
        BigDecimal holding = holding(c.id());
        List<ShareTransactionView> txns = jdbc.sql("""
                SELECT t.txn_id, t.kind, t.amount, t.business_date, a.account_number, t.transfer_id, t.entry_id, t.created_by,
                       t.created_at
                  FROM shares.share_transaction t
                  LEFT JOIN savings.deposit_account a ON a.tenant_id = t.tenant_id AND a.account_id = t.source_account_id
                 WHERE t.customer_id = :c ORDER BY t.created_at DESC LIMIT 200
                """).param("c", c.id())
            .query((rs, n) -> new ShareTransactionView(rs.getObject(1, UUID.class), rs.getString(2), m(rs.getBigDecimal(3)),
                    count(rs.getBigDecimal(3), s.par()), rs.getObject(4, LocalDate.class), rs.getString(5),
                    rs.getObject(6, UUID.class), rs.getObject(7, UUID.class), rs.getString(8),
                    rs.getObject(9, OffsetDateTime.class)))
            .list();
        return new MemberShares(customerNumber, c.name(), m(holding), count(holding, s.par()), m(s.minimum()), txns);
    }

    @Transactional(readOnly = true)
    public ShareRegister register(UUID tenantId) {
        session.bind(tenantId);
        Settings s = requireSettings();
        List<ShareholderRow> rows = jdbc.sql("""
                SELECT c.customer_number, c.first_name || ' ' || c.last_name, c.branch_code,
                       sum(CASE t.kind WHEN 'TRANSFER_OUT' THEN -t.amount ELSE t.amount END) AS holding
                  FROM shares.share_transaction t
                  JOIN customer.customer c ON c.tenant_id = t.tenant_id AND c.customer_id = t.customer_id
                 GROUP BY c.customer_number, c.first_name, c.last_name, c.branch_code
                HAVING sum(CASE t.kind WHEN 'TRANSFER_OUT' THEN -t.amount ELSE t.amount END) > 0
                 ORDER BY c.customer_number
                """)
            .query((rs, n) -> new ShareholderRow(rs.getString(1), rs.getString(2), rs.getString(3), m(rs.getBigDecimal(4)),
                    count(rs.getBigDecimal(4), s.par())))
            .list();
        BigDecimal total = rows.stream().map(r -> new BigDecimal(r.holding())).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ShareRegister(m(s.par()), m(total), count(total, s.par()), rows.size(), rows);
    }

    // ------------------------------------------------------------------ purchase

    /** Buys whole shares for a member, paid from one of their own savings accounts, on the business date. */
    @Transactional
    public MemberShares purchase(UUID tenantId, String idempotencyKey, String customerNumber, String accountNumber, BigDecimal amount,
                                 String actor) {
        session.bind(tenantId);
        Settings s = requireSettings();
        Optional<String> replay = jdbc.sql("""
                SELECT c.customer_number FROM shares.share_transaction t
                  JOIN customer.customer c ON c.tenant_id = t.tenant_id AND c.customer_id = t.customer_id
                 WHERE t.idempotency_key = :k
                """).param("k", "purchase:" + idempotencyKey).query(String.class).optional();
        if (replay.isPresent()) {
            return member(tenantId, replay.get());
        }
        if (amount.signum() <= 0 || amount.remainder(s.par()).signum() != 0) {
            throw new IllegalArgumentException("shares are bought in whole shares of " + m(s.par()));
        }
        record A(UUID id, UUID owner, String control, String branch, String status, String customerStatus) { }
        A a = jdbc.sql("""
                SELECT a.account_id, a.customer_id, a.gl_control_account, a.branch_code, a.status, c.status
                  FROM savings.deposit_account a
                  LEFT JOIN customer.customer c ON c.tenant_id = a.tenant_id AND c.customer_id = a.customer_id
                 WHERE a.account_number = :n FOR UPDATE OF a
                """).param("n", accountNumber)
            .query((rs, n) -> new A(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getString(6)))
            .optional().orElseThrow(() -> new NotFoundException("savings account " + accountNumber + " not found"));
        UUID customerId = customer(customerNumber).id();
        if (!customerId.equals(a.owner())) {
            throw new IllegalArgumentException("account " + accountNumber + " does not belong to " + customerNumber);
        }
        if (!"ACTIVE".equals(a.customerStatus())) {
            throw new ConflictException("only active members buy shares");
        }
        if (!"ACTIVE".equals(a.status())) {
            throw new ConflictException("account " + accountNumber + " is " + a.status());
        }
        BigDecimal after = holding(customerId).add(amount);
        if (after.compareTo(s.minimum()) < 0) {
            throw new ConflictException("a member holds at least " + m(s.minimum()) + " in shares; this purchase would leave "
                    + customerNumber + " with " + m(after));
        }
        BigDecimal available = jdbc.sql("SELECT available_balance FROM savings.account_balance WHERE account_id = :id")
            .param("id", a.id()).query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
        if (available.compareTo(amount) < 0) {
            throw new ConflictException("account " + accountNumber + " has " + m(available) + " available; " + m(amount) + " needed");
        }
        Money money = money(amount, s.currency());
        LocalDate today = businessDates.current();
        String key = "shares:purchase:" + idempotencyKey;
        UUID entryId = UUID.nameUUIDFromBytes((tenantId + "|" + key).getBytes(StandardCharsets.UTF_8));
        journal.post(new JournalEntry(entryId, tenantId, key, today, "SHARES", "shares:" + customerNumber, null, null, List.of(
                JournalLine.onAccount(a.control(), Side.DEBIT, money, a.branch(), a.id()),
                JournalLine.credit(s.capital(), money, a.branch()))), actor);
        jdbc.sql("""
                INSERT INTO shares.share_transaction (tenant_id, txn_id, idempotency_key, customer_id, kind, amount, business_date,
                    entry_id, source_account_id, created_by)
                VALUES (tenancy.current_tenant(), :id, :k, :c, 'PURCHASE', :amount, :d, :e, :a, :actor)
                """)
            .param("id", UUID.nameUUIDFromBytes((tenantId + "|share-txn|" + key).getBytes(StandardCharsets.UTF_8)))
            .param("k", "purchase:" + idempotencyKey).param("c", customerId).param("amount", amount).param("d", today)
            .param("e", entryId).param("a", a.id()).param("actor", actor).update();
        return member(tenantId, customerNumber);
    }

    // ------------------------------------------------------------------ transfers

    @Transactional
    public ShareTransferView requestTransfer(UUID tenantId, String idempotencyKey, String fromCustomerNumber, String toCustomerNumber,
                                             BigDecimal amount, String reason, String actor) {
        session.bind(tenantId);
        Settings s = requireSettings();
        Optional<UUID> replay = jdbc.sql("SELECT transfer_id FROM shares.share_transfer WHERE idempotency_key = :k")
            .param("k", idempotencyKey).query(UUID.class).optional();
        if (replay.isPresent()) {
            return transfer(replay.get());
        }
        if (amount.signum() <= 0 || amount.remainder(s.par()).signum() != 0) {
            throw new IllegalArgumentException("shares are transferred in whole shares of " + m(s.par()));
        }
        UUID from = customer(fromCustomerNumber).id();
        UUID to = customer(toCustomerNumber).id();
        if (from.equals(to)) {
            throw new IllegalArgumentException("a member cannot transfer shares to themselves");
        }
        BigDecimal left = holding(from).subtract(amount);
        if (left.compareTo(s.minimum()) < 0) {
            throw new ConflictException(fromCustomerNumber + " holds " + m(holding(from)) + "; after the transfer they would keep "
                    + m(left) + ", below the minimum holding of " + m(s.minimum()));
        }
        UUID id = UUID.nameUUIDFromBytes((tenantId + "|share-transfer|" + idempotencyKey).getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO shares.share_transfer (tenant_id, transfer_id, idempotency_key, from_customer_id, to_customer_id, amount,
                    reason, requested_by)
                VALUES (tenancy.current_tenant(), :id, :k, :f, :t, :amount, :reason, :actor)
                """).param("id", id).param("k", idempotencyKey).param("f", from).param("t", to).param("amount", amount)
            .param("reason", reason.strip()).param("actor", actor).update();
        return transfer(id);
    }

    @Transactional
    public ShareTransferView decideTransfer(UUID tenantId, UUID transferId, boolean approve, String reason, String actor) {
        session.bind(tenantId);
        Settings s = requireSettings();
        record T(UUID from, UUID to, BigDecimal amount, String status, String requestedBy) { }
        T t = jdbc.sql("""
                SELECT from_customer_id, to_customer_id, amount, status, requested_by FROM shares.share_transfer
                 WHERE transfer_id = :id FOR UPDATE
                """).param("id", transferId)
            .query((rs, n) -> new T(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getBigDecimal(3), rs.getString(4),
                    rs.getString(5)))
            .optional().orElseThrow(() -> new NotFoundException("share transfer not found"));
        if (!"REQUESTED".equals(t.status())) {
            throw new ConflictException("transfer is already " + t.status());
        }
        if (t.requestedBy().equals(actor)) {
            throw new AccessDeniedException("the person who asked for the transfer cannot decide it");
        }
        String why = reason == null || reason.isBlank() ? null : reason.strip();
        if (!approve) {
            if (why == null || why.length() < 3) {
                throw new IllegalArgumentException("a rejection needs a reason");
            }
            jdbc.sql("""
                    UPDATE shares.share_transfer SET status = 'REJECTED', decided_by = :a, decided_at = clock_timestamp(),
                           decision_reason = :r WHERE transfer_id = :id
                    """).param("a", actor).param("r", why).param("id", transferId).update();
            return transfer(transferId);
        }
        // lock both members' registers in a fixed order so two transfers cannot both pass the minimum check
        for (UUID c : t.from().compareTo(t.to()) < 0 ? List.of(t.from(), t.to()) : List.of(t.to(), t.from())) {
            jdbc.sql("SELECT 1 FROM customer.customer WHERE customer_id = :c FOR UPDATE").param("c", c).query(Integer.class).single();
        }
        BigDecimal left = holding(t.from()).subtract(t.amount());
        if (left.compareTo(s.minimum()) < 0) {
            throw new ConflictException("the seller would now keep " + m(left) + ", below the minimum holding of " + m(s.minimum()));
        }
        LocalDate today = businessDates.current();
        jdbc.sql("""
                UPDATE shares.share_transfer SET status = 'APPROVED', decided_by = :a, decided_at = clock_timestamp(),
                       decision_reason = :r, business_date = :d WHERE transfer_id = :id
                """).param("a", actor).param("r", why).param("d", today).param("id", transferId).update();
        for (String kind : List.of("TRANSFER_OUT", "TRANSFER_IN")) {
            jdbc.sql("""
                    INSERT INTO shares.share_transaction (tenant_id, txn_id, idempotency_key, customer_id, kind, amount, business_date,
                        transfer_id, created_by)
                    VALUES (tenancy.current_tenant(), :id, :k, :c, :kind, :amount, :d, :t, :actor)
                    """)
                .param("id", UUID.nameUUIDFromBytes((tenantId + "|" + transferId + "|" + kind).getBytes(StandardCharsets.UTF_8)))
                .param("k", "transfer:" + transferId + ":" + kind).param("c", "TRANSFER_OUT".equals(kind) ? t.from() : t.to())
                .param("kind", kind).param("amount", t.amount()).param("d", today).param("t", transferId).param("actor", actor)
                .update();
        }
        return transfer(transferId);
    }

    @Transactional(readOnly = true)
    public List<ShareTransferView> transfers(UUID tenantId, String status) {
        session.bind(tenantId);
        return jdbc.sql(TRANSFER + " WHERE (CAST(:s AS text) IS NULL OR t.status = :s) ORDER BY t.requested_at DESC LIMIT 200")
            .param("s", status).query((rs, n) -> transferRow(rs)).list();
    }

    private ShareTransferView transfer(UUID id) {
        return jdbc.sql(TRANSFER + " WHERE t.transfer_id = :id").param("id", id).query((rs, n) -> transferRow(rs)).single();
    }

    private static final String TRANSFER = """
            SELECT t.transfer_id, f.customer_number, f.first_name || ' ' || f.last_name, o.customer_number,
                   o.first_name || ' ' || o.last_name, t.amount, t.reason, t.status, t.requested_by, t.requested_at, t.decided_by,
                   t.decided_at, t.decision_reason, t.business_date, shares.holding(t.tenant_id, t.from_customer_id, NULL),
                   (SELECT par_value FROM shares.share_settings)
              FROM shares.share_transfer t
              JOIN customer.customer f ON f.tenant_id = t.tenant_id AND f.customer_id = t.from_customer_id
              JOIN customer.customer o ON o.tenant_id = t.tenant_id AND o.customer_id = t.to_customer_id
            """;

    private static ShareTransferView transferRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ShareTransferView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                m(rs.getBigDecimal(6)), count(rs.getBigDecimal(6), rs.getBigDecimal(16)), rs.getString(7), rs.getString(8),
                rs.getString(9), rs.getObject(10, OffsetDateTime.class), rs.getString(11), rs.getObject(12, OffsetDateTime.class),
                rs.getString(13), rs.getObject(14, LocalDate.class), m(rs.getBigDecimal(15)));
    }

    // ------------------------------------------------------------------ dividends

    @Transactional(readOnly = true)
    public DividendPreview previewDividend(UUID tenantId, int financialYear, LocalDate recordDate, BigDecimal ratePct) {
        session.bind(tenantId);
        Settings s = requireSettings();
        checkDividendInputs(financialYear, recordDate, ratePct);
        List<Line> lines = computeLines(recordDate, ratePct, s.taxRate());
        Totals t = totals(lines);
        return new DividendPreview(financialYear, recordDate, pct(ratePct), pct(s.taxRate()), lines.size(), m(t.shares()), m(t.gross()),
                m(t.tax()), m(t.net()), m(t.savings()), m(t.payable()), lines.stream().map(ShareService::lineView).toList());
    }

    @Transactional
    public DividendRunView requestDividend(UUID tenantId, String idempotencyKey, int financialYear, LocalDate recordDate,
                                           BigDecimal ratePct, String actor) {
        session.bind(tenantId);
        Settings s = requireSettings();
        Optional<UUID> replay = jdbc.sql("SELECT run_id FROM shares.dividend_run WHERE idempotency_key = :k")
            .param("k", idempotencyKey).query(UUID.class).optional();
        if (replay.isPresent()) {
            return run(replay.get());
        }
        checkDividendInputs(financialYear, recordDate, ratePct);
        boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM shares.dividend_run WHERE financial_year = :y AND status <> 'REJECTED')")
            .param("y", financialYear).query(Boolean.class).single();
        if (exists) {
            throw new ConflictException("a dividend run for " + financialYear + " already exists");
        }
        boolean closed = jdbc.sql("SELECT EXISTS (SELECT 1 FROM ledger.eod_run WHERE business_date = :d AND status = 'COMPLETED')")
            .param("d", recordDate).query(Boolean.class).single();
        if (!closed) {
            throw new ConflictException("the record date " + recordDate + " has not been closed by end of day");
        }
        List<Line> lines = computeLines(recordDate, ratePct, s.taxRate());
        if (lines.isEmpty()) {
            throw new ConflictException("no member held shares on " + recordDate);
        }
        Totals t = totals(lines);
        UUID id = UUID.nameUUIDFromBytes((tenantId + "|dividend|" + idempotencyKey).getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO shares.dividend_run (tenant_id, run_id, idempotency_key, financial_year, record_date, rate_pct, tax_rate,
                    gl_dividend_source, gl_dividends_payable, gl_dividend_tax, members, shares_total, gross_total, tax_total,
                    net_total, to_savings_total, to_payable_total, requested_by)
                VALUES (tenancy.current_tenant(), :id, :k, :y, :rd, :rate, :tax, :src, :pay, :taxgl, :members, :shares, :gross,
                        :taxtotal, :net, :savings, :payable, :actor)
                """)
            .param("id", id).param("k", idempotencyKey).param("y", financialYear).param("rd", recordDate).param("rate", ratePct)
            .param("tax", s.taxRate()).param("src", s.source()).param("pay", s.payable()).param("taxgl", s.tax())
            .param("members", lines.size()).param("shares", t.shares()).param("gross", t.gross()).param("taxtotal", t.tax())
            .param("net", t.net()).param("savings", t.savings()).param("payable", t.payable()).param("actor", actor).update();
        for (Line l : lines) {
            jdbc.sql("""
                    INSERT INTO shares.dividend_line (tenant_id, run_id, customer_id, shares_amount, gross, tax, net, account_id,
                        branch_code)
                    VALUES (tenancy.current_tenant(), :run, :c, :shares, :gross, :tax, :net, :a, :b)
                    """)
                .param("run", id).param("c", l.customerId()).param("shares", l.shares()).param("gross", l.gross())
                .param("tax", l.tax()).param("net", l.net()).param("a", l.accountId()).param("b", l.branch()).update();
        }
        return run(id);
    }

    /** Posts an approved run: the source pays gross; tax and payable are credited; each member's net goes to their savings. */
    @Transactional
    public DividendRunView decideDividend(UUID tenantId, UUID runId, boolean approve, String reason, String actor) {
        session.bind(tenantId);
        Settings s = requireSettings();
        DividendRunView r = jdbc.sql(RUN + " WHERE run_id = :id FOR UPDATE").param("id", runId).query((rs, n) -> runRow(rs))
            .optional().orElseThrow(() -> new NotFoundException("dividend run not found"));
        if (!"REQUESTED".equals(r.status())) {
            throw new ConflictException("run is already " + r.status());
        }
        if (r.requestedBy().equals(actor)) {
            throw new AccessDeniedException("the person who asked for the dividend run cannot decide it");
        }
        String why = reason == null || reason.isBlank() ? null : reason.strip();
        if (!approve) {
            if (why == null || why.length() < 3) {
                throw new IllegalArgumentException("a rejection needs a reason");
            }
            jdbc.sql("""
                    UPDATE shares.dividend_run SET status = 'REJECTED', decided_by = :a, decided_at = clock_timestamp(),
                           decision_reason = :r WHERE run_id = :id
                    """).param("a", actor).param("r", why).param("id", runId).update();
            return run(runId);
        }
        List<Line> lines = jdbc.sql("""
                SELECT d.customer_id, c.customer_number, c.first_name || ' ' || c.last_name, d.shares_amount, d.gross, d.tax, d.net,
                       d.account_id, a.account_number, a.gl_control_account, d.branch_code
                  FROM shares.dividend_line d
                  JOIN customer.customer c ON c.tenant_id = d.tenant_id AND c.customer_id = d.customer_id
                  LEFT JOIN savings.deposit_account a ON a.tenant_id = d.tenant_id AND a.account_id = d.account_id
                 WHERE d.run_id = :id ORDER BY c.customer_number
                """).param("id", runId).query((rs, n) -> line(rs)).list();
        // the accounts frozen on the run when it was asked for
        record G(String source, String payable, String tax) { }
        G g = jdbc.sql("SELECT gl_dividend_source, gl_dividends_payable, gl_dividend_tax FROM shares.dividend_run WHERE run_id = :id")
            .param("id", runId).query((rs, n) -> new G(rs.getString(1), rs.getString(2), rs.getString(3))).single();
        String source = g.source();
        String payable = g.payable();
        String tax = g.tax();
        Map<String, BigDecimal[]> byBranch = new LinkedHashMap<>();
        List<JournalLine> entry = new ArrayList<>();
        for (Line l : lines) {
            BigDecimal[] b = byBranch.computeIfAbsent(l.branch(), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            b[0] = b[0].add(l.gross());
            b[1] = b[1].add(l.tax());
            if (l.accountId() == null) {
                b[2] = b[2].add(l.net());
            } else if (l.net().signum() > 0) {
                entry.add(JournalLine.onAccount(l.control(), Side.CREDIT, money(l.net(), s.currency()), l.branch(), l.accountId()));
            }
        }
        for (Map.Entry<String, BigDecimal[]> e : byBranch.entrySet()) {
            BigDecimal[] b = e.getValue();
            if (b[0].signum() > 0) {
                entry.add(JournalLine.debit(source, money(b[0], s.currency()), e.getKey()));
            }
            if (b[1].signum() > 0) {
                entry.add(JournalLine.credit(tax, money(b[1], s.currency()), e.getKey()));
            }
            if (b[2].signum() > 0) {
                entry.add(JournalLine.credit(payable, money(b[2], s.currency()), e.getKey()));
            }
        }
        if (entry.size() < 2) {
            throw new ConflictException("the run pays nothing; reject it instead");
        }
        LocalDate today = businessDates.current();
        String key = "shares:dividend:" + runId;
        UUID entryId = UUID.nameUUIDFromBytes((tenantId + "|" + key).getBytes(StandardCharsets.UTF_8));
        journal.post(new JournalEntry(entryId, tenantId, key, today, "SHARES", "dividend:" + r.financialYear(), null, null, entry), actor);
        jdbc.sql("""
                UPDATE shares.dividend_run SET status = 'POSTED', decided_by = :a, decided_at = clock_timestamp(), decision_reason = :r,
                       business_date = :d, entry_id = :e WHERE run_id = :id
                """).param("a", actor).param("r", why).param("d", today).param("e", entryId).param("id", runId).update();
        return run(runId);
    }

    @Transactional(readOnly = true)
    public List<DividendRunView> dividendRuns(UUID tenantId) {
        session.bind(tenantId);
        return jdbc.sql(RUN + " ORDER BY financial_year DESC, requested_at DESC LIMIT 50").query((rs, n) -> runRow(rs)).list();
    }

    @Transactional(readOnly = true)
    public List<DividendLineView> dividendLines(UUID tenantId, UUID runId) {
        session.bind(tenantId);
        run(runId);
        return jdbc.sql("""
                SELECT d.customer_id, c.customer_number, c.first_name || ' ' || c.last_name, d.shares_amount, d.gross, d.tax, d.net,
                       d.account_id, a.account_number, a.gl_control_account, d.branch_code
                  FROM shares.dividend_line d
                  JOIN customer.customer c ON c.tenant_id = d.tenant_id AND c.customer_id = d.customer_id
                  LEFT JOIN savings.deposit_account a ON a.tenant_id = d.tenant_id AND a.account_id = d.account_id
                 WHERE d.run_id = :id ORDER BY c.customer_number
                """).param("id", runId).query((rs, n) -> lineView(line(rs))).list();
    }

    private record Totals(BigDecimal shares, BigDecimal gross, BigDecimal tax, BigDecimal net, BigDecimal savings, BigDecimal payable) { }

    private static Totals totals(List<Line> lines) {
        BigDecimal shares = BigDecimal.ZERO;
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        BigDecimal savings = BigDecimal.ZERO;
        BigDecimal payable = BigDecimal.ZERO;
        for (Line l : lines) {
            shares = shares.add(l.shares());
            gross = gross.add(l.gross());
            tax = tax.add(l.tax());
            if (l.accountId() == null) {
                payable = payable.add(l.net());
            } else {
                savings = savings.add(l.net());
            }
        }
        return new Totals(shares, gross, tax, gross.subtract(tax), savings, payable);
    }

    /** Every member holding shares at the record date, paid to their oldest active savings account if they have one. */
    private List<Line> computeLines(LocalDate recordDate, BigDecimal ratePct, BigDecimal taxRate) {
        return jdbc.sql("""
                WITH h AS (
                    SELECT customer_id, sum(CASE kind WHEN 'TRANSFER_OUT' THEN -amount ELSE amount END) AS shares
                      FROM shares.share_transaction WHERE business_date <= :rd
                     GROUP BY customer_id HAVING sum(CASE kind WHEN 'TRANSFER_OUT' THEN -amount ELSE amount END) > 0),
                acct AS (
                    SELECT DISTINCT ON (customer_id) customer_id, account_id, account_number, gl_control_account, branch_code
                      FROM savings.deposit_account WHERE status = 'ACTIVE' AND customer_id IS NOT NULL
                     ORDER BY customer_id, opened_on, account_number),
                g AS (
                    SELECT h.customer_id, h.shares, round(h.shares * :rate / 100, 2) AS gross FROM h)
                SELECT g.customer_id, c.customer_number, c.first_name || ' ' || c.last_name, g.shares, g.gross,
                       round(g.gross * :tax / 100, 2) AS tax, g.gross - round(g.gross * :tax / 100, 2) AS net,
                       a.account_id, a.account_number, a.gl_control_account, COALESCE(a.branch_code, c.branch_code)
                  FROM g
                  JOIN customer.customer c ON c.tenant_id = tenancy.current_tenant() AND c.customer_id = g.customer_id
                  LEFT JOIN acct a ON a.customer_id = g.customer_id
                 ORDER BY c.customer_number
                """).param("rd", recordDate).param("rate", ratePct).param("tax", taxRate).query((rs, n) -> line(rs)).list();
    }

    private void checkDividendInputs(int financialYear, LocalDate recordDate, BigDecimal ratePct) {
        if (ratePct.signum() <= 0 || ratePct.compareTo(new BigDecimal("100")) > 0 || ratePct.scale() > 4) {
            throw new IllegalArgumentException("the dividend rate is a percentage above 0 and at most 100, with up to 4 decimals");
        }
        if (recordDate.getYear() < financialYear) {
            throw new IllegalArgumentException("the record date must be in or after the financial year " + financialYear);
        }
        if (recordDate.isAfter(businessDates.current())) {
            throw new IllegalArgumentException("the record date cannot be after the business date");
        }
    }

    private static Line line(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Line(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getBigDecimal(4), rs.getBigDecimal(5),
                rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getObject(8, UUID.class), rs.getString(9), rs.getString(10),
                rs.getString(11));
    }

    private static DividendLineView lineView(Line l) {
        return new DividendLineView(l.customerNumber(), l.name(), m(l.shares()), m(l.gross()), m(l.tax()), m(l.net()),
                l.accountNumber() == null ? "Dividends payable (no savings account)" : l.accountNumber());
    }

    private DividendRunView run(UUID runId) {
        return jdbc.sql(RUN + " WHERE run_id = :id").param("id", runId).query((rs, n) -> runRow(rs)).optional()
            .orElseThrow(() -> new NotFoundException("dividend run not found"));
    }

    private static final String RUN = "SELECT * FROM shares.dividend_run";

    private static DividendRunView runRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DividendRunView(rs.getObject("run_id", UUID.class), rs.getInt("financial_year"),
                rs.getObject("record_date", LocalDate.class), pct(rs.getBigDecimal("rate_pct")), pct(rs.getBigDecimal("tax_rate")),
                rs.getInt("members"), m(rs.getBigDecimal("shares_total")), m(rs.getBigDecimal("gross_total")),
                m(rs.getBigDecimal("tax_total")), m(rs.getBigDecimal("net_total")), m(rs.getBigDecimal("to_savings_total")),
                m(rs.getBigDecimal("to_payable_total")), rs.getString("status"), rs.getString("requested_by"),
                rs.getObject("requested_at", OffsetDateTime.class), rs.getString("decided_by"),
                rs.getObject("decided_at", OffsetDateTime.class), rs.getString("decision_reason"),
                rs.getObject("business_date", LocalDate.class), rs.getObject("entry_id", UUID.class));
    }

    // ------------------------------------------------------------------ helpers

    private record Member(UUID id, String name) { }

    private Member customer(String customerNumber) {
        return jdbc.sql("SELECT customer_id, first_name || ' ' || last_name FROM customer.customer WHERE customer_number = :n")
            .param("n", customerNumber).query((rs, n) -> new Member(rs.getObject(1, UUID.class), rs.getString(2))).optional()
            .orElseThrow(() -> new NotFoundException("member " + customerNumber + " not found"));
    }

    private BigDecimal holding(UUID customerId) {
        return jdbc.sql("SELECT shares.holding(tenancy.current_tenant(), :c, NULL)").param("c", customerId)
            .query(BigDecimal.class).single();
    }

    private static Money money(BigDecimal amount, String currency) {
        Currency ccy = Currency.getInstance(currency);
        return Money.of(amount.setScale(ccy.getDefaultFractionDigits(), RoundingMode.UNNECESSARY), ccy);
    }

    private static long count(BigDecimal amount, BigDecimal par) {
        return amount == null || par == null ? 0 : amount.divide(par, 0, RoundingMode.DOWN).longValueExact();
    }

    private static String m(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String pct(BigDecimal v) {
        return v.stripTrailingZeros().scale() < 2 ? v.setScale(2, RoundingMode.HALF_UP).toPlainString() : v.stripTrailingZeros().toPlainString();
    }
}
