package ke.co.technopick.mfi.core.termdeposits.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 * Fixed (term) deposits (ADR-0041): products, placement from the member's own savings, maturity
 * instructions, early withdrawal with maker-checker, and the closure postings shared with the
 * end-of-day maturity step. The database re-checks every rule (V38).
 */
@Service
public class FixedDepositService {

    public static final Set<String> INSTRUCTIONS = Set.of("PAYOUT", "ROLLOVER_PRINCIPAL", "ROLLOVER_ALL");

    public record FixedDepositProductView(String productCode, String name, String currency, int termMonths, String ratePct,
                                          String earlyRatePct, boolean earlyWithdrawalAllowed, String minAmount, String maxAmount,
                                          String taxRatePct, String principalAccount, String interestPayableAccount,
                                          String interestExpenseAccount, String taxAccount, String status, String updatedBy,
                                          OffsetDateTime updatedAt, int activeDeposits) { }

    public record FixedDepositView(UUID depositId, String depositNumber, String customerNumber, String memberName,
                                   String productCode, String accountNumber, String branchCode, String currency, String principal,
                                   String ratePct, String earlyRatePct, boolean earlyWithdrawalAllowed, String taxRatePct,
                                   int termMonths, LocalDate startDate, LocalDate maturityDate, String maturityInstruction,
                                   String status, String accruedInterest, String rolledFromNumber, String createdBy,
                                   OffsetDateTime createdAt) { }

    /** What the deposit pays: at maturity, and if withdrawn early today (when allowed and still active). */
    public record FixedDepositQuote(int nights, String ratePct, String interest, String tax, String netInterest, String total) { }

    public record FixedDepositClosureView(String kind, LocalDate businessDate, int nights, String rateApplied, String interestEarned,
                                          String accruedBefore, String tax, String netInterest, String toSavings,
                                          String newPrincipal, String newDepositNumber, UUID entryId, String createdBy) { }

    public record EarlyWithdrawalView(UUID requestId, String depositNumber, String customerNumber, String memberName,
                                      String principal, LocalDate maturityDate, String reason, String status, String requestedBy,
                                      OffsetDateTime requestedAt, String decidedBy, OffsetDateTime decidedAt,
                                      String decisionReason, FixedDepositQuote quoteToday) { }

    public record FixedDepositDetail(FixedDepositView deposit, FixedDepositQuote atMaturity, FixedDepositQuote earlyToday,
                                     FixedDepositClosureView closure, EarlyWithdrawalView openRequest) { }

    public record MemberFixedDeposits(String customerNumber, String name, String activePrincipal,
                                      List<FixedDepositView> deposits) { }

    public record FixedDepositPortfolio(LocalDate businessDate, int active, String principal, String accruedInterest,
                                        List<FixedDepositView> maturingSoon) { }

    /** Result of closing one deposit (maturity or early withdrawal). */
    public record ClosureResult(String depositNumber, String kind, BigDecimal toSavings, BigDecimal newPrincipal,
                                String newDepositNumber) { }

    public record Deposit(UUID id, String number, UUID customerId, String productCode, UUID accountId, String control, String branch,
                   String currency, BigDecimal principal, BigDecimal rate, BigDecimal earlyRate, boolean earlyAllowed,
                   BigDecimal taxRate, LocalDate start, LocalDate maturity, String instruction, String status) { }

    public record Product(String code, String currency, int termMonths, BigDecimal rate, BigDecimal earlyRate, boolean earlyAllowed,
                   BigDecimal min, BigDecimal max, BigDecimal taxRate, String principal, String payable, String expense, String tax,
                   String status) { }

    private final JdbcClient jdbc;
    private final TenantSession session;
    private final BusinessDates businessDates;
    private final JournalPosting journal;

    public FixedDepositService(JdbcClient jdbc, TenantSession session, BusinessDates businessDates, JournalPosting journal) {
        this.jdbc = jdbc;
        this.session = session;
        this.businessDates = businessDates;
        this.journal = journal;
    }

    // ------------------------------------------------------------------ products

    @Transactional(readOnly = true)
    public List<FixedDepositProductView> products(UUID tenantId) {
        session.bind(tenantId);
        return jdbc.sql("""
                SELECT p.*, (SELECT count(*) FROM termdep.deposit d WHERE d.product_code = p.product_code AND d.status = 'ACTIVE') AS active
                  FROM termdep.product p ORDER BY p.status, p.product_code
                """).query((rs, n) -> productView(rs)).list();
    }

