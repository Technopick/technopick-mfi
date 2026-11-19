package ke.co.technopick.mfi.core.accounting.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import ke.co.technopick.mfi.core.tenancy.persistence.TenantSession;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Subledger-to-control reconciliation (ADR-0024). Each check compares a control account's ledger
 * balance with the same figure built independently from the business records:
 * <ul>
 *   <li>SAVINGS_CONTROL - sum of deposit account balances per control account;</li>
 *   <li>SAVINGS_INTEREST_PAYABLE - interest accrued (V18) less interest credited, per payable account;</li>
 *   <li>LOAN_PRINCIPAL - principal disbursed less principal repaid and written off;</li>
 *   <li>LOAN_INTEREST_RECEIVABLE - per installment, interest accrued less paid, less amounts written off;</li>
 *   <li>LOAN_PENALTY_RECEIVABLE - penalties charged less paid and written off;</li>
 *   <li>LOAN_OVERPAYMENT - credit held for customers (overpayments less credit applied).</li>
 * </ul>
 * A difference means a posting reached the ledger without its business record (or the reverse),
 * and finance must investigate. Balances are in the account's natural sign.
 */
@Service
public class ReconciliationService {

    public record CheckResult(String checkCode, String accountCode, String currency, String ledgerBalance,
                              String subledgerBalance, String difference, int items, boolean reconciled) { }

    public record Reconciliation(LocalDate businessDate, boolean recorded, OffsetDateTime checkedAt, List<CheckResult> results,
                                 int differences) { }

    private static final String CHECKS = """
            WITH gl AS (
                SELECT l.account_code, sum(CASE l.side WHEN 'D' THEN l.amount ELSE -l.amount END) AS debit_positive
                  FROM ledger.journal_line l GROUP BY l.account_code),
            loans AS (
                SELECT l.tenant_id, l.loan_id, l.currency, v.gl_principal, v.gl_interest_receivable, v.gl_penalty_receivable,
                       v.gl_overpayment, w.principal AS wo_principal, w.interest AS wo_interest, w.penalty AS wo_penalty
                  FROM lending.loan l
                  JOIN lending.loan_product_version v ON v.tenant_id = l.tenant_id AND v.version_id = l.product_version_id
                  LEFT JOIN lending.write_off w ON w.tenant_id = l.tenant_id AND w.loan_id = l.loan_id AND w.status = 'APPROVED'
                 WHERE l.status <> 'APPROVED'),
            sub AS (
                SELECT 'SAVINGS_CONTROL' AS check_code, a.gl_control_account AS account_code, a.currency,
                       sum(b.ledger_balance) AS amount, count(*) AS items
                  FROM savings.deposit_account a
                  JOIN savings.account_balance b ON b.tenant_id = a.tenant_id AND b.account_id = a.account_id
                 GROUP BY a.gl_control_account, a.currency
                UNION ALL
                SELECT 'SAVINGS_INTEREST_PAYABLE', p.gl_interest_payable, p.currency,
                       COALESCE((SELECT sum(i.posted) FROM savings.interest_accrual i
                                   JOIN savings.deposit_product q ON q.tenant_id = i.tenant_id AND q.product_code = i.product_code
                                  WHERE q.gl_interest_payable = p.gl_interest_payable), 0)
                     - COALESCE((SELECT sum(x.gross) FROM savings.interest_posting x
                                   JOIN savings.deposit_account a ON a.tenant_id = x.tenant_id AND a.account_id = x.account_id
                                   JOIN savings.deposit_product q ON q.tenant_id = a.tenant_id AND q.product_code = a.product_code
                                  WHERE q.gl_interest_payable = p.gl_interest_payable), 0),
                       (SELECT count(DISTINCT i.account_id) FROM savings.interest_accrual i
                          JOIN savings.deposit_product q ON q.tenant_id = i.tenant_id AND q.product_code = i.product_code
                         WHERE q.gl_interest_payable = p.gl_interest_payable)
                  FROM (SELECT DISTINCT gl_interest_payable, currency FROM savings.deposit_product
                         WHERE gl_interest_payable IS NOT NULL) p
                UNION ALL
                SELECT 'LOAN_PRINCIPAL', gl_principal, currency,
                       sum(lending.loan_principal_outstanding(tenant_id, loan_id) - COALESCE(wo_principal, 0)), count(*)
                  FROM loans GROUP BY gl_principal, currency
                UNION ALL
                SELECT 'LOAN_INTEREST_RECEIVABLE', gl_interest_receivable, currency,
                       sum(lending.loan_interest_receivable(tenant_id, loan_id) - COALESCE(wo_interest, 0)), count(*)
                  FROM loans GROUP BY gl_interest_receivable, currency
                UNION ALL
                SELECT 'LOAN_PENALTY_RECEIVABLE', gl_penalty_receivable, currency,
                       sum(lending.loan_penalty_unpaid(tenant_id, loan_id) - COALESCE(wo_penalty, 0)), count(*)
                  FROM loans GROUP BY gl_penalty_receivable, currency
                UNION ALL
                SELECT 'LOAN_OVERPAYMENT', gl_overpayment, currency, sum(lending.loan_credit(tenant_id, loan_id)), count(*)
                  FROM loans GROUP BY gl_overpayment, currency)
            SELECT s.check_code, s.account_code, s.currency, s.amount AS subledger, s.items,
                   CASE WHEN a.account_class IN ('ASSET', 'EXPENSE') THEN COALESCE(g.debit_positive, 0)
                        ELSE -COALESCE(g.debit_positive, 0) END AS ledger
              FROM sub s
              JOIN ledger.gl_account a ON a.account_code = s.account_code
              LEFT JOIN gl g ON g.account_code = s.account_code
             ORDER BY s.check_code, s.account_code
            """;

