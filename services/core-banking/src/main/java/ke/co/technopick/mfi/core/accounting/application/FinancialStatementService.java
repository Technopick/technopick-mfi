package ke.co.technopick.mfi.core.accounting.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import ke.co.technopick.mfi.core.tenancy.persistence.BusinessDates;
import ke.co.technopick.mfi.core.tenancy.persistence.TenantSession;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Income statement and balance sheet from the ledger (ADR-0026). Read-only and computed from
 * journal lines by business date. Until a year-end close exists, income less expenses since the
 * first posting appears on the balance sheet as "earnings not yet closed to equity", which is
 * what makes assets equal liabilities plus equity.
 */
@Service
public class FinancialStatementService {

    static final int MAX_DAYS = 366;

    public record FinancialStatementLine(String accountCode, String name, String accountClass, String currency, String amount) { }

    public record IncomeStatementTotal(String currency, String totalIncome, String totalExpenses, String netProfit) { }

    public record IncomeStatement(LocalDate from, LocalDate to, String branchCode, List<FinancialStatementLine> income,
                                  List<FinancialStatementLine> expenses, List<IncomeStatementTotal> totals) { }

    public record BalanceSheetTotal(String currency, String totalAssets, String totalLiabilities, String totalEquity,
                                    String unclosedEarnings, String liabilitiesAndEquity, boolean balanced) { }

    public record BalanceSheet(LocalDate asOf, String branchCode, List<FinancialStatementLine> assets, List<FinancialStatementLine> liabilities,
                               List<FinancialStatementLine> equity, List<BalanceSheetTotal> totals) { }

    private final JdbcClient jdbc;
    private final TenantSession session;
    private final BusinessDates businessDates;

    public FinancialStatementService(JdbcClient jdbc, TenantSession session, BusinessDates businessDates) {
        this.jdbc = jdbc;
        this.session = session;
        this.businessDates = businessDates;
    }

    /** Income and expenses between two business dates; by default the current month to date. */
    @Transactional(readOnly = true)
    public IncomeStatement incomeStatement(UUID tenantId, LocalDate from, LocalDate to, String branchCode) {
        session.bind(tenantId);
        LocalDate end = to == null ? businessDates.current() : to;
        LocalDate start = from == null ? end.withDayOfMonth(1) : from;
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("from must not be after to");
        }
        if (ChronoUnit.DAYS.between(start, end) > MAX_DAYS) {
            throw new IllegalArgumentException("at most " + MAX_DAYS + " days at a time");
        }
        String branch = blankToNull(branchCode);
        List<FinancialStatementLine> lines = lines("""
                SELECT a.account_code, a.name, a.account_class, a.currency,
                       sum(CASE l.side WHEN 'D' THEN l.amount ELSE -l.amount END) AS debit_positive
                  FROM ledger.journal_line l
                  JOIN ledger.journal_entry e ON e.tenant_id = l.tenant_id AND e.entry_id = l.entry_id
                  JOIN ledger.gl_account a ON a.tenant_id = l.tenant_id AND a.account_code = l.account_code
                 WHERE a.account_class IN ('INCOME', 'EXPENSE') AND e.business_date BETWEEN :from AND :to
                   AND (CAST(:b AS varchar) IS NULL OR l.branch_code = :b)
                 GROUP BY a.account_code, a.name, a.account_class, a.currency
                HAVING sum(CASE l.side WHEN 'D' THEN l.amount ELSE -l.amount END) <> 0
                 ORDER BY a.account_code
                """, Map.of("from", start, "to", end), branch);
        List<FinancialStatementLine> income = lines.stream().filter(l -> "INCOME".equals(l.accountClass())).toList();
        List<FinancialStatementLine> expenses = lines.stream().filter(l -> "EXPENSE".equals(l.accountClass())).toList();
        Map<String, BigDecimal[]> byCcy = new TreeMap<>();
        for (FinancialStatementLine l : lines) {
            BigDecimal[] t = byCcy.computeIfAbsent(l.currency(), c -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            t["INCOME".equals(l.accountClass()) ? 0 : 1] = t["INCOME".equals(l.accountClass()) ? 0 : 1].add(new BigDecimal(l.amount()));
        }
        List<IncomeStatementTotal> totals = new ArrayList<>();
        byCcy.forEach((c, t) -> totals.add(new IncomeStatementTotal(c, plain(t[0]), plain(t[1]), plain(t[0].subtract(t[1])))));
        return new IncomeStatement(start, end, branch, income, expenses, totals);
    }

