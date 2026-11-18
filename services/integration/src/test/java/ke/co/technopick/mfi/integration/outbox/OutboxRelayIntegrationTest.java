package ke.co.technopick.mfi.integration.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * Relay against real PostgreSQL 17 (core-banking's own Flyway migrations) and RabbitMQ 4.
 * The timer route is off; the test drives batches directly so assertions are deterministic.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(properties = {"mfi.outbox.enabled=false", "mfi.daraja.stk-consumer-enabled=false"})
class OutboxRelayIntegrationTest {

    static final String A = "00000000-0000-0000-0000-0000000000a1";
    static final String B = "00000000-0000-0000-0000-0000000000b2";

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17").withInitScript("db/test-init.sql");

    @Container
    static final RabbitMQContainer MQ = new RabbitMQContainer("rabbitmq:4.1");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", () -> "mfi_relay");
        r.add("spring.datasource.password", () -> "it-only-relay");
        r.add("spring.rabbitmq.host", MQ::getHost);
        r.add("spring.rabbitmq.port", MQ::getAmqpPort);
        r.add("spring.rabbitmq.username", MQ::getAdminUsername);
        r.add("spring.rabbitmq.password", MQ::getAdminPassword);
    }

    @TestConfiguration
    static class Listener {
        @Bean
        Queue everything() {
            return new Queue("test.all-events", false, false, false);
        }

        @Bean
        Binding everythingBinding(Queue everything, TopicExchange eventsExchange) {
            return BindingBuilder.bind(everything).to(eventsExchange).with("#");
        }
    }

    @Autowired
    OutboxRelay relay;
    @Autowired
    RabbitTemplate rabbit;
    @Autowired
    AmqpAdmin admin;
    @Autowired
    JdbcClient relayJdbc;
    @Autowired
    TopicExchange eventsExchange;

    static JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
    }

    @BeforeAll
    static void schemaAndData() {
        Flyway.configure().dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
            .locations("classpath:db/migration").load().migrate();
        owner().execute("""
            INSERT INTO tenancy.tenant (tenant_id, code, legal_name, licence_type, deposits_allowed, base_currency) VALUES
              ('%1$s', 'TEST-A', 'TEST ONLY A', 'MICROFINANCE_BANK', true, 'KES'),
              ('%2$s', 'TEST-B', 'TEST ONLY B', 'DEPOSIT_TAKING_SACCO', true, 'KES');
            """.formatted(A, B));
    }

    static void event(String id, String tenant, String type, String published) {
        owner().update("""
            INSERT INTO messaging.outbox_event (event_id, tenant_id, event_type, aggregate_type, aggregate_id, payload, published_at)
            VALUES (?::uuid, ?::uuid, ?, 'hold', ?, CAST(? AS jsonb), CAST(? AS timestamptz))
            """, id, tenant, type, id, "{\"holdId\":\"" + id + "\"}", published);
    }

    Set<String> drain() {
        Set<String> ids = new HashSet<>();
        Message m;
        while ((m = rabbit.receive("test.all-events", 2000)) != null) {
            ids.add(String.valueOf(m.getMessageProperties().getHeaders().get("mfiEventId")));
            assertTrue(new String(m.getBody(), StandardCharsets.UTF_8).contains("holdId"));
        }
        return ids;
    }

    @Test
    void publishesEachEventOnceAcrossTenantsAndKeepsFailuresForRetry() {
        event("10000000-0000-0000-0000-000000000001", A, "AuthorizationCompleted", null);
        event("10000000-0000-0000-0000-000000000002", A, "AuthorizationReversed", null);
        event("10000000-0000-0000-0000-000000000003", B, "AuthorizationCompleted", null);
        event("10000000-0000-0000-0000-000000000004", A, "AuthorizationCompleted", "2026-11-18T07:00:00Z");

        assertEquals(3, relay.publishBatch());
        assertEquals(Set.of("10000000-0000-0000-0000-000000000001", "10000000-0000-0000-0000-000000000002",
                "10000000-0000-0000-0000-000000000003"), drain());
        assertEquals(0, owner().queryForObject(
                "SELECT count(*) FROM messaging.outbox_event WHERE published_at IS NULL", Integer.class));

        // Nothing is published twice.
        assertEquals(0, relay.publishBatch());
        assertEquals(Set.of(), drain());

        // Broker-side failure: the exchange is gone, so the publish is not confirmed. The event stays
        // unpublished (attempt counted) and goes out once the exchange is back.
        event("10000000-0000-0000-0000-000000000005", B, "AuthorizationCompleted", null);
        admin.deleteExchange(eventsExchange.getName());
        assertThrows(RuntimeException.class, () -> relay.publishBatch());
        assertNull(owner().queryForObject("SELECT published_at FROM messaging.outbox_event WHERE event_id = "
                + "'10000000-0000-0000-0000-000000000005'", java.time.OffsetDateTime.class));
        assertEquals(1, owner().queryForObject("SELECT publish_attempts FROM messaging.outbox_event WHERE event_id = "
                + "'10000000-0000-0000-0000-000000000005'", Integer.class));
        admin.declareExchange(eventsExchange);
        admin.declareBinding(BindingBuilder.bind(new Queue("test.all-events", false, false, false))
                .to(eventsExchange).with("#"));
        assertEquals(1, relay.publishBatch());
        assertEquals(Set.of("10000000-0000-0000-0000-000000000005"), drain());
        assertNotNull(owner().queryForObject("SELECT published_at FROM messaging.outbox_event WHERE event_id = "
                + "'10000000-0000-0000-0000-000000000005'", java.time.OffsetDateTime.class));
    }

    @Test
    void relayRoleCannotRewriteEvents() {
        event("20000000-0000-0000-0000-000000000001", A, "AuthorizationCompleted", null);
        assertThrows(RuntimeException.class, () -> relayJdbc.sql(
                "UPDATE messaging.outbox_event SET payload = '{}'::jsonb WHERE event_id = '20000000-0000-0000-0000-000000000001'")
            .update());
        assertThrows(RuntimeException.class, () -> relayJdbc.sql(
                "DELETE FROM messaging.outbox_event WHERE event_id = '20000000-0000-0000-0000-000000000001'").update());
    }
}
