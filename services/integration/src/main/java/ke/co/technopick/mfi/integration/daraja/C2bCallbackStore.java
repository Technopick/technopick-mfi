package ke.co.technopick.mfi.integration.daraja;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent-consumer store for C2B confirmations: the raw callback is persisted (unique per
 * TransID) before Daraja is acknowledged; a repeat is acknowledged without a second row.
 */
@Component
public class C2bCallbackStore {

    public static final Map<String, Object> ACK = Map.of("ResultCode", "0", "ResultDesc", "Accepted");

    private final JdbcClient jdbc;

    public C2bCallbackStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Map<String, Object> store(C2bCallback c) {
        UUID id = UUID.nameUUIDFromBytes(("MPESA|C2B_CONFIRMATION|" + c.transId()).getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO integration.provider_callback (callback_id, provider, callback_type, provider_ref, tenant_id, payload)
                VALUES (:id, 'MPESA', 'C2B_CONFIRMATION', :ref, :tenant, CAST(:payload AS jsonb))
                ON CONFLICT (provider, callback_type, provider_ref) DO NOTHING
                """)
            .param("id", id)
            .param("ref", c.transId())
            .param("tenant", c.tenantId())
            .param("payload", c.rawJson())
            .update();
        return ACK;
    }
}
