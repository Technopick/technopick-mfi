package ke.co.technopick.mfi.core.lending.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import ke.co.technopick.mfi.core.identity.api.CurrentCaller;
import ke.co.technopick.mfi.core.lending.application.LoanLimitService;
import ke.co.technopick.mfi.core.lending.application.LoanLimitService.ApplicationCover;
import ke.co.technopick.mfi.core.lending.application.LoanLimitService.GuaranteeView;
import ke.co.technopick.mfi.core.lending.application.LoanLimitService.LoanLimitRuleView;
import ke.co.technopick.mfi.core.lending.application.LoanLimitService.LoanLimitView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Loan limits and guarantors (ADR-0043). */
@RestController
@RequestMapping(path = "/api/v1/lending", produces = "application/json")
public class LoanGuaranteeController {

    public record LoanLimitRuleRequest(@NotNull @DecimalMin("0.01") @DecimalMax("20") @Digits(integer = 2, fraction = 2) BigDecimal savingsMultiple,
                                       @NotNull Boolean includeShares, @NotNull Boolean deductOutstanding,
                                       @NotNull Boolean guaranteesRequired) { }

    public record GuaranteePledgeRequest(@NotBlank @Size(max = 64) String idempotencyKey,
                                         @NotBlank @Pattern(regexp = "^CU[0-9]{2}-[0-9]{7}$") String guarantorNumber,
                                         @NotBlank @Pattern(regexp = "^[0-9]{10}$") String accountNumber,
                                         @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount,
                                         @NotBlank @Size(min = 3, max = 100) String consentReference) { }

    public record GuaranteeWithdrawRequest(@NotBlank @Size(min = 3, max = 300) String reason) { }

    private final LoanLimitService limits;
    private final CurrentCaller caller;

    public LoanGuaranteeController(LoanLimitService limits, CurrentCaller caller) {
        this.limits = limits;
        this.caller = caller;
    }

    @GetMapping("/limit-rules")
    @PreAuthorize("hasAnyRole('FINANCE', 'SUPERVISOR', 'BRANCH_MANAGER', 'LOAN_OFFICER')")
    public List<LoanLimitRuleView> rules() {
        return limits.rules(caller.tenantId());
    }

    @PutMapping(path = "/limit-rules/{productCode}", consumes = "application/json")
    @PreAuthorize("hasRole('FINANCE')")
    public LoanLimitRuleView saveRule(@PathVariable String productCode, @Valid @RequestBody LoanLimitRuleRequest r) {
        return limits.saveRule(caller.tenantId(), productCode, r.savingsMultiple(), r.includeShares(), r.deductOutstanding(),
                r.guaranteesRequired(), caller.actor());
    }

    @PostMapping("/limit-rules/{productCode}/remove")
    @PreAuthorize("hasRole('FINANCE')")
    public List<LoanLimitRuleView> removeRule(@PathVariable String productCode) {
        limits.removeRule(caller.tenantId(), productCode);
        return limits.rules(caller.tenantId());
    }

    @GetMapping("/limits")
    @PreAuthorize("hasAnyRole('LOAN_OFFICER', 'BRANCH_MANAGER', 'TELLER', 'SUPERVISOR', 'FINANCE')")
    public LoanLimitView limit(@RequestParam String customerNumber, @RequestParam String productCode) {
        return limits.limit(caller.tenantId(), customerNumber, productCode);
    }

    @GetMapping("/applications/{applicationId}/cover")
    @PreAuthorize("hasAnyRole('LOAN_OFFICER', 'BRANCH_MANAGER')")
    public ApplicationCover cover(@PathVariable UUID applicationId) {
        return limits.cover(caller.tenantId(), applicationId);
    }

    @PostMapping(path = "/applications/{applicationId}/guarantees", consumes = "application/json")
    @PreAuthorize("hasAnyRole('LOAN_OFFICER', 'BRANCH_MANAGER')")
    public ApplicationCover pledge(@PathVariable UUID applicationId, @Valid @RequestBody GuaranteePledgeRequest r) {
        return limits.pledge(caller.tenantId(), applicationId, r.idempotencyKey(), r.guarantorNumber(), r.accountNumber(), r.amount(),
                r.consentReference(), caller.actor());
    }

    @PostMapping(path = "/guarantees/{guaranteeId}/withdraw", consumes = "application/json")
    @PreAuthorize("hasAnyRole('LOAN_OFFICER', 'BRANCH_MANAGER')")
    public ApplicationCover withdraw(@PathVariable UUID guaranteeId, @Valid @RequestBody GuaranteeWithdrawRequest r) {
        return limits.withdraw(caller.tenantId(), guaranteeId, r.reason(), caller.actor());
    }

    @GetMapping("/members/{customerNumber}/guarantees-given")
    @PreAuthorize("hasAnyRole('LOAN_OFFICER', 'BRANCH_MANAGER', 'TELLER', 'SUPERVISOR', 'OPERATIONS')")
    public List<GuaranteeView> givenBy(@PathVariable String customerNumber) {
        return limits.givenBy(caller.tenantId(), customerNumber);
    }
}