    /** Creates a product, or changes its name, rates, limits and status (accounts and currency never change). */
    @Transactional
    public FixedDepositProductView saveProduct(UUID tenantId, String code, String name, int termMonths, BigDecimal rate,
                                               BigDecimal earlyRate, boolean earlyAllowed, BigDecimal min, BigDecimal max,
                                               BigDecimal taxRate, String principal, String payable, String expense, String tax,
                                               String status, String actor) {
        session.bind(tenantId);
        if (earlyRate.compareTo(rate) > 0) {
            throw new IllegalArgumentException("the early withdrawal rate cannot be above the deposit rate");
        }
        if (max != null && max.compareTo(min) < 0) {
            throw new IllegalArgumentException("the maximum amount cannot be below the minimum");
        }
        String currency = jdbc.sql("SELECT currency FROM ledger.gl_account WHERE account_code = :c").param("c", principal)
            .query(String.class).optional().orElseThrow(() -> new IllegalArgumentException("no ledger account " + principal));
        jdbc.sql("""
                INSERT INTO termdep.product (tenant_id, product_code, name, currency, term_months, rate_pct, early_rate_pct,
                    early_withdrawal_allowed, min_amount, max_amount, tax_rate_pct, gl_principal, gl_interest_payable,
                    gl_interest_expense, gl_tax, status, updated_by)
                VALUES (tenancy.current_tenant(), :code, :name, :ccy, :term, :rate, :early, :allowed, :min, :max, :tax, :p, :i, :e, :t,
                        :status, :actor)
                ON CONFLICT (tenant_id, product_code) DO UPDATE
                   SET name = EXCLUDED.name, term_months = EXCLUDED.term_months, rate_pct = EXCLUDED.rate_pct,
                       early_rate_pct = EXCLUDED.early_rate_pct, early_withdrawal_allowed = EXCLUDED.early_withdrawal_allowed,
                       min_amount = EXCLUDED.min_amount, max_amount = EXCLUDED.max_amount, tax_rate_pct = EXCLUDED.tax_rate_pct,
                       gl_principal = EXCLUDED.gl_principal, gl_interest_payable = EXCLUDED.gl_interest_payable,
                       gl_interest_expense = EXCLUDED.gl_interest_expense, gl_tax = EXCLUDED.gl_tax, status = EXCLUDED.status,
                       updated_by = EXCLUDED.updated_by, updated_at = clock_timestamp()
                """)
            .param("code", code).param("name", name).param("ccy", currency).param("term", termMonths).param("rate", rate)
            .param("early", earlyRate).param("allowed", earlyAllowed).param("min", min).param("max", max).param("tax", taxRate)
            .param("p", principal).param("i", payable).param("e", expense).param("t", tax).param("status", status)
            .param("actor", actor).update();
        return products(tenantId).stream().filter(p -> p.productCode().equals(code)).findFirst().orElseThrow();
    }

    private Product product(String code) {
        return jdbc.sql("SELECT * FROM termdep.product WHERE product_code = :c").param("c", code)
            .query((rs, n) -> new Product(rs.getString("product_code"), rs.getString("currency"), rs.getInt("term_months"),
                    rs.getBigDecimal("rate_pct"), rs.getBigDecimal("early_rate_pct"), rs.getBoolean("early_withdrawal_allowed"),
                    rs.getBigDecimal("min_amount"), rs.getBigDecimal("max_amount"), rs.getBigDecimal("tax_rate_pct"),
                    rs.getString("gl_principal"), rs.getString("gl_interest_payable"), rs.getString("gl_interest_expense"),
                    rs.getString("gl_tax"), rs.getString("status")))
            .optional().orElseThrow(() -> new NotFoundException("fixed deposit product " + code + " not found"));
    }

    // ------------------------------------------------------------------ deposits

