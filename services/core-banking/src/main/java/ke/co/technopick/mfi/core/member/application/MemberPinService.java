package ke.co.technopick.mfi.core.member.application;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import ke.co.technopick.mfi.core.common.ConflictException;
import ke.co.technopick.mfi.core.common.NotFoundException;
import ke.co.technopick.mfi.core.tenancy.persistence.TenantSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Member PIN administration by staff (ADR-0034). Staff never see or choose a PIN: one person asks
 * for a PIN setup (first time or reset), a second person approves it, and that opens a window in
 * which the member chooses their own PIN on USSD from their registered phone.
 */
@Service
public class MemberPinService {

    public record SetupView(UUID setupId, String customerNumber, String customerName, String kind, String reason, String status,
                            String requestedBy, OffsetDateTime requestedAt, String decidedBy, OffsetDateTime decidedAt,
                            String decisionReason, boolean replayed) { }

    public record ChannelEvent(OffsetDateTime occurredAt, String event, String detail) { }

    /**
     * NONE (never set up), SETUP_OPEN (the member may choose a PIN until setupExpiresAt), SETUP_EXPIRED,
     * ACTIVE, LOCKED (too many wrong attempts; needs a reset).
     */
    public record PinStatus(String customerNumber, String status, int failedAttempts, OffsetDateTime setupExpiresAt,
                            OffsetDateTime pinSetAt, OffsetDateTime lockedAt, int maxAttempts, SetupView pendingSetup,
                            List<SetupView> setups, List<ChannelEvent> recentEvents) { }

    private static final String SETUP_SELECT = """
            SELECT s.setup_id, c.customer_number, c.first_name || ' ' || c.last_name, s.kind, s.reason, s.status,
                   s.requested_by, s.requested_at, s.decided_by, s.decided_at, s.decision_reason
              FROM member.pin_setup s JOIN customer.customer c ON c.tenant_id = s.tenant_id AND c.customer_id = s.customer_id
            """;

    private final JdbcClient jdbc;
    private final TenantSession session;
    private final Duration setupWindow;
    private final int maxAttempts;

    public MemberPinService(JdbcClient jdbc, TenantSession session,
                            @Value("${mfi.ussd.pin-setup-hours:24}") int setupHours,
                            @Value("${mfi.ussd.max-pin-attempts:3}") int maxAttempts) {
        if (setupHours < 1 || setupHours > 168) {
            throw new IllegalStateException("mfi.ussd.pin-setup-hours must be 1 to 168");
        }
        this.jdbc = jdbc;
        this.session = session;
        this.setupWindow = Duration.ofHours(setupHours);
        this.maxAttempts = maxAttempts;
    }

    @Transactional(readOnly = true)
    public PinStatus status(UUID tenantId, String customerNumber) {
        session.bind(tenantId);
        UUID customerId = customerId(customerNumber);
        record P(String status, int failed, OffsetDateTime expires, OffsetDateTime setAt, OffsetDateTime lockedAt) { }
        P p = jdbc.sql("""
                SELECT status, failed_attempts, setup_expires_at, pin_set_at, locked_at FROM member.pin WHERE customer_id = :c
                """).param("c", customerId)
            .query((rs, i) -> new P(rs.getString(1), rs.getInt(2), rs.getObject(3, OffsetDateTime.class),
                    rs.getObject(4, OffsetDateTime.class), rs.getObject(5, OffsetDateTime.class)))
            .optional().orElse(null);
        List<SetupView> setups = jdbc.sql(SETUP_SELECT + " WHERE s.customer_id = :c ORDER BY s.requested_at DESC LIMIT 10")
            .param("c", customerId).query((rs, i) -> setup(rs, false)).list();
        List<ChannelEvent> events = jdbc.sql("""
                SELECT occurred_at, event, detail FROM member.ussd_event WHERE customer_id = :c ORDER BY event_id DESC LIMIT 20
                """).param("c", customerId)
            .query((rs, i) -> new ChannelEvent(rs.getObject(1, OffsetDateTime.class), rs.getString(2), rs.getString(3))).list();
        SetupView pending = setups.stream().filter(s -> "REQUESTED".equals(s.status())).findFirst().orElse(null);
        String status = p == null ? "NONE" : p.status();
        if ("SETUP_OPEN".equals(status) && !p.expires().isAfter(OffsetDateTime.now())) {
            status = "SETUP_EXPIRED";
        }
        return new PinStatus(customerNumber, status, p == null ? 0 : p.failed(), p == null ? null : p.expires(),
                p == null ? null : p.setAt(), p == null ? null : p.lockedAt(), maxAttempts, pending, setups, events);
    }

    @Transactional(readOnly = true)
    public List<SetupView> pending(UUID tenantId) {
        session.bind(tenantId);
        return jdbc.sql(SETUP_SELECT + " WHERE s.status = 'REQUESTED' ORDER BY s.requested_at").query((rs, i) -> setup(rs, false)).list();
    }

