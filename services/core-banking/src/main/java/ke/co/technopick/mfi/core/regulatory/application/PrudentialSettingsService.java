package ke.co.technopick.mfi.core.regulatory.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import ke.co.technopick.mfi.core.regulatory.application.PrudentialReturnService.PrudentialSettingsView;
import ke.co.technopick.mfi.core.regulatory.domain.PrudentialRules.Settings;
import ke.co.technopick.mfi.core.tenancy.persistence.TenantSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The tenant's prudential settings and the mapping of ledger accounts to return lines (ADR-0030).
 * Until finance saves settings, the regulation defaults apply and the report says so.
 */
@Service
public class PrudentialSettingsService {

    private static final Logger LOG = LoggerFactory.getLogger(PrudentialSettingsService.class);

    record Stored(Settings settings, boolean defaults, String updatedBy, OffsetDateTime updatedAt) { }

    public record PrudentialSettingsRequest(String minCoreCapital, String minCoreToAssetsPct, String minInstitutionalToAssetsPct,
                                            String minCoreToDepositsPct, String minLiquidityPct, Integer watchMaxDpd,
                                            Integer substandardMaxDpd, Integer doubtfulMaxDpd, String provisionPerformingPct,
                                            String provisionWatchPct, String provisionSubstandardPct, String provisionDoubtfulPct,
                                            String provisionLossPct, Boolean includeCurrentYearSurplus) { }

    public record RegulatoryLine(String line, String accountClass, String description) { }

    public record MappedAccount(String accountCode, String name, String accountClass, String line) { }

    public record RegulatoryMapping(List<RegulatoryLine> lines, List<MappedAccount> accounts) { }

    public record MappingChange(String accountCode, String line) { }

    public record RegulatoryMappingRequest(List<MappingChange> changes) { }

    private final JdbcClient jdbc;
    private final TenantSession session;

    public PrudentialSettingsService(JdbcClient jdbc, TenantSession session) {
        this.jdbc = jdbc;
        this.session = session;
    }

    /** The current tenant's settings; the caller has bound the tenant. */
    Stored stored() {
        return jdbc.sql("""
                SELECT min_core_capital, min_core_to_assets_pct, min_institutional_to_assets_pct, min_core_to_deposits_pct,
                       min_liquidity_pct, watch_max_dpd, substandard_max_dpd, doubtful_max_dpd, provision_performing_pct,
                       provision_watch_pct, provision_substandard_pct, provision_doubtful_pct, provision_loss_pct,
                       include_current_year_surplus, updated_by, updated_at
                  FROM ledger.prudential_settings
                """)
            .query((rs, i) -> new Stored(new Settings(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                    rs.getBigDecimal(5), rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getBigDecimal(9), rs.getBigDecimal(10),
                    rs.getBigDecimal(11), rs.getBigDecimal(12), rs.getBigDecimal(13), rs.getBoolean(14)), false, rs.getString(15),
                    rs.getObject(16, OffsetDateTime.class)))
            .optional().orElse(new Stored(Settings.regulationDefaults(), true, null, null));
    }

    PrudentialSettingsView view(Stored st) {
        Settings s = st.settings();
        return new PrudentialSettingsView(s.minCoreCapital().toPlainString(), s.minCoreToAssetsPct().toPlainString(),
                s.minInstitutionalToAssetsPct().toPlainString(), s.minCoreToDepositsPct().toPlainString(),
                s.minLiquidityPct().toPlainString(), s.watchMaxDpd(), s.substandardMaxDpd(), s.doubtfulMaxDpd(),
                s.provisionPerformingPct().toPlainString(), s.provisionWatchPct().toPlainString(),
                s.provisionSubstandardPct().toPlainString(), s.provisionDoubtfulPct().toPlainString(),
                s.provisionLossPct().toPlainString(), s.includeCurrentYearSurplus(), st.defaults(), st.updatedBy(), st.updatedAt());
    }

    @Transactional(readOnly = true)
    public PrudentialSettingsView settings(UUID tenantId) {
        session.bind(tenantId);
        return view(stored());
    }

    private static BigDecimal amount(String field, String v) {
        if (v == null || !v.matches("^\\d{1,15}(\\.\\d{1,2})?$")) {
            throw new IllegalArgumentException(field + " must be a non-negative amount with at most two decimals");
        }
        return new BigDecimal(v);
    }

