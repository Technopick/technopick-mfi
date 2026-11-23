package ke.co.technopick.mfi.core.regulatory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ke.co.technopick.mfi.core.regulatory.domain.PrudentialRules;
import ke.co.technopick.mfi.core.regulatory.domain.PrudentialRules.LoanClass;
import ke.co.technopick.mfi.core.regulatory.domain.PrudentialRules.Settings;
import ke.co.technopick.mfi.core.tenancy.persistence.TenantSession;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SASRA prudential returns for a closed business date (ADR-0030): capital adequacy (Reg 9),
 * liquidity (Reg 13) and risk classification of loans with the required allowance for loan loss
 * (Regs 41, 42 and 44 of LN 95/2010). Every figure lists the ledger accounts or loans it comes
 * from. Computed on demand from the ledger and the end-of-day arrears snapshots; once the month's
 * period is closed the ledger refuses new postings to it, so the figures for that month are final.
 */
@Service
public class PrudentialReturnService {

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    public record AccountAmount(String accountCode, String name, String amount) { }

    public record ReturnLine(String line, String label, String amount, List<AccountAmount> accounts) { }

    public record RatioCheck(String code, String label, String numerator, String denominator, String valuePct, String minimum,
                             Boolean met) { }

    public record CapitalAdequacy(List<ReturnLine> lines, String coreCapital, String institutionalCapital, String totalAssets,
                                  String totalDeposits, List<RatioCheck> checks) { }

    public record Liquidity(List<ReturnLine> lines, String liquidAssets, String depositsAndShortTermLiabilities,
                            List<RatioCheck> checks) { }

    public record ClassificationRow(String loanClass, String daysPastDue, int loans, String outstandingPrincipal,
                                    String allowancePct, String requiredAllowance) { }

    public record RiskClassification(List<ClassificationRow> rows, int totalLoans, String grossLoans, String requiredAllowance,
                                     ReturnLine allowanceHeld, String allowanceShortfall, String nonPerformingLoans,
                                     String nonPerformingRatioPct, String interestAccruedOnNonPerforming,
                                     String ledgerLoanPrincipal, String unexplainedPrincipalDifference) { }

    public record PrudentialSettingsView(String minCoreCapital, String minCoreToAssetsPct, String minInstitutionalToAssetsPct,
                                         String minCoreToDepositsPct, String minLiquidityPct, int watchMaxDpd,
                                         int substandardMaxDpd, int doubtfulMaxDpd, String provisionPerformingPct,
                                         String provisionWatchPct, String provisionSubstandardPct, String provisionDoubtfulPct,
                                         String provisionLossPct, boolean includeCurrentYearSurplus, boolean regulationDefaults,
                                         String updatedBy, OffsetDateTime updatedAt) { }

    public record PrudentialReturn(LocalDate asOf, String currency, boolean periodClosed, PrudentialSettingsView settings,
                                   CapitalAdequacy capital, Liquidity liquidity, RiskClassification classification,
                                   List<AccountAmount> unmappedBalanceSheetAccounts, List<String> notes) { }

    public record ClassifiedLoan(String loanNumber, String branchCode, int daysPastDue, String loanClass,
                                 String outstandingPrincipal, String requiredAllowance) { }

    private final JdbcClient jdbc;
    private final TenantSession session;
    private final PrudentialSettingsService settingsService;

    public PrudentialReturnService(JdbcClient jdbc, TenantSession session, PrudentialSettingsService settingsService) {
        this.jdbc = jdbc;
        this.session = session;
        this.settingsService = settingsService;
    }

    private record Account(String code, String name, String accountClass, String currency, BigDecimal natural) { }

    private LocalDate requireClosed(LocalDate asOf) {
        LocalDate lastClosed = jdbc.sql("SELECT max(business_date) FROM ledger.eod_run WHERE status = 'COMPLETED'")
            .query(LocalDate.class).optional().orElse(null);
        if (lastClosed == null) {
            throw new IllegalArgumentException("no business day has been closed yet; returns are prepared for closed days");
        }
        LocalDate d = asOf == null ? lastClosed : asOf;
        if (d.isAfter(lastClosed)) {
            throw new IllegalArgumentException("asOf " + d + " is not closed yet; the last closed business day is " + lastClosed);
        }
        return d;
    }

