package ke.co.technopick.mfi.integration.daraja;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ke.co.technopick.mfi.integration.core.CoreClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends one STK Push for an {@code MpesaStkPushRequested} event (at-least-once delivery).
 * Core's dispatch claim makes the send at-most-once: a redelivered event finds the request already
 * claimed (409) and is dropped. The callback URL carries the request id, so the provider's result
 * can be matched even if this runtime stops before reporting the CheckoutRequestID.
 */
@Component
public class StkDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(StkDispatcher.class);

    public enum Outcome { SUBMITTED, REJECTED, UNKNOWN, ALREADY_DISPATCHED }

    private final CoreClient core;
    private final DarajaClient daraja;
    private final DarajaProperties props;
    private final JsonMapper json = JsonMapper.builder().build();

    public StkDispatcher(CoreClient core, DarajaClient daraja, DarajaProperties props) {
        this.core = core;
        this.daraja = daraja;
        this.props = props;
    }

    public Outcome dispatch(String eventJson, String tenantHeader) {
        @SuppressWarnings("unchecked")
        Map<String, Object> event = json.readValue(eventJson, Map.class);
        UUID tenantId = UUID.fromString(tenantHeader);
        String requestId = UUID.fromString(String.valueOf(event.get("requestId"))).toString();
        String base = "/api/v1/payments/mpesa/stk-requests/" + requestId;
        Map<String, Object> claim;
        try {
            claim = core.post(base + "/dispatch", Map.of("tenantId", tenantId.toString()));
        } catch (HttpClientErrorException.Conflict e) {
            LOG.info("STK request {} already dispatched; redelivered event dropped", requestId);
            return Outcome.ALREADY_DISPATCHED;
        }
        String shortCode = String.valueOf(claim.get("shortCode"));
        Optional<DarajaProperties.Paybill> paybill = props.paybills().stream()
            .filter(p -> p.tenantId().equals(tenantId) && p.shortCode().equals(shortCode))
            .filter(p -> p.passkey() != null && !p.passkey().isBlank())
            .findFirst();
        if (paybill.isEmpty() || !props.stkConfigured()) {
            core.post(base + "/send-failure", Map.of("tenantId", tenantId.toString(), "code", "CONFIG",
                    "description", "STK Push not configured for this paybill"));
            return Outcome.REJECTED;
        }
        String callbackUrl = props.callbackBaseUrl() + "/daraja/stk/" + paybill.get().token() + "/callback/" + requestId;
        DarajaClient.StkOutcome outcome = daraja.stkPush(paybill.get(), String.valueOf(claim.get("msisdn")),
                String.valueOf(claim.get("amount")), String.valueOf(claim.get("accountReference")),
                String.valueOf(claim.get("description")), callbackUrl);
        switch (outcome.kind()) {
            case ACCEPTED -> {
                core.post(base + "/submitted", Map.of("tenantId", tenantId.toString(),
                        "merchantRequestId", outcome.merchantRequestId(), "checkoutRequestId", outcome.checkoutRequestId()));
                return Outcome.SUBMITTED;
            }
            case REJECTED -> {
                core.post(base + "/send-failure", Map.of("tenantId", tenantId.toString(),
                        "code", truncate(outcome.code(), 14),
                        "description", truncate(String.valueOf(outcome.description()), 200)));
                return Outcome.REJECTED;
            }
            default -> {
                // Not marked failed: the customer may still get the prompt and pay. The result
                // callback settles it; requests left DISPATCHED are listed for reconciliation.
                LOG.warn("STK request {} sent with unknown outcome; awaiting the result callback", requestId);
                return Outcome.UNKNOWN;
            }
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
