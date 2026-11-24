package ke.co.technopick.mfi.core.member.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import ke.co.technopick.mfi.core.identity.api.CurrentCaller;
import ke.co.technopick.mfi.core.member.application.MemberPinService;
import ke.co.technopick.mfi.core.member.application.MemberPinService.PinStatus;
import ke.co.technopick.mfi.core.member.application.MemberPinService.SetupView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff administration of member PINs (ADR-0034): status, setup requests and their approval by a
 * second person. Staff never see or choose the PIN.
 */
@RestController
public class MemberPinController {

    public record SetupRequest(@NotBlank @Size(max = 80) String idempotencyKey, @NotBlank @Size(min = 10, max = 300) String reason) { }

    public record SetupDecision(@Size(max = 300) String reason) { }

    private final MemberPinService pins;
    private final CurrentCaller caller;

    public MemberPinController(MemberPinService pins, CurrentCaller caller) {
        this.pins = pins;
        this.caller = caller;
    }

    @GetMapping(path = "/api/v1/customers/{customerNumber}/member-pin", produces = "application/json")
    @PreAuthorize("hasAnyRole('LOAN_OFFICER', 'TELLER', 'BRANCH_MANAGER', 'OPERATIONS', 'SUPERVISOR')")
    public PinStatus status(@PathVariable String customerNumber) {
        return pins.status(caller.tenantId(), customerNumber);
    }

    @PostMapping(path = "/api/v1/customers/{customerNumber}/member-pin/setups", produces = "application/json")
    @PreAuthorize("hasAnyRole('TELLER', 'BRANCH_MANAGER', 'OPERATIONS')")
    public SetupView request(@PathVariable String customerNumber, @Valid @RequestBody SetupRequest r) {
        return pins.request(caller.tenantId(), customerNumber, r.idempotencyKey(), r.reason(), caller.actor());
    }

    @GetMapping(path = "/api/v1/member-pin-setups", produces = "application/json")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER', 'SUPERVISOR')")
    public List<SetupView> pending() {
        return pins.pending(caller.tenantId());
    }

    @PostMapping(path = "/api/v1/member-pin-setups/{setupId}/approve", produces = "application/json")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER', 'SUPERVISOR')")
    public SetupView approve(@PathVariable UUID setupId, @Valid @RequestBody(required = false) SetupDecision d) {
        return pins.decide(caller.tenantId(), setupId, true, d == null ? null : d.reason(), caller.actor());
    }

    @PostMapping(path = "/api/v1/member-pin-setups/{setupId}/reject", produces = "application/json")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER', 'SUPERVISOR')")
    public SetupView reject(@PathVariable UUID setupId, @Valid @RequestBody SetupDecision d) {
        return pins.decide(caller.tenantId(), setupId, false, d.reason(), caller.actor());
    }
}
