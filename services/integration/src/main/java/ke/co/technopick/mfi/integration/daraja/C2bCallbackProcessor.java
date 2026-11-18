package ke.co.technopick.mfi.integration.daraja;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ke.co.technopick.mfi.integration.core.CoreClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applies stored C2B confirmations in core banking, one callback per short transaction
 * (claimed FOR UPDATE SKIP LOCKED, so several nodes can run). Failures are retried after a
 * back-off up to max attempts; after that the callback stays FAILED as a dead letter for
 * operator replay. Core applies each TransID at most once, so retries are always safe.
 */
@Component
public class C2bCallbackProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(C2bCallbackProcessor.class);

    private record Pending(UUID id, UUID tenantId, String payload) { }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final CoreClient core;
    private final DarajaProperties props;
    private final JsonMapper json = JsonMapper.builder().build();

    public C2bCallbackProcessor(JdbcClient jdbc, PlatformTransactionManager txManager, CoreClient core,
                                DarajaProperties props) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.core = core;
        this.props = props;
    }

    /** Processes up to 100 due callbacks; returns how many were applied. */
    public int processPending() {
        // Each callback is tried at most once per pass, whatever the back-off.
        OffsetDateTime passStart = jdbc.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single();
        int applied = 0;
        for (int i = 0; i < 100; i++) {
            Boolean result = tx.execute(s -> processOne(passStart));
            if (result == null) {
                break;
            }
            if (result) {
                applied++;
            }
        }
        return applied;
    }

    /** null = nothing due; true = applied; false = failed (will retry). */
    private Boolean processOne(OffsetDateTime passStart) {
        Duration backoff = props.retryBackoff();
        Optional<Pending> next = jdbc.sql("""
                SELECT callback_id, tenant_id, payload::text AS payload FROM integration.provider_callback
                 WHERE provider = 'MPESA' AND callback_type = 'C2B_CONFIRMATION' AND status <> 'APPLIED'
                   AND attempts < :max
                   AND (last_attempt_at IS NULL OR (last_attempt_at < :passStart
                        AND last_attempt_at <= clock_timestamp() - make_interval(secs => :backoff)))
                 ORDER BY received_at
                 LIMIT 1
                   FOR UPDATE SKIP LOCKED
                """)
            .param("max", props.maxAttempts())
            .param("passStart", passStart)
            .param("backoff", (double) backoff.toMillis() / 1000.0)
            .query((rs, n) -> new Pending(rs.getObject("callback_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                    rs.getString("payload")))
            .optional();
        if (next.isEmpty()) {
            return null;
        }
        Pending p = next.get();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = json.readValue(p.payload(), Map.class);
            C2bCallback c = C2bCallback.from(p.tenantId(), body, p.payload());
            Map<String, Object> request = new HashMap<>();
            request.put("tenantId", c.tenantId().toString());
            request.put("transId", c.transId());
            request.put("shortCode", c.shortCode());
            request.put("amount", c.amount());
            request.put("currency", "KES");
            request.put("billRef", c.billRef());
            request.put("msisdnMasked", c.maskedMsisdn());
            request.put("transTime", c.transTimeWithOffset().toString());
            request.put("correlationId", "mpesa:" + c.transId());
            Map<String, Object> response = core.post("/api/v1/payments/mpesa/c2b", request);
            jdbc.sql("""
                    UPDATE integration.provider_callback
                       SET status = 'APPLIED', attempts = attempts + 1, last_attempt_at = clock_timestamp(),
                           last_error = NULL, outcome = :outcome
                     WHERE callback_id = :id
                    """)
                .param("outcome", String.valueOf(response.get("outcome")))
                .param("id", p.id())
                .update();
            return true;
        } catch (RuntimeException e) {
            String error = (e.getClass().getSimpleName() + ": " + e.getMessage());
            jdbc.sql("""
                    UPDATE integration.provider_callback
                       SET status = 'FAILED', attempts = attempts + 1, last_attempt_at = clock_timestamp(),
                           last_error = :error
                     WHERE callback_id = :id
                    """)
                .param("error", error.length() > 500 ? error.substring(0, 500) : error)
                .param("id", p.id())
                .update();
            LOG.warn("C2B callback {} not applied yet: {}", p.id(), error);
            return false;
        }
    }
}
