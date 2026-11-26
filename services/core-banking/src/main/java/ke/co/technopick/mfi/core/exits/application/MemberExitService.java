package ke.co.technopick.mfi.core.exits.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
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
 * Members leaving the Sacco (ADR-0042): a request with a notice period, approval by another person
 * once nothing is outstanding, settlement of savings (balance and unpaid interest less tax) into
 * member refunds payable, and the refund payment with maker-checker. The database re-checks every
 * rule (V39).
 */
@Service
public class MemberExitService {

    public record ExitSettingsView(int noticeDays, String refundsPayableAccount, String updatedBy, OffsetDateTime updatedAt) { }

    public record ExitAccountView(String accountNumber, String balance, String interestGross, String interestTax, String refund) { }

    public record RefundPaymentView(UUID paymentId, String amount, String method, String paidFrom, String reference, String status,
                                    String requestedBy, OffsetDateTime requestedAt, String decidedBy, OffsetDateTime decidedAt,
                                    String decisionReason, LocalDate businessDate, UUID entryId) { }

    public record ExitView(UUID exitId, String customerNumber, String memberName, String reason, LocalDate requestedOn,
                           int noticeDays, LocalDate earliestExitDate, String status, String requestedBy, OffsetDateTime requestedAt,
                           String decidedBy, OffsetDateTime decidedAt, String decisionReason, LocalDate businessDate,
                           String refundTotal, UUID entryId, List<String> blockers, List<ExitAccountView> accounts,
                           RefundPaymentView payment) { }

    /** Where a member stands: what would stop them leaving and what they would be refunded today. */
    public record MemberExitPosition(String customerNumber, String memberName, String memberStatus, List<String> blockers,
                                     List<ExitAccountView> accounts, String refundToday, ExitView openExit) { }

    record Account(UUID id, String number, String control, String branch, String currency, BigDecimal balance, BigDecimal interest,
                   BigDecimal tax, String payable, String taxAccount) { }

    private final JdbcClient jdbc;
    private final TenantSession session;
    private final BusinessDates businessDates;
    private final JournalPosting journal;

    public MemberExitService(JdbcClient jdbc, TenantSession session, BusinessDates businessDates, JournalPosting journal) {
        this.jdbc = jdbc;
        this.session = session;
        this.businessDates = businessDates;
        this.journal = journal;
    }

    // ------------------------------------------------------------------ settings

    @Transactional(readOnly = true)
    public Optional<ExitSettingsView> settings(UUID tenantId) {
        session.bind(tenantId);
        return settingsView();
    }

    @Transactional
    public ExitSettingsView saveSettings(UUID tenantId, int noticeDays, String refundsPayable, String actor) {
        session.bind(tenantId);
        jdbc.sql("""
                INSERT INTO membership.exit_settings (tenant_id, notice_days, gl_refunds_payable, updated_by)
                VALUES (tenancy.current_tenant(), :n, :g, :a)
                ON CONFLICT (tenant_id) DO UPDATE SET notice_days = EXCLUDED.notice_days, gl_refunds_payable = EXCLUDED.gl_refunds_payable,
                       updated_by = EXCLUDED.updated_by, updated_at = clock_timestamp()
                """).param("n", noticeDays).param("g", refundsPayable).param("a", actor).update();
        return settingsView().orElseThrow();
    }

    private Optional<ExitSettingsView> settingsView() {
        return jdbc.sql("SELECT notice_days, gl_refunds_payable, updated_by, updated_at FROM membership.exit_settings")
            .query((rs, n) -> new ExitSettingsView(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getObject(4, OffsetDateTime.class)))
            .optional();
    }

    // ------------------------------------------------------------------ position and requests

