package ke.co.technopick.mfi.core.termdeposits.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ke.co.technopick.mfi.core.accounting.application.JournalPosting;
import ke.co.technopick.mfi.core.accounting.domain.JournalEntry;
import ke.co.technopick.mfi.core.accounting.domain.JournalLine;
import ke.co.technopick.mfi.core.termdeposits.application.FixedDepositService.ClosureResult;
import ke.co.technopick.mfi.core.termdeposits.application.FixedDepositService.Deposit;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Fixed deposits at the close of a business date (ADR-0041). The caller runs each method in its own
 * transaction with the tenant bound: interest accrues per product and branch (one entry per group);
 * then every deposit maturing on the next date is closed. Both are safe to repeat after a crash: a
 * group already accrued today, or a deposit already closed, is not found again.
 */
@Component
public class FixedDepositEndOfDayStep {

    public record AccrualGroup(String productCode, String branchCode) { }

    public record AccrualResult(int deposits, BigDecimal amount) { }

    private final JdbcClient jdbc;
    private final JournalPosting journal;
    private final FixedDepositService deposits;

    public FixedDepositEndOfDayStep(JdbcClient jdbc, JournalPosting journal, FixedDepositService deposits) {
        this.jdbc = jdbc;
        this.journal = journal;
        this.deposits = deposits;
    }

    /** Product and branch groups with active deposits not yet accrued for {@code date}. */
    public List<AccrualGroup> accrualGroups(LocalDate date) {
        return jdbc.sql("""
                SELECT DISTINCT d.product_code, d.branch_code FROM termdep.deposit d
                 WHERE d.status = 'ACTIVE' AND d.start_date <= :d
                   AND NOT EXISTS (SELECT 1 FROM termdep.accrual_batch b WHERE b.business_date = :d
                                      AND b.product_code = d.product_code AND b.branch_code = d.branch_code)
                 ORDER BY 1, 2
                """).param("d", date).query((rs, n) -> new AccrualGroup(rs.getString(1), rs.getString(2))).list();
    }

    /** Accrues one group: cumulative interest to tonight less what earlier closes accrued (V38 FD3). */
    public AccrualResult accrue(UUID tenantId, AccrualGroup group, LocalDate date, String actor) {
        record Row(UUID deposit, int nights, BigDecimal cumulative, BigDecimal posted, String currency) { }
        List<Row> rows = jdbc.sql("""
                WITH c AS (
                    SELECT d.deposit_id, d.currency, LEAST(:d, d.maturity_date - 1) - d.start_date + 1 AS nights, d.principal, d.rate_pct
                      FROM termdep.deposit d
                     WHERE d.status = 'ACTIVE' AND d.start_date <= :d AND d.product_code = :p AND d.branch_code = :b)
                SELECT c.deposit_id, c.nights, round(c.principal * c.rate_pct * c.nights / 36500, 2) AS cumulative,
                       round(c.principal * c.rate_pct * c.nights / 36500, 2)
                         - COALESCE((SELECT max(x.cumulative) FROM termdep.accrual x
                                      WHERE x.deposit_id = c.deposit_id AND x.business_date < :d), 0) AS posted,
                       c.currency
                  FROM c
                """).param("d", date).param("p", group.productCode()).param("b", group.branchCode())
            .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getInt(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                    rs.getString(5)))
            .list().stream().filter(r -> r.posted().signum() > 0).toList();
        if (rows.isEmpty()) {
            return new AccrualResult(0, BigDecimal.ZERO);
        }
        BigDecimal total = rows.stream().map(Row::posted).reduce(BigDecimal.ZERO, BigDecimal::add);
        record G(String expense, String payable) { }
        G g = jdbc.sql("SELECT gl_interest_expense, gl_interest_payable FROM termdep.product WHERE product_code = :p")
            .param("p", group.productCode()).query((rs, n) -> new G(rs.getString(1), rs.getString(2))).single();
        String key = "termdep:accrual:" + date + ":" + group.productCode() + ":" + group.branchCode();
        UUID entryId = FixedDepositService.uuid(tenantId + "|" + key);
        String currency = rows.get(0).currency();
        journal.post(new JournalEntry(entryId, tenantId, key, date, "TERMDEP", "fd-accrual:" + date, null, null, List.of(
                JournalLine.debit(g.expense(), FixedDepositService.money(total, currency), group.branchCode()),
                JournalLine.credit(g.payable(), FixedDepositService.money(total, currency), group.branchCode()))), actor);
        jdbc.sql("""
                INSERT INTO termdep.accrual_batch (tenant_id, entry_id, business_date, product_code, branch_code, deposits, total, created_by)
                VALUES (tenancy.current_tenant(), :e, :d, :p, :b, :n, :t, :a)
                """).param("e", entryId).param("d", date).param("p", group.productCode()).param("b", group.branchCode())
            .param("n", rows.size()).param("t", total).param("a", actor).update();
        for (Row r : rows) {
            jdbc.sql("""
                    INSERT INTO termdep.accrual (tenant_id, deposit_id, business_date, nights, cumulative, posted, entry_id)
                    VALUES (tenancy.current_tenant(), :id, :d, :n, :c, :p, :e)
                    """).param("id", r.deposit()).param("d", date).param("n", r.nights()).param("c", r.cumulative())
                .param("p", r.posted()).param("e", entryId).update();
        }
        return new AccrualResult(rows.size(), total);
    }

    /** Active deposits that mature on the next date (or earlier, if a close was ever missed). */
    public List<UUID> maturing(LocalDate date) {
        return jdbc.sql("SELECT deposit_id FROM termdep.deposit WHERE status = 'ACTIVE' AND maturity_date <= :d ORDER BY deposit_number")
            .param("d", date.plusDays(1)).query(UUID.class).list();
    }

    public ClosureResult mature(UUID tenantId, UUID depositId, LocalDate date, String actor) {
        Deposit d = deposits.deposit(depositId, true);
        if (!"ACTIVE".equals(d.status())) {
            return new ClosureResult(d.number(), "MATURITY", BigDecimal.ZERO, BigDecimal.ZERO, null);
        }
        return deposits.close(tenantId, d, "MATURITY", date, null, actor);
    }
}
