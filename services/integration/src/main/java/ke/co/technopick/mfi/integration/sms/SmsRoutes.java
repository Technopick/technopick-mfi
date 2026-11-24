package ke.co.technopick.mfi.integration.sms;

import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

/** {@code sms-sender}: timer that claims due SMS from core and sends them (ADR-0035); off unless {@code mfi.sms.enabled}. */
@Component
public class SmsRoutes extends RouteBuilder {

    @Override
    public void configure() {
        from("timer:sms-sender?period={{mfi.sms.poll-interval-ms}}&delay=3000")
            .routeId("sms-sender")
            .autoStartup("{{mfi.sms.enabled}}")
            .bean(SmsSender.class, "run");
    }
}
