package ke.co.technopick.mfi.integration.ussd;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import ke.co.technopick.mfi.integration.core.CoreClient;
import ke.co.technopick.mfi.integration.daraja.CallbackGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP edge for USSD aggregators (ADR-0034), in the callback style common to Kenyan aggregators:
 * a form POST with sessionId, serviceCode, phoneNumber and text (every key typed this session,
 * joined with '*'), answered in plain text starting with "CON " or "END ". The call is trusted only
 * from an allowed source range with a configured secret path token, which also gives the tenant;
 * the service code must be that token's. Core banking decides everything else.
 *
 * <p>The text holds the member's PIN, so it is never logged.
 */
@RestController
public class UssdCallbackController {

    static final String UNAVAILABLE = "END Sorry, mobile banking is not available right now. Please try again later.";
    private static final Logger LOG = LoggerFactory.getLogger(UssdCallbackController.class);

    private final UssdProperties props;
    private final CoreClient core;

    public UssdCallbackController(UssdProperties props, CoreClient core) {
        this.props = props;
        this.core = core;
    }

    @PostMapping(path = "/ussd/{token}", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> session(@PathVariable String token, @RequestParam Map<String, String> form,
                                          HttpServletRequest request) {
        if (!sourceAllowed(request.getRemoteAddr())) {
            LOG.warn("USSD callback from disallowed source {}", request.getRemoteAddr());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Optional<UssdProperties.Service> service = serviceFor(token);
        if (service.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String sessionId = form.get("sessionId");
        String serviceCode = form.get("serviceCode");
        String phone = form.get("phoneNumber");
        if (sessionId == null || phone == null || !service.get().serviceCode().equals(serviceCode)) {
            LOG.warn("malformed or misrouted USSD callback for {}", service.get());
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN).body("END Invalid request.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", service.get().tenantId().toString());
        body.put("sessionId", sessionId);
        body.put("serviceCode", serviceCode);
        body.put("phoneNumber", phone);
        body.put("text", form.getOrDefault("text", ""));
        String answer;
        try {
            Object response = core.post("/api/v1/channels/ussd/sessions", body).get("response");
            answer = response instanceof String s && (s.startsWith("CON ") || s.startsWith("END ")) ? s : UNAVAILABLE;
        } catch (RuntimeException e) {
            // Never the request body: it holds the PIN.
            LOG.error("USSD session {} for {} could not be answered by core: {}", sessionId, service.get(), e.getClass().getSimpleName());
            answer = UNAVAILABLE;
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(answer);
    }

    private boolean sourceAllowed(String remoteAddress) {
        try {
            InetAddress address = InetAddress.getByName(remoteAddress);
            for (String cidr : props.allowedCidrs()) {
                if (CallbackGuard.inRange(address, cidr)) {
                    return true;
                }
            }
        } catch (UnknownHostException e) {
            return false;
        }
        return false;
    }

    /** Constant-time comparison against every configured token. */
    private Optional<UssdProperties.Service> serviceFor(String token) {
        byte[] presented = token.getBytes(StandardCharsets.UTF_8);
        UssdProperties.Service match = null;
        for (UssdProperties.Service s : props.services()) {
            if (MessageDigest.isEqual(presented, s.token().getBytes(StandardCharsets.UTF_8))) {
                match = s;
            }
        }
        return Optional.ofNullable(match);
    }
}
