package ke.co.technopick.mfi.core.lending.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import ke.co.technopick.mfi.core.identity.api.CurrentCaller;
import ke.co.technopick.mfi.core.lending.application.LoanRescheduleService;
import ke.co.technopick.mfi.core.lending.application.LoanRescheduleService.ReschedulePreview;
import ke.co.technopick.mfi.core.lending.application.LoanRescheduleService.RescheduleTerms;
import ke.co.technopick.mfi.core.lending.application.LoanRescheduleService.RescheduleView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Loan restructuring and partial prepayment with maker-checker (ADR-0037). */
@RestController
@RequestMapping(path = "/api/v1/lending", produces = "application/json")
public class LoanRescheduleController {

    public record RescheduleRequest(@NotBlank String idempotencyKey, @NotBlank String kind, Integer instalments, Integer graceMonths,
                                    String amount, String mode, @NotBlank String reason) { }

    public record RescheduleDecision(String reason) { }

    private final LoanRescheduleService reschedules;
    private final CurrentCaller caller;

    public LoanRescheduleController(LoanRescheduleService reschedules, CurrentCaller caller) {
        this.reschedules = reschedules;
        this.caller = caller;
    }

    @PostMapping("/loans/{loanNumber}/reschedules/preview")
    @PreAuthorize("hasAnyRole('LOAN_OFFICER', 'BRANCH_MANAGER', 'SUPERVISOR')")
    public ReschedulePreview preview(@PathVariable String loanNumber, @RequestBody RescheduleTerms terms) {
        return reschedules.preview(caller.tenantId(), loanNumber, terms);
    }

    @PostMapping("/loans/{loanNumber}/reschedules")
    @PreAuthorize("hasAnyRole('LOAN_OFFICER', 'BRANCH_MANAGER')")
    public RescheduleView request(@PathVariable String loanNumber, @Valid @RequestBody RescheduleRequest r) {
        return reschedules.request(caller.tenantId(), loanNumber, r.idempotencyKey(),
                new RescheduleTerms(r.kind(), r.instalments(), r.graceMonths(), r.amount(), r.mode()), r.reason(), caller.actor());
    }

    @GetMapping("/loans/{loanNumber}/reschedules")
    @PreAuthorize("hasAnyRole('LOAN_OFFICER', 'BRANCH_MANAGER', 'SUPERVISOR', 'OPERATIONS')")
    public List<RescheduleView> forLoan(@PathVariable String loanNumber) {
        return reschedules.forLoan(caller.tenantId(), loanNumber);
    }

    @GetMapping("/reschedules")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER', 'SUPERVISOR')")
    public List<RescheduleView> pending() {
        return reschedules.pending(caller.tenantId());
    }

    @PostMapping("/reschedules/{rescheduleId}/approve")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER', 'SUPERVISOR')")
    public RescheduleView approve(@PathVariable UUID rescheduleId, @RequestBody(required = false) RescheduleDecision d) {
        return reschedules.decide(caller.tenantId(), rescheduleId, true, d == null ? null : d.reason(), caller.actor());
    }

    @PostMapping("/reschedules/{rescheduleId}/reject")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER', 'SUPERVISOR')")
    public RescheduleView reject(@PathVariable UUID rescheduleId, @RequestBody RescheduleDecision d) {
        return reschedules.decide(caller.tenantId(), rescheduleId, false, d.reason(), caller.actor());
    }
}
