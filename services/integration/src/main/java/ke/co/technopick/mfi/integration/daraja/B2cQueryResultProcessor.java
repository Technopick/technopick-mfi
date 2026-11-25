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

/**
 * Forwards stored Transaction Status and Account Balance answers to core, with retries and dead
 * letters as for the other callbacks (ADR-0039). Parties' names and numbers in the answers are not
 * forwarded.
 */
@Component
public class B2cQueryResultProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(B2cQueryResultProcessor.class);
    private static final String BASE = "/api/v1/payments/mpesa/b2c";

    private record Pending(UUID id, UUID tenantId, String type, String requestId, String payload) { }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final CoreClient core;
    private final DarajaProperties props;
    private final JsonMapper json = JsonMapper.builder().build();

    public B2cQueryResultProcessor(JdbcClient jdbc, PlatformTransactionManager txManager, CoreClient core,
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
                 WHERE provider = 'MPESA'
                   AND callback_type IN ('B2C_STATUS', 'B2C_STATUS_TIMEOUT', 'B2C_BALANCE', 'B2C_BALANCE_TIMEOUT')
                   AND status <> 'APPLIED' AND attempts < :max
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
        try {
            Map<String, Object> response = switch (p.type()) {
                case "B2C_STATUS" -> core.post(BASE + "/status-queries/" + p.requestId() + "/result", status(p.tenantId(), p.payload()));
                case "B2C_STATUS_TIMEOUT" -> core.post(BASE + "/status-queries/" + p.requestId() + "/no-answer",
                        Map.of("tenantId", p.tenantId().toString()));
                case "B2C_BALANCE" -> core.post(BASE + "/balance-checks/" + p.requestId() + "/result", balance(p.tenantId(), p.payload()));
                default -> core.post(BASE + "/balance-checks/" + p.requestId() + "/no-answer",
                        Map.of("tenantId", p.tenantId().toString()));
            };
            mark(p.id(), "APPLIED", null, String.valueOf(response.get("status")));
            return true;
        } catch (RuntimeException e) {
            String error = e.getClass().getSimpleName() + ": " + e.getMessage();
            mark(p.id(), "FAILED", error.length() > 500 ? error.substring(0, 500) : error, null);
            LOG.warn("B2C answer {} not applied yet: {}", p.id(), error);
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

    /** Maps a Transaction Status {@code Result} to core's request: code, status, receipt and amount only. */
    Map<String, Object> status(UUID tenantId, String payload) {
        Map<String, Object> result = result(payload);
        Map<String, Object> params = parameters(result);
        Map<String, Object> request = base(tenantId, result);
        request.put("transactionStatus", text(params.get("TransactionStatus"), 40));
        Object receipt = params.containsKey("ReceiptNo") ? params.get("ReceiptNo") : result.get("TransactionID");
        String r = receipt == null ? null : String.valueOf(receipt).strip().toUpperCase(java.util.Locale.ROOT);
        request.put("receiptNo", r != null && r.matches("^[A-Z0-9]{8,20}$") ? r : null);
        request.put("amount", amount(params.get("Amount")));
        return request;
    }

    /** Maps an Account Balance {@code Result}: code and the AccountBalance text, which core reads. */
    Map<String, Object> balance(UUID tenantId, String payload) {
        Map<String, Object> result = result(payload);
        Map<String, Object> request = base(tenantId, result);
        request.put("accountBalance", text(parameters(result).get("AccountBalance"), 2000));
        return request;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> result(String payload) {
        return (Map<String, Object>) json.readValue(payload, Map.class).get("Result");
    }

    private static Map<String, Object> base(UUID tenantId, Map<String, Object> result) {
        Map<String, Object> request = new HashMap<>();
        request.put("tenantId", tenantId.toString());
        request.put("resultCode", text(result.get("ResultCode"), 20));
        request.put("resultDesc", text(result.get("ResultDesc"), 200));
        return request;
    }

    /** ResultParameters.ResultParameter as Key -> Value (a single parameter may come as an object, not a list). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parameters(Map<String, Object> result) {
        Map<String, Object> out = new HashMap<>();
        if (result.get("ResultParameters") instanceof Map<?, ?> params) {
            Object list = params.get("ResultParameter");
            List<Object> items = list instanceof List<?> l ? (List<Object>) l : list == null ? List.of() : List.of(list);
            for (Object o : items) {
                if (o instanceof Map<?, ?> kv && kv.get("Key") != null) {
                    out.put(String.valueOf(kv.get("Key")), kv.get("Value"));
                }
            }
        }
        return out;
    }

    private static String amount(Object value) {
        if (value == null) {
            return null;
        }
        try {
            BigDecimal v = new BigDecimal(String.valueOf(value).strip().replace(",", ""));
            return v.signum() > 0 && v.scale() <= 2 ? v.setScale(2, RoundingMode.UNNECESSARY).toPlainString() : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String text(Object value, int max) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value);
        return s.length() > max ? s.substring(0, max) : s;
    }
}
