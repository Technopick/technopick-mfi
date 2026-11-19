package ke.co.technopick.mfi.core.savings.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import ke.co.technopick.mfi.canonical.Money;
import ke.co.technopick.mfi.core.accounting.application.JournalPosting;
import ke.co.technopick.mfi.core.accounting.domain.JournalEntry;
import ke.co.technopick.mfi.core.accounting.domain.JournalLine;
import ke.co.technopick.mfi.core.accounting.domain.Side;
import ke.co.technopick.mfi.core.messaging.persistence.Outbox;
import ke.co.technopick.mfi.core.savings.domain.SavingsInterest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Savings interest during end of day (ADR-0019). Every method runs inside the caller's
 * tenant-bound transaction; the end-of-day job gives each product/branch group and each account
 * posting its own transaction so a failure stops the run without half-written groups. The
 * database (V18) re-checks balances, rates, rounding, tax and entry amounts.
 */
@Service
public class SavingsInterestService {

    public record AccrualGroup(String productCode, String branchCode) { }

    public record AccrualResult(int accounts, BigDecimal amount) { }

    public record PostingResult(BigDecimal gross, BigDecimal tax, BigDecimal net) { }

    private record Eligible(UUID accountId, BigDecimal balance, BigDecimal periodExact, BigDecimal periodPosted) { }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final JdbcClient jdbc;
    private final JournalPosting journal;
    private final Outbox outbox;

    public SavingsInterestService(JdbcClient jdbc, JournalPosting journal, Outbox outbox) {
        this.jdbc = jdbc;
        this.journal = journal;
        this.outbox = outbox;
    }

    /** Product and branch combinations whose accounts earn interest. */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<AccrualGroup> accrualGroups() {
        return jdbc.sql("""
                SELECT DISTINCT a.product_code, a.branch_code
                  FROM savings.deposit_account a
                  JOIN savings.deposit_product p ON p.tenant_id = a.tenant_id AND p.product_code = a.product_code
                 WHERE p.interest_rate > 0 AND a.status <> 'CLOSED'
                 ORDER BY 1, 2
                """)
            .query((rs, i) -> new AccrualGroup(rs.getString(1), rs.getString(2))).list();
    }