    /** Balances of assets, liabilities and equity at the end of a business date (by default today). */
    @Transactional(readOnly = true)
    public BalanceSheet balanceSheet(UUID tenantId, LocalDate asOf, String branchCode) {
        session.bind(tenantId);
        LocalDate date = asOf == null ? businessDates.current() : asOf;
        String branch = blankToNull(branchCode);
        List<FinancialStatementLine> lines = lines("""
                SELECT a.account_code, a.name, a.account_class, a.currency,
                       sum(CASE l.side WHEN 'D' THEN l.amount ELSE -l.amount END) AS debit_positive
                  FROM ledger.journal_line l
                  JOIN ledger.journal_entry e ON e.tenant_id = l.tenant_id AND e.entry_id = l.entry_id
                  JOIN ledger.gl_account a ON a.tenant_id = l.tenant_id AND a.account_code = l.account_code
                 WHERE e.business_date <= :to AND (CAST(:b AS varchar) IS NULL OR l.branch_code = :b)
                 GROUP BY a.account_code, a.name, a.account_class, a.currency
                HAVING sum(CASE l.side WHEN 'D' THEN l.amount ELSE -l.amount END) <> 0
                 ORDER BY a.account_code
                """, Map.of("to", date), branch);
        Map<String, BigDecimal[]> byCcy = new TreeMap<>(); // assets, liabilities, equity, income, expenses
        for (FinancialStatementLine l : lines) {
            BigDecimal[] t = byCcy.computeIfAbsent(l.currency(), c -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            int i = List.of("ASSET", "LIABILITY", "EQUITY", "INCOME", "EXPENSE").indexOf(l.accountClass());
            t[i] = t[i].add(new BigDecimal(l.amount()));
        }
        List<BalanceSheetTotal> totals = new ArrayList<>();
        byCcy.forEach((c, t) -> {
            BigDecimal earnings = t[3].subtract(t[4]);
            BigDecimal right = t[1].add(t[2]).add(earnings);
            totals.add(new BalanceSheetTotal(c, plain(t[0]), plain(t[1]), plain(t[2]), plain(earnings), plain(right),
                    t[0].compareTo(right) == 0));
        });
        return new BalanceSheet(date, branch, byClass(lines, "ASSET"), byClass(lines, "LIABILITY"), byClass(lines, "EQUITY"),
                totals);
    }

    private List<FinancialStatementLine> lines(String sql, Map<String, LocalDate> dates, String branch) {
        var spec = jdbc.sql(sql).param("b", branch);
        for (var e : dates.entrySet()) {
            spec = spec.param(e.getKey(), e.getValue());
        }
        return spec.query((rs, i) -> {
            String cls = rs.getString("account_class");
            BigDecimal net = rs.getBigDecimal("debit_positive");
            BigDecimal natural = "ASSET".equals(cls) || "EXPENSE".equals(cls) ? net : net.negate();
            return new FinancialStatementLine(rs.getString("account_code"), rs.getString("name"), cls, rs.getString("currency"),
                    plain(natural));
        }).list();
    }

    private static List<FinancialStatementLine> byClass(List<FinancialStatementLine> lines, String cls) {
        return lines.stream().filter(l -> cls.equals(l.accountClass())).toList();
    }

    private static String plain(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
