package ke.co.technopick.mfi.integration.daraja;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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

/**
 * Applies stored STK results in core, one per short transaction (SKIP LOCKED), with the same
 * retry / dead-letter behaviour as C2B confirmations. Core applies each result at most once.
 */
@Component
public class StkResultProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(StkResultProcessor.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId EAT = ZoneId.of("Africa/Nairobi");

    private record Pending(UUID id, UUID tenantId, String requestId, String payload) { }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final CoreClient core;
    private final DarajaProperties props;
    private final JsonMapper json = JsonMapper.builder().build();

    public StkResultProcessor(JdbcClient jdbc, PlatformTransactionManager txManager, CoreClient core,
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
                SELECT callback_id, tenant_id, route_ref, payload::text AS payload FROM integration.provider_callback
                 WHERE provider = 'MPESA' AND callback_type = 'STK_RESULT' AND status <> 'APPLIED'
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
                    rs.getString("route_ref"), rs.getString("payload")))
            .optional();
        if (next.isEmpty()) {
            return null;
        }
        Pending p = next.get();
        try {
            Map<String, Object> response = core.post("/api/v1/payments/mpesa/stk-requests/" + p.requestId() + "/result",
                    toCore(p.tenantId(), p.payload()));
            mark(p.id(), "APPLIED", null, String.valueOf(response.get("status")));
            return true;
        } catch (RuntimeException e) {
            String error = e.getClass().getSimpleName() + ": " + e.getMessage();
            mark(p.id(), "FAILED", error.length() > 500 ? error.substring(0, 500) : error, null);
            LOG.warn("STK result {} not applied yet: {}", p.id(), error);
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

    /** Maps Daraja's {@code Body.stkCallback} to core's result request. */
    @SuppressWarnings("unchecked")
    Map<String, Object> toCore(UUID tenantId, String payload) {
        Map<String, Object> root = json.readValue(payload, Map.class);
        Map<String, Object> cb = (Map<String, Object>) ((Map<String, Object>) root.get("Body")).get("stkCallback");
        Map<String, Object> request = new HashMap<>();
        request.put("tenantId", tenantId.toString());
        request.put("checkoutRequestId", String.valueOf(cb.get("CheckoutRequestID")));
        request.put("merchantRequestId", String.valueOf(cb.get("MerchantRequestID")));
        request.put("resultCode", String.valueOf(cb.get("ResultCode")));
        request.put("resultDesc", String.valueOf(cb.get("ResultDesc")));
        Object meta = cb.get("CallbackMetadata");
        if (meta instanceof Map<?, ?> m && m.get("Item") instanceof List<?> items) {
            for (Object o : items) {
                Map<String, Object> item = (Map<String, Object>) o;
                Object v = item.get("Value");
                switch (String.valueOf(item.get("Name"))) {
                    case "Amount" -> request.put("amount",
                            new BigDecimal(String.valueOf(v)).setScale(2, RoundingMode.UNNECESSARY).toPlainString());
                    case "MpesaReceiptNumber" -> request.put("receipt", String.valueOf(v));
                    case "TransactionDate" -> request.put("transactionTime",
                            LocalDateTime.parse(String.valueOf(v), STAMP).atZone(EAT).toOffsetDateTime().toString());
                    default -> { } // PhoneNumber deliberately not forwarded: core already holds the masked number
                }
            }
        }
        return request;
    }
}
