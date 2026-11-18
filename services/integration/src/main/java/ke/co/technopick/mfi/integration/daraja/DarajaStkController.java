package ke.co.technopick.mfi.integration.daraja;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.camel.ProducerTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP edge for STK Push result callbacks: {@code /daraja/stk/{token}/callback/{requestId}}.
 * Same authentication as C2B (source range + secret path token -> tenant); the result is stored
 * before it is acknowledged.
 */
@RestController
@RequestMapping(path = "/daraja/stk/{token}", produces = "application/json")
public class DarajaStkController {

    private static final Logger LOG = LoggerFactory.getLogger(DarajaStkController.class);

    private final CallbackGuard guard;
    private final ProducerTemplate camel;
    private final JsonMapper json = JsonMapper.builder().build();

    public DarajaStkController(CallbackGuard guard, ProducerTemplate camel) {
        this.guard = guard;
        this.camel = camel;
    }

    @PostMapping("/callback/{requestId}")
    public ResponseEntity<Map<String, Object>> callback(@PathVariable String token, @PathVariable String requestId,
                                                        @RequestBody Map<String, Object> body,
                                                        HttpServletRequest request) {
        if (!guard.sourceAllowed(request.getRemoteAddr())) {
            LOG.warn("STK callback from disallowed source {}", request.getRemoteAddr());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Optional<DarajaProperties.Paybill> paybill = guard.paybillFor(token);
        if (paybill.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        UUID id;
        try {
            id = UUID.fromString(requestId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        String checkout = null;
        if (body.get("Body") instanceof Map<?, ?> b && b.get("stkCallback") instanceof Map<?, ?> cb
                && cb.get("CheckoutRequestID") != null && cb.get("ResultCode") != null) {
            checkout = String.valueOf(cb.get("CheckoutRequestID"));
        }
        if (checkout == null || checkout.length() > 60) {
            LOG.warn("rejected malformed STK callback for request {}", id);
            return ResponseEntity.badRequest().build();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> ack = camel.requestBody("direct:daraja-stk-result",
                new StkCallbackStore.StkResult(paybill.get().tenantId(), id, checkout, json.writeValueAsString(body)),
                Map.class);
        return ResponseEntity.ok(ack);
    }
}
