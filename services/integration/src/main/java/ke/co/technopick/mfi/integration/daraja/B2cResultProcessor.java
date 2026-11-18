package ke.co.technopick.mfi.integration.daraja;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
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

/** Applies stored B2C results and queue timeouts in core, with retries and dead letters (as C2B/STK). */
@Component
public class B2cResultProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(B2cResultProcessor.class);

    private record Pending(UUID id, UUID tenantId, String type, String disbursementId, String payload) { }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final CoreClient core;
    private final DarajaProperties props;
    private final JsonMapper json = JsonMapper.builder().build();

    public B2cResultProcessor(JdbcClient jdbc, PlatformTransactionManager txManager, CoreClient core,
                              DarajaProperties props) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.core = core;
        this.props = props;
    }

    public int processPending() {
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

    private Boolean processOne(OffsetDateTime passStart) {
        Optional<Pending> next = jdbc.sql("""
                SELECT callback_id, tenant_id, callback_type, route_ref, payload::text AS payload
                  FROM integration.provider_callback
                 WHERE provider = 'MPESA' AND callback_type IN ('B2C_RESULT', 'B2C_TIMEOUT') AND status <> 'APPLIED'
                   AND attempts < :max
                   AND (last_attempt_at IS NULL OR (last_attempt_at < :passStart
                        AND last_attempt_at <= clock_timestamp() - make_interval(secs => :backoff)))
                 ORDER BY received_at
                 LIMIT 1
                   FOR UPDATE SKIP LOCKED
                """)
            .param("max", props.maxAttempts())
            .param("passStart", passStart)
            .param("backoff", (double) props.retryBackoff().toMillis() / 1000.0)
            .query((rs, n) -> new Pending(rs.getObject("callback_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                    rs.getString("callback_type"), rs.getString("route_ref"), rs.getString("payload")))
            .optional();
        if (next.isEmpty()) {
            return null;
        }
        Pending p = next.get();
        String base = "/api/v1/payments/mpesa/b2c/disbursements/" + p.disbursementId();
        try {
            Map<String, Object> response = "B2C_TIMEOUT".equals(p.type())
                    ? core.post(base + "/timeout", Map.of("tenantId", p.tenantId().toString()))
                    : core.post(base + "/result", toCore(p.tenantId(), p.payload()));
            mark(p.id(), "APPLIED", null, String.valueOf(response.get("status")));
            return true;
        } catch (RuntimeException e) {
            String error = e.getClass().getSimpleName() + ": " + e.getMessage();
            mark(p.id(), "FAILED", error.length() > 500 ? error.substring(0, 500) : error, null);
            LOG.warn("B2C callback {} not applied yet: {}", p.id(), error);
            return false;
        }
    }

    private void mark(UUID id, String status, String error, String outcome) {
        jdbc.sql("""
                UPDATE integration.provider_callback
                   SET status = :status, attempts = attempts + 1, last_attempt_at = clock_timestamp(),
                       last_error = :error, outcome = :outcome
                 WHERE callback_id = :id
                """)
            .param("status", status).param("error", error).param("outcome", outcome).param("id", id).update();
    }

    /** Maps Daraja's {@code Result} to core's result request. The receiver's name and number are not forwarded. */
    @SuppressWarnings("unchecked")
    Map<String, Object> toCore(UUID tenantId, String payload) {
        Map<String, Object> result = (Map<String, Object>) json.readValue(payload, Map.class).get("Result");
        String code = String.valueOf(result.get("ResultCode"));
        Map<String, Object> request = new HashMap<>();
        request.put("tenantId", tenantId.toString());
        request.put("conversationId", String.valueOf(result.get("ConversationID")));
        request.put("resultCode", code);
        String desc = String.valueOf(result.get("ResultDesc"));
        request.put("resultDesc", desc.length() > 200 ? desc.substring(0, 200) : desc);
        if ("0".equals(code)) {
            request.put("transactionId", String.valueOf(result.get("TransactionID")));
            if (result.get("ResultParameters") instanceof Map<?, ?> params && params.get("ResultParameter") instanceof List<?> list) {
                for (Object o : list) {
                    Map<String, Object> kv = (Map<String, Object>) o;
                    if ("TransactionAmount".equals(kv.get("Key"))) {
                        request.put("amount", new BigDecimal(String.valueOf(kv.get("Value")))
                                .setScale(2, RoundingMode.UNNECESSARY).toPlainString());
                    }
                }
            }
        }
        return request;
    }
}