    @Transactional
    public PrudentialSettingsView updateSettings(UUID tenantId, String actor, PrudentialSettingsRequest r) {
        if (r == null || r.watchMaxDpd() == null || r.substandardMaxDpd() == null || r.doubtfulMaxDpd() == null
                || r.includeCurrentYearSurplus() == null) {
            throw new IllegalArgumentException("every setting is required");
        }
        Settings s = new Settings(amount("minCoreCapital", r.minCoreCapital()), amount("minCoreToAssetsPct", r.minCoreToAssetsPct()),
                amount("minInstitutionalToAssetsPct", r.minInstitutionalToAssetsPct()),
                amount("minCoreToDepositsPct", r.minCoreToDepositsPct()), amount("minLiquidityPct", r.minLiquidityPct()),
                r.watchMaxDpd(), r.substandardMaxDpd(), r.doubtfulMaxDpd(), amount("provisionPerformingPct", r.provisionPerformingPct()),
                amount("provisionWatchPct", r.provisionWatchPct()), amount("provisionSubstandardPct", r.provisionSubstandardPct()),
                amount("provisionDoubtfulPct", r.provisionDoubtfulPct()), amount("provisionLossPct", r.provisionLossPct()),
                r.includeCurrentYearSurplus());
        session.bind(tenantId);
        jdbc.sql("""
                INSERT INTO ledger.prudential_settings (tenant_id, min_core_capital, min_core_to_assets_pct,
                    min_institutional_to_assets_pct, min_core_to_deposits_pct, min_liquidity_pct, watch_max_dpd, substandard_max_dpd,
                    doubtful_max_dpd, provision_performing_pct, provision_watch_pct, provision_substandard_pct, provision_doubtful_pct,
                    provision_loss_pct, include_current_year_surplus, updated_by)
                VALUES (:t, :a, :b, :c, :d, :e, :f, :g, :h, :i, :j, :k, :l, :m, :n, :by)
                ON CONFLICT (tenant_id) DO UPDATE SET min_core_capital = EXCLUDED.min_core_capital,
                    min_core_to_assets_pct = EXCLUDED.min_core_to_assets_pct,
                    min_institutional_to_assets_pct = EXCLUDED.min_institutional_to_assets_pct,
                    min_core_to_deposits_pct = EXCLUDED.min_core_to_deposits_pct, min_liquidity_pct = EXCLUDED.min_liquidity_pct,
                    watch_max_dpd = EXCLUDED.watch_max_dpd, substandard_max_dpd = EXCLUDED.substandard_max_dpd,
                    doubtful_max_dpd = EXCLUDED.doubtful_max_dpd, provision_performing_pct = EXCLUDED.provision_performing_pct,
                    provision_watch_pct = EXCLUDED.provision_watch_pct, provision_substandard_pct = EXCLUDED.provision_substandard_pct,
                    provision_doubtful_pct = EXCLUDED.provision_doubtful_pct, provision_loss_pct = EXCLUDED.provision_loss_pct,
                    include_current_year_surplus = EXCLUDED.include_current_year_surplus, updated_by = EXCLUDED.updated_by,
                    updated_at = clock_timestamp()
                """)
            .param("t", tenantId).param("a", s.minCoreCapital()).param("b", s.minCoreToAssetsPct())
            .param("c", s.minInstitutionalToAssetsPct()).param("d", s.minCoreToDepositsPct()).param("e", s.minLiquidityPct())
            .param("f", s.watchMaxDpd()).param("g", s.substandardMaxDpd()).param("h", s.doubtfulMaxDpd())
            .param("i", s.provisionPerformingPct()).param("j", s.provisionWatchPct()).param("k", s.provisionSubstandardPct())
            .param("l", s.provisionDoubtfulPct()).param("m", s.provisionLossPct()).param("n", s.includeCurrentYearSurplus())
            .param("by", actor).update();
        LOG.info("Prudential settings for tenant {} changed by {}", tenantId, actor);
        return view(stored());
    }

    @Transactional(readOnly = true)
    public RegulatoryMapping mapping(UUID tenantId) {
        session.bind(tenantId);
        List<RegulatoryLine> lines = jdbc.sql("SELECT line, account_class, description FROM ledger.regulatory_line ORDER BY account_class, line")
            .query((rs, i) -> new RegulatoryLine(rs.getString(1), rs.getString(2), rs.getString(3))).list();
        List<MappedAccount> accounts = jdbc.sql("""
                SELECT a.account_code, a.name, a.account_class, m.line
                  FROM ledger.gl_account a LEFT JOIN ledger.regulatory_mapping m
                    ON m.tenant_id = a.tenant_id AND m.account_code = a.account_code
                 WHERE a.account_class IN ('ASSET', 'LIABILITY', 'EQUITY')
                 ORDER BY a.account_code
                """).query((rs, i) -> new MappedAccount(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))).list();
        return new RegulatoryMapping(lines, accounts);
    }

    /** Sets or clears (line null) the line of each listed account, all or nothing. */
    @Transactional
    public RegulatoryMapping updateMapping(UUID tenantId, String actor, RegulatoryMappingRequest r) {
        if (r == null || r.changes() == null || r.changes().isEmpty() || r.changes().size() > 500) {
            throw new IllegalArgumentException("changes must list between 1 and 500 accounts");
        }
        session.bind(tenantId);
        for (MappingChange c : r.changes()) {
            if (c == null || c.accountCode() == null || c.accountCode().isBlank()) {
                throw new IllegalArgumentException("every change needs an accountCode");
            }
            boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM ledger.gl_account WHERE account_code = :c)")
                .param("c", c.accountCode()).query(Boolean.class).single();
            if (!exists) {
                throw new IllegalArgumentException("unknown account " + c.accountCode());
            }
            if (c.line() == null) {
                jdbc.sql("DELETE FROM ledger.regulatory_mapping WHERE account_code = :c").param("c", c.accountCode()).update();
                continue;
            }
            boolean known = jdbc.sql("SELECT EXISTS (SELECT 1 FROM ledger.regulatory_line WHERE line = :l)")
                .param("l", c.line()).query(Boolean.class).single();
            if (!known) {
                throw new IllegalArgumentException("unknown return line " + c.line());
            }
            jdbc.sql("""
                    INSERT INTO ledger.regulatory_mapping (tenant_id, account_code, line, updated_by)
                    VALUES (:t, :c, :l, :by)
                    ON CONFLICT (tenant_id, account_code) DO UPDATE SET line = EXCLUDED.line, updated_by = EXCLUDED.updated_by,
                        updated_at = clock_timestamp()
                    """).param("t", tenantId).param("c", c.accountCode()).param("l", c.line()).param("by", actor).update();
        }
        LOG.info("Regulatory mapping for tenant {}: {} account(s) changed by {}", tenantId, r.changes().size(), actor);
        return mapping(tenantId);
    }
}
