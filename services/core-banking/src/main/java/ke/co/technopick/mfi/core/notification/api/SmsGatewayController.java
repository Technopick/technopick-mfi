package ke.co.technopick.mfi.core.notification.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import ke.co.technopick.mfi.core.notification.application.SmsService;
import ke.co.technopick.mfi.core.notification.application.SmsService.ClaimedSms;
import ke.co.technopick.mfi.core.notification.application.SmsService.SmsView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal API for the integration runtime's SMS sender (role INTEGRATION, ADR-0035). */
@RestController
@RequestMapping(path = "/api/v1/notifications/sms", produces = "application/json")
@PreAuthorize("hasRole('INTEGRATION')")
public class SmsGatewayController {

    public record SmsClaimRequest(int limit) { }

    public record SmsSentReport(@NotNull UUID tenantId, @NotBlank @Size(max = 100) String providerMessageId) { }

    public record SmsFailureReport(@NotNull UUID tenantId, @Size(max = 40) String code, @Size(max = 300) String description,
                                boolean retryable) { }

    public record SmsDeliveryReport(@NotNull UUID tenantId, @NotBlank @Size(max = 100) String providerMessageId, boolean delivered,
                                 @Size(max = 300) String reason) { }

    private final SmsService sms;

    public SmsGatewayController(SmsService sms) {
        this.sms = sms;
    }

    @PostMapping("/claim")
    public List<ClaimedSms> claim(@RequestBody SmsClaimRequest r) {
        return sms.claim(r.limit());
    }

    @PostMapping("/{messageId}/sent")
    public SmsView sent(@PathVariable UUID messageId, @Valid @RequestBody SmsSentReport r) {
        return sms.sent(r.tenantId(), messageId, r.providerMessageId());
    }

    @PostMapping("/{messageId}/failed")
    public SmsView failed(@PathVariable UUID messageId, @Valid @RequestBody SmsFailureReport r) {
        return sms.failed(r.tenantId(), messageId, r.code(), r.description(), r.retryable());
    }

    @PostMapping("/{messageId}/delivery")
    public SmsView delivery(@PathVariable UUID messageId, @Valid @RequestBody SmsDeliveryReport r) {
        return sms.delivery(r.tenantId(), messageId, r.providerMessageId(), r.delivered(), r.reason());
    }
}
