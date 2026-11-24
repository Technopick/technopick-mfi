package ke.co.technopick.mfi.core.lending.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ke.co.technopick.mfi.canonical.Money;
import ke.co.technopick.mfi.core.accounting.application.JournalPosting;
import ke.co.technopick.mfi.core.accounting.domain.JournalEntry;
import ke.co.technopick.mfi.core.accounting.domain.JournalLine;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Interest in suspense on non-performing loans (Reg 42 of LN 95/2010; ADR-0031). The tenant switches
 * it on and names the suspense account (a contra asset). A loan's suspense is brought to its
 * target with one journal entry and one {@code lending.interest_suspense} movement per account:
 * <ul>
 *   <li>at end of day: the loan's unpaid accrued interest if it is more than the watch band's days
 *       past due (substandard or worse), otherwise zero. Interest paid since the last close and a loan
 *       becoming performing again both release suspense to interest income;</li>
 *   <li>at closure: zero, released to interest income (everything has been paid);</li>
 *   <li>at write-off: zero, released against the write-off expense, because suspended interest
 *       was never income.</li>
 * </ul>
 * Runs inside the caller's tenant-bound transaction; the database checks every movement (V28).
 */
@Service
public class InterestSuspenseService {

    public enum Reason { END_OF_DAY, CLOSURE, WRITE_OFF }

    private final JdbcClient jdbc;
    private final JournalPosting journal;

    public InterestSuspenseService(JdbcClient jdbc, JournalPosting journal) {
        this.jdbc = jdbc;
        this.journal = journal;
    }

    private record LoanGl(String number, String branch, Currency currency, String income, String writeOffExpense) { }

    private record Policy(boolean enabled, String account, int watchMaxDpd, boolean loanHasSuspense) { }

    /** One round trip: the tenant's policy and whether this loan has ever had interest in suspense. */
    private Policy policy(UUID loanId) {
        return jdbc.sql("""
                SELECT COALESCE(p.interest_suspension_enabled, false), p.interest_suspense_account, COALESCE(p.watch_max_dpd, 30),
                       EXISTS (SELECT 1 FROM lending.interest_suspense m WHERE m.loan_id = :loan)
                  FROM (VALUES (1)) AS one LEFT JOIN ledger.prudential_settings p ON true
                """).param("loan", loanId)
            .query((rs, i) -> new Policy(rs.getBoolean(1), rs.getString(2), rs.getInt(3), rs.getBoolean(4))).single();
    }

    /** End of day, after the loan's arrears snapshot: suspense follows the loan's classification. */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal adjustAtEndOfDay(UUID tenantId, UUID loanId, LocalDate date, long daysPastDue, String actor) {
        Policy p = policy(loanId);
        boolean suspend = p.enabled() && daysPastDue > p.watchMaxDpd();
        if (!suspend && !p.loanHasSuspense()) {
            return BigDecimal.ZERO; // the common case: nothing held and nothing to hold
        }
        Map<String, BigDecimal> target = new LinkedHashMap<>();
        if (suspend) {
            BigDecimal unpaid = jdbc.sql("SELECT lending.loan_interest_receivable(:t, :loan)").param("t", tenantId)
                .param("loan", loanId).query(BigDecimal.class).single();
            target.put(p.account(), unpaid);
        }
        return bringTo(tenantId, loanId, date, target, Reason.END_OF_DAY, actor);
    }

    /** Closure or write-off: nothing stays in suspense. */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal releaseAll(UUID tenantId, UUID loanId, LocalDate date, Reason reason, String actor) {
        return bringTo(tenantId, loanId, date, Map.of(), reason, actor);
    }

    /** @return the net amount moved into suspense (negative when released) */
    private BigDecimal bringTo(UUID tenantId, UUID loanId, LocalDate date, Map<String, BigDecimal> target, Reason reason,
                               String actor) {
        Map<String, BigDecimal> current = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT account_code, sum(amount) FROM lending.interest_suspense WHERE loan_id = :loan
                 GROUP BY account_code HAVING sum(amount) <> 0 ORDER BY account_code
                """).param("loan", loanId)
            .query((rs, i) -> current.put(rs.getString(1), rs.getBigDecimal(2))).list();
        if (current.isEmpty() && target.values().stream().allMatch(v -> v.signum() == 0)) {
            return BigDecimal.ZERO;
        }
        LoanGl gl = jdbc.sql("""
                SELECT l.loan_number, l.branch_code, l.currency, v.gl_interest_income, v.gl_write_off
                  FROM lending.loan l JOIN lending.loan_product_version v
                    ON v.tenant_id = l.tenant_id AND v.version_id = l.product_version_id
                 WHERE l.loan_id = :loan
                """).param("loan", loanId)
            .query((rs, i) -> new LoanGl(rs.getString(1), rs.getString(2), Currency.getInstance(rs.getString(3)), rs.getString(4),
                    rs.getString(5)))
            .single();
        String other = reason == Reason.WRITE_OFF ? gl.writeOffExpense() : gl.income();
        Map<String, BigDecimal> accounts = new LinkedHashMap<>(current);
        target.keySet().forEach(a -> accounts.putIfAbsent(a, BigDecimal.ZERO));
        BigDecimal moved = BigDecimal.ZERO;
        for (String account : accounts.keySet()) {
            BigDecimal diff = target.getOrDefault(account, BigDecimal.ZERO).subtract(current.getOrDefault(account, BigDecimal.ZERO));
            if (diff.signum() == 0) {
                continue;
            }
            Money amount = Money.of(diff.abs(), gl.currency());
            List<JournalLine> lines = diff.signum() > 0
                    ? List.of(JournalLine.debit(other, amount, gl.branch()), JournalLine.credit(account, amount, gl.branch()))
                    : List.of(JournalLine.debit(account, amount, gl.branch()), JournalLine.credit(other, amount, gl.branch()));
            String key = "loan:" + loanId + ":SUSPENSE:" + reason + ":" + date + ":" + account;
            UUID entryId = UUID.nameUUIDFromBytes((tenantId + "|" + key).getBytes(StandardCharsets.UTF_8));
            journal.post(new JournalEntry(entryId, tenantId, key, date, "LOAN", "suspense:" + gl.number(), null, null, lines), actor);
            jdbc.sql("""
                    INSERT INTO lending.interest_suspense (tenant_id, loan_id, business_date, account_code, amount, reason, entry_id)
                    VALUES (:t, :loan, :d, :a, :amount, :reason, :entry)
                    """).param("t", tenantId).param("loan", loanId).param("d", date).param("a", account).param("amount", diff)
                .param("reason", reason.name()).param("entry", entryId).update();
            moved = moved.add(diff);
        }
        return moved;
    }
}
