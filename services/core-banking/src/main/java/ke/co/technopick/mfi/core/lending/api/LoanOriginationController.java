package ke.co.technopick.mfi.core.lending.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ke.co.technopick.mfi.core.identity.api.CurrentCaller;
import ke.co.technopick.mfi.core.lending.application.LoanOriginationService;
import ke.co.technopick.mfi.core.lending.application.LoanOriginationService.LoanApplicationSubmitted;
import ke.co.technopick.mfi.core.lending.application.LoanOriginationService.LoanApplicationView;
import ke.co.technopick.mfi.core.lending.application.LoanOriginationService.LoanScheduleView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Loan origination (ADR-0014). LOAN_OFFICER submits; a different BRANCH_MANAGER approves or
 * declines. Tenant from the token. Approved loans are disbursed via the B2C disbursement API.
 */
@RestController
@RequestMapping(path = "/api/v1/lending", produces = "application/json")
public class LoanOriginationController {

    public record LoanApplicationRequest(@NotBlank @Size(max = 80) String idempotencyKey,
                                         @NotBlank @Size(max = 64) String customerRef,
                                         @NotBlank @Size(max = 20) String productCode,
                                         @NotBlank @Size(max = 16) String branchCode,
                                         @NotBlank @Pattern(regexp = "^\\d{1,15}(\\.\\d{1,2})?$") String principal,
                                         @Min(1) @Max(360) int termMonths, LocalDate firstDueDate,
                                         @NotBlank @Size(min = 3, max = 200) String purpose) { }

    public record LoanDecisionRequest(@Size(max = 300) String reason) { }

    private final LoanOriginationService service;
    private final CurrentCaller caller;

    public LoanOriginationController(LoanOriginationService service, CurrentCaller caller) {
        this.service = service;
        this.caller = caller;
    }

    @PostMapping("/applications")
    @PreAuthorize("hasRole('LOAN_OFFICER')")
    public LoanApplicationSubmitted submit(@Valid @RequestBody LoanApplicationRequest r) {
        return service.submit(caller.tenantId(), r.idempotencyKey(), r.customerRef(), r.productCode(), r.branchCode(),
                new BigDecimal(r.principal()), r.termMonths(), r.firstDueDate(), r.purpose(), caller.actor());
    }

    @GetMapping("/applications")
    @PreAuthorize("hasAnyRole('LOAN_OFFICER', 'BRANCH_MANAGER')")
    public List<LoanApplicationView> pending() {
        return service.pending(caller.tenantId());
    }

    @GetMapping("/applications/{applicationId}")
    @PreAuthorize("hasAnyRole('LOAN_OFFICER', 'BRANCH_MANAGER')")
    public LoanApplicationView get(@PathVariable UUID applicationId) {
        return service.view(caller.tenantId(), applicationId);
    }

    @PostMapping("/applications/{applicationId}/approve")
    @PreAuthorize("hasRole('BRANCH_MANAGER')")
    public LoanApplicationView approve(@PathVariable UUID applicationId, @Valid @RequestBody(required = false) LoanDecisionRequest r) {
        return service.decide(caller.tenantId(), applicationId, true, r == null ? null : r.reason(), caller.actor());
    }

    @PostMapping("/applications/{applicationId}/decline")
    @PreAuthorize("hasRole('BRANCH_MANAGER')")
    public LoanApplicationView decline(@PathVariable UUID applicationId, @Valid @RequestBody LoanDecisionRequest r) {
        return service.decide(caller.tenantId(), applicationId, false, r.reason(), caller.actor());
    }

    @GetMapping("/loans/{loanNumber}/schedule")
    @PreAuthorize("hasAnyRole('LOAN_OFFICER', 'BRANCH_MANAGER', 'TELLER', 'OPERATIONS')")
    public LoanScheduleView schedule(@PathVariable String loanNumber) {
        return service.schedule(caller.tenantId(), loanNumber);
    }
}