    /** Accrues one day's interest for one product at one branch. Already-accrued accounts are skipped. */
    @Transactional(propagation = Propagation.MANDATORY)
    public AccrualResult accrue(UUID tenantId, AccrualGroup group, LocalDate date, String actor) {
        record Product(BigDecimal rate, BigDecimal minBalance, String expense, String payable, String currency) { }
        Product p = jdbc.sql("""
                SELECT interest_rate, interest_min_balance, gl_interest_expense, gl_interest_payable, currency
                  FROM savings.deposit_product WHERE product_code = :p
                """)
            .param("p", group.productCode())
            .query((rs, i) -> new Product(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getString(3), rs.getString(4),
                    rs.getString(5)))
            .single();
        List<Eligible> eligible = jdbc.sql("""
                SELECT a.account_id, bal.balance, COALESCE(prev.exact, 0) AS exact, COALESCE(prev.posted, 0) AS posted
                  FROM savings.deposit_account a
                  CROSS JOIN LATERAL (
                        SELECT COALESCE(sum(CASE l.side WHEN 'C' THEN l.amount ELSE -l.amount END), 0) AS balance
                          FROM ledger.journal_line l
                          JOIN ledger.journal_entry e ON e.tenant_id = l.tenant_id AND e.entry_id = l.entry_id
                         WHERE l.tenant_id = a.tenant_id AND l.subledger_account_id = a.account_id
                           AND e.business_date <= :d) bal
                  CROSS JOIN LATERAL (
                        SELECT sum(i.daily_exact) AS exact, sum(i.posted) AS posted
                          FROM savings.interest_accrual i
                         WHERE i.tenant_id = a.tenant_id AND i.account_id = a.account_id
                           AND i.business_date > COALESCE((SELECT max(x.period_end) FROM savings.interest_posting x
                                                            WHERE x.tenant_id = a.tenant_id AND x.account_id = a.account_id),
                                                          '-infinity'::date)) prev
                 WHERE a.product_code = :p AND a.branch_code = :b AND a.status <> 'CLOSED'
                   AND bal.balance > 0 AND bal.balance >= :min
                   AND NOT EXISTS (SELECT 1 FROM savings.interest_accrual d
                                    WHERE d.tenant_id = a.tenant_id AND d.account_id = a.account_id AND d.business_date = :d)
                 ORDER BY a.account_number
                """)
            .param("d", date).param("p", group.productCode()).param("b", group.branchCode()).param("min", p.minBalance())
            .query((rs, i) -> new Eligible(rs.getObject("account_id", UUID.class), rs.getBigDecimal("balance"),
                    rs.getBigDecimal("exact"), rs.getBigDecimal("posted")))
            .list();
        if (eligible.isEmpty()) {
            return new AccrualResult(0, BigDecimal.ZERO.setScale(2));
        }
        List<BigDecimal> today = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (Eligible e : eligible) {
            BigDecimal post = SavingsInterest.postToday(e.periodExact(), e.periodPosted(),
                    SavingsInterest.daily(e.balance(), p.rate()));
            today.add(post);
            total = total.add(post);
        }
        UUID entryId = null;
        if (total.signum() > 0) {
            String key = "savings-accrual:" + group.productCode() + ":" + group.branchCode() + ":" + date;
            entryId = entryId(tenantId, key);
            Money amount = Money.of(total, Currency.getInstance(p.currency()));
            journal.post(new JournalEntry(entryId, tenantId, key, date, "EOD", "eod:" + date, null, null, List.of(
                    JournalLine.debit(p.expense(), amount, group.branchCode()),
                    JournalLine.credit(p.payable(), amount, group.branchCode()))), actor);
        }
        for (int i = 0; i < eligible.size(); i++) {
            Eligible e = eligible.get(i);
            BigDecimal post = today.get(i);
            jdbc.sql("""
                    INSERT INTO savings.interest_accrual (tenant_id, account_id, business_date, product_code, branch_code,
                        balance, rate, posted, entry_id)
                    VALUES (:tenant, :account, :d, :p, :b, :balance, :rate, :posted, :entry)
                    """)
                .param("tenant", tenantId).param("account", e.accountId()).param("d", date).param("p", group.productCode())
                .param("b", group.branchCode()).param("balance", e.balance()).param("rate", p.rate()).param("posted", post)
                .param("entry", post.signum() > 0 ? entryId : null).update();
        }
        return new AccrualResult(eligible.size(), total);
    }

    /** Accounts with accrued, unpaid interest to credit today. */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<UUID> accountsToCredit(LocalDate date) {
        return jdbc.sql("""
                SELECT i.account_id
                  FROM savings.interest_accrual i
                  JOIN savings.deposit_account a ON a.tenant_id = i.tenant_id AND a.account_id = i.account_id
                 WHERE a.status <> 'CLOSED' AND i.business_date <= :d
                   AND i.business_date > COALESCE((SELECT max(x.period_end) FROM savings.interest_posting x
                                                    WHERE x.tenant_id = i.tenant_id AND x.account_id = i.account_id),
                                                  '-infinity'::date)
                 GROUP BY i.account_id, a.account_number
                HAVING sum(i.posted) > 0
                 ORDER BY a.account_number
                """)
            .param("d", date).query(UUID.class).list();
    }

