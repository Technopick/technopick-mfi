package ke.co.technopick.mfi.core.savings.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import ke.co.technopick.mfi.core.identity.api.CurrentCaller;
import ke.co.technopick.mfi.core.savings.application.SavingsAccountService;
import ke.co.technopick.mfi.core.savings.application.SavingsAccountService.SavingsAccountOpened;
import ke.co.technopick.mfi.core.savings.application.SavingsAccountService.SavingsAccountView;
import ke.co.technopick.mfi.core.savings.application.SavingsAccountService.SavingsStatement;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Savings accounts for verified customers (ADR-0017). Tenant from the token. */
@RestController
@RequestMapping(path = "/api/v1/savings/accounts", produces = "application/json")
public class SavingsAccountController {

    public record SavingsAccountOpening(@NotBlank @Size(max = 80) String idempotencyKey,
                                        @NotBlank @Size(max = 12) String customerNumber,
                                        @NotBlank @Size(max = 32) String productCode,
                                        @NotBlank @Size(max = 16) String branchCode) { }

    private final SavingsAccountService service;
    private final CurrentCaller caller;

    public SavingsAccountController(SavingsAccountService service, CurrentCaller caller) {
        this.service = service;
        this.caller = caller;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('TELLER', 'LOAN_OFFICER', 'OPERATIONS')")
    public SavingsAccountOpened open(@Valid @RequestBody SavingsAccountOpening r) {
        return service.open(caller.tenantId(), r.idempotencyKey(), r.customerNumber(), r.productCode(), r.branchCode(),
                caller.actor());
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('TELLER', 'LOAN_OFFICER', 'OPERATIONS', 'BRANCH_MANAGER', 'SUPERVISOR')")
    public List<SavingsAccountView> ofCustomer(@RequestParam String customerNumber) {
        return service.ofCustomer(caller.tenantId(), customerNumber);
    }

    @GetMapping("/{accountNumber}")
    @PreAuthorize("hasAnyRole('TELLER', 'LOAN_OFFICER', 'OPERATIONS', 'BRANCH_MANAGER', 'SUPERVISOR')")
    public SavingsAccountView get(@PathVariable String accountNumber) {
        return service.view(caller.tenantId(), accountNumber);
    }

    @GetMapping("/{accountNumber}/statement")
    @PreAuthorize("hasAnyRole('TELLER', 'LOAN_OFFICER', 'OPERATIONS', 'BRANCH_MANAGER', 'SUPERVISOR')")
    public SavingsStatement statement(@PathVariable String accountNumber,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.statement(caller.tenantId(), accountNumber, from, to);
    }
}