    @Transactional(readOnly = true)
    public MemberFixedDeposits member(UUID tenantId, String customerNumber) {
        session.bind(tenantId);
        record C(UUID id, String name) { }
        C c = jdbc.sql("SELECT customer_id, first_name || ' ' || last_name FROM customer.customer WHERE customer_number = :n")
            .param("n", customerNumber).query((rs, n) -> new C(rs.getObject(1, UUID.class), rs.getString(2))).optional()
            .orElseThrow(() -> new NotFoundException("member " + customerNumber + " not found"));
        List<FixedDepositView> deposits = jdbc.sql(VIEW + " WHERE d.customer_id = :c ORDER BY d.created_at DESC LIMIT 100")
            .param("c", c.id()).query((rs, n) -> view(rs)).list();
        BigDecimal active = deposits.stream().filter(d -> "ACTIVE".equals(d.status())).map(d -> new BigDecimal(d.principal()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new MemberFixedDeposits(customerNumber, c.name(), m(active), deposits);
    }

    @Transactional(readOnly = true)
    public FixedDepositDetail detail(UUID tenantId, String depositNumber) {
        session.bind(tenantId);
        return detail(depositNumber);
    }

    private FixedDepositDetail detail(String depositNumber) {
        FixedDepositView v = jdbc.sql(VIEW + " WHERE d.deposit_number = :n").param("n", depositNumber).query((rs, n) -> view(rs))
            .optional().orElseThrow(() -> new NotFoundException("fixed deposit " + depositNumber + " not found"));
        Deposit d = deposit(v.depositId(), false);
        LocalDate today = businessDates.current();
        FixedDepositQuote atMaturity = quote(d, d.rate(), nights(d.start(), d.maturity()), d.principal());
        FixedDepositQuote early = "ACTIVE".equals(d.status()) && d.earlyAllowed() && today.isBefore(d.maturity())
                ? quote(d, d.earlyRate(), nights(d.start(), today), d.principal()) : null;
        FixedDepositClosureView closure = jdbc.sql("""
                SELECT c.*, n.deposit_number AS new_number FROM termdep.closure c
                  LEFT JOIN termdep.deposit n ON n.tenant_id = c.tenant_id AND n.deposit_id = c.new_deposit_id
                 WHERE c.deposit_id = :id
                """).param("id", d.id())
            .query((rs, n) -> new FixedDepositClosureView(rs.getString("kind"), rs.getObject("business_date", LocalDate.class),
                    rs.getInt("nights"), pct(rs.getBigDecimal("rate_applied")), m(rs.getBigDecimal("interest_earned")),
                    m(rs.getBigDecimal("accrued_before")), m(rs.getBigDecimal("tax")), m(rs.getBigDecimal("net_interest")),
                    m(rs.getBigDecimal("to_savings")), m(rs.getBigDecimal("new_principal")), rs.getString("new_number"),
                    rs.getObject("entry_id", UUID.class), rs.getString("created_by")))
            .optional().orElse(null);
        EarlyWithdrawalView open = jdbc.sql(REQUEST + " WHERE r.deposit_id = :id AND r.status = 'REQUESTED'").param("id", d.id())
            .query((rs, n) -> requestView(rs)).optional().orElse(null);
        return new FixedDepositDetail(v, atMaturity, early, closure, open);
    }

    /** Places a deposit for a member, paid from one of their own savings accounts, on the business date. */
    @Transactional
    public FixedDepositDetail place(UUID tenantId, String idempotencyKey, String customerNumber, String accountNumber,
                                    String productCode, BigDecimal amount, String instruction, String actor) {
        session.bind(tenantId);
        Optional<String> replay = jdbc.sql("SELECT deposit_number FROM termdep.deposit WHERE idempotency_key = :k")
            .param("k", "place:" + idempotencyKey).query(String.class).optional();
        if (replay.isPresent()) {
            return detail(replay.get());
        }
        Product p = product(productCode);
        if (!"ACTIVE".equals(p.status())) {
            throw new ConflictException("product " + productCode + " is retired and takes no new deposits");
        }
        if (amount.compareTo(p.min()) < 0 || (p.max() != null && amount.compareTo(p.max()) > 0)) {
            throw new IllegalArgumentException("product " + productCode + " takes " + m(p.min()) + " to "
                    + (p.max() == null ? "any amount" : m(p.max())));
        }
        record A(UUID id, UUID owner, String control, String branch, String currency, String status, String customerStatus,
                 String customerNumber) { }
        A a = jdbc.sql("""
                SELECT a.account_id, a.customer_id, a.gl_control_account, a.branch_code, a.currency, a.status, c.status, c.customer_number
                  FROM savings.deposit_account a
                  LEFT JOIN customer.customer c ON c.tenant_id = a.tenant_id AND c.customer_id = a.customer_id
                 WHERE a.account_number = :n FOR UPDATE OF a
                """).param("n", accountNumber)
            .query((rs, n) -> new A(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)))
            .optional().orElseThrow(() -> new NotFoundException("savings account " + accountNumber + " not found"));
        if (!customerNumber.equals(a.customerNumber())) {
            throw new IllegalArgumentException("account " + accountNumber + " does not belong to " + customerNumber);
        }
        if (!"ACTIVE".equals(a.customerStatus())) {
            throw new ConflictException("only active members place fixed deposits");
        }
        if (!"ACTIVE".equals(a.status())) {
            throw new ConflictException("account " + accountNumber + " is " + a.status());
        }
        if (!p.currency().equals(a.currency())) {
            throw new IllegalArgumentException("account " + accountNumber + " is in " + a.currency() + "; the product is in " + p.currency());
        }
        BigDecimal available = jdbc.sql("SELECT available_balance FROM savings.account_balance WHERE account_id = :id")
            .param("id", a.id()).query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
        if (available.compareTo(amount) < 0) {
            throw new ConflictException("account " + accountNumber + " has " + m(available) + " available; " + m(amount) + " needed");
        }
        LocalDate today = businessDates.current();
        String key = "termdep:place:" + idempotencyKey;
        UUID entryId = uuid(tenantId + "|" + key);
        Money money = money(amount, p.currency());
        journal.post(new JournalEntry(entryId, tenantId, key, today, "TERMDEP", "fd:" + customerNumber, null, null, List.of(
                JournalLine.onAccount(a.control(), Side.DEBIT, money, a.branch(), a.id()),
                JournalLine.credit(p.principal(), money, a.branch()))), actor);
        String number = insertDeposit(tenantId, "place:" + idempotencyKey, a.owner(), p, a.id(), a.branch(), amount, today,
                instruction, entryId, null, actor);
        return detail(number);
    }

    public String insertDeposit(UUID tenantId, String key, UUID customerId, Product p, UUID accountId, String branch, BigDecimal principal,
                         LocalDate start, String instruction, UUID entryId, UUID rolledFrom, String actor) {
        String number = jdbc.sql("SELECT termdep.next_number(tenancy.current_tenant())").query(String.class).single();
        jdbc.sql("""
                INSERT INTO termdep.deposit (tenant_id, deposit_id, deposit_number, idempotency_key, customer_id, product_code,
                    savings_account_id, branch_code, currency, principal, rate_pct, early_rate_pct, early_withdrawal_allowed,
                    tax_rate_pct, term_months, start_date, maturity_date, maturity_instruction, placement_entry_id, rolled_from,
                    created_by)
                VALUES (tenancy.current_tenant(), :id, :number, :key, :customer, :product, :account, :branch, :ccy, :principal,
                        :rate, :early, :allowed, :tax, :term, :start, :maturity, :instruction, :entry, :from, :actor)
                """)
            .param("id", uuid(tenantId + "|termdep-deposit|" + key)).param("number", number).param("key", key)
            .param("customer", customerId).param("product", p.code()).param("account", accountId).param("branch", branch)
            .param("ccy", p.currency()).param("principal", principal).param("rate", p.rate()).param("early", p.earlyRate())
            .param("allowed", p.earlyAllowed()).param("tax", p.taxRate()).param("term", p.termMonths()).param("start", start)
            .param("maturity", start.plusMonths(p.termMonths())).param("instruction", instruction).param("entry", entryId)
            .param("from", rolledFrom).param("actor", actor).update();
        return number;
    }

    @Transactional
    public FixedDepositDetail changeInstruction(UUID tenantId, String depositNumber, String instruction, String actor) {
        session.bind(tenantId);
        UUID id = depositId(depositNumber);
        Deposit d = deposit(id, true);
        if (!"ACTIVE".equals(d.status())) {
            throw new ConflictException("deposit " + depositNumber + " is " + d.status());
        }
        if (!instruction.equals(d.instruction())) {
            jdbc.sql("""
                    UPDATE termdep.deposit SET maturity_instruction = :i, instruction_changed_by = :a,
                           instruction_changed_at = clock_timestamp() WHERE deposit_id = :id
                    """).param("i", instruction).param("a", actor).param("id", id).update();
        }
        return detail(depositNumber);
    }

    // ------------------------------------------------------------------ early withdrawal

    @Transactional
    public EarlyWithdrawalView requestEarly(UUID tenantId, String idempotencyKey, String depositNumber, String reason, String actor) {
        session.bind(tenantId);
        Optional<UUID> replay = jdbc.sql("SELECT request_id FROM termdep.early_request WHERE idempotency_key = :k")
            .param("k", idempotencyKey).query(UUID.class).optional();
        if (replay.isPresent()) {
            return request(replay.get());
        }
        UUID id = depositId(depositNumber);
        Deposit d = deposit(id, true);
        if (!"ACTIVE".equals(d.status())) {
            throw new ConflictException("deposit " + depositNumber + " is " + d.status());
        }
        if (!d.earlyAllowed()) {
            throw new ConflictException("deposit " + depositNumber + " cannot be withdrawn before maturity");
        }
        if (!businessDates.current().isBefore(d.maturity().minusDays(1))) {
            throw new ConflictException("deposit " + depositNumber + " matures at tonight's close; it pays out then");
        }
        boolean open = jdbc.sql("SELECT EXISTS (SELECT 1 FROM termdep.early_request WHERE deposit_id = :id AND status = 'REQUESTED')")
            .param("id", id).query(Boolean.class).single();
        if (open) {
            throw new ConflictException("an early withdrawal of " + depositNumber + " is already waiting for a decision");
        }
        UUID requestId = uuid(tenantId + "|termdep-early|" + idempotencyKey);
        jdbc.sql("""
                INSERT INTO termdep.early_request (tenant_id, request_id, idempotency_key, deposit_id, reason, requested_by)
                VALUES (tenancy.current_tenant(), :id, :k, :d, :r, :a)
                """).param("id", requestId).param("k", idempotencyKey).param("d", id).param("r", reason.strip()).param("a", actor)
            .update();
        return request(requestId);
    }

    @Transactional
    public EarlyWithdrawalView decideEarly(UUID tenantId, UUID requestId, boolean approve, String reason, String actor) {
        session.bind(tenantId);
        record R(UUID deposit, String status, String requestedBy) { }
        R r = jdbc.sql("SELECT deposit_id, status, requested_by FROM termdep.early_request WHERE request_id = :id FOR UPDATE")
            .param("id", requestId).query((rs, n) -> new R(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)))
            .optional().orElseThrow(() -> new NotFoundException("early withdrawal request not found"));
        if (!"REQUESTED".equals(r.status())) {
            throw new ConflictException("request is already " + r.status());
        }
        if (r.requestedBy().equals(actor)) {
            throw new AccessDeniedException("the person who asked for the withdrawal cannot decide it");
        }
        String why = reason == null || reason.isBlank() ? null : reason.strip();
        if (!approve) {
            if (why == null || why.length() < 3) {
                throw new IllegalArgumentException("a rejection needs a reason");
            }
            jdbc.sql("""
                    UPDATE termdep.early_request SET status = 'REJECTED', decided_by = :a, decided_at = clock_timestamp(),
                           decision_reason = :r WHERE request_id = :id
                    """).param("a", actor).param("r", why).param("id", requestId).update();
            return request(requestId);
        }
        Deposit d = deposit(r.deposit(), true);
        if (!"ACTIVE".equals(d.status())) {
            throw new ConflictException("deposit " + d.number() + " is already " + d.status());
        }
        LocalDate today = businessDates.current();
        if (!today.isBefore(d.maturity())) {
            throw new ConflictException("deposit " + d.number() + " has reached maturity");
        }
        jdbc.sql("""
                UPDATE termdep.early_request SET status = 'APPROVED', decided_by = :a, decided_at = clock_timestamp(),
                       decision_reason = :r WHERE request_id = :id
                """).param("a", actor).param("r", why).param("id", requestId).update();
        close(tenantId, d, "EARLY", today, requestId, actor);
        return request(requestId);
    }

