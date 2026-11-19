package ke.co.technopick.mfi.core.lending.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import ke.co.technopick.mfi.core.identity.api.CurrentCaller;
import ke.co.technopick.mfi.core.lending.application.LoanWriteOffService;
import ke.co.technopick.mfi.core.lending.application.LoanWriteOffService.WriteOffView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Loan write-off (ADR-0022): BRANCH_MANAGER or OPERATIONS request; a SUPERVISOR who did not
 * request it approves or rejects.
 */
@RestController
@RequestMapping(path = "/api/v1/lending", produces = "application/json")
public class LoanWriteOffController {

    public record WriteOffRequest(@NotBlank @Size(max = 80) String idempotencyKey, @NotBlank @Size(min = 10, max = 300) String reason) { }

    public record WriteOffDecision(@Size(max = 300) String reason) { }

    private final LoanWriteOffService service;
    private final CurrentCaller caller;

    public LoanWriteOffController(LoanWriteOffService service, CurrentCaller caller) {
        this.service = service;
        this.caller = caller;
    }

    @PostMapping("/loans/{loanNumber}/write-off")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER', 'OPERATIONS')")
    public WriteOffView request(@PathVariable @Size(max = 34) String loanNumber, @Valid @RequestBody WriteOffRequest r) {
        return service.request(caller.tenantId(), r.idempotencyKey(), loanNumber, r.reason(), caller.actor());
    }

    @GetMapping("/loans/{loanNumber}/write-off")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER', 'OPERATIONS', 'SUPERVISOR', 'LOAN_OFFICER')")
    public WriteOffView ofLoan(@PathVariable @Size(max = 34) String loanNumber) {
        return service.ofLoan(caller.tenantId(), loanNumber);
    }

    @GetMapping("/write-offs")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER', 'OPERATIONS', 'SUPERVISOR')")
    public List<WriteOffView> list(@RequestParam(required = false) @Pattern(regexp = "PENDING|APPROVED|REJECTED") String status) {
        return service.list(caller.tenantId(), status);
    }

    @PostMapping("/write-offs/{writeOffId}/approve")
    @PreAuthorize("hasRole('SUPERVISOR')")
    public WriteOffView approve(@PathVariable UUID writeOffId, @Valid @RequestBody(required = false) WriteOffDecision r) {
        return service.decide(caller.tenantId(), writeOffId, true, r == null ? null : r.reason(), caller.actor());
    }

    @PostMapping("/write-offs/{writeOffId}/reject")
    @PreAuthorize("hasRole('SUPERVISOR')")
    public WriteOffView reject(@PathVariable UUID writeOffId, @Valid @RequestBody WriteOffDecision r) {
        return service.decide(caller.tenantId(), writeOffId, false, r.reason(), caller.actor());
    }
}
