package ke.co.technopick.mfi.core.provisioning.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import ke.co.technopick.mfi.canonical.Money;
import ke.co.technopick.mfi.core.accounting.application.JournalPosting;
import ke.co.technopick.mfi.core.accounting.domain.JournalEntry;
import ke.co.technopick.mfi.core.accounting.domain.JournalLine;
import ke.co.technopick.mfi.core.common.ConflictException;
import ke.co.technopick.mfi.core.common.NotFoundException;
import ke.co.technopick.mfi.core.tenancy.persistence.TenantSession;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IFRS 9 expected credit loss for loans, simplified approach (ADR-0036). At a closed month end each
 * loan with principal outstanding is staged by its days past due (stage 2 above
 * {@code stage2Dpd}, stage 3 above {@code stage3Dpd}; a loan restructured in the last
 * {@code restructureProbationMonths} is at least stage 2, ADR-0037) and provided at its product's rate for that
 * stage (or the default '*' rate) on its outstanding principal. A finance user requests a run; a
 * second finance user approves it, which posts, branch by branch, only the change from what the
 * allowance already holds.
 */
@Service
public class EclService {

    public record EclSettingsView(boolean configured, String allowanceAccount, String expenseAccount, int stage2Dpd, int stage3Dpd,
                                  int restructureProbationMonths, String updatedBy, OffsetDateTime updatedAt) { }

    /** restructureProbationMonths: a restructured loan stays at least in stage 2 this long (ADR-0037). */
    public record EclSettingsRequest(String allowanceAccount, String expenseAccount, Integer stage2Dpd, Integer stage3Dpd,
                                     Integer restructureProbationMonths) { }

    /** Rates in percent of exposure; blank means no rate (the default row '*' applies). */
    public record EclRateRow(String productCode, String productName, String stage1Pct, String stage2Pct, String stage3Pct) { }

    public record EclRates(List<EclRateRow> rows) { }

    public record EclStageRow(int stage, int loans, String exposure, String ecl, String coveragePct) { }

    public record EclProductRow(String productCode, int stage, int loans, String exposure, String ratePct, String ecl) { }

    public record EclCalculation(LocalDate asOf, boolean monthEnd, int stage2Dpd, int stage3Dpd, int loans, String exposure,
                                 String requiredTotal, String currentAllowance, String change, List<EclStageRow> stages,
                                 List<EclProductRow> byProduct, List<String> productsWithoutRates) { }

    public record EclRunView(UUID runId, LocalDate asOf, String status, int loans, String requiredTotal, String previousTotal,
                             String change, UUID entryId, String requestedBy, OffsetDateTime requestedAt, String decidedBy,
                             OffsetDateTime decidedAt, String decisionReason, List<EclStageRow> stages, boolean replayed) { }

    public record EclLine(String loanNumber, String customerNumber, String productCode, String branchCode, int daysPastDue,
                          int stage, String exposure, String ratePct, String ecl) { }

    private record Settings(String allowance, String expense, int stage2, int stage3, int probation, Currency currency) { }

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** Each loan's stage, exposure and rate at :d (the rate may be missing). */
    private static final String STAGED = """
            WITH s AS (
                SELECT a.loan_id, v.product_code, l.branch_code, a.days_past_due AS dpd,
                       round(a.outstanding_principal, 2) AS exposure,
                       CASE WHEN a.days_past_due > :s3 THEN 3
                            WHEN a.days_past_due > :s2 OR lending.restructured_within(a.tenant_id, a.loan_id, :d, :probation) THEN 2
                            ELSE 1 END AS stage
                  FROM lending.arrears_snapshot a
                  JOIN lending.loan l ON l.tenant_id = a.tenant_id AND l.loan_id = a.loan_id
                  JOIN lending.loan_product_version v ON v.tenant_id = l.tenant_id AND v.version_id = l.product_version_id
                 WHERE a.as_of = :d AND a.outstanding_principal > 0)
            SELECT s.loan_id, s.product_code, s.branch_code, s.dpd, s.exposure, s.stage, COALESCE(p.rate_pct, x.rate_pct) AS rate,
                   round(s.exposure * COALESCE(p.rate_pct, x.rate_pct) / 100, 2) AS ecl
              FROM s
              LEFT JOIN ledger.ecl_rate p ON p.product_code = s.product_code AND p.stage = s.stage
              LEFT JOIN ledger.ecl_rate x ON x.product_code = '*' AND x.stage = s.stage
            """;

