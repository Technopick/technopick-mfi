package ke.co.technopick.mfi.core.collections.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ke.co.technopick.mfi.core.tenancy.persistence.TenantSession;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Portfolio at risk from the end-of-day arrears snapshots (ADR-0021): PAR by branch, the loans in
 * arrears, and closed loans still holding overpaid money for the customer. Reads only.
 */
@Service
public class PortfolioReportService {

    public record BranchPar(String branchCode, String currency, int loans, String portfolio, String atRisk1,
                            String atRisk30, String atRisk90, String par1, String par30, String par90) { }

    public record LoanInArrears(String loanNumber, String branchCode, String customerNumber, int daysPastDue, String bucket,
                                String classification, String arrearsAmount, String outstandingPrincipal, boolean restructured) { }

    public record ClosedWithCredit(String loanNumber, String branchCode, String customerNumber, LocalDate closedOn,
                                   String creditBalance) { }

    public record PortfolioReport(LocalDate asOf, List<BranchPar> branches, List<LoanInArrears> loansInArrears,
                                  List<ClosedWithCredit> closedWithCredit) { }

    private final JdbcClient jdbc;
    private final TenantSession session;

    public PortfolioReportService(JdbcClient jdbc, TenantSession session) {
        this.jdbc = jdbc;
        this.session = session;
    }

    /** @param asOf snapshot date; null means the latest closed day */
    @Transactional(readOnly = true)
    public PortfolioReport report(UUID tenantId, LocalDate asOf, String branchCode) {
        session.bind(tenantId);
        LocalDate date = asOf != null ? asOf
                : jdbc.sql("SELECT max(as_of) FROM lending.arrears_snapshot").query(LocalDate.class).optional().orElse(null);
        String branch = branchCode == null || branchCode.isBlank() ? null : branchCode.strip();
        List<BranchPar> branches = date == null ? List.of() : jdbc.sql("""
                SELECT branch_code, currency, loans, portfolio, COALESCE(at_risk_1, 0) AS r1, COALESCE(at_risk_30, 0) AS r30,
                       COALESCE(at_risk_90, 0) AS r90, COALESCE(par_1, 0) AS p1, COALESCE(par_30, 0) AS p30,
                       COALESCE(par_90, 0) AS p90
                  FROM lending.par_by_branch
                 WHERE as_of = :d AND (CAST(:b AS varchar) IS NULL OR branch_code = :b)
                 ORDER BY branch_code, currency
                """)
            .param("d", date).param("b", branch)
            .query((rs, i) -> new BranchPar(rs.getString("branch_code"), rs.getString("currency"), rs.getInt("loans"),
                    money(rs.getBigDecimal("portfolio")), money(rs.getBigDecimal("r1")), money(rs.getBigDecimal("r30")),
                    money(rs.getBigDecimal("r90")), percent(rs.getBigDecimal("p1")), percent(rs.getBigDecimal("p30")),
                    percent(rs.getBigDecimal("p90"))))
            .list();
        List<LoanInArrears> arrears = date == null ? List.of() : jdbc.sql("""
                SELECT l.loan_number, l.branch_code, c.customer_number, s.days_past_due, s.bucket, s.classification,
                       s.arrears_amount, s.outstanding_principal,
                       EXISTS (SELECT 1 FROM lending.loan_reschedule r WHERE r.tenant_id = l.tenant_id AND r.loan_id = l.loan_id
                                  AND r.status = 'APPROVED' AND r.kind = 'RESTRUCTURE' AND r.business_date <= s.as_of) AS restructured
                  FROM lending.arrears_snapshot s
                  JOIN lending.loan l ON l.tenant_id = s.tenant_id AND l.loan_id = s.loan_id
                  LEFT JOIN customer.customer c ON c.tenant_id = l.tenant_id AND c.customer_id = l.customer_id
                 WHERE s.as_of = :d AND s.days_past_due > 0 AND (CAST(:b AS varchar) IS NULL OR l.branch_code = :b)
                 ORDER BY s.days_past_due DESC, l.loan_number
                 LIMIT 500
                """)
            .param("d", date).param("b", branch)
            .query((rs, i) -> new LoanInArrears(rs.getString("loan_number"), rs.getString("branch_code"),
                    rs.getString("customer_number"), rs.getInt("days_past_due"), rs.getString("bucket"),
                    rs.getString("classification"), money(rs.getBigDecimal("arrears_amount")),
                    money(rs.getBigDecimal("outstanding_principal")), rs.getBoolean("restructured")))
            .list();
        List<ClosedWithCredit> credit = jdbc.sql("""
                SELECT l.loan_number, l.branch_code, c.customer_number, k.closed_on, lending.loan_credit(l.tenant_id, l.loan_id) AS credit
                  FROM lending.loan_closure k
                  JOIN lending.loan l ON l.tenant_id = k.tenant_id AND l.loan_id = k.loan_id
                  LEFT JOIN customer.customer c ON c.tenant_id = l.tenant_id AND c.customer_id = l.customer_id
                 WHERE lending.loan_credit(l.tenant_id, l.loan_id) > 0 AND (CAST(:b AS varchar) IS NULL OR l.branch_code = :b)
                 ORDER BY k.closed_on, l.loan_number
                 LIMIT 500
                """)
            .param("b", branch)
            .query((rs, i) -> new ClosedWithCredit(rs.getString("loan_number"), rs.getString("branch_code"),
                    rs.getString("customer_number"), rs.getObject("closed_on", LocalDate.class),
                    money(rs.getBigDecimal("credit"))))
            .list();
        return new PortfolioReport(date, branches, arrears, credit);
    }

    private static String money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }

    /** Ratio (4 decimals) as a percentage with 2 decimals. */
    private static String percent(BigDecimal ratio) {
        return ratio.movePointRight(2).setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }
}
