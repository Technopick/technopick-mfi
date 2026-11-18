package ke.co.technopick.mfi.integration.daraja;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
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
 * HTTP edge for Safaricom Daraja C2B callbacks. Authenticates the call, then hands it to the
 * Camel routes. Unknown tokens get 404 (no hint that the endpoint exists); disallowed sources 403.
 */
@RestController
@RequestMapping(path = "/daraja/c2b/{token}", produces = "application/json")
public class DarajaC2bController {

    private static final Logger LOG = LoggerFactory.getLogger(DarajaC2bController.class);

    private final CallbackGuard guard;
    private final ProducerTemplate camel;
    private final JsonMapper json = JsonMapper.builder().build();

    public DarajaC2bController(CallbackGuard guard, ProducerTemplate camel) {
        this.guard = guard;
        this.camel = camel;
    }

    @PostMapping("/validation")
    public ResponseEntity<Map<String, Object>> validation(@PathVariable String token, @RequestBody Map<String, Object> body,
                                                          HttpServletRequest request) {
        Optional<ResponseEntity<Map<String, Object>>> denied = deny(request);
        if (denied.isPresent()) {
            return denied.get();
        }
        Optional<DarajaProperties.Paybill> paybill = guard.paybillFor(token);
        if (paybill.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        C2bCallback c = C2bCallback.from(paybill.get().tenantId(), body, json.writeValueAsString(body));
        if (!c.wellFormed()) {
            return ResponseEntity.ok(Map.of("ResultCode", "C2B00016", "ResultDesc", "Rejected: malformed request"));
        }
        if (!paybill.get().shortCode().equals(c.shortCode())) {
            LOG.warn("validation short code {} does not match token's paybill", c.shortCode());
            return ResponseEntity.ok(Map.of("ResultCode", "C2B00015", "ResultDesc", "Invalid short code"));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> answer = camel.requestBody("direct:daraja-c2b-validation", c, Map.class);
        return ResponseEntity.ok(answer);
    }

    @PostMapping("/confirmation")
    public ResponseEntity<Map<String, Object>> confirmation(@PathVariable String token,
                                                            @RequestBody Map<String, Object> body,
                                                            HttpServletRequest request) {
        Optional<ResponseEntity<Map<String, Object>>> denied = deny(request);
        if (denied.isPresent()) {
            return denied.get();
        }
        Optional<DarajaProperties.Paybill> paybill = guard.paybillFor(token);
        if (paybill.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        C2bCallback c = C2bCallback.from(paybill.get().tenantId(), body, json.writeValueAsString(body));
        if (!c.wellFormed() || !paybill.get().shortCode().equals(c.shortCode())) {
            LOG.warn("rejected malformed or misrouted C2B confirmation {}", c.transId());
            return ResponseEntity.badRequest().build();
        }
        // Stored durably before the acknowledgement; a storage failure returns 500 (no false ack).
        @SuppressWarnings("unchecked")
        Map<String, Object> ack = camel.requestBody("direct:daraja-c2b-confirmation", c, Map.class);
        return ResponseEntity.ok(ack);
    }

    private Optional<ResponseEntity<Map<String, Object>>> deny(HttpServletRequest request) {
        if (!guard.sourceAllowed(request.getRemoteAddr())) {
            LOG.warn("Daraja callback from disallowed source {}", request.getRemoteAddr());
            return Optional.of(ResponseEntity.status(HttpStatus.FORBIDDEN).build());
        }
        return Optional.empty();
    }
}