    /** Credits one account with its period's interest, less withholding tax. */
    @Transactional(propagation = Propagation.MANDATORY)
    public PostingResult credit(UUID tenantId, UUID accountId, LocalDate date, String actor) {
        record Acc(String number, String branch, String control, String currency, BigDecimal taxRate, String payable,
                   String taxGl) { }
        Acc a = jdbc.sql("""
                SELECT a.account_number, a.branch_code, a.gl_control_account, a.currency, p.withholding_tax_rate,
                       p.gl_interest_payable, p.gl_withholding_tax
                  FROM savings.deposit_account a
                  JOIN savings.deposit_product p ON p.tenant_id = a.tenant_id AND p.product_code = a.product_code
                 WHERE a.account_id = :id FOR UPDATE OF a
                """)
            .param("id", accountId)
            .query((rs, i) -> new Acc(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getBigDecimal(5), rs.getString(6), rs.getString(7)))
            .single();
        record Period(LocalDate start, BigDecimal gross) { }
        Period period = jdbc.sql("""
                SELECT min(business_date), sum(posted) FROM savings.interest_accrual i
                 WHERE i.account_id = :id AND i.business_date <= :d
                   AND i.business_date > COALESCE((SELECT max(x.period_end) FROM savings.interest_posting x
                                                    WHERE x.tenant_id = i.tenant_id AND x.account_id = i.account_id),
                                                  '-infinity'::date)
                """)
            .param("id", accountId).param("d", date)
            .query((rs, i) -> new Period(rs.getObject(1, LocalDate.class), rs.getBigDecimal(2))).single();
        LocalDate lastEnd = jdbc.sql("SELECT max(period_end) FROM savings.interest_posting WHERE account_id = :id")
            .param("id", accountId).query(LocalDate.class).optional().orElse(null);
        LocalDate start = lastEnd == null ? period.start() : lastEnd.plusDays(1);
        BigDecimal gross = period.gross().setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal tax = SavingsInterest.tax(gross, a.taxRate());
        BigDecimal net = gross.subtract(tax);
        Currency ccy = Currency.getInstance(a.currency());
        String key = "savings-interest:" + accountId + ":" + date;
        UUID entryId = entryId(tenantId, key);
        List<JournalLine> lines = new ArrayList<>();
        lines.add(JournalLine.debit(a.payable(), Money.of(gross, ccy), a.branch()));
        if (net.signum() > 0) {
            String narrative = "Interest " + DAY.format(start) + " to " + DAY.format(date)
                    + (tax.signum() > 0 ? " (gross " + gross.toPlainString() + ", tax " + tax.toPlainString() + ")" : "");
            lines.add(new JournalLine(a.control(), Side.CREDIT, Money.of(net, ccy), a.branch(), narrative, accountId));
        }
        if (tax.signum() > 0) {
            lines.add(JournalLine.credit(a.taxGl(), Money.of(tax, ccy), a.branch()));
        }
        journal.post(new JournalEntry(entryId, tenantId, key, date, "EOD", "eod:" + date, null, null, lines), actor);
        jdbc.sql("""
                INSERT INTO savings.interest_posting (tenant_id, posting_id, account_id, period_start, period_end, gross, tax,
                    net, tax_rate, entry_id)
                VALUES (:tenant, :id, :account, :start, :end, :gross, :tax, :net, :rate, :entry)
                """)
            .param("tenant", tenantId).param("id", UUID.randomUUID()).param("account", accountId).param("start", start)
            .param("end", date).param("gross", gross).param("tax", tax).param("net", net).param("rate", a.taxRate())
            .param("entry", entryId).update();
        outbox.record(tenantId, "SavingsInterestPosted", "interest_posting", accountId + ":" + date,
                "{\"accountNumber\":\"%s\",\"periodStart\":\"%s\",\"periodEnd\":\"%s\",\"gross\":\"%s\",\"tax\":\"%s\",\"net\":\"%s\",\"currency\":\"%s\"}"
                        .formatted(a.number(), start, date, gross.toPlainString(), tax.toPlainString(), net.toPlainString(),
                                a.currency()),
                "eod:" + date);
        return new PostingResult(gross, tax, net);
    }

    private static UUID entryId(UUID tenantId, String key) {
        return UUID.nameUUIDFromBytes((tenantId + "|" + key).getBytes(StandardCharsets.UTF_8));
    }
}
