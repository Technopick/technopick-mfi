package ke.co.technopick.mfi.integration.outbox;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.camel.ProducerTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transactional-outbox relay: claims a batch of unpublished events (FOR UPDATE SKIP LOCKED, so
 * several relay nodes never claim the same event), publishes each to the broker with publisher
 * confirms, and marks it published in the same database transaction.
 *
 * <p>Delivery is at-least-once: if the broker confirmed but the commit failed, the event is sent
 * again. Consumers de-duplicate on the {@code mfiEventId} header (inbox pattern). Ordering is per
 * batch only; consumers must not rely on global order.
 */
@Component
public class OutboxRelay {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);

    record Event(UUID eventId, UUID tenantId, String eventType, String aggregateType, String aggregateId,
                 String payload, String correlationId) { }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TransactionTemplate separateTx;
    private final ProducerTemplate producer;
    private final int batchSize;

    public OutboxRelay(JdbcClient jdbc, PlatformTransactionManager txManager, ProducerTemplate producer,
                       @Value("${mfi.outbox.batch-size}") int batchSize) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.separateTx = new TransactionTemplate(txManager);
        this.separateTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.producer = producer;
        this.batchSize = batchSize;
    }

    /** Publishes one batch; returns the number of events published. A failure rolls the batch back. */
    public int publishBatch() {
        UUID[] failed = new UUID[1];
        try {
            Integer published = tx.execute(status -> {
                List<Event> events = jdbc.sql("""
                        SELECT event_id, tenant_id, event_type, aggregate_type, aggregate_id, payload::text AS payload,
                               correlation_id
                          FROM messaging.outbox_event
                         WHERE published_at IS NULL
                         ORDER BY occurred_at
                         LIMIT :n
                           FOR UPDATE SKIP LOCKED
                        """)
                    .param("n", batchSize)
                    .query((rs, i) -> new Event(rs.getObject("event_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                            rs.getString("event_type"), rs.getString("aggregate_type"), rs.getString("aggregate_id"),
                            rs.getString("payload"), rs.getString("correlation_id")))
                    .list();
                for (Event e : events) {
                    failed[0] = e.eventId();
                    // Throws if the broker rejects or does not confirm: the whole batch rolls back.
                    producer.sendBodyAndHeaders("direct:publish-event", e.payload(), headers(e));
                    jdbc.sql("""
                            UPDATE messaging.outbox_event
                               SET published_at = clock_timestamp(), publish_attempts = publish_attempts + 1
                             WHERE event_id = :id
                            """)
                        .param("id", e.eventId())
                        .update();
                }
                failed[0] = null;
                return events.size();
            });
            return published == null ? 0 : published;
        } catch (RuntimeException ex) {
            if (failed[0] != null) {
                UUID id = failed[0];
                separateTx.executeWithoutResult(s -> jdbc.sql(
                        "UPDATE messaging.outbox_event SET publish_attempts = publish_attempts + 1 WHERE event_id = :id")
                    .param("id", id).update());
                LOG.warn("outbox publish failed for event {}; batch rolled back, will retry", id, ex);
            }
            throw ex;
        }
    }

    private static Map<String, Object> headers(Event e) {
        Map<String, Object> h = new HashMap<>();
        h.put("mfiEventId", e.eventId().toString());
        h.put("mfiTenantId", e.tenantId().toString());
        h.put("mfiEventType", e.eventType());
        h.put("mfiAggregateType", e.aggregateType());
        h.put("mfiAggregateId", e.aggregateId());
        if (e.correlationId() != null) {
            h.put("mfiCorrelationId", e.correlationId());
        }
        // Routing key: <EventType>.<tenantId>, so consumers can bind per event type and/or tenant.
        h.put("mfiRoutingKey", e.eventType() + "." + e.tenantId());
        return h;
    }
}
