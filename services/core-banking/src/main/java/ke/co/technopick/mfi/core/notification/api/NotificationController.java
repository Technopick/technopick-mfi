package ke.co.technopick.mfi.core.notification.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import ke.co.technopick.mfi.core.identity.api.CurrentCaller;
import ke.co.technopick.mfi.core.notification.application.SmsService;
import ke.co.technopick.mfi.core.notification.application.SmsService.MemberSms;
import ke.co.technopick.mfi.core.notification.application.SmsService.SmsOverview;
import ke.co.technopick.mfi.core.notification.application.SmsService.SmsSettings;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Staff view of SMS to members: per-member messages and choices, tenant settings, operations list (ADR-0035). */
@RestController
public class NotificationController {

    public record SmsPreferenceUpdate(@NotBlank String language, boolean paymentAlerts, boolean reminders) { }

    public record SmsSettingsUpdate(boolean enabled, @NotBlank String signature, String senderId, String ussdCode, int reminderHour) { }

    private final SmsService sms;
    private final CurrentCaller caller;

    public NotificationController(SmsService sms, CurrentCaller caller) {
        this.sms = sms;
        this.caller = caller;
    }

    @GetMapping(path = "/api/v1/customers/{customerNumber}/sms", produces = "application/json")
    @PreAuthorize("hasAnyRole('LOAN_OFFICER', 'TELLER', 'BRANCH_MANAGER', 'OPERATIONS', 'SUPERVISOR')")
    public MemberSms member(@PathVariable String customerNumber) {
        return sms.member(caller.tenantId(), customerNumber);
    }

    @PutMapping(path = "/api/v1/customers/{customerNumber}/sms-preferences", produces = "application/json")
    @PreAuthorize("hasAnyRole('LOAN_OFFICER', 'TELLER', 'BRANCH_MANAGER', 'OPERATIONS')")
    public MemberSms preferences(@PathVariable String customerNumber, @Valid @RequestBody SmsPreferenceUpdate u) {
        return sms.setPreferences(caller.tenantId(), customerNumber, u.language(), u.paymentAlerts(), u.reminders(), caller.actor());
    }

    @GetMapping(path = "/api/v1/notifications/settings", produces = "application/json")
    @PreAuthorize("hasAnyRole('OPERATIONS', 'SUPERVISOR')")
    public SmsSettings settings() {
        return sms.settings(caller.tenantId());
    }

    @PutMapping(path = "/api/v1/notifications/settings", produces = "application/json")
    @PreAuthorize("hasRole('SUPERVISOR')")
    public SmsSettings saveSettings(@Valid @RequestBody SmsSettingsUpdate u) {
        return sms.saveSettings(caller.tenantId(), u.enabled(), u.signature(), u.senderId(), u.ussdCode(), u.reminderHour(),
                caller.actor());
    }

    @GetMapping(path = "/api/v1/notifications/sms", produces = "application/json")
    @PreAuthorize("hasAnyRole('OPERATIONS', 'SUPERVISOR')")
    public SmsOverview overview(@RequestParam(required = false) String status) {
        return sms.overview(caller.tenantId(), status);
    }
}
