package ke.co.technopick.mfi.integration.daraja;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import ke.co.technopick.mfi.integration.core.CoreClient;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

/**
 * Daraja C2B routes (owner: payments integration team):
 * <ul>
 *   <li>{@code daraja-c2b-validation}: synchronous ask to core, behind a Resilience4j circuit
 *       breaker with a 3 s timeout (Daraja waits only briefly); fallback per configuration.</li>
 *   <li>{@code daraja-c2b-confirmation}: persist then acknowledge (idempotent consumer on TransID).</li>
 *   <li>{@code daraja-c2b-processor}: timer that applies stored confirmations in core, with retries.</li>
 *   <li>{@code daraja-stk-dispatch}: consumes {@code MpesaStkPushRequested} events and sends the prompt.</li>
 *   <li>{@code daraja-stk-result}: persist then acknowledge STK results (idempotent on CheckoutRequestID).</li>
 *   <li>{@code daraja-stk-processor}: timer that applies stored STK results in core, with retries.</li>
 * </ul>
 */
@Component
public class DarajaRoutes extends RouteBuilder {

    private final CoreClient core;
    private final DarajaProperties props;
    private final StkDispatcher stkDispatcher;

    public DarajaRoutes(CoreClient core, DarajaProperties props, StkDispatcher stkDispatcher) {
        this.core = core;
        this.props = props;
        this.stkDispatcher = stkDispatcher;
    }

    private StkDispatcher dispatcher() {
        return stkDispatcher;
    }

    @Override
    public void configure() {
        from("direct:daraja-c2b-validation")
            .routeId("daraja-c2b-validation")
            .circuitBreaker()
                .resilience4jConfiguration().timeoutEnabled(true).timeoutDuration("3000").end()
                .process(e -> e.getMessage().setBody(validateInCore(e.getMessage().getBody(C2bCallback.class))))
            .onFallback()
                .process(e -> e.getMessage().setBody(onValidationError()))
            .end();

        from("direct:daraja-c2b-confirmation")
            .routeId("daraja-c2b-confirmation")
            .bean(C2bCallbackStore.class, "store");

        from("timer:daraja-c2b-processor?period={{mfi.daraja.process-interval-ms}}&delay=2000")
            .routeId("daraja-c2b-processor")
            .autoStartup("{{mfi.daraja.processor-enabled}}")
            .bean(C2bCallbackProcessor.class, "processPending");

        // STK Push: requests arrive as outbox events (at-least-once); results as stored callbacks.
        // Queue and binding are declared by StkMessaging (Spring AMQP), not by the endpoint.
        from("spring-rabbitmq:{{mfi.outbox.exchange}}?queues={{mfi.daraja.stk-queue}}&autoDeclare=false"
                + "&concurrentConsumers=2")
            .routeId("daraja-stk-dispatch")
            .autoStartup("{{mfi.daraja.stk-consumer-enabled}}")
            .errorHandler(defaultErrorHandler().maximumRedeliveries(5).redeliveryDelay(2000).useExponentialBackOff())
            .process(e -> e.getMessage().setBody(dispatcher().dispatch(e.getMessage().getBody(String.class),
                    e.getMessage().getHeader("mfiTenantId", String.class)).name()));

        from("direct:daraja-stk-result")
            .routeId("daraja-stk-result")
            .bean(StkCallbackStore.class, "store");

        from("timer:daraja-stk-processor?period={{mfi.daraja.process-interval-ms}}&delay=2000")
            .routeId("daraja-stk-processor")
            .autoStartup("{{mfi.daraja.processor-enabled}}")
            .bean(StkResultProcessor.class, "processPending");
    }

    private Map<String, Object> validateInCore(C2bCallback c) {
        Map<String, Object> request = new HashMap<>();
        request.put("tenantId", c.tenantId().toString());
        request.put("shortCode", c.shortCode());
        request.put("billRef", c.billRef());
        request.put("amount", new BigDecimal(c.amount()).toPlainString());
        request.put("currency", "KES");
        Map<String, Object> r = core.post("/api/v1/payments/mpesa/c2b/validation", request);
        return Map.of("ResultCode", String.valueOf(r.get("resultCode")), "ResultDesc", String.valueOf(r.get("resultDesc")));
    }

    private Map<String, Object> onValidationError() {
        return props.validationOnError() == DarajaProperties.OnError.ACCEPT
                ? Map.of("ResultCode", "0", "ResultDesc", "Accepted")
                : Map.of("ResultCode", "C2B00016", "ResultDesc", "Rejected: temporarily unable to validate");
    }
}