    @Transactional(readOnly = true)
    public List<EarlyWithdrawalView> earlyRequests(UUID tenantId, String status) {
        session.bind(tenantId);
        return jdbc.sql(REQUEST + " WHERE (CAST(:s AS text) IS NULL OR r.status = :s) ORDER BY r.requested_at DESC LIMIT 200")
            .param("s", status).query((rs, n) -> requestView(rs)).list();
    }

    @Transactional(readOnly = true)
    public FixedDepositPortfolio portfolio(UUID tenantId, int days) {
        session.bind(tenantId);
        LocalDate today = businessDates.current();
        record T(int count, BigDecimal principal, BigDecimal accrued) { }
        T t = jdbc.sql("""
                SELECT count(*), COALESCE(sum(principal), 0),
                       COALESCE((SELECT sum(x.posted) FROM termdep.accrual x JOIN termdep.deposit d ON d.deposit_id = x.deposit_id
                                  WHERE d.status = 'ACTIVE'), 0)
                  FROM termdep.deposit WHERE status = 'ACTIVE'
                """).query((rs, n) -> new T(rs.getInt(1), rs.getBigDecimal(2), rs.getBigDecimal(3))).single();
        List<FixedDepositView> soon = jdbc.sql(VIEW + " WHERE d.status = 'ACTIVE' AND d.maturity_date <= :until ORDER BY d.maturity_date, d.deposit_number LIMIT 500")
            .param("until", today.plusDays(days)).query((rs, n) -> view(rs)).list();
        return new FixedDepositPortfolio(today, t.count(), m(t.principal()), m(t.accrued()), soon);
    }

