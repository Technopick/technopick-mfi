package ke.co.technopick.mfi.core.lending.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import ke.co.technopick.mfi.core.common.NotFoundException;
import ke.co.technopick.mfi.core.identity.api.CurrentCaller;
import ke.co.technopick.mfi.core.lending.application.GuarantorCallService;
import ke.co.technopick.mfi.core.lending.application.GuarantorCallService.GuaranteeSettingsView;
import ke.co.technopick.mfi.core.lending.application.GuarantorCallService.GuarantorCallView;
import ke.co.technopick.mfi.core.lending.application.GuarantorCallService.LoanGuarantees;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Calling on guarantors and releasing guarantees as loans are repaid (ADR-0044). */
@RestController
@RequestMapping(path = "/api/v1/lending", produces = "application/json")
public class GuarantorCallController {

    public record GuaranteeSettingsRequest(@NotBlank @Size(max = 40) String recoveryClearingAccount,
                                           @NotNull @Min(1) @Max(3650) Integer minDaysPastDue, @NotNull Boolean releaseAsRepaid) { }

    public record GuarantorCallRequest(@NotBlank @Size(max = 64) String idempotencyKey,
                                       @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount,
                                       @NotBlank @Size(min = 10, max = 300) String reason) { }

    public record GuarantorCallDecision(@Size(max = 300) String reason) { }

    private final GuarantorCallService calls;
    private final CurrentCaller caller;

    public GuarantorCallController(GuarantorCallService calls, CurrentCaller caller) {
        this.calls = calls;
        this.caller = caller;
    }

    @GetMapping("/guarantee-settings")
    @PreAuthorize("hasAnyRole('FINANCE', 'SUPERVISOR', 'BRANCH_MANAGER', 'LOAN_OFFICER', 'OPERATIONS')")
    public GuaranteeSettingsView settings() {
        return calls.settings(caller.tenantId()).orElseThrow(() -> new NotFoundException("guarantor calls are not set up"));
    }

    @PutMapping(path = "/guarantee-settings", consumes = "application/json")
    @PreAuthorize("hasRole('FINANCE')")
    public GuaranteeSettingsView saveSettings(@Valid @RequestBody GuaranteeSettingsRequest r) {
        return calls.saveSettings(caller.tenantId(), r.recoveryClearingAccount().strip(), r.minDaysPastDue(), r.releaseAsRepaid(),
                caller.actor());
    }

    @GetMapping("/loans/{loanNumber}/guarantees")
    @PreAuthorize("hasAnyRole('LOAN_OFFICER', 'BRANCH_MANAGER', 'OPERATIONS', 'SUPERVISOR', 'TELLER')")
    public LoanGuarantees loanGuarantees(@PathVariable String loanNumber) {
        return calls.loan(caller.tenantId(), loanNumber);
    }

    @PostMapping(path = "/loans/{loanNumber}/guarantor-calls", consumes = "application/json")
    @PreAuthorize("hasAnyRole('LOAN_OFFICER', 'BRANCH_MANAGER', 'OPERATIONS')")
    public GuarantorCallView request(@PathVariable String loanNumber, @Valid @RequestBody GuarantorCallRequest r) {
        return calls.request(caller.tenantId(), r.idempotencyKey(), loanNumber, r.amount(), r.reason(), caller.actor());
    }

    @GetMapping("/guarantor-calls")
    @PreAuthorize("hasAnyRole('LOAN_OFFICER', 'BRANCH_MANAGER', 'OPERATIONS', 'SUPERVISOR', 'FINANCE')")
    public List<GuarantorCallView> list(@RequestParam(required = false) String status) {
        if (status != null && !status.matches("REQUESTED|APPROVED|REJECTED")) {
            throw new IllegalArgumentException("status is REQUESTED, APPROVED or REJECTED");
        }
        return calls.calls(caller.tenantId(), status);
    }

    @PostMapping("/guarantor-calls/{callId}/approve")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER', 'SUPERVISOR')")
    public GuarantorCallView approve(@PathVariable UUID callId) {
        return calls.decide(caller.tenantId(), callId, true, null, caller.actor());
    }

    @PostMapping(path = "/guarantor-calls/{callId}/reject", consumes = "application/json")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER', 'SUPERVISOR')")
    public GuarantorCallView reject(@PathVariable UUID callId, @Valid @RequestBody GuarantorCallDecision r) {
        return calls.decide(caller.tenantId(), callId, false, r.reason(), caller.actor());
    }
}
