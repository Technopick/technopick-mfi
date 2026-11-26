package ke.co.technopick.mfi.core.ops.application;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Per-tenant operational figures for monitoring (ADR-0045): how far end of day is behind, runs
 * that hang, reconciliation differences, events not yet published, SMS and M-Pesa payouts that are
 * overdue, and whether audit chains verify. The figures come from the database's ops functions
 * (V42), which return counts only, and are published as Prometheus gauges tagged by tenant code.
 * They are refreshed on a schedule, never on a scrape, so scraping costs the database nothing.
 */
@Component
public class OperationalHealth {

    /** One figure for one tenant; value is null when the tenant has nothing to measure yet. */
    public record Figure(String tenant, String metric, BigDecimal value) { }

    public record ChainStatus(String tenant, Long brokenAt, long links) { }

    private static final Logger log = LoggerFactory.getLogger(OperationalHealth.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final MeterRegistry registry;
    private final Clock clock;
    private final Map<String, MultiGauge> gauges = new ConcurrentHashMap<>();
    private final AtomicLong snapshotAt = new AtomicLong();
    private final AtomicLong chainsAt = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    public OperationalHealth(JdbcClient jdbc, PlatformTransactionManager transactions, MeterRegistry registry, Clock clock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setTimeout(60);
        this.registry = registry;
        this.clock = clock;
        Gauge.builder("mfi.ops.snapshot.last.success.seconds", snapshotAt, AtomicLong::get)
            .description("When the operational figures were last read (epoch seconds; 0 = never)").register(registry);
        Gauge.builder("mfi.audit.chain.last.check.seconds", chainsAt, AtomicLong::get)
            .description("When the audit chains were last verified (epoch seconds; 0 = never)").register(registry);
        Gauge.builder("mfi.ops.snapshot.failures", failures, AtomicLong::get)
            .description("Failed reads of the operational figures since start").register(registry);
    }

    /** Reads every tenant's figures and publishes them; returns what was read. */
    public List<Figure> refresh() {
        try {
            List<Figure> figures = tx.execute(s -> jdbc.sql("SELECT tenant_code, metric, value FROM ops.health_snapshot()")
                .query((rs, n) -> new Figure(rs.getString(1), rs.getString(2), rs.getBigDecimal(3))).list());
            Map<String, List<MultiGauge.Row<?>>> rows = new LinkedHashMap<>();
            for (Figure f : figures) {
                List<MultiGauge.Row<?>> list = rows.computeIfAbsent(f.metric(), k -> new ArrayList<>());
                if (f.value() != null) {
                    list.add(MultiGauge.Row.of(Tags.of("tenant", f.tenant()), f.value().doubleValue()));
                }
            }
            rows.forEach((metric, list) -> gauge("mfi." + metric.replace('_', '.')).register(list, true));
            snapshotAt.set(clock.instant().getEpochSecond());
            return figures;
        } catch (RuntimeException e) {
            failures.incrementAndGet();
            log.warn("OPS_METRICS: could not read operational figures: {}", e.toString());
            throw e;
        }
    }

    /** Verifies every tenant's audit chain (slow: hourly) and publishes 1 for a broken chain. */
    public List<ChainStatus> refreshAuditChains() {
        try {
            List<ChainStatus> status = tx.execute(s -> jdbc.sql("SELECT tenant_code, broken_at, links FROM ops.audit_chain_status()")
                .query((rs, n) -> new ChainStatus(rs.getString(1), rs.getObject(2, Long.class), rs.getLong(3))).list());
            List<MultiGauge.Row<?>> broken = new ArrayList<>();
            List<MultiGauge.Row<?>> links = new ArrayList<>();
            for (ChainStatus c : status) {
                broken.add(MultiGauge.Row.of(Tags.of("tenant", c.tenant()), c.brokenAt() == null ? 0 : 1));
                links.add(MultiGauge.Row.of(Tags.of("tenant", c.tenant()), c.links()));
                if (c.brokenAt() != null) {
                    log.error("AUDIT_CHAIN_BROKEN: tenant {} audit chain does not verify from link {}", c.tenant(), c.brokenAt());
                }
            }
            gauge("mfi.audit.chain.broken").register(broken, true);
            gauge("mfi.audit.chain.links").register(links, true);
            chainsAt.set(clock.instant().getEpochSecond());
            return status;
        } catch (RuntimeException e) {
            failures.incrementAndGet();
            log.warn("OPS_METRICS: could not verify audit chains: {}", e.toString());
            throw e;
        }
    }

    private MultiGauge gauge(String name) {
        return gauges.computeIfAbsent(name, n -> MultiGauge.builder(n).description("Operational figure per tenant (ADR-0045)")
            .register(registry));
    }
}
