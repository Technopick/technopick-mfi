package ke.co.technopick.mfi.integration.sms;

import java.util.List;
import java.util.Map;
import ke.co.technopick.mfi.integration.core.CoreClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Claims due SMS from core, sends each through the provider and reports the outcome (ADR-0035).
 * Core decides retries and back-off; a message claimed but never reported (this runtime stopped)
 * is queued again by core after a few minutes.
 */
@Component
public class SmsSender {

    private static final Logger LOG = LoggerFactory.getLogger(SmsSender.class);
    static final String BASE = "/api/v1/notifications/sms/";

    private final CoreClient core;
    private final SmsGateway gateway;
    private final SmsProperties props;

    public SmsSender(CoreClient core, SmsGateway gateway, SmsProperties props) {
        this.core = core;
        this.gateway = gateway;
        this.props = props;
    }

    /** One round: returns how many messages the provider accepted. */
    public int run() {
        List<Map<String, Object>> due = core.postForList(BASE + "claim", Map.of("limit", props.batchSize()));
        int accepted = 0;
        for (Map<String, Object> m : due) {
            String tenantId = String.valueOf(m.get("tenantId"));
            String messageId = String.valueOf(m.get("messageId"));
            String callback = props.callbackBaseUrl() + "/sms/delivery/" + props.deliveryToken() + "/" + tenantId + "/" + messageId;
            Object sender = m.get("senderId");
            SmsGateway.Result r = gateway.send(String.valueOf(m.get("msisdn")), sender == null ? null : String.valueOf(sender),
                    String.valueOf(m.get("body")), callback);
            try {
                if (r.kind() == SmsGateway.Kind.ACCEPTED) {
                    core.post(BASE + messageId + "/sent", Map.of("tenantId", tenantId, "providerMessageId", r.providerMessageId()));
                    accepted++;
                } else {
                    core.post(BASE + messageId + "/failed", Map.of("tenantId", tenantId, "code", cut(r.code(), 40),
                            "description", cut(r.description(), 300), "retryable", r.kind() == SmsGateway.Kind.RETRY));
                    LOG.warn("SMS {} not sent ({}): {}", messageId, r.kind(), r.code());
                }
            } catch (RuntimeException e) {
                // Core will queue the claim again; the member may then get the message twice.
                LOG.error("SMS {} outcome {} could not be reported to core: {}", messageId, r.kind(), e.getClass().getSimpleName());
            }
        }
        return accepted;
    }

    private static String cut(String s, int max) {
        String v = s == null ? "" : s;
        return v.length() <= max ? v : v.substring(0, max);
    }
}
