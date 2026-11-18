package ke.co.technopick.mfi.core.messaging.persistence;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Writes integration events in the caller's transaction (transactional outbox). The event id is
 * derived from the tenant, type and aggregate, so a replayed business call cannot create a
 * second event: the primary key rejects it inside the same transaction that would duplicate it.
 */
@Repository
public class Outbox {

    private final JdbcClient jdbc;

    public Outbox(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param payloadJson JSON object built by the caller from identifiers and amounts only
     *                    (no free text, no PAN, no personal data beyond what consumers need)
     */
    public UUID record(UUID tenantId, String eventType, String aggregateType, String aggregateId, String payloadJson,
                       String correlationId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("outbox events must be written inside the business transaction");
        }
        UUID eventId = UUID.nameUUIDFromBytes((tenantId + "|" + eventType + "|" + aggregateType + "|" + aggregateId)
                .getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO messaging.outbox_event (event_id, tenant_id, event_type, aggregate_type, aggregate_id,
                    payload, correlation_id)
                VALUES (:id, :tenant, :type, :aggType, :aggId, CAST(:payload AS jsonb), :corr)
                """)
            .param("id", eventId)
            .param("tenant", tenantId)
            .param("type", eventType)
            .param("aggType", aggregateType)
            .param("aggId", aggregateId)
            .param("payload", payloadJson)
            .param("corr", correlationId)
            .update();
        return eventId;
    }
}
