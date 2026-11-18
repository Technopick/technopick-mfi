package ke.co.technopick.mfi.core.savings.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import ke.co.technopick.mfi.core.tenancy.persistence.TenantSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Expires authorization holds past their expiry time, giving the reserved funds back.
 *
 * <p>Safe to run on several nodes at once: each tenant batch takes a transaction-scoped
 * advisory lock (others skip that tenant) and rows are claimed with SKIP LOCKED, so a hold
 * being completed or reversed by the switch at the same moment is never touched. The V2
 * trigger independently refuses to expire a hold before its expiry time.
 */
@Service
public class HoldExpiryService {

    private static final Logger LOG = LoggerFactory.getLogger(HoldExpiryService.class);
    static final int BATCH = 500;

    private final JdbcClient jdbc;
    private final TenantSession session;
    private final Counter expired;

    public HoldExpiryService(JdbcClient jdbc, TenantSession session, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.session = session;
        this.expired = Counter.builder("mfi.holds.expired").description("Authorization holds expired by the job")
            .register(meters);
    }

    public List<UUID> activeTenants() {
        return jdbc.sql("SELECT tenancy.active_tenant_ids()").query(UUID.class).list();
    }

    /** Expires up to one batch of due holds for one tenant; returns how many were expired. */
    @Transactional
    public int expireDue(UUID tenantId) {
        session.bind(tenantId);
        Boolean locked = jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtext('hold-expiry:' || :tenant))")
            .param("tenant", tenantId.toString())
            .query(Boolean.class)
            .single();
        if (!Boolean.TRUE.equals(locked)) {
            return 0;
        }
        int n = jdbc.sql("""
                UPDATE savings.account_hold SET status = 'EXPIRED'
                 WHERE hold_id IN (SELECT hold_id FROM savings.account_hold
                                    WHERE status = 'ACTIVE' AND kind = 'AUTHORIZATION'
                                      AND expires_at <= clock_timestamp()
                                    ORDER BY expires_at
                                    LIMIT :batch
                                      FOR UPDATE SKIP LOCKED)
                """)
            .param("batch", BATCH)
            .update();
        if (n > 0) {
            expired.increment(n);
            LOG.info("expired {} authorization holds for tenant {}", n, tenantId);
        }
        return n;
    }
}
