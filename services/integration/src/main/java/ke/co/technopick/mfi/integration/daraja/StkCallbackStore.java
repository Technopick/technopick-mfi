package ke.co.technopick.mfi.integration.daraja;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Store-then-ack for STK results: one row per CheckoutRequestID, repeats acknowledged without a second row. */
@Component
public class StkCallbackStore {

    /** Parsed {@code Body.stkCallback} plus what the authenticated URL told us. */
    public record StkResult(UUID tenantId, UUID requestId, String checkoutRequestId, String rawJson) { }

    private final JdbcClient jdbc;

    public StkCallbackStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Map<String, Object> store(StkResult r) {
        UUID id = UUID.nameUUIDFromBytes(("MPESA|STK_RESULT|" + r.checkoutRequestId()).getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO integration.provider_callback (callback_id, provider, callback_type, provider_ref, tenant_id,
                    payload, route_ref)
                VALUES (:id, 'MPESA', 'STK_RESULT', :ref, :tenant, CAST(:payload AS jsonb), :route)
                ON CONFLICT (provider, callback_type, provider_ref) DO NOTHING
                """)
            .param("id", id)
            .param("ref", r.checkoutRequestId())
            .param("tenant", r.tenantId())
            .param("payload", r.rawJson())
            .param("route", r.requestId().toString())
            .update();
        return Map.of("ResultCode", 0, "ResultDesc", "Accepted");
    }
}
