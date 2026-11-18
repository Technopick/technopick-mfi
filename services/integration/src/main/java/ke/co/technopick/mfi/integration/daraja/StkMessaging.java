package ke.co.technopick.mfi.integration.daraja;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Broker topology for STK Push requests, declared by Spring AMQP's RabbitAdmin on first connection:
 * a durable queue bound to the {@code mfi.events} topic exchange for {@code MpesaStkPushRequested.*}.
 */
@Configuration
public class StkMessaging {

    @Bean
    Queue stkRequestQueue(@Value("${mfi.daraja.stk-queue}") String name) {
        return QueueBuilder.durable(name).build();
    }

    @Bean
    Binding stkRequestBinding(Queue stkRequestQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(stkRequestQueue).to(eventsExchange).with("MpesaStkPushRequested.#");
    }
}
