package ke.co.technopick.mfi.core.member.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import ke.co.technopick.mfi.core.member.application.UssdService;
import ke.co.technopick.mfi.core.member.application.UssdService.UssdReply;
import ke.co.technopick.mfi.core.member.application.UssdService.UssdRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal API for the integration runtime's USSD adapter (role INTEGRATION). The integration
 * runtime derives the tenant from the aggregator's authenticated callback path; the member is
 * identified by the phone number the aggregator reports and their PIN (ADR-0034).
 */
@RestController
@RequestMapping(path = "/api/v1/channels/ussd", produces = "application/json")
@PreAuthorize("hasRole('INTEGRATION')")
public class UssdController {

    /** text: every key the member typed this session, joined with '*'. It holds the PIN, so it is never echoed or logged. */
    public record SessionRequest(@NotNull UUID tenantId, @NotBlank String sessionId, @NotBlank String serviceCode,
                                 @NotBlank String phoneNumber, String text) { }

    /** response: "CON ..." to wait for more input or "END ..." to close the session. */
    public record SessionResponse(String response, boolean end) { }

    private final UssdService ussd;

    public UssdController(UssdService ussd) {
        this.ussd = ussd;
    }

    @PostMapping("/sessions")
    public SessionResponse session(@Valid @RequestBody SessionRequest r) {
        UssdReply reply = ussd.handle(new UssdRequest(r.tenantId(), r.sessionId(), r.serviceCode(), r.phoneNumber(), r.text()));
        return new SessionResponse(reply.response(), reply.ends());
    }
}
