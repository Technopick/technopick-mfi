package ke.co.technopick.mfi.core.audit.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import ke.co.technopick.mfi.core.tenancy.persistence.TenantSession;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Audit trail of staff actions (ADR-0027). Rows are written in their own transaction after the
 * request has been handled, so failed and refused requests are recorded too; the database chains
 * them with SHA-256 per tenant and refuses changes (V24).
 */
@Service
public class AuditTrailService {

    public record AuditRecord(UUID tenantId, OffsetDateTime occurredAt, String actor, String roles, String method,
                              String route, String path, String query, int status, int durationMs, String clientIp,
                              String requestId) { }

    public record StaffAction(long chainNo, OffsetDateTime occurredAt, String actor, String roles, String method, String route,
                              String path, String query, int status, int durationMs, String clientIp, String requestId) { }

    public record ChainStatus(boolean intact, Long firstBrokenChainNo, long rows) { }

    static final int MAX_ROWS = 500;

    private final JdbcClient jdbc;
    private final TenantSession session;

    public AuditTrailService(JdbcClient jdbc, TenantSession session) {
        this.jdbc = jdbc;
        this.session = session;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditRecord r) {
        session.bind(r.tenantId());
        jdbc.sql("""
                INSERT INTO audit.staff_action (tenant_id, occurred_at, actor, roles, method, route, path, query, status,
                    duration_ms, client_ip, request_id, prev_hash, hash)
                VALUES (:t, :at, :actor, :roles, :method, :route, :path, :query, :status, :ms, :ip, :rid, '', '')
                """)
            .param("t", r.tenantId()).param("at", r.occurredAt()).param("actor", cut(r.actor(), 100))
            .param("roles", cut(r.roles(), 300)).param("method", r.method()).param("route", cut(r.route(), 200))
            .param("path", cut(r.path(), 500)).param("query", cut(r.query(), 500)).param("status", r.status())
            .param("ms", r.durationMs()).param("ip", cut(r.clientIp(), 64)).param("rid", r.requestId()).update();
    }

    @Transactional(readOnly = true)
    public List<StaffAction> search(UUID tenantId, String actor, OffsetDateTime from, OffsetDateTime to, String method,
                                    String pathContains, Boolean refusedOnly) {
        session.bind(tenantId);
        return jdbc.sql("""
                SELECT chain_no, occurred_at, actor, roles, method, route, path, query, status, duration_ms, client_ip, request_id
                  FROM audit.staff_action
                 WHERE (CAST(:actor AS varchar) IS NULL OR actor = :actor)
                   AND (CAST(:from AS timestamptz) IS NULL OR occurred_at >= :from)
                   AND (CAST(:to AS timestamptz) IS NULL OR occurred_at < :to)
                   AND (CAST(:method AS varchar) IS NULL OR method = :method)
                   AND (CAST(:path AS varchar) IS NULL OR path ILIKE '%' || :path || '%')
                   AND (NOT :refused OR status >= 400)
                 ORDER BY chain_no DESC
                 LIMIT :limit
                """)
            .param("actor", blank(actor)).param("from", from).param("to", to).param("method", blank(method))
            .param("path", blank(pathContains) == null ? null : pathContains.strip().replace("%", "").replace("_", "\\_"))
            .param("refused", Boolean.TRUE.equals(refusedOnly)).param("limit", MAX_ROWS)
            .query((rs, i) -> new StaffAction(rs.getLong(1), rs.getObject(2, OffsetDateTime.class), rs.getString(3),
                    rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getInt(9),
                    rs.getInt(10), rs.getString(11), rs.getString(12)))
            .list();
    }

    @Transactional(readOnly = true)
    public ChainStatus verify(UUID tenantId) {
        session.bind(tenantId);
        Long broken = jdbc.sql("SELECT audit.verify_chain(:t)").param("t", tenantId).query(Long.class).optional().orElse(null);
        long rows = jdbc.sql("SELECT count(*) FROM audit.staff_action").query(Long.class).single();
        return new ChainStatus(broken == null, broken, rows);
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
