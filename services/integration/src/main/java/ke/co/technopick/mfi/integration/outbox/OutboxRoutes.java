package ke.co.technopick.mfi.integration.outbox;

import org.apache.camel.builder.RouteBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Routes (owner: platform team):
 * <ul>
 *   <li>{@code outbox-relay}: timer -> {@link OutboxRelay#publishBatch()}. Errors are logged and the
 *       next tick retries; events are never lost because they stay unpublished in PostgreSQL.</li>
 *   <li>{@code outbox-publish}: transport only - publishes one event to the {@code mfi.events}
 *       topic exchange with publisher confirms.</li>
 * </ul>
 */
@Configuration
public class OutboxRoutes extends RouteBuilder {

    private final String exchange;

    public OutboxRoutes(@Value("${mfi.outbox.exchange}") String exchange) {
        this.exchange = exchange;
    }

    /** Durable topic exchange, declared by Spring AMQP's RabbitAdmin on first connection. */
    @Bean
    TopicExchange eventsExchange() {
        return new TopicExchange(exchange, true, false);
    }

    @Override
    public void configure() {
        from("timer:outbox-relay?period={{mfi.outbox.poll-interval-ms}}&delay=1000")
            .routeId("outbox-relay")
            .autoStartup("{{mfi.outbox.enabled}}")
            .bean(OutboxRelay.class, "publishBatch");

        from("direct:publish-event")
            .routeId("outbox-publish")
            .toD("spring-rabbitmq:" + exchange
                    + "?routingKey=${header.mfiRoutingKey}&confirm=enabled&confirmTimeout=5000");
    }
}