    private final JdbcClient jdbc;
    private final TenantSession session;
    private final JournalPosting journal;

    public EclService(JdbcClient jdbc, TenantSession session, JournalPosting journal) {
        this.jdbc = jdbc;
        this.session = session;
        this.journal = journal;
    }

    // ------------------------------------------------------------------ settings and rates

    @Transactional(readOnly = true)
    public EclSettingsView settings(UUID tenantId) {
        session.bind(tenantId);
        return jdbc.sql("""
                SELECT allowance_account, expense_account, stage2_dpd, stage3_dpd, restructure_probation_months, updated_by, updated_at
                  FROM ledger.ecl_settings
                """)
            .query((rs, i) -> new EclSettingsView(true, rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5),
                    rs.getString(6), rs.getObject(7, OffsetDateTime.class)))
            .optional().orElse(new EclSettingsView(false, null, null, 30, 90, 6, null, null));
    }

    @Transactional
    public EclSettingsView saveSettings(UUID tenantId, EclSettingsRequest r, String actor) {
        session.bind(tenantId);
        if (r == null || blank(r.allowanceAccount()) || blank(r.expenseAccount())) {
            throw new IllegalArgumentException("allowanceAccount and expenseAccount are required");
        }
        int s2 = r.stage2Dpd() == null ? 30 : r.stage2Dpd();
        int s3 = r.stage3Dpd() == null ? 90 : r.stage3Dpd();
        if (s2 < 1 || s3 <= s2 || s3 > 365) {
            throw new IllegalArgumentException("stage thresholds: 1 <= stage 2 days < stage 3 days <= 365");
        }
        int probation = r.restructureProbationMonths() == null ? 6 : r.restructureProbationMonths();
        if (probation < 0 || probation > 24) {
            throw new IllegalArgumentException("restructure probation: 0 to 24 months");
        }
        if (open() != null) {
            throw new ConflictException("a provision run is waiting for approval; decide it before changing the settings");
        }
        jdbc.sql("""
                INSERT INTO ledger.ecl_settings (tenant_id, allowance_account, expense_account, stage2_dpd, stage3_dpd,
                    restructure_probation_months, updated_by)
                VALUES (tenancy.current_tenant(), :a, :e, :s2, :s3, :p, :actor)
                ON CONFLICT (tenant_id) DO UPDATE SET allowance_account = EXCLUDED.allowance_account,
                    expense_account = EXCLUDED.expense_account, stage2_dpd = EXCLUDED.stage2_dpd, stage3_dpd = EXCLUDED.stage3_dpd,
                    restructure_probation_months = EXCLUDED.restructure_probation_months,
                    updated_by = EXCLUDED.updated_by, updated_at = clock_timestamp()
                """).param("a", r.allowanceAccount().strip()).param("e", r.expenseAccount().strip()).param("s2", s2).param("s3", s3)
            .param("p", probation).param("actor", actor).update();
        return settings(tenantId);
    }

    /** One row per loan product, then the default row '*'. */
    @Transactional(readOnly = true)
    public EclRates rates(UUID tenantId) {
        session.bind(tenantId);
        Map<String, String[]> byProduct = new TreeMap<>();
        Map<String, String> names = new TreeMap<>();
        jdbc.sql("SELECT product_code, name FROM lending.loan_product ORDER BY product_code").query((rs, i) -> {
            names.put(rs.getString(1), rs.getString(2));
            byProduct.put(rs.getString(1), new String[3]);
            return null;
        }).list();
        byProduct.put("*", new String[3]);
        names.put("*", "Default for products without their own rates");
        jdbc.sql("SELECT product_code, stage, rate_pct FROM ledger.ecl_rate").query((rs, i) -> {
            byProduct.computeIfAbsent(rs.getString(1), k -> new String[3])[rs.getInt(2) - 1] = pct(rs.getBigDecimal(3));
            return null;
        }).list();
        List<EclRateRow> rows = new ArrayList<>();
        byProduct.forEach((code, r) -> {
            if (!"*".equals(code)) {
                rows.add(new EclRateRow(code, names.getOrDefault(code, code), r[0], r[1], r[2]));
            }
        });
        String[] d = byProduct.get("*");
        rows.add(new EclRateRow("*", names.get("*"), d[0], d[1], d[2]));
        return new EclRates(rows);
    }

    @Transactional
    public EclRates saveRates(UUID tenantId, EclRates request, String actor) {
        session.bind(tenantId);
        if (request == null || request.rows() == null || request.rows().isEmpty()) {
            throw new IllegalArgumentException("rows are required");
        }
        if (open() != null) {
            throw new ConflictException("a provision run is waiting for approval; decide it before changing the rates");
        }
        for (EclRateRow row : request.rows()) {
            String code = row.productCode() == null ? "" : row.productCode().strip();
            boolean known = "*".equals(code) || jdbc.sql("SELECT EXISTS (SELECT 1 FROM lending.loan_product WHERE product_code = :p)")
                .param("p", code).query(Boolean.class).single();
            if (!known) {
                throw new IllegalArgumentException("unknown loan product " + code);
            }
            String[] given = {row.stage1Pct(), row.stage2Pct(), row.stage3Pct()};
            for (int stage = 1; stage <= 3; stage++) {
                BigDecimal rate = rate(given[stage - 1], code, stage);
                if (rate == null) {
                    jdbc.sql("DELETE FROM ledger.ecl_rate WHERE product_code = :p AND stage = :s").param("p", code).param("s", stage).update();
                } else {
                    jdbc.sql("""
                            INSERT INTO ledger.ecl_rate (tenant_id, product_code, stage, rate_pct, updated_by)
                            VALUES (tenancy.current_tenant(), :p, :s, :r, :actor)
                            ON CONFLICT (tenant_id, product_code, stage) DO UPDATE SET rate_pct = EXCLUDED.rate_pct,
                                updated_by = EXCLUDED.updated_by, updated_at = clock_timestamp()
                            """).param("p", code).param("s", stage).param("r", rate).param("actor", actor).update();
                }
            }
        }
        return rates(tenantId);
    }

    // ------------------------------------------------------------------ calculation and runs

    /** What a run at {@code asOf} would provide, without recording anything (any closed day). */
    @Transactional(readOnly = true)
    public EclCalculation preview(UUID tenantId, LocalDate asOf) {
        session.bind(tenantId);
        Settings s = requireSettings();
        requireClosed(asOf);
        return calculate(s, asOf);
    }

    @Transactional
    public EclRunView request(UUID tenantId, String idempotencyKey, LocalDate asOf, String actor) {
        session.bind(tenantId);
        if (blank(idempotencyKey) || idempotencyKey.length() > 80) {
            throw new IllegalArgumentException("idempotencyKey (up to 80 characters) is required");
        }
        UUID existing = jdbc.sql("SELECT run_id FROM ledger.ecl_run WHERE idempotency_key = :k").param("k", idempotencyKey.strip())
            .query(UUID.class).optional().orElse(null);
        if (existing != null) {
            return view(existing, true);
        }
        Settings s = requireSettings();
        if (asOf == null || !asOf.equals(asOf.withDayOfMonth(asOf.lengthOfMonth()))) {
            throw new IllegalArgumentException("provision runs are for month ends (the last day of a month)");
        }
        requireClosed(asOf);
        if (open() != null) {
            throw new ConflictException("a provision run is already waiting for approval");
        }
        LocalDate latest = jdbc.sql("SELECT as_of FROM ledger.ecl_run WHERE status = 'POSTED' ORDER BY as_of DESC LIMIT 1").query(LocalDate.class)
            .optional().orElse(null);
        if (latest != null && !asOf.isAfter(latest)) {
            throw new ConflictException("provisions are already posted for " + latest + "; runs go forward month by month");
        }
        EclCalculation c = calculate(s, asOf);
        if (!c.productsWithoutRates().isEmpty()) {
            throw new ConflictException("no provision rate for products " + c.productsWithoutRates()
                    + " in the stages their loans are in; set them (or default rates) first");
        }
        UUID runId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO ledger.ecl_run (tenant_id, run_id, idempotency_key, as_of, allowance_account, expense_account, stage2_dpd,
                    stage3_dpd, restructure_probation_months, loans, required_total, previous_total, change, requested_by)
                VALUES (tenancy.current_tenant(), :id, :k, :d, :a, :e, :s2, :s3, :p, :n, :req, :prev, :chg, :actor)
                """).param("id", runId).param("k", idempotencyKey.strip()).param("d", asOf).param("a", s.allowance())
            .param("e", s.expense()).param("s2", s.stage2()).param("s3", s.stage3()).param("p", s.probation()).param("n", c.loans())
            .param("req", new BigDecimal(c.requiredTotal())).param("prev", new BigDecimal(c.currentAllowance()))
            .param("chg", new BigDecimal(c.change())).param("actor", actor).update();
        jdbc.sql("INSERT INTO ledger.ecl_run_line (tenant_id, run_id, loan_id, product_code, branch_code, days_past_due, stage, "
                + "exposure, rate_pct, ecl) SELECT tenancy.current_tenant(), :id, loan_id, product_code, branch_code, dpd, stage, "
                + "exposure, rate, ecl FROM (" + STAGED + ") staged")
            .param("id", runId).param("d", asOf).param("s2", s.stage2()).param("s3", s.stage3()).param("probation", s.probation()).update();
        return view(runId, false);
    }

    /** A second finance user approves (posting the change, branch by branch) or rejects a run. */
    @Transactional
    public EclRunView decide(UUID tenantId, UUID runId, boolean approve, String reason, String actor) {
        session.bind(tenantId);
        record Run(String status, String requestedBy, LocalDate asOf, String allowance, String expense) { }
        Run r = jdbc.sql("SELECT status, requested_by, as_of, allowance_account, expense_account FROM ledger.ecl_run WHERE run_id = :id FOR UPDATE")
            .param("id", runId)
            .query((rs, i) -> new Run(rs.getString(1), rs.getString(2), rs.getObject(3, LocalDate.class), rs.getString(4), rs.getString(5)))
            .optional().orElseThrow(() -> new NotFoundException("provision run not found"));
        if (!"REQUESTED".equals(r.status())) {
            throw new ConflictException("provision run is already " + r.status());
        }
        if (r.requestedBy().equals(actor)) {
            throw new AccessDeniedException("the person who requested the provision run cannot decide it");
        }
        String why = blank(reason) ? null : reason.strip();
        if (why != null && why.length() > 300) {
            throw new IllegalArgumentException("reason is at most 300 characters");
        }
        if (!approve) {
            if (why == null || why.length() < 3) {
                throw new IllegalArgumentException("a rejection needs a reason");
            }
            jdbc.sql("UPDATE ledger.ecl_run SET status = 'REJECTED', decided_by = :a, decided_at = clock_timestamp(), decision_reason = :w WHERE run_id = :id")
                .param("a", actor).param("w", why).param("id", runId).update();
            return view(runId, false);
        }
        Currency currency = Currency.getInstance(jdbc.sql("SELECT currency FROM ledger.gl_account WHERE account_code = :a")
            .param("a", r.allowance()).query(String.class).single());
        // Per branch: what this run provides less what the allowance already holds there.
        record Delta(String branch, BigDecimal amount) { }
        List<Delta> deltas = jdbc.sql("""
                SELECT COALESCE(w.branch_code, h.branch_code), COALESCE(w.wanted, 0) - COALESCE(h.held, 0)
                  FROM (SELECT branch_code, sum(ecl) AS wanted FROM ledger.ecl_run_line WHERE run_id = :id GROUP BY branch_code) w
                  FULL JOIN (SELECT branch_code, sum(CASE side WHEN 'C' THEN amount ELSE -amount END) AS held FROM ledger.journal_line
                              WHERE account_code = :a GROUP BY branch_code) h ON h.branch_code = w.branch_code
                 ORDER BY 1
                """).param("id", runId).param("a", r.allowance())
            .query((rs, i) -> new Delta(rs.getString(1), rs.getBigDecimal(2))).list()
            .stream().filter(d -> d.amount().signum() != 0).toList();
        UUID entryId = null;
        if (!deltas.isEmpty()) {
            List<JournalLine> lines = new ArrayList<>();
            for (Delta d : deltas) {
                Money m = Money.of(d.amount().abs(), currency);
                if (d.amount().signum() > 0) {
                    lines.add(JournalLine.debit(r.expense(), m, d.branch()));
                    lines.add(JournalLine.credit(r.allowance(), m, d.branch()));
                } else {
                    lines.add(JournalLine.debit(r.allowance(), m, d.branch()));
                    lines.add(JournalLine.credit(r.expense(), m, d.branch()));
                }
            }
            entryId = UUID.randomUUID();
            // The run row must name the entry before the entry's allowance lines are checked at commit.
            jdbc.sql("UPDATE ledger.ecl_run SET status = 'POSTED', decided_by = :a, decided_at = clock_timestamp(), decision_reason = :w, "
                    + "entry_id = :e WHERE run_id = :id")
                .param("a", actor).param("w", why).param("e", entryId).param("id", runId).update();
            journal.post(new JournalEntry(entryId, tenantId, "ecl:" + runId, r.asOf(), "PROVISION", "ecl:" + r.asOf(), null, null, lines),
                    actor);
        } else {
            jdbc.sql("UPDATE ledger.ecl_run SET status = 'POSTED', decided_by = :a, decided_at = clock_timestamp(), decision_reason = :w WHERE run_id = :id")
                .param("a", actor).param("w", why).param("id", runId).update();
        }
        return view(runId, false);
    }

    @Transactional(readOnly = true)
    public List<EclRunView> runs(UUID tenantId) {
        session.bind(tenantId);
        return jdbc.sql("SELECT run_id FROM ledger.ecl_run ORDER BY requested_at DESC LIMIT 24").query(UUID.class).list()
            .stream().map(id -> view(id, false)).toList();
    }

    @Transactional(readOnly = true)
    public EclRunView run(UUID tenantId, UUID runId) {
        session.bind(tenantId);
        return view(runId, false);
    }

    /** Every loan of a run, for finance and the auditors; largest provisions first. */
    @Transactional(readOnly = true)
    public List<EclLine> lines(UUID tenantId, UUID runId) {
        session.bind(tenantId);
        view(runId, false);
        return jdbc.sql("""
                SELECT l.loan_number, c.customer_number, x.product_code, x.branch_code, x.days_past_due, x.stage,
                       x.exposure, x.rate_pct, x.ecl
                  FROM ledger.ecl_run_line x
                  JOIN lending.loan l ON l.tenant_id = x.tenant_id AND l.loan_id = x.loan_id
                  LEFT JOIN customer.customer c ON c.tenant_id = l.tenant_id AND c.customer_id = l.customer_id
                 WHERE x.run_id = :id ORDER BY x.stage DESC, x.ecl DESC, l.loan_number
                """).param("id", runId)
            .query((rs, i) -> new EclLine(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                    rs.getInt(6), m(rs.getBigDecimal(7)), pct(rs.getBigDecimal(8)), m(rs.getBigDecimal(9))))
            .list();
    }

    // ------------------------------------------------------------------

    private EclCalculation calculate(Settings s, LocalDate asOf) {
        record L(String product, int stage, BigDecimal exposure, BigDecimal rate, BigDecimal ecl) { }
        List<L> lines = jdbc.sql(STAGED).param("d", asOf).param("s2", s.stage2()).param("s3", s.stage3()).param("probation", s.probation())
            .query((rs, i) -> new L(rs.getString("product_code"), rs.getInt("stage"), rs.getBigDecimal("exposure"),
                    rs.getBigDecimal("rate"), rs.getBigDecimal("ecl")))
            .list();
        BigDecimal[] stageExposure = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        BigDecimal[] stageEcl = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        int[] stageLoans = new int[3];
        Map<String, Object[]> byProduct = new TreeMap<>();
        List<String> missing = new ArrayList<>();
        for (L l : lines) {
            int k = l.stage() - 1;
            stageLoans[k]++;
            stageExposure[k] = stageExposure[k].add(l.exposure());
            BigDecimal ecl = l.ecl() == null ? BigDecimal.ZERO : l.ecl();
            stageEcl[k] = stageEcl[k].add(ecl);
            if (l.rate() == null && !missing.contains(l.product() + " stage " + l.stage())) {
                missing.add(l.product() + " stage " + l.stage());
            }
            Object[] p = byProduct.computeIfAbsent(l.product() + "|" + l.stage(),
                    key -> new Object[] {0, BigDecimal.ZERO, l.rate(), BigDecimal.ZERO});
            p[0] = (int) p[0] + 1;
            p[1] = ((BigDecimal) p[1]).add(l.exposure());
            p[3] = ((BigDecimal) p[3]).add(ecl);
        }
        List<EclStageRow> stages = new ArrayList<>();
        BigDecimal exposure = BigDecimal.ZERO;
        BigDecimal required = BigDecimal.ZERO;
        for (int k = 0; k < 3; k++) {
            stages.add(new EclStageRow(k + 1, stageLoans[k], m(stageExposure[k]), m(stageEcl[k]), coverage(stageEcl[k], stageExposure[k])));
            exposure = exposure.add(stageExposure[k]);
            required = required.add(stageEcl[k]);
        }
        List<EclProductRow> products = new ArrayList<>();
        byProduct.forEach((key, p) -> {
            String[] parts = key.split("\\|");
            products.add(new EclProductRow(parts[0], Integer.parseInt(parts[1]), (int) p[0], m((BigDecimal) p[1]),
                    p[2] == null ? null : pct((BigDecimal) p[2]), m((BigDecimal) p[3])));
        });
        BigDecimal held = jdbc.sql("SELECT COALESCE(sum(change), 0) FROM ledger.ecl_run WHERE status = 'POSTED' AND allowance_account = :a")
            .param("a", s.allowance()).query(BigDecimal.class).single();
        return new EclCalculation(asOf, asOf.equals(asOf.withDayOfMonth(asOf.lengthOfMonth())), s.stage2(), s.stage3(), lines.size(),
                m(exposure), m(required), m(held), m(required.subtract(held)), stages, products, missing);
    }

    private EclRunView view(UUID runId, boolean replayed) {
        record R(LocalDate asOf, String status, int loans, BigDecimal required, BigDecimal previous, BigDecimal change, UUID entry,
                 String requestedBy, OffsetDateTime requestedAt, String decidedBy, OffsetDateTime decidedAt, String reason) { }
        R r = jdbc.sql("""
                SELECT as_of, status, loans, required_total, previous_total, change, entry_id, requested_by, requested_at, decided_by,
                       decided_at, decision_reason FROM ledger.ecl_run WHERE run_id = :id
                """).param("id", runId)
            .query((rs, i) -> new R(rs.getObject(1, LocalDate.class), rs.getString(2), rs.getInt(3), rs.getBigDecimal(4),
                    rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getObject(7, UUID.class), rs.getString(8),
                    rs.getObject(9, OffsetDateTime.class), rs.getString(10), rs.getObject(11, OffsetDateTime.class), rs.getString(12)))
            .optional().orElseThrow(() -> new NotFoundException("provision run not found"));
        List<EclStageRow> stages = jdbc.sql("""
                SELECT g.stage, count(x.loan_id), COALESCE(sum(x.exposure), 0), COALESCE(sum(x.ecl), 0)
                  FROM generate_series(1, 3) g(stage)
                  LEFT JOIN ledger.ecl_run_line x ON x.stage = g.stage AND x.run_id = :id
                 GROUP BY g.stage ORDER BY g.stage
                """).param("id", runId)
            .query((rs, i) -> new EclStageRow(rs.getInt(1), rs.getInt(2), m(rs.getBigDecimal(3)), m(rs.getBigDecimal(4)),
                    coverage(rs.getBigDecimal(4), rs.getBigDecimal(3))))
            .list();
        return new EclRunView(runId, r.asOf(), r.status(), r.loans(), m(r.required()), m(r.previous()), m(r.change()), r.entry(),
                r.requestedBy(), r.requestedAt(), r.decidedBy(), r.decidedAt(), r.reason(), stages, replayed);
    }

    private Settings requireSettings() {
        return jdbc.sql("""
                SELECT s.allowance_account, s.expense_account, s.stage2_dpd, s.stage3_dpd, s.restructure_probation_months, a.currency
                  FROM ledger.ecl_settings s
                  JOIN ledger.gl_account a ON a.tenant_id = s.tenant_id AND a.account_code = s.allowance_account
                """)
            .query((rs, i) -> new Settings(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5),
                    Currency.getInstance(rs.getString(6))))
            .optional().orElseThrow(() -> new ConflictException("set the loss allowance and impairment expense accounts first"));
    }

    private void requireClosed(LocalDate asOf) {
        if (asOf == null) {
            throw new IllegalArgumentException("asOf is required");
        }
        boolean closed = jdbc.sql("SELECT EXISTS (SELECT 1 FROM ledger.eod_run WHERE business_date = :d AND status = 'COMPLETED')")
            .param("d", asOf).query(Boolean.class).single();
        if (!closed) {
            throw new ConflictException(asOf + " has not been closed by end of day; provisions use closed days only");
        }
    }

    private UUID open() {
        return jdbc.sql("SELECT run_id FROM ledger.ecl_run WHERE status = 'REQUESTED'").query(UUID.class).optional().orElse(null);
    }

    private static BigDecimal rate(String value, String product, int stage) {
        if (blank(value)) {
            return null;
        }
        BigDecimal r;
        try {
            r = new BigDecimal(value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("rate for " + product + " stage " + stage + " is not a number");
        }
        if (r.signum() < 0 || r.compareTo(HUNDRED) > 0 || r.stripTrailingZeros().scale() > 4) {
            throw new IllegalArgumentException("rate for " + product + " stage " + stage + " must be 0 to 100 with at most 4 decimals");
        }
        return r;
    }

    private static String coverage(BigDecimal ecl, BigDecimal exposure) {
        return exposure.signum() == 0 ? "0.00" : ecl.multiply(HUNDRED).divide(exposure, 2, RoundingMode.HALF_EVEN).toPlainString();
    }

    private static String m(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }

    private static String pct(BigDecimal v) {
        return v.stripTrailingZeros().scale() < 0 ? v.setScale(0).toPlainString() : v.stripTrailingZeros().toPlainString();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
