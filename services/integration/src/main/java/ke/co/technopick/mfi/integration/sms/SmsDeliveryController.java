package ke.co.technopick.mfi.integration.sms;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import ke.co.technopick.mfi.integration.core.CoreClient;
import ke.co.technopick.mfi.integration.daraja.CallbackGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Delivery reports from the SMS provider (ADR-0035): {@code {"id":"<provider id>","status":"DELIVERED"|"FAILED",
 * "reason":"..."}} posted to the URL given with the message, which names the tenant and message.
 * Accepted only from configured source ranges with the secret path token.
 */
@RestController
public class SmsDeliveryController {

    private static final Logger LOG = LoggerFactory.getLogger(SmsDeliveryController.class);

    private final SmsProperties props;
    private final CoreClient core;

    public SmsDeliveryController(SmsProperties props, CoreClient core) {
        this.props = props;
        this.core = core;
    }

    @PostMapping(path = "/sms/delivery/{token}/{tenantId}/{messageId}", consumes = "application/json")
    public ResponseEntity<Void> delivery(@PathVariable String token, @PathVariable UUID tenantId, @PathVariable UUID messageId,
                                         @RequestBody Map<String, Object> report, HttpServletRequest request) {
        if (!sourceAllowed(request.getRemoteAddr())) {
            LOG.warn("SMS delivery report from disallowed source {}", request.getRemoteAddr());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (props.deliveryToken() == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                props.deliveryToken().getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.notFound().build();
        }
        Object id = report.get("id");
        String status = String.valueOf(report.get("status")).toUpperCase(Locale.ROOT);
        if (id == null || !(status.equals("DELIVERED") || status.equals("FAILED"))) {
            // Intermediate statuses (queued at the network, etc.) need no action.
            return ResponseEntity.ok().build();
        }
        Object reason = report.get("reason");
        try {
            core.post(SmsSender.BASE + messageId + "/delivery", Map.of("tenantId", tenantId.toString(),
                    "providerMessageId", String.valueOf(id), "delivered", status.equals("DELIVERED"),
                    "reason", reason == null ? "" : String.valueOf(reason).substring(0, Math.min(300, String.valueOf(reason).length()))));
        } catch (HttpClientErrorException e) {
            LOG.warn("SMS {} delivery report refused by core: {}", messageId, e.getStatusCode());
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.ok().build();
    }

    private boolean sourceAllowed(String remoteAddress) {
        try {
            InetAddress address = InetAddress.getByName(remoteAddress);
            for (String cidr : props.allowedCidrs()) {
                if (CallbackGuard.inRange(address, cidr)) {
                    return true;
                }
            }
            return false;
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