    /** Natural balances (assets and expenses debit-positive, the rest credit-positive) on {@code d}. */
    private List<Account> balances(LocalDate d) {
        return jdbc.sql("""
                SELECT a.account_code, a.name, a.account_class, a.currency,
                       COALESCE(sum(CASE WHEN l.side = 'D' THEN l.amount ELSE -l.amount END), 0) AS debit_balance
                  FROM ledger.gl_account a
                  LEFT JOIN (ledger.journal_line l JOIN ledger.journal_entry e
                                ON e.tenant_id = l.tenant_id AND e.entry_id = l.entry_id AND e.business_date <= :d)
                         ON l.tenant_id = a.tenant_id AND l.account_code = a.account_code
                 GROUP BY a.account_code, a.name, a.account_class, a.currency
                 ORDER BY a.account_code
                """).param("d", d)
            .query((rs, i) -> {
                String cls = rs.getString(3);
                BigDecimal debit = rs.getBigDecimal(5);
                boolean debitNatural = "ASSET".equals(cls) || "EXPENSE".equals(cls);
                return new Account(rs.getString(1), rs.getString(2), cls, rs.getString(4), debitNatural ? debit : debit.negate());
            }).list();
    }

    private static String m(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String pct(BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }

    @Transactional(readOnly = true)
    public PrudentialReturn prudentialReturn(UUID tenantId, LocalDate asOf) {
        session.bind(tenantId);
        LocalDate d = requireClosed(asOf);
        PrudentialSettingsService.Stored stored = settingsService.stored();
        Settings s = stored.settings();
        String currency = jdbc.sql("SELECT base_currency FROM tenancy.tenant").query(String.class).single();
        boolean periodClosed = jdbc.sql("""
                SELECT status = 'CLOSED' FROM ledger.accounting_period WHERE :d BETWEEN start_date AND end_date
                """).param("d", d).query(Boolean.class).optional().orElse(false);

        List<Account> accounts = balances(d);
        Map<String, String> mapping = new HashMap<>();
        jdbc.sql("SELECT account_code, line FROM ledger.regulatory_mapping").query((rs, i) -> mapping.put(rs.getString(1), rs.getString(2)))
            .list();
        Map<String, String> labels = new LinkedHashMap<>();
        jdbc.sql("SELECT line, description FROM ledger.regulatory_line ORDER BY line")
            .query((rs, i) -> labels.put(rs.getString(1), rs.getString(2))).list();

        List<String> notes = new ArrayList<>();
        Map<String, List<AccountAmount>> byLine = new HashMap<>();
        Map<String, BigDecimal> total = new HashMap<>();
        List<AccountAmount> unmapped = new ArrayList<>();
        BigDecimal totalAssets = ZERO;
        BigDecimal surplus = ZERO;
        for (Account a : accounts) {
            if (!a.currency().equals(currency)) {
                if (a.natural().signum() != 0) {
                    notes.add("Account " + a.code() + " is in " + a.currency() + " and is not included; the returns are in " + currency + ".");
                }
                continue;
            }
            switch (a.accountClass()) {
                case "ASSET" -> totalAssets = totalAssets.add(a.natural());
                case "INCOME" -> surplus = surplus.add(a.natural());
                case "EXPENSE" -> surplus = surplus.subtract(a.natural());
                default -> { }
            }
            String line = mapping.get(a.code());
            if (line != null) {
                byLine.computeIfAbsent(line, k -> new ArrayList<>()).add(new AccountAmount(a.code(), a.name(), m(a.natural())));
                total.merge(line, a.natural(), BigDecimal::add);
            } else if (!"INCOME".equals(a.accountClass()) && !"EXPENSE".equals(a.accountClass()) && a.natural().signum() != 0) {
                unmapped.add(new AccountAmount(a.code(), a.name(), m(a.natural())));
            }
        }
        java.util.function.Function<String, BigDecimal> t = line -> total.getOrDefault(line, ZERO);
        java.util.function.Function<String, ReturnLine> rl = line -> new ReturnLine(line, labels.get(line), m(t.apply(line)),
                byLine.getOrDefault(line, List.of()));

        // ---- capital adequacy (Reg 9; core and institutional capital as defined in Reg 3)
        BigDecimal counted = s.includeCurrentYearSurplus() ? surplus : ZERO;
        BigDecimal institutional = t.apply("DISCLOSED_RESERVES").add(t.apply("RETAINED_EARNINGS")).add(t.apply("GRANTS_DONATIONS"))
            .add(counted);
        BigDecimal core = t.apply("SHARE_CAPITAL").add(institutional);
        BigDecimal deposits = t.apply("MEMBER_DEPOSITS");
        List<ReturnLine> capitalLines = new ArrayList<>(List.of(rl.apply("SHARE_CAPITAL"), rl.apply("DISCLOSED_RESERVES"),
                rl.apply("RETAINED_EARNINGS"), rl.apply("GRANTS_DONATIONS")));
        capitalLines.add(new ReturnLine("CURRENT_YEAR_SURPLUS", "Earnings not yet closed to equity (income less expenses to date)"
                + (s.includeCurrentYearSurplus() ? "" : ": not counted (tenant setting)"), m(surplus), List.of()));
        capitalLines.add(rl.apply("EXCLUDED_RESERVES"));
        capitalLines.add(rl.apply("MEMBER_DEPOSITS"));
        List<RatioCheck> capitalChecks = List.of(
            new RatioCheck("CORE_CAPITAL_MINIMUM", "Core capital of at least " + m(s.minCoreCapital()) + " (Reg 9(a))", m(core), null,
                    null, m(s.minCoreCapital()), core.compareTo(s.minCoreCapital()) >= 0),
            new RatioCheck("CORE_TO_ASSETS", "Core capital to total assets (Reg 9(b))", m(core), m(totalAssets),
                    pct(PrudentialRules.percent(core, totalAssets)), s.minCoreToAssetsPct().toPlainString(),
                    PrudentialRules.meets(core, totalAssets, s.minCoreToAssetsPct())),
            new RatioCheck("INSTITUTIONAL_TO_ASSETS", "Institutional capital to total assets (Reg 9(c))", m(institutional), m(totalAssets),
                    pct(PrudentialRules.percent(institutional, totalAssets)), s.minInstitutionalToAssetsPct().toPlainString(),
                    PrudentialRules.meets(institutional, totalAssets, s.minInstitutionalToAssetsPct())),
            new RatioCheck("CORE_TO_DEPOSITS", "Core capital to total deposits (Reg 9(d))", m(core), m(deposits),
                    pct(PrudentialRules.percent(core, deposits)), s.minCoreToDepositsPct().toPlainString(),
                    PrudentialRules.meets(core, deposits, s.minCoreToDepositsPct())));
        CapitalAdequacy capital = new CapitalAdequacy(capitalLines, m(core), m(institutional), m(totalAssets), m(deposits), capitalChecks);

        // ---- liquidity (Reg 13): liquid assets net of amounts owed to the same banks and Saccos
        BigDecimal liquid = t.apply("CASH").add(t.apply("BANK_BALANCES")).subtract(t.apply("BANK_AMOUNTS_OWED"))
            .add(t.apply("GOVERNMENT_SECURITIES")).add(t.apply("SACCO_DEPOSITS_90D")).subtract(t.apply("SACCO_AMOUNTS_OWED"));
        BigDecimal liabilities = deposits.add(t.apply("SHORT_TERM_LIABILITIES"));
        Liquidity liquidity = new Liquidity(List.of(rl.apply("CASH"), rl.apply("BANK_BALANCES"), rl.apply("BANK_AMOUNTS_OWED"),
                rl.apply("GOVERNMENT_SECURITIES"), rl.apply("SACCO_DEPOSITS_90D"), rl.apply("SACCO_AMOUNTS_OWED"),
                rl.apply("MEMBER_DEPOSITS"), rl.apply("SHORT_TERM_LIABILITIES")), m(liquid), m(liabilities),
            List.of(new RatioCheck("LIQUIDITY", "Liquid assets to savings deposits and short-term liabilities (Reg 13)", m(liquid),
                    m(liabilities), pct(PrudentialRules.percent(liquid, liabilities)), s.minLiquidityPct().toPlainString(),
                    PrudentialRules.meets(liquid, liabilities, s.minLiquidityPct()))));

        // ---- risk classification and allowance for loan loss (Regs 41(3), 42, 44(1))
        Map<LoanClass, int[]> counts = new EnumMap<>(LoanClass.class);
        Map<LoanClass, BigDecimal> principal = new EnumMap<>(LoanClass.class);
        Map<LoanClass, BigDecimal> required = new EnumMap<>(LoanClass.class);
        for (LoanClass c : LoanClass.values()) {
            counts.put(c, new int[1]);
            principal.put(c, ZERO);
            required.put(c, ZERO);
        }
        for (ClassifiedLoan l : loans(s, d, null)) {
            LoanClass c = LoanClass.valueOf(l.loanClass());
            counts.get(c)[0]++;
            principal.merge(c, new BigDecimal(l.outstandingPrincipal()), BigDecimal::add);
            required.merge(c, new BigDecimal(l.requiredAllowance()), BigDecimal::add);
        }
        List<ClassificationRow> rows = new ArrayList<>();
        BigDecimal gross = ZERO;
        BigDecimal requiredTotal = ZERO;
        BigDecimal npl = ZERO;
        int loanCount = 0;
        for (LoanClass c : LoanClass.values()) {
            rows.add(new ClassificationRow(c.name(), band(c, s), counts.get(c)[0], m(principal.get(c)), s.provisionPct(c).toPlainString(),
                    m(required.get(c))));
            gross = gross.add(principal.get(c));
            requiredTotal = requiredTotal.add(required.get(c));
            loanCount += counts.get(c)[0];
            if (c.nonPerforming()) {
                npl = npl.add(principal.get(c));
            }
        }
        BigDecimal held = t.apply("LOAN_LOSS_ALLOWANCE").negate(); // a contra asset: its credit balance is the allowance held
        ReturnLine heldLine = new ReturnLine("LOAN_LOSS_ALLOWANCE", labels.get("LOAN_LOSS_ALLOWANCE"), m(held),
                byLine.getOrDefault("LOAN_LOSS_ALLOWANCE", List.of()));
        BigDecimal shortfall = requiredTotal.subtract(held).max(ZERO);
        BigDecimal ledgerPrincipal = jdbc.sql("""
                SELECT COALESCE(sum(CASE WHEN l.side = 'D' THEN l.amount ELSE -l.amount END), 0)
                  FROM ledger.journal_line l JOIN ledger.journal_entry e ON e.tenant_id = l.tenant_id AND e.entry_id = l.entry_id
                 WHERE e.business_date <= :d
                   AND l.account_code IN (SELECT DISTINCT gl_principal FROM lending.loan_product_version)
                """).param("d", d).query(BigDecimal.class).single();
        BigDecimal interestOnNpl = jdbc.sql("""
                WITH npl AS (SELECT loan_id FROM lending.arrears_snapshot WHERE as_of = :d AND days_past_due > :watch),
                     a AS (SELECT x.loan_id, x.installment_no, sum(x.amount) AS accrued FROM lending.loan_accrual x
                            WHERE x.business_date <= :d AND x.loan_id IN (SELECT loan_id FROM npl) GROUP BY 1, 2),
                     p AS (SELECT r.loan_id, al.installment_no, sum(al.amount) AS paid
                             FROM lending.repayment_allocation al
                             JOIN lending.repayment r ON r.tenant_id = al.tenant_id AND r.repayment_id = al.repayment_id
                             JOIN ledger.journal_entry e ON e.tenant_id = r.tenant_id AND e.entry_id = r.journal_entry_id
                            WHERE al.component = 'INTEREST' AND e.business_date <= :d AND r.loan_id IN (SELECT loan_id FROM npl)
                            GROUP BY 1, 2)
                SELECT COALESCE(sum(GREATEST(COALESCE(a.accrued, 0) - COALESCE(p.paid, 0), 0)), 0)
                  FROM a FULL JOIN p ON p.loan_id = a.loan_id AND p.installment_no = a.installment_no
                """).param("d", d).param("watch", s.watchMaxDpd()).query(BigDecimal.class).single();
        RiskClassification classification = new RiskClassification(rows, loanCount, m(gross), m(requiredTotal), heldLine,
                m(shortfall), m(npl), pct(PrudentialRules.percent(npl, gross)), m(interestOnNpl), m(ledgerPrincipal),
                m(ledgerPrincipal.subtract(gross)));

        if (ledgerPrincipal.compareTo(gross) != 0) {
            notes.add("The loans classified (" + m(gross) + ") differ from loan principal in the ledger (" + m(ledgerPrincipal)
                    + ") by " + m(ledgerPrincipal.subtract(gross)) + ". Check reconciliation for " + d + " before submitting.");
        }
        if (interestOnNpl.signum() > 0) {
            notes.add("Interest of " + m(interestOnNpl) + " accrued on non-performing loans is still in income and receivables;"
                    + " Reg 42 requires it to be suspended. Interest suspension is not built yet.");
        }
        if (mapping.isEmpty()) {
            notes.add("No ledger accounts are mapped to return lines yet; capital and liquidity figures are zero until finance maps them.");
        }
        if (!unmapped.isEmpty()) {
            notes.add(unmapped.size() + " balance-sheet account(s) with a balance are not mapped to any return line (listed).");
        }
        if (!periodClosed) {
            notes.add("The accounting period containing " + d + " is still open: figures can change until finance closes it.");
        }
        return new PrudentialReturn(d, currency, periodClosed, settingsService.view(stored), capital, liquidity, classification,
                unmapped, notes);
    }

    private static String band(LoanClass c, Settings s) {
        return switch (c) {
            case PERFORMING -> "0";
            case WATCH -> "1-" + s.watchMaxDpd();
            case SUBSTANDARD -> (s.watchMaxDpd() + 1) + "-" + s.substandardMaxDpd();
            case DOUBTFUL -> (s.substandardMaxDpd() + 1) + "-" + s.doubtfulMaxDpd();
            case LOSS -> "over " + s.doubtfulMaxDpd();
        };
    }

    private List<ClassifiedLoan> loans(Settings s, LocalDate d, LoanClass only) {
        List<ClassifiedLoan> out = new ArrayList<>();
        jdbc.sql("""
                SELECT l.loan_number, l.branch_code, s.days_past_due, s.outstanding_principal
                  FROM lending.arrears_snapshot s JOIN lending.loan l ON l.tenant_id = s.tenant_id AND l.loan_id = s.loan_id
                 WHERE s.as_of = :d ORDER BY s.days_past_due DESC, l.loan_number
                """).param("d", d)
            .query((rs, i) -> {
                int dpd = rs.getInt(3);
                BigDecimal p = rs.getBigDecimal(4);
                LoanClass c = PrudentialRules.classify(dpd, s);
                if (only == null || only == c) {
                    out.add(new ClassifiedLoan(rs.getString(1), rs.getString(2), dpd, c.name(), m(p),
                            m(PrudentialRules.requiredAllowance(p, c, s))));
                }
                return null;
            }).list();
        return out;
    }

    /** The loans behind one row of the classification, for checking and for the return's schedule. */
    @Transactional(readOnly = true)
    public List<ClassifiedLoan> classifiedLoans(UUID tenantId, LocalDate asOf, String loanClass) {
        session.bind(tenantId);
        LocalDate d = requireClosed(asOf);
        LoanClass only;
        try {
            only = loanClass == null ? null : LoanClass.valueOf(loanClass);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("loanClass must be one of PERFORMING, WATCH, SUBSTANDARD, DOUBTFUL, LOSS");
        }
        return loans(settingsService.stored().settings(), d, only);
    }
}