    @Transactional(readOnly = true)
    public MemberExitPosition position(UUID tenantId, String customerNumber) {
        session.bind(tenantId);
        record C(UUID id, String name, String status) { }
        C c = jdbc.sql("SELECT customer_id, first_name || ' ' || last_name, status FROM customer.customer WHERE customer_number = :n")
            .param("n", customerNumber).query((rs, n) -> new C(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)))
            .optional().orElseThrow(() -> new NotFoundException("member " + customerNumber + " not found"));
        List<Account> accounts = "EXITED".equals(c.status()) ? List.of() : accounts(c.id(), false);
        BigDecimal total = accounts.stream().map(MemberExitService::refund).reduce(BigDecimal.ZERO, BigDecimal::add);
        ExitView open = jdbc.sql(EXIT + " WHERE r.customer_id = :c AND r.status IN ('REQUESTED', 'APPROVED') ORDER BY r.requested_at DESC LIMIT 1")
            .param("c", c.id()).query((rs, n) -> exitRow(rs)).optional().map(this::withDetail).orElse(null);
        return new MemberExitPosition(customerNumber, c.name(), c.status(), "EXITED".equals(c.status()) ? List.of() : blockers(c.id()),
                accounts.stream().map(MemberExitService::accountView).toList(), m(total), open);
    }

    @Transactional
    public ExitView request(UUID tenantId, String idempotencyKey, String customerNumber, String reason, String actor) {
        session.bind(tenantId);
        Optional<UUID> replay = jdbc.sql("SELECT exit_id FROM membership.exit_request WHERE idempotency_key = :k")
            .param("k", idempotencyKey).query(UUID.class).optional();
        if (replay.isPresent()) {
            return exit(replay.get());
        }
        ExitSettingsView s = settingsView()
            .orElseThrow(() -> new ConflictException("member exits are not set up; finance must set the notice period and refunds account"));
        record C(UUID id, String status) { }
        C c = jdbc.sql("SELECT customer_id, status FROM customer.customer WHERE customer_number = :n FOR UPDATE").param("n", customerNumber)
            .query((rs, n) -> new C(rs.getObject(1, UUID.class), rs.getString(2))).optional()
            .orElseThrow(() -> new NotFoundException("member " + customerNumber + " not found"));
        if (!"ACTIVE".equals(c.status()) && !"BLOCKED".equals(c.status())) {
            throw new ConflictException(customerNumber + " is " + c.status() + " and cannot ask to leave");
        }
        boolean open = jdbc.sql("SELECT EXISTS (SELECT 1 FROM membership.exit_request WHERE customer_id = :c AND status IN ('REQUESTED', 'APPROVED'))")
            .param("c", c.id()).query(Boolean.class).single();
        if (open) {
            throw new ConflictException(customerNumber + " has already asked to leave");
        }
        LocalDate today = businessDates.current();
        UUID id = uuid(tenantId + "|exit|" + idempotencyKey);
        jdbc.sql("""
                INSERT INTO membership.exit_request (tenant_id, exit_id, idempotency_key, customer_id, reason, requested_on, notice_days,
                    earliest_exit_date, requested_by)
                VALUES (tenancy.current_tenant(), :id, :k, :c, :r, :d, :n, :e, :a)
                """).param("id", id).param("k", idempotencyKey).param("c", c.id()).param("r", reason.strip()).param("d", today)
            .param("n", s.noticeDays()).param("e", today.plusDays(s.noticeDays())).param("a", actor).update();
        return exit(id);
    }

    /** Rejects (by another person, with a reason) or cancels (anyone allowed to ask, with a reason) a request. */
    @Transactional
    public ExitView close(UUID tenantId, UUID exitId, String outcome, String reason, String actor) {
        session.bind(tenantId);
        ExitView e = lock(exitId);
        if (reason == null || reason.strip().length() < 3) {
            throw new IllegalArgumentException("a reason is needed");
        }
        if ("REJECTED".equals(outcome) && e.requestedBy().equals(actor)) {
            throw new AccessDeniedException("the person who asked cannot decide; cancel the request instead");
        }
        jdbc.sql("""
                UPDATE membership.exit_request SET status = :s, decided_by = :a, decided_at = clock_timestamp(), decision_reason = :r
                 WHERE exit_id = :id
                """).param("s", outcome).param("a", actor).param("r", reason.strip()).param("id", exitId).update();
        return exit(exitId);
    }

    /** Approves the exit: settles every open savings account into refunds payable, closes them and marks the member EXITED. */
    @Transactional
    public ExitView approve(UUID tenantId, UUID exitId, String actor) {
        session.bind(tenantId);
        ExitView e = lock(exitId);
        if (e.requestedBy().equals(actor)) {
            throw new AccessDeniedException("the person who asked for the exit cannot approve it");
        }
        LocalDate today = businessDates.current();
        if (today.isBefore(e.earliestExitDate())) {
            throw new ConflictException("the notice period runs until " + e.earliestExitDate());
        }
        UUID customerId = jdbc.sql("SELECT customer_id FROM membership.exit_request WHERE exit_id = :id").param("id", exitId)
            .query(UUID.class).single();
        jdbc.sql("SELECT 1 FROM customer.customer WHERE customer_id = :c FOR UPDATE").param("c", customerId).query(Integer.class).single();
        List<String> blockers = blockers(customerId);
        if (!blockers.isEmpty()) {
            throw new ConflictException("the member cannot leave yet: " + String.join("; ", blockers));
        }
        ExitSettingsView s = settingsView().orElseThrow(() -> new ConflictException("member exits are not set up"));
        List<Account> accounts = accounts(customerId, true);
        BigDecimal total = accounts.stream().map(MemberExitService::refund).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<JournalLine> lines = new ArrayList<>();
        Map<String, BigDecimal> refundsByBranch = new LinkedHashMap<>();
        String currency = null;
        for (Account a : accounts) {
            currency = a.currency();
            if (a.balance().signum() > 0) {
                lines.add(JournalLine.onAccount(a.control(), Side.DEBIT, money(a.balance(), a.currency()), a.branch(), a.id()));
            }
            if (a.interest().signum() > 0) {
                lines.add(JournalLine.debit(a.payable(), money(a.interest(), a.currency()), a.branch()));
            }
            if (a.tax().signum() > 0) {
                lines.add(JournalLine.credit(a.taxAccount(), money(a.tax(), a.currency()), a.branch()));
            }
            refundsByBranch.merge(a.branch(), refund(a), BigDecimal::add);
        }
        for (Map.Entry<String, BigDecimal> r : refundsByBranch.entrySet()) {
            if (r.getValue().signum() > 0) {
                lines.add(JournalLine.credit(s.refundsPayableAccount(), money(r.getValue(), currency), r.getKey()));
            }
        }
        UUID entryId = null;
        if (!lines.isEmpty()) {
            String key = "exit:" + exitId;
            entryId = uuid(tenantId + "|" + key);
            journal.post(new JournalEntry(entryId, tenantId, key, today, "MEMBERSHIP", "exit:" + e.customerNumber(), null, null, lines), actor);
        }
        jdbc.sql("""
                UPDATE membership.exit_request SET status = 'APPROVED', decided_by = :a, decided_at = clock_timestamp(), business_date = :d,
                       gl_refunds_payable = :g, refund_total = :t, entry_id = :e WHERE exit_id = :id
                """).param("a", actor).param("d", today).param("g", s.refundsPayableAccount()).param("t", total).param("e", entryId)
            .param("id", exitId).update();
        for (Account a : accounts) {
            jdbc.sql("""
                    INSERT INTO membership.exit_account (tenant_id, exit_id, account_id, balance, interest_gross, interest_tax, refund)
                    VALUES (tenancy.current_tenant(), :x, :a, :b, :i, :t, :r)
                    """).param("x", exitId).param("a", a.id()).param("b", a.balance()).param("i", a.interest()).param("t", a.tax())
                .param("r", refund(a)).update();
            jdbc.sql("UPDATE savings.deposit_account SET status = 'CLOSED' WHERE account_id = :a").param("a", a.id()).update();
        }
        jdbc.sql("UPDATE customer.customer SET status = 'EXITED', status_reason = :r WHERE customer_id = :c")
            .param("r", "Left the Sacco: " + e.reason()).param("c", customerId).update();
        return exit(exitId);
    }

    @Transactional(readOnly = true)
    public List<ExitView> exits(UUID tenantId, String status) {
        session.bind(tenantId);
        return jdbc.sql(EXIT + " WHERE (CAST(:s AS text) IS NULL OR r.status = :s) ORDER BY r.requested_at DESC LIMIT 200")
            .param("s", status).query((rs, n) -> exitRow(rs)).list().stream().map(this::withDetail).toList();
    }

    @Transactional(readOnly = true)
    public ExitView exit(UUID tenantId, UUID exitId) {
        session.bind(tenantId);
        return exit(exitId);
    }

    // ------------------------------------------------------------------ refund payment

    @Transactional
    public ExitView recordRefund(UUID tenantId, String idempotencyKey, UUID exitId, String method, String paidFrom, String reference,
                                 String actor) {
        session.bind(tenantId);
        Optional<UUID> replay = jdbc.sql("SELECT exit_id FROM membership.refund_payment WHERE idempotency_key = :k")
            .param("k", idempotencyKey).query(UUID.class).optional();
        if (replay.isPresent()) {
            return exit(replay.get());
        }
        ExitView e = exit(exitId);
        if (!"APPROVED".equals(e.status())) {
            throw new ConflictException("the exit is " + e.status() + "; only approved exits are refunded");
        }
        BigDecimal total = new BigDecimal(e.refundTotal());
        if (total.signum() == 0) {
            throw new ConflictException("nothing is owed to this member");
        }
        if (e.payment() != null && !"REJECTED".equals(e.payment().status())) {
            throw new ConflictException("the refund is already " + ("APPROVED".equals(e.payment().status()) ? "paid" : "recorded"));
        }
        record G(String cls, boolean control) { }
        G g = jdbc.sql("SELECT account_class, ledger.is_control_account(tenant_id, account_code) FROM ledger.gl_account WHERE account_code = :c")
            .param("c", paidFrom).query((rs, n) -> new G(rs.getString(1), rs.getBoolean(2))).optional()
            .orElseThrow(() -> new IllegalArgumentException("no ledger account " + paidFrom));
        if (!"ASSET".equals(g.cls()) || g.control()) {
            throw new IllegalArgumentException("refunds are paid from a bank or cash ASSET account, not " + paidFrom);
        }
        jdbc.sql("""
                INSERT INTO membership.refund_payment (tenant_id, payment_id, idempotency_key, exit_id, amount, method, paid_from, reference,
                    requested_by)
                VALUES (tenancy.current_tenant(), :id, :k, :x, :amt, :m, :p, :r, :a)
                """).param("id", uuid(tenantId + "|refund|" + idempotencyKey)).param("k", idempotencyKey).param("x", exitId)
            .param("amt", total).param("m", method).param("p", paidFrom).param("r", reference.strip()).param("a", actor).update();
        return exit(exitId);
    }

    @Transactional
    public ExitView decideRefund(UUID tenantId, UUID paymentId, boolean approve, String reason, String actor) {
        session.bind(tenantId);
        record P(UUID exit, String status, String requestedBy, BigDecimal amount, String paidFrom) { }
        P p = jdbc.sql("SELECT exit_id, status, requested_by, amount, paid_from FROM membership.refund_payment WHERE payment_id = :id FOR UPDATE")
            .param("id", paymentId).query((rs, n) -> new P(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                    rs.getBigDecimal(4), rs.getString(5)))
            .optional().orElseThrow(() -> new NotFoundException("refund payment not found"));
        if (!"REQUESTED".equals(p.status())) {
            throw new ConflictException("the payment is already " + p.status());
        }
        if (p.requestedBy().equals(actor)) {
            throw new AccessDeniedException("the person who recorded the payment cannot approve it");
        }
        String why = reason == null || reason.isBlank() ? null : reason.strip();
        if (!approve) {
            if (why == null || why.length() < 3) {
                throw new IllegalArgumentException("a rejection needs a reason");
            }
            jdbc.sql("""
                    UPDATE membership.refund_payment SET status = 'REJECTED', decided_by = :a, decided_at = clock_timestamp(),
                           decision_reason = :r WHERE payment_id = :id
                    """).param("a", actor).param("r", why).param("id", paymentId).update();
            return exit(p.exit());
        }
        record X(String gl, String branch, String customerNumber) { }
        X x = jdbc.sql("""
                SELECT r.gl_refunds_payable, c.branch_code, c.customer_number FROM membership.exit_request r
                  JOIN customer.customer c ON c.tenant_id = r.tenant_id AND c.customer_id = r.customer_id WHERE r.exit_id = :id
                """).param("id", p.exit()).query((rs, n) -> new X(rs.getString(1), rs.getString(2), rs.getString(3))).single();
        String currency = jdbc.sql("SELECT currency FROM ledger.gl_account WHERE account_code = :c").param("c", x.gl()).query(String.class).single();
        LocalDate today = businessDates.current();
        String key = "exit-refund:" + paymentId;
        UUID entryId = uuid(tenantId + "|" + key);
        // refunds payable was credited in the accounts' branches; pay out of the same branches
        List<Map.Entry<String, BigDecimal>> byBranch = jdbc.sql("""
                SELECT l.branch_code, sum(l.amount) FROM ledger.journal_line l
                  JOIN membership.exit_request r ON r.tenant_id = l.tenant_id AND r.entry_id = l.entry_id
                 WHERE r.exit_id = :id AND l.account_code = r.gl_refunds_payable AND l.side = 'C' GROUP BY l.branch_code ORDER BY 1
                """).param("id", p.exit()).query((rs, n) -> Map.entry(rs.getString(1), rs.getBigDecimal(2))).list();
        List<JournalLine> lines = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> b : byBranch) {
            lines.add(JournalLine.debit(x.gl(), money(b.getValue(), currency), b.getKey()));
            lines.add(JournalLine.credit(p.paidFrom(), money(b.getValue(), currency), b.getKey()));
        }
        journal.post(new JournalEntry(entryId, tenantId, key, today, "MEMBERSHIP", "exit-refund:" + x.customerNumber(), null, null, lines),
                actor);
        jdbc.sql("""
                UPDATE membership.refund_payment SET status = 'APPROVED', decided_by = :a, decided_at = clock_timestamp(), decision_reason = :r,
                       business_date = :d, entry_id = :e WHERE payment_id = :id
                """).param("a", actor).param("r", why).param("d", today).param("e", entryId).param("id", paymentId).update();
        return exit(p.exit());
    }

    // ------------------------------------------------------------------ reading

    private List<String> blockers(UUID customerId) {
        return jdbc.sql("SELECT membership.blockers(tenancy.current_tenant(), :c)").param("c", customerId)
            .query((rs, n) -> strings(rs.getArray(1))).single();
    }

    private List<Account> accounts(UUID customerId, boolean lock) {
        return jdbc.sql("""
                SELECT a.account_id, a.account_number, a.gl_control_account, a.branch_code, a.currency, b.ledger_balance,
                       membership.unposted_interest(a.tenant_id, a.account_id) AS interest, p.withholding_tax_rate,
                       p.gl_interest_payable, p.gl_withholding_tax
                  FROM savings.deposit_account a
                  JOIN savings.account_balance b ON b.tenant_id = a.tenant_id AND b.account_id = a.account_id
                  JOIN savings.deposit_product p ON p.tenant_id = a.tenant_id AND p.product_code = a.product_code
                 WHERE a.customer_id = :c AND a.status <> 'CLOSED' ORDER BY a.account_number
                """ + (lock ? " FOR UPDATE OF a" : "")).param("c", customerId)
            .query((rs, n) -> {
                BigDecimal interest = rs.getBigDecimal("interest");
                BigDecimal tax = interest.multiply(rs.getBigDecimal("withholding_tax_rate")).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                return new Account(rs.getObject("account_id", UUID.class), rs.getString("account_number"), rs.getString("gl_control_account"),
                        rs.getString("branch_code"), rs.getString("currency"), rs.getBigDecimal("ledger_balance"), interest, tax,
                        rs.getString("gl_interest_payable"), rs.getString("gl_withholding_tax"));
            }).list();
    }

    private ExitView lock(UUID exitId) {
        ExitView e = jdbc.sql(EXIT + " WHERE r.exit_id = :id FOR UPDATE OF r").param("id", exitId).query((rs, n) -> exitRow(rs)).optional()
            .orElseThrow(() -> new NotFoundException("exit request not found"));
        if (!"REQUESTED".equals(e.status())) {
            throw new ConflictException("the exit is already " + e.status());
        }
        return e;
    }

    private ExitView exit(UUID exitId) {
        return jdbc.sql(EXIT + " WHERE r.exit_id = :id").param("id", exitId).query((rs, n) -> exitRow(rs)).optional().map(this::withDetail)
            .orElseThrow(() -> new NotFoundException("exit request not found"));
    }

    private ExitView withDetail(ExitView e) {
        List<String> blockers = "REQUESTED".equals(e.status()) ? jdbc.sql("""
                SELECT membership.blockers(r.tenant_id, r.customer_id) FROM membership.exit_request r WHERE r.exit_id = :id
                """).param("id", e.exitId()).query((rs, n) -> strings(rs.getArray(1))).single() : List.of();
        List<ExitAccountView> accounts = jdbc.sql("""
                SELECT a.account_number, x.balance, x.interest_gross, x.interest_tax, x.refund FROM membership.exit_account x
                  JOIN savings.deposit_account a ON a.tenant_id = x.tenant_id AND a.account_id = x.account_id
                 WHERE x.exit_id = :id ORDER BY a.account_number
                """).param("id", e.exitId())
            .query((rs, n) -> new ExitAccountView(rs.getString(1), m(rs.getBigDecimal(2)), m(rs.getBigDecimal(3)), m(rs.getBigDecimal(4)),
                    m(rs.getBigDecimal(5)))).list();
        RefundPaymentView payment = jdbc.sql("""
                SELECT * FROM membership.refund_payment WHERE exit_id = :id ORDER BY (status = 'REJECTED'), requested_at DESC LIMIT 1
                """).param("id", e.exitId())
            .query((rs, n) -> new RefundPaymentView(rs.getObject("payment_id", UUID.class), m(rs.getBigDecimal("amount")),
                    rs.getString("method"), rs.getString("paid_from"), rs.getString("reference"), rs.getString("status"),
                    rs.getString("requested_by"), rs.getObject("requested_at", OffsetDateTime.class), rs.getString("decided_by"),
                    rs.getObject("decided_at", OffsetDateTime.class), rs.getString("decision_reason"),
                    rs.getObject("business_date", LocalDate.class), rs.getObject("entry_id", UUID.class)))
            .optional().orElse(null);
        return new ExitView(e.exitId(), e.customerNumber(), e.memberName(), e.reason(), e.requestedOn(), e.noticeDays(), e.earliestExitDate(),
                e.status(), e.requestedBy(), e.requestedAt(), e.decidedBy(), e.decidedAt(), e.decisionReason(), e.businessDate(),
                e.refundTotal(), e.entryId(), blockers, accounts, payment);
    }

    private static final String EXIT = """
            SELECT r.*, c.customer_number, c.first_name || ' ' || c.last_name AS member_name
              FROM membership.exit_request r
              JOIN customer.customer c ON c.tenant_id = r.tenant_id AND c.customer_id = r.customer_id
            """;

    private static ExitView exitRow(ResultSet rs) throws SQLException {
        return new ExitView(rs.getObject("exit_id", UUID.class), rs.getString("customer_number"), rs.getString("member_name"),
                rs.getString("reason"), rs.getObject("requested_on", LocalDate.class), rs.getInt("notice_days"),
                rs.getObject("earliest_exit_date", LocalDate.class), rs.getString("status"), rs.getString("requested_by"),
                rs.getObject("requested_at", OffsetDateTime.class), rs.getString("decided_by"), rs.getObject("decided_at", OffsetDateTime.class),
                rs.getString("decision_reason"), rs.getObject("business_date", LocalDate.class), m(rs.getBigDecimal("refund_total")),
                rs.getObject("entry_id", UUID.class), List.of(), List.of(), null);
    }

    private static List<String> strings(Array a) throws SQLException {
        return a == null ? List.of() : List.of((String[]) a.getArray());
    }

    private static BigDecimal refund(Account a) {
        return a.balance().add(a.interest()).subtract(a.tax());
    }

    private static ExitAccountView accountView(Account a) {
        return new ExitAccountView(a.number(), m(a.balance()), m(a.interest()), m(a.tax()), m(refund(a)));
    }

    private static UUID uuid(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    private static Money money(BigDecimal amount, String currency) {
        Currency ccy = Currency.getInstance(currency);
        return Money.of(amount.setScale(ccy.getDefaultFractionDigits(), RoundingMode.UNNECESSARY), ccy);
    }

    private static String m(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