    @Transactional
    public SetupView request(UUID tenantId, String customerNumber, String idempotencyKey, String reason, String actor) {
        session.bind(tenantId);
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 80) {
            throw new IllegalArgumentException("idempotencyKey (up to 80 characters) is required");
        }
        if (reason == null || reason.strip().length() < 10 || reason.length() > 300) {
            throw new IllegalArgumentException("a reason of 10 to 300 characters is required, e.g. how the member's identity was checked");
        }
        UUID customerId = customerId(customerNumber);
        var existing = jdbc.sql(SETUP_SELECT + " WHERE s.idempotency_key = :k").param("k", idempotencyKey.strip())
            .query((rs, i) -> setup(rs, true)).optional();
        if (existing.isPresent()) {
            if (!existing.get().customerNumber().equals(customerNumber)) {
                throw new ConflictException("idempotency key already used for another member");
            }
            return existing.get();
        }
        String status = jdbc.sql("SELECT status FROM customer.customer WHERE customer_id = :c").param("c", customerId)
            .query(String.class).single();
        if (!"ACTIVE".equals(status)) {
            throw new ConflictException("a PIN is set up only for an active (verified) member; this member is " + status);
        }
        boolean open = jdbc.sql("SELECT EXISTS (SELECT 1 FROM member.pin_setup WHERE customer_id = :c AND status = 'REQUESTED')")
            .param("c", customerId).query(Boolean.class).single();
        if (open) {
            throw new ConflictException("this member already has a PIN setup waiting for approval");
        }
        boolean hasPin = jdbc.sql("SELECT EXISTS (SELECT 1 FROM member.pin WHERE customer_id = :c)").param("c", customerId)
            .query(Boolean.class).single();
        UUID setupId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO member.pin_setup (tenant_id, setup_id, idempotency_key, customer_id, kind, reason, requested_by)
                VALUES (tenancy.current_tenant(), :id, :k, :c, :kind, :reason, :actor)
                """).param("id", setupId).param("k", idempotencyKey.strip()).param("c", customerId)
            .param("kind", hasPin ? "RESET" : "FIRST").param("reason", reason.strip()).param("actor", actor).update();
        return view(setupId);
    }

    /** Approval opens the member's PIN window; any earlier PIN stops working at once. */
    @Transactional
    public SetupView decide(UUID tenantId, UUID setupId, boolean approve, String reason, String actor) {
        session.bind(tenantId);
        record S(UUID customerId, String status, String requestedBy) { }
        S s = jdbc.sql("SELECT customer_id, status, requested_by FROM member.pin_setup WHERE setup_id = :id FOR UPDATE")
            .param("id", setupId)
            .query((rs, i) -> new S(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)))
            .optional().orElseThrow(() -> new NotFoundException("PIN setup not found"));
        if (!"REQUESTED".equals(s.status())) {
            throw new ConflictException("PIN setup is already " + s.status());
        }
        if (s.requestedBy().equals(actor)) {
            throw new AccessDeniedException("the person who asked for the PIN setup cannot decide it");
        }
        String why = reason == null || reason.isBlank() ? null : reason.strip();
        if (why != null && why.length() > 300) {
            throw new IllegalArgumentException("reason is at most 300 characters");
        }
        if (!approve && (why == null || why.length() < 3)) {
            throw new IllegalArgumentException("a rejection needs a reason");
        }
        jdbc.sql("""
                UPDATE member.pin_setup SET status = :st, decided_by = :actor, decided_at = clock_timestamp(), decision_reason = :why
                 WHERE setup_id = :id
                """).param("st", approve ? "APPROVED" : "REJECTED").param("actor", actor).param("why", why)
            .param("id", setupId).update();
        if (approve) {
            jdbc.sql("""
                    INSERT INTO member.pin (tenant_id, customer_id, status, setup_id, setup_expires_at)
                    VALUES (tenancy.current_tenant(), :c, 'SETUP_OPEN', :s, clock_timestamp() + make_interval(secs => :secs))
                    ON CONFLICT (tenant_id, customer_id) DO UPDATE
                       SET status = 'SETUP_OPEN', setup_id = EXCLUDED.setup_id, setup_expires_at = EXCLUDED.setup_expires_at,
                           pin_hash = NULL, failed_attempts = 0, locked_at = NULL
                    """).param("c", s.customerId()).param("s", setupId).param("secs", setupWindow.toSeconds()).update();
        }
        return view(setupId);
    }

    private SetupView view(UUID setupId) {
        return jdbc.sql(SETUP_SELECT + " WHERE s.setup_id = :id").param("id", setupId).query((rs, i) -> setup(rs, false)).single();
    }

    private UUID customerId(String customerNumber) {
        return jdbc.sql("SELECT customer_id FROM customer.customer WHERE customer_number = :n")
            .param("n", customerNumber == null ? "" : customerNumber.strip())
            .query(UUID.class).optional().orElseThrow(() -> new NotFoundException("customer not found"));
    }

    private static SetupView setup(java.sql.ResultSet rs, boolean replayed) throws java.sql.SQLException {
        return new SetupView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getObject(8, OffsetDateTime.class), rs.getString(9),
                rs.getObject(10, OffsetDateTime.class), rs.getString(11), replayed);
    }
}
