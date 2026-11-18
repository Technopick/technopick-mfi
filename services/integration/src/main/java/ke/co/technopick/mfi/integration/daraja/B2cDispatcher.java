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
 * Sends one B2C payment for an {@code MpesaB2cDisbursementApproved} event (ADR-0012). Core's
 * dispatch claim makes the send at-most-once; a redelivered event is dropped. An unknown outcome
 * is never retried: core flags it for a person after a while.
 */
@Component
public class B2cDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(B2cDispatcher.class);

    public enum Outcome { SUBMITTED, REJECTED, UNKNOWN, ALREADY_DISPATCHED }

    private final CoreClient core;
    private final DarajaClient daraja;
    private final DarajaProperties props;
    private final JsonMapper json = JsonMapper.builder().build();

    public B2cDispatcher(CoreClient core, DarajaClient daraja, DarajaProperties props) {
        this.core = core;
        this.daraja = daraja;
        this.props = props;
    }

    public Outcome dispatch(String eventJson, String tenantHeader) {
        @SuppressWarnings("unchecked")
        Map<String, Object> event = json.readValue(eventJson, Map.class);
        UUID tenantId = UUID.fromString(tenantHeader);
        String id = UUID.fromString(String.valueOf(event.get("disbursementId"))).toString();
        String base = "/api/v1/payments/mpesa/b2c/disbursements/" + id;
        Map<String, Object> claim;
        try {
            claim = core.post(base + "/dispatch", Map.of("tenantId", tenantId.toString()));
        } catch (HttpClientErrorException.Conflict e) {
            LOG.info("B2C disbursement {} already dispatched; redelivered event dropped", id);
            return Outcome.ALREADY_DISPATCHED;
        }
        String shortCode = String.valueOf(claim.get("shortCode"));
        Optional<DarajaProperties.B2cAccount> account = props.b2cAccounts().stream()
            .filter(a -> a.tenantId().equals(tenantId) && a.shortCode().equals(shortCode))
            .findFirst();
        if (account.isEmpty() || !props.b2cConfigured()) {
            core.post(base + "/send-failure", Map.of("tenantId", tenantId.toString(), "code", "CONFIG",
                    "description", "B2C not configured for this short code"));
            return Outcome.REJECTED;
        }
        String callbacks = props.callbackBaseUrl() + "/daraja/b2c/" + account.get().token();
        DarajaClient.B2cOutcome outcome = daraja.b2c(account.get(), id, String.valueOf(claim.get("msisdn")),
                String.valueOf(claim.get("amount")), String.valueOf(claim.get("remarks")),
                String.valueOf(claim.get("occasion")), callbacks + "/result/" + id, callbacks + "/timeout/" + id);
        switch (outcome.kind()) {
            case ACCEPTED -> {
                core.post(base + "/submitted", Map.of("tenantId", tenantId.toString(),
                        "conversationId", outcome.conversationId()));
                return Outcome.SUBMITTED;
            }
            case REJECTED -> {
                String code = outcome.code() == null ? "REJECTED" : outcome.code();
                core.post(base + "/send-failure", Map.of("tenantId", tenantId.toString(),
                        "code", code.length() > 14 ? code.substring(0, 14) : code,
                        "description", String.valueOf(outcome.description())));
                return Outcome.REJECTED;
            }
            default -> {
                LOG.warn("B2C disbursement {} sent with unknown outcome; awaiting result, never resent", id);
                return Outcome.UNKNOWN;
            }
        }
    }
}