    // ------------------------------------------------------------------ closure (early and maturity)

    /**
     * Closes a deposit on {@code today}: early (at the early rate for the nights held) or at maturity
     * (the full term). Rollovers continue from the maturity date at the product's current terms; if the
     * product is retired, or the member or account is no longer active, the deposit is paid out instead.
     */
    public ClosureResult close(UUID tenantId, Deposit d, String kind, LocalDate today, UUID requestId, String actor) {
        boolean early = "EARLY".equals(kind);
        int nights = early ? nights(d.start(), today) : nights(d.start(), d.maturity());
        BigDecimal rate = early ? d.earlyRate() : d.rate();
        BigDecimal earned = interest(d.principal(), rate, nights);
        BigDecimal tax = earned.multiply(d.taxRate()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal net = earned.subtract(tax);
        BigDecimal accrued = jdbc.sql("SELECT COALESCE(sum(posted), 0) FROM termdep.accrual WHERE deposit_id = :id")
            .param("id", d.id()).query(BigDecimal.class).single();
        Product p = product(d.productCode());
        String instruction = early ? "PAYOUT" : d.instruction();
        if (!"PAYOUT".equals(instruction) && !canRollOver(d, p)) {
            instruction = "PAYOUT";
        }
        BigDecimal newPrincipal = switch (instruction) {
            case "ROLLOVER_PRINCIPAL" -> d.principal();
            case "ROLLOVER_ALL" -> d.principal().add(net);
            default -> BigDecimal.ZERO;
        };
        BigDecimal toSavings = switch (instruction) {
            case "ROLLOVER_PRINCIPAL" -> net;
            case "ROLLOVER_ALL" -> BigDecimal.ZERO;
            default -> d.principal().add(net);
        };
        List<JournalLine> lines = new ArrayList<>();
        BigDecimal principalChange = newPrincipal.subtract(d.principal());
        if (principalChange.signum() < 0) {
            lines.add(JournalLine.debit(p.principal(), money(principalChange.negate(), d.currency()), d.branch()));
        } else if (principalChange.signum() > 0) {
            lines.add(JournalLine.credit(p.principal(), money(principalChange, d.currency()), d.branch()));
        }
        if (accrued.signum() > 0) {
            lines.add(JournalLine.debit(p.payable(), money(accrued, d.currency()), d.branch()));
        }
        BigDecimal expense = accrued.subtract(earned);
        if (expense.signum() > 0) {
            lines.add(JournalLine.credit(p.expense(), money(expense, d.currency()), d.branch()));
        } else if (expense.signum() < 0) {
            lines.add(JournalLine.debit(p.expense(), money(expense.negate(), d.currency()), d.branch()));
        }
        if (tax.signum() > 0) {
            lines.add(JournalLine.credit(p.tax(), money(tax, d.currency()), d.branch()));
        }
        if (toSavings.signum() > 0) {
            lines.add(JournalLine.onAccount(d.control(), Side.CREDIT, money(toSavings, d.currency()), d.branch(), d.accountId()));
        }
        UUID entryId = null;
        if (!lines.isEmpty()) {
            String key = "termdep:close:" + d.id();
            entryId = uuid(tenantId + "|" + key);
            journal.post(new JournalEntry(entryId, tenantId, key, today, "TERMDEP", "fd:" + d.number(), null, null, lines), actor);
        }
        String newNumber = null;
        UUID newId = null;
        if (newPrincipal.signum() > 0) {
            newNumber = insertDeposit(tenantId, "rollover:" + d.id(), d.customerId(), p, d.accountId(), d.branch(), newPrincipal,
                    d.maturity(), d.instruction(), entryId, d.id(), actor);
            newId = uuid(tenantId + "|termdep-deposit|rollover:" + d.id());
        }
        jdbc.sql("""
                INSERT INTO termdep.closure (tenant_id, deposit_id, kind, business_date, nights, rate_applied, interest_earned,
                    accrued_before, tax, net_interest, to_savings, new_principal, new_deposit_id, early_request_id, entry_id, created_by)
                VALUES (tenancy.current_tenant(), :d, :kind, :date, :nights, :rate, :earned, :accrued, :tax, :net, :savings, :np, :nd,
                        :req, :entry, :actor)
                """)
            .param("d", d.id()).param("kind", kind).param("date", today).param("nights", nights).param("rate", rate)
            .param("earned", earned).param("accrued", accrued).param("tax", tax).param("net", net).param("savings", toSavings)
            .param("np", newPrincipal).param("nd", newId).param("req", requestId).param("entry", entryId).param("actor", actor)
            .update();
        String status = early ? "WITHDRAWN_EARLY" : newPrincipal.signum() > 0 ? "ROLLED_OVER" : "MATURED";
        jdbc.sql("UPDATE termdep.deposit SET status = :s WHERE deposit_id = :id").param("s", status).param("id", d.id()).update();
        return new ClosureResult(d.number(), kind, toSavings, newPrincipal, newNumber);
    }

    private boolean canRollOver(Deposit d, Product p) {
        return "ACTIVE".equals(p.status()) && jdbc.sql("""
                SELECT a.status = 'ACTIVE' AND c.status = 'ACTIVE' FROM savings.deposit_account a
                  JOIN customer.customer c ON c.tenant_id = a.tenant_id AND c.customer_id = a.customer_id
                 WHERE a.account_id = :a
                """).param("a", d.accountId()).query(Boolean.class).single();
    }

    // ------------------------------------------------------------------ reading

    public Deposit deposit(UUID id, boolean lock) {
        return jdbc.sql("""
                SELECT d.*, a.gl_control_account FROM termdep.deposit d
                  JOIN savings.deposit_account a ON a.tenant_id = d.tenant_id AND a.account_id = d.savings_account_id
                 WHERE d.deposit_id = :id
                """ + (lock ? " FOR UPDATE OF d" : "")).param("id", id)
            .query((rs, n) -> new Deposit(rs.getObject("deposit_id", UUID.class), rs.getString("deposit_number"),
                    rs.getObject("customer_id", UUID.class), rs.getString("product_code"),
                    rs.getObject("savings_account_id", UUID.class), rs.getString("gl_control_account"), rs.getString("branch_code"),
                    rs.getString("currency"), rs.getBigDecimal("principal"), rs.getBigDecimal("rate_pct"),
                    rs.getBigDecimal("early_rate_pct"), rs.getBoolean("early_withdrawal_allowed"), rs.getBigDecimal("tax_rate_pct"),
                    rs.getObject("start_date", LocalDate.class), rs.getObject("maturity_date", LocalDate.class),
                    rs.getString("maturity_instruction"), rs.getString("status")))
            .optional().orElseThrow(() -> new NotFoundException("fixed deposit not found"));
    }

    private UUID depositId(String number) {
        return jdbc.sql("SELECT deposit_id FROM termdep.deposit WHERE deposit_number = :n").param("n", number).query(UUID.class)
            .optional().orElseThrow(() -> new NotFoundException("fixed deposit " + number + " not found"));
    }

    private EarlyWithdrawalView request(UUID id) {
        return jdbc.sql(REQUEST + " WHERE r.request_id = :id").param("id", id).query((rs, n) -> requestView(rs)).single();
    }

    private static final String VIEW = """
            SELECT d.*, c.customer_number, c.first_name || ' ' || c.last_name AS member_name, a.account_number,
                   (SELECT COALESCE(sum(x.posted), 0) FROM termdep.accrual x WHERE x.tenant_id = d.tenant_id AND x.deposit_id = d.deposit_id
                       AND d.status = 'ACTIVE') AS accrued,
                   f.deposit_number AS from_number
              FROM termdep.deposit d
              JOIN customer.customer c ON c.tenant_id = d.tenant_id AND c.customer_id = d.customer_id
              JOIN savings.deposit_account a ON a.tenant_id = d.tenant_id AND a.account_id = d.savings_account_id
              LEFT JOIN termdep.deposit f ON f.tenant_id = d.tenant_id AND f.deposit_id = d.rolled_from
            """;

    private static FixedDepositView view(ResultSet rs) throws SQLException {
        return new FixedDepositView(rs.getObject("deposit_id", UUID.class), rs.getString("deposit_number"),
                rs.getString("customer_number"), rs.getString("member_name"), rs.getString("product_code"),
                rs.getString("account_number"), rs.getString("branch_code"), rs.getString("currency"), m(rs.getBigDecimal("principal")),
                pct(rs.getBigDecimal("rate_pct")), pct(rs.getBigDecimal("early_rate_pct")), rs.getBoolean("early_withdrawal_allowed"),
                pct(rs.getBigDecimal("tax_rate_pct")), rs.getInt("term_months"), rs.getObject("start_date", LocalDate.class),
                rs.getObject("maturity_date", LocalDate.class), rs.getString("maturity_instruction"), rs.getString("status"),
                m(rs.getBigDecimal("accrued")), rs.getString("from_number"), rs.getString("created_by"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private static final String REQUEST = """
            SELECT r.*, d.deposit_number, d.principal, d.maturity_date, d.start_date, d.early_rate_pct, d.tax_rate_pct, d.status AS deposit_status,
                   c.customer_number, c.first_name || ' ' || c.last_name AS member_name,
                   (SELECT current_date_ FROM tenancy.business_date) AS today
              FROM termdep.early_request r
              JOIN termdep.deposit d ON d.tenant_id = r.tenant_id AND d.deposit_id = r.deposit_id
              JOIN customer.customer c ON c.tenant_id = d.tenant_id AND c.customer_id = d.customer_id
            """;

    private static EarlyWithdrawalView requestView(ResultSet rs) throws SQLException {
        FixedDepositQuote q = null;
        if ("REQUESTED".equals(rs.getString("status")) && "ACTIVE".equals(rs.getString("deposit_status"))) {
            BigDecimal principal = rs.getBigDecimal("principal");
            int nights = nights(rs.getObject("start_date", LocalDate.class), rs.getObject("today", LocalDate.class));
            q = quote(principal, rs.getBigDecimal("early_rate_pct"), rs.getBigDecimal("tax_rate_pct"), nights);
        }
        return new EarlyWithdrawalView(rs.getObject("request_id", UUID.class), rs.getString("deposit_number"),
                rs.getString("customer_number"), rs.getString("member_name"), m(rs.getBigDecimal("principal")),
                rs.getObject("maturity_date", LocalDate.class), rs.getString("reason"), rs.getString("status"),
                rs.getString("requested_by"), rs.getObject("requested_at", OffsetDateTime.class), rs.getString("decided_by"),
                rs.getObject("decided_at", OffsetDateTime.class), rs.getString("decision_reason"), q);
    }

    private static FixedDepositProductView productView(ResultSet rs) throws SQLException {
        BigDecimal max = rs.getBigDecimal("max_amount");
        return new FixedDepositProductView(rs.getString("product_code"), rs.getString("name"), rs.getString("currency"),
                rs.getInt("term_months"), pct(rs.getBigDecimal("rate_pct")), pct(rs.getBigDecimal("early_rate_pct")),
                rs.getBoolean("early_withdrawal_allowed"), m(rs.getBigDecimal("min_amount")), max == null ? null : m(max),
                pct(rs.getBigDecimal("tax_rate_pct")), rs.getString("gl_principal"), rs.getString("gl_interest_payable"),
                rs.getString("gl_interest_expense"), rs.getString("gl_tax"), rs.getString("status"), rs.getString("updated_by"),
                rs.getObject("updated_at", OffsetDateTime.class), rs.getInt("active"));
    }

    // ------------------------------------------------------------------ arithmetic (mirrors V38)

    static int nights(LocalDate from, LocalDate to) {
        return (int) Math.max(0, to.toEpochDay() - from.toEpochDay());
    }

    /** round(principal x rate x nights / 36500, 2). */
    static BigDecimal interest(BigDecimal principal, BigDecimal ratePct, int nights) {
        return principal.multiply(ratePct).multiply(BigDecimal.valueOf(nights)).divide(BigDecimal.valueOf(36500), 2, RoundingMode.HALF_UP);
    }

    private static FixedDepositQuote quote(Deposit d, BigDecimal rate, int nights, BigDecimal principal) {
        FixedDepositQuote q = quote(principal, rate, d.taxRate(), nights);
        return q;
    }

    private static FixedDepositQuote quote(BigDecimal principal, BigDecimal rate, BigDecimal taxRate, int nights) {
        BigDecimal interest = interest(principal, rate, nights);
        BigDecimal tax = interest.multiply(taxRate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal net = interest.subtract(tax);
        return new FixedDepositQuote(nights, pct(rate), m(interest), m(tax), m(net), m(principal.add(net)));
    }

    static UUID uuid(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    static Money money(BigDecimal amount, String currency) {
        Currency ccy = Currency.getInstance(currency);
        return Money.of(amount.setScale(ccy.getDefaultFractionDigits(), RoundingMode.UNNECESSARY), ccy);
    }

    static String m(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    static String pct(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 2 ? v.setScale(2, RoundingMode.HALF_UP).toPlainString() : s.toPlainString();
    }
}
