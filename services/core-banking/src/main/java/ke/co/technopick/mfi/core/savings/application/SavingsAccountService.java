package ke.co.technopick.mfi.core.savings.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import ke.co.technopick.mfi.core.common.ConflictException;
import ke.co.technopick.mfi.core.common.NotFoundException;
import ke.co.technopick.mfi.core.customer.application.CustomerService;
import ke.co.technopick.mfi.core.messaging.persistence.Outbox;
import ke.co.technopick.mfi.core.tenancy.persistence.BusinessDates;
import ke.co.technopick.mfi.core.tenancy.persistence.TenantSession;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Savings accounts for verified customers (ADR-0017): opening, listing, balances and statements.
 * Balances and statements are read from the ledger (the subledger lines of the account), so they
 * always agree with the books.
 */
@Service
public class SavingsAccountService {

    static final int MAX_STATEMENT_DAYS = 366;

    public record SavingsAccountOpened(String accountNumber, String status, boolean replayed) { }

    public record SavingsAccountView(String accountNumber, String customerNumber, String customerName, String productCode,
                                     String productName, String currency, String branchCode, String status,
                                     LocalDate openedOn, String ledgerBalance, String heldAmount,
                                     String availableBalance) { }

    public record StatementLine(LocalDate date, OffsetDateTime postedAt, UUID entryId, String description, String debit,
                                String credit, String balance) { }

    public record SavingsStatement(String accountNumber, String currency, LocalDate from, LocalDate to,
                                   String openingBalance, String totalDebits, String totalCredits, String closingBalance,
                                   List<StatementLine> lines) { }

    private final JdbcClient jdbc;
    private final TenantSession session;
    private final BusinessDates businessDates;
    private final CustomerService customers;
    private final Outbox outbox;

    public SavingsAccountService(JdbcClient jdbc, TenantSession session, BusinessDates businessDates,
                                 CustomerService customers, Outbox outbox) {
        this.jdbc = jdbc;
        this.session = session;
        this.businessDates = businessDates;
        this.customers = customers;
        this.outbox = outbox;
    }

