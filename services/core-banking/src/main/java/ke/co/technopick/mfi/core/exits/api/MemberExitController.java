package ke.co.technopick.mfi.core.exits.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import ke.co.technopick.mfi.core.common.NotFoundException;
import ke.co.technopick.mfi.core.exits.application.MemberExitService;
import ke.co.technopick.mfi.core.exits.application.MemberExitService.ExitSettingsView;
import ke.co.technopick.mfi.core.exits.application.MemberExitService.ExitView;
import ke.co.technopick.mfi.core.exits.application.MemberExitService.MemberExitPosition;
import ke.co.technopick.mfi.core.identity.api.CurrentCaller;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Members leaving the Sacco (ADR-0042). */
@RestController
@RequestMapping(path = "/api/v1/member-exits", produces = "application/json")
public class MemberExitController {

    static final String STAFF = "hasAnyRole('TELLER', 'LOAN_OFFICER', 'BRANCH_MANAGER', 'OPERATIONS', 'SUPERVISOR', 'FINANCE')";

    public record ExitSettingsRequest(@NotNull @Min(0) @Max(365) Integer noticeDays,
                                      @NotBlank @Size(max = 40) String refundsPayableAccount) { }

    public record ExitRequestCreate(@NotBlank @Size(max = 64) String idempotencyKey,
                                    @NotBlank @Pattern(regexp = "^CU[0-9]{2}-[0-9]{7}$") String customerNumber,
                                    @NotBlank @Size(min = 10, max = 300) String reason) { }

    public record ExitDecisionRequest(@Size(max = 300) String reason) { }

    public record RefundPaymentRequest(@NotBlank @Size(max = 64) String idempotencyKey,
                                       @NotBlank @Pattern(regexp = "BANK_TRANSFER|CHEQUE|CASH|MPESA") String method,
                                       @NotBlank @Size(max = 40) String paidFromAccount,
                                       @NotBlank @Size(min = 3, max = 100) String reference) { }

    private final MemberExitService exits;
    private final CurrentCaller caller;

    public MemberExitController(MemberExitService exits, CurrentCaller caller) {
        this.exits = exits;
        this.caller = caller;
    }

    @GetMapping("/settings")
    @PreAuthorize(STAFF)
    public ExitSettingsView settings() {
        return exits.settings(caller.tenantId()).orElseThrow(() -> new NotFoundException("member exits are not set up"));
    }

    @PutMapping(path = "/settings", consumes = "application/json")
    @PreAuthorize("hasRole('FINANCE')")
    public ExitSettingsView saveSettings(@Valid @RequestBody ExitSettingsRequest r) {
        return exits.saveSettings(caller.tenantId(), r.noticeDays(), r.refundsPayableAccount().strip(), caller.actor());
    }

    @GetMapping("/members/{customerNumber}")
    @PreAuthorize(STAFF)
    public MemberExitPosition position(@PathVariable String customerNumber) {
        return exits.position(caller.tenantId(), customerNumber);
    }

    @PostMapping(consumes = "application/json")
    @PreAuthorize("hasAnyRole('TELLER', 'LOAN_OFFICER', 'BRANCH_MANAGER', 'OPERATIONS')")
    public ExitView request(@Valid @RequestBody ExitRequestCreate r) {
        return exits.request(caller.tenantId(), r.idempotencyKey(), r.customerNumber(), r.reason(), caller.actor());
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('TELLER', 'LOAN_OFFICER', 'BRANCH_MANAGER', 'OPERATIONS', 'SUPERVISOR', 'FINANCE')")
    public List<ExitView> exits(@RequestParam(required = false) String status) {
        if (status != null && !status.matches("REQUESTED|APPROVED|REJECTED|CANCELLED")) {
            throw new IllegalArgumentException("status is REQUESTED, APPROVED, REJECTED or CANCELLED");
        }
        return exits.exits(caller.tenantId(), status);
    }

    @GetMapping("/{exitId}")
    @PreAuthorize(STAFF)
    public ExitView exit(@PathVariable UUID exitId) {
        return exits.exit(caller.tenantId(), exitId);
    }

    @PostMapping("/{exitId}/approve")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER', 'SUPERVISOR')")
    public ExitView approve(@PathVariable UUID exitId) {
        return exits.approve(caller.tenantId(), exitId, caller.actor());
    }

    @PostMapping(path = "/{exitId}/reject", consumes = "application/json")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER', 'SUPERVISOR')")
    public ExitView reject(@PathVariable UUID exitId, @Valid @RequestBody ExitDecisionRequest r) {
        return exits.close(caller.tenantId(), exitId, "REJECTED", r.reason(), caller.actor());
    }

    @PostMapping(path = "/{exitId}/cancel", consumes = "application/json")
    @PreAuthorize("hasAnyRole('TELLER', 'LOAN_OFFICER', 'BRANCH_MANAGER', 'OPERATIONS', 'SUPERVISOR')")
    public ExitView cancel(@PathVariable UUID exitId, @Valid @RequestBody ExitDecisionRequest r) {
        return exits.close(caller.tenantId(), exitId, "CANCELLED", r.reason(), caller.actor());
    }

    @PostMapping(path = "/{exitId}/refund-payments", consumes = "application/json")
    @PreAuthorize("hasRole('FINANCE')")
    public ExitView recordRefund(@PathVariable UUID exitId, @Valid @RequestBody RefundPaymentRequest r) {
        return exits.recordRefund(caller.tenantId(), r.idempotencyKey(), exitId, r.method(), r.paidFromAccount().strip(), r.reference(),
                caller.actor());
    }

    @PostMapping("/refund-payments/{paymentId}/approve")
    @PreAuthorize("hasAnyRole('FINANCE', 'SUPERVISOR')")
    public ExitView approveRefund(@PathVariable UUID paymentId) {
        return exits.decideRefund(caller.tenantId(), paymentId, true, null, caller.actor());
    }

    @PostMapping(path = "/refund-payments/{paymentId}/reject", consumes = "application/json")
    @PreAuthorize("hasAnyRole('FINANCE', 'SUPERVISOR')")
    public ExitView rejectRefund(@PathVariable UUID paymentId, @Valid @RequestBody ExitDecisionRequest r) {
        return exits.decideRefund(caller.tenantId(), paymentId, false, r.reason(), caller.actor());
    }
}
