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
 * </ul>
 */
@Component
public class DarajaRoutes extends RouteBuilder {

    private final CoreClient core;
    private final DarajaProperties props;

    public DarajaRoutes(CoreClient core, DarajaProperties props) {
        this.core = core;
        this.props = props;
    }

    @Override
    public void configure() {
        from("direct:daraja-c2b-validation")
            .routeId("daraja-c2b-validation")
            .circuitBreaker()
                .resilience4jConfiguration().timeoutEnabled(true).timeoutDuration(3000).end()
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