    @Transactional
    public SavingsAccountOpened open(UUID tenantId, String idempotencyKey, String customerNumber, String productCode,
                                     String branchCode, String actor) {
        session.bind(tenantId);
        Optional<SavingsAccountOpened> replay = jdbc.sql("""
                SELECT account_number, status, customer_ref, product_code FROM savings.deposit_account
                 WHERE open_idempotency_key = :key
                """)
            .param("key", idempotencyKey)
            .query((rs, i) -> {
                if (!rs.getString("customer_ref").equalsIgnoreCase(customerNumber.strip())
                        || !rs.getString("product_code").equalsIgnoreCase(productCode.strip())) {
                    throw new ConflictException("idempotency key already used for a different account");
                }
                return new SavingsAccountOpened(rs.getString("account_number"), rs.getString("status"), true);
            })
            .optional();
        if (replay.isPresent()) {
            return replay.get();
        }
        CustomerService.CustomerRef customer = customers.byNumber(customerNumber)
                .filter(c -> "ACTIVE".equals(c.status()))
                .orElseThrow(() -> new IllegalArgumentException("customer " + customerNumber
                        + " not found or not verified; verify the customer first"));
        record Product(String currency, String gl) { }
        Product product = jdbc.sql("SELECT currency, gl_control_account FROM savings.deposit_product WHERE product_code = :p")
            .param("p", productCode.strip().toUpperCase(Locale.ROOT))
            .query((rs, i) -> new Product(rs.getString("currency"), rs.getString("gl_control_account"))).optional()
            .orElseThrow(() -> new IllegalArgumentException("savings product " + productCode + " not found"));
        Integer branch = jdbc.sql("SELECT count(*) FROM tenancy.branch WHERE branch_code = :b").param("b", branchCode.strip())
            .query(Integer.class).single();
        if (branch == 0) {
            throw new IllegalArgumentException("branch " + branchCode + " not found");
        }
        String number = jdbc.sql("SELECT savings.next_account_number()").query(String.class).single();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO savings.deposit_account (tenant_id, account_id, account_number, customer_ref, customer_id,
                    product_code, currency, branch_code, gl_control_account, opened_on, open_idempotency_key)
                VALUES (:tenant, :id, :number, :customerRef, :customerId, :product, :ccy, :branch, :gl, :opened, :key)
                """)
            .param("tenant", tenantId).param("id", id).param("number", number)
            .param("customerRef", customer.customerNumber()).param("customerId", customer.customerId())
            .param("product", productCode.strip().toUpperCase(Locale.ROOT)).param("ccy", product.currency())
            .param("branch", branchCode.strip()).param("gl", product.gl()).param("opened", businessDates.current())
            .param("key", idempotencyKey)
            .update();
        outbox.record(tenantId, "SavingsAccountOpened", "deposit_account", id.toString(),
                "{\"accountId\":\"%s\",\"accountNumber\":\"%s\",\"customerId\":\"%s\",\"actor\":\"%s\"}"
                        .formatted(id, number, customer.customerId(), actor),
                "account:" + id);
        return new SavingsAccountOpened(number, "ACTIVE", false);
    }

    @Transactional(readOnly = true)
    public List<SavingsAccountView> ofCustomer(UUID tenantId, String customerNumber) {
        session.bind(tenantId);
        return views(" WHERE a.customer_ref = :v", customerNumber.strip().toUpperCase(Locale.ROOT));
    }

    @Transactional(readOnly = true)
    public SavingsAccountView view(UUID tenantId, String accountNumber) {
        session.bind(tenantId);
        return views(" WHERE a.account_number = :v", accountNumber.strip()).stream().findFirst()
            .orElseThrow(() -> new NotFoundException("account not found"));
    }

    private List<SavingsAccountView> views(String where, String value) {
        return jdbc.sql("""
                SELECT a.account_number, a.customer_ref, c.first_name, c.middle_name, c.last_name, a.product_code,
                       p.name AS product_name, a.currency, a.branch_code, a.status, a.opened_on,
                       b.ledger_balance, b.active_holds, b.available_balance
                  FROM savings.deposit_account a
                  JOIN savings.deposit_product p ON p.tenant_id = a.tenant_id AND p.product_code = a.product_code
                  JOIN savings.account_balance b ON b.tenant_id = a.tenant_id AND b.account_id = a.account_id
                  LEFT JOIN customer.customer c ON c.tenant_id = a.tenant_id AND c.customer_id = a.customer_id""" + where
                + " ORDER BY a.opened_on, a.account_number")
            .param("v", value)
            .query((rs, i) -> new SavingsAccountView(rs.getString("account_number"), rs.getString("customer_ref"),
                    rs.getString("first_name") == null ? null : String.join(" ", nonBlank(rs.getString("first_name"),
                            rs.getString("middle_name"), rs.getString("last_name"))),
                    rs.getString("product_code"), rs.getString("product_name"), rs.getString("currency"),
                    rs.getString("branch_code"), rs.getString("status"), rs.getObject("opened_on", LocalDate.class),
                    plain(rs.getBigDecimal("ledger_balance")), plain(rs.getBigDecimal("active_holds")),
                    plain(rs.getBigDecimal("available_balance"))))
            .list();
    }

    /** Statement from the ledger: opening balance, every posting with a running balance, closing balance. */
    @Transactional(readOnly = true)
    public SavingsStatement statement(UUID tenantId, String accountNumber, LocalDate from, LocalDate to) {
        session.bind(tenantId);
        LocalDate end = to == null ? businessDates.current() : to;
        LocalDate start = from == null ? end.minusDays(30) : from;
        if (start.isAfter(end) || ChronoUnit.DAYS.between(start, end) > MAX_STATEMENT_DAYS) {
            throw new IllegalArgumentException("statement period must run forwards and cover at most " + MAX_STATEMENT_DAYS + " days");
        }
        record Acc(UUID id, String currency) { }
        Acc acc = jdbc.sql("SELECT account_id, currency FROM savings.deposit_account WHERE account_number = :n")
            .param("n", accountNumber.strip())
            .query((rs, i) -> new Acc(rs.getObject("account_id", UUID.class), rs.getString("currency"))).optional()
            .orElseThrow(() -> new NotFoundException("account not found"));
        BigDecimal opening = jdbc.sql("""
                SELECT COALESCE(sum(CASE l.side WHEN 'C' THEN l.amount ELSE -l.amount END), 0)
                  FROM ledger.journal_line l JOIN ledger.journal_entry e ON e.tenant_id = l.tenant_id AND e.entry_id = l.entry_id
                 WHERE l.subledger_account_id = :a AND e.business_date < :from
                """)
            .param("a", acc.id()).param("from", start).query(BigDecimal.class).single();
        BigDecimal[] running = {opening};
        BigDecimal[] totals = {BigDecimal.ZERO, BigDecimal.ZERO};
        List<StatementLine> lines = new ArrayList<>(jdbc.sql("""
                SELECT e.business_date, e.posted_at, e.entry_id, l.side, l.amount,
                       COALESCE(t.narrative, l.narrative,
                                CASE e.source WHEN 'MPESA' THEN 'M-Pesa ' || coalesce(e.switch_reference, '')
                                              ELSE initcap(e.source) || coalesce(' ' || e.switch_reference, '') END)
                           AS description
                  FROM ledger.journal_line l
                  JOIN ledger.journal_entry e ON e.tenant_id = l.tenant_id AND e.entry_id = l.entry_id
                  LEFT JOIN savings.teller_transaction t ON t.tenant_id = l.tenant_id AND t.journal_entry_id = e.entry_id
                 WHERE l.subledger_account_id = :a AND e.business_date BETWEEN :from AND :to
                 ORDER BY e.business_date, e.posted_at, e.entry_id, l.line_no
                """)
            .param("a", acc.id()).param("from", start).param("to", end)
            .query((rs, i) -> {
                BigDecimal amount = rs.getBigDecimal("amount");
                boolean credit = "C".equals(rs.getString("side"));
                running[0] = credit ? running[0].add(amount) : running[0].subtract(amount);
                totals[credit ? 1 : 0] = totals[credit ? 1 : 0].add(amount);
                return new StatementLine(rs.getObject("business_date", LocalDate.class),
                        rs.getObject("posted_at", OffsetDateTime.class), rs.getObject("entry_id", UUID.class),
                        rs.getString("description").strip(), credit ? null : plain(amount), credit ? plain(amount) : null,
                        plain(running[0]));
            })
            .list());
        return new SavingsStatement(accountNumber.strip(), acc.currency(), start, end, plain(opening), plain(totals[0]),
                plain(totals[1]), plain(running[0]), lines);
    }

    private static String[] nonBlank(String... parts) {
        return java.util.Arrays.stream(parts).filter(p -> p != null && !p.isBlank()).toArray(String[]::new);
    }

    static String plain(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