    private final JdbcClient jdbc;
    private final TenantSession session;

    public ReconciliationService(JdbcClient jdbc, TenantSession session) {
        this.jdbc = jdbc;
        this.session = session;
    }

    /** Runs the checks now, inside the caller's tenant-bound transaction. */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<CheckResult> check() {
        return jdbc.sql(CHECKS)
            .query((rs, i) -> {
                BigDecimal ledger = rs.getBigDecimal("ledger");
                BigDecimal sub = rs.getBigDecimal("subledger");
                BigDecimal diff = ledger.subtract(sub);
                return new CheckResult(rs.getString("check_code"), rs.getString("account_code"), rs.getString("currency"),
                        plain(ledger), plain(sub), plain(diff), rs.getInt("items"), diff.signum() == 0);
            })
            .list();
    }

    /** End of day: run the checks and record them for the business date being closed. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<CheckResult> record(UUID tenantId, LocalDate businessDate) {
        List<CheckResult> results = check();
        for (CheckResult r : results) {
            jdbc.sql("""
                    INSERT INTO ledger.reconciliation_result (tenant_id, business_date, check_code, account_code, currency,
                        ledger_balance, subledger_balance, items)
                    VALUES (:t, :d, :c, :a, :ccy, :l, :s, :n)
                    ON CONFLICT DO NOTHING
                    """)
                .param("t", tenantId).param("d", businessDate).param("c", r.checkCode()).param("a", r.accountCode())
                .param("ccy", r.currency()).param("l", new BigDecimal(r.ledgerBalance()))
                .param("s", new BigDecimal(r.subledgerBalance())).param("n", r.items()).update();
        }
        return results;
    }

    /** Live check now, or the results recorded by the end of day of a past date. */
    @Transactional(readOnly = true)
    public Reconciliation view(UUID tenantId, LocalDate businessDate) {
        session.bind(tenantId);
        if (businessDate == null) {
            List<CheckResult> live = check();
            return new Reconciliation(null, false, OffsetDateTime.now(), live,
                    (int) live.stream().filter(r -> !r.reconciled()).count());
        }
        List<CheckResult> stored = jdbc.sql("""
                SELECT check_code, account_code, currency, ledger_balance, subledger_balance, difference, items
                  FROM ledger.reconciliation_result WHERE business_date = :d ORDER BY check_code, account_code
                """)
            .param("d", businessDate)
            .query((rs, i) -> new CheckResult(rs.getString(1), rs.getString(2), rs.getString(3), plain(rs.getBigDecimal(4)),
                    plain(rs.getBigDecimal(5)), plain(rs.getBigDecimal(6)), rs.getInt(7), rs.getBigDecimal(6).signum() == 0))
            .list();
        OffsetDateTime at = jdbc.sql("SELECT max(checked_at) FROM ledger.reconciliation_result WHERE business_date = :d")
            .param("d", businessDate).query(OffsetDateTime.class).optional().orElse(null);
        return new Reconciliation(businessDate, true, at, stored, (int) stored.stream().filter(r -> !r.reconciled()).count());
    }

    private static String plain(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }
}
