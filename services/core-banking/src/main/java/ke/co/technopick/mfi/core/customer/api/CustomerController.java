package ke.co.technopick.mfi.core.customer.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import ke.co.technopick.mfi.core.customer.application.CustomerService;
import ke.co.technopick.mfi.core.customer.application.CustomerService.CustomerDetail;
import ke.co.technopick.mfi.core.customer.application.CustomerService.CustomerRegistered;
import ke.co.technopick.mfi.core.customer.application.CustomerService.CustomerSummary;
import ke.co.technopick.mfi.core.identity.api.CurrentCaller;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customers and KYC (ADR-0016). Registration by LOAN_OFFICER or TELLER; verification by a
 * different BRANCH_MANAGER or OPERATIONS user. Tenant from the token.
 */
@RestController
@RequestMapping(path = "/api/v1/customers", produces = "application/json")
public class CustomerController {

    public record CustomerRegistration(@NotBlank @Size(max = 80) String idempotencyKey,
                                       @NotBlank @Size(max = 60) String firstName, @Size(max = 60) String middleName,
                                       @NotBlank @Size(max = 60) String lastName, @NotNull @Past LocalDate dateOfBirth,
                                       @NotNull CustomerService.IdType idType, @NotBlank @Size(max = 20) String idNumber,
                                       @Size(max = 11) String kraPin, @NotBlank @Size(max = 16) String phoneNumber,
                                       @Email @Size(max = 120) String email, @NotBlank @Size(max = 16) String branchCode,
                                       boolean consentGiven) { }

    public record CustomerVerification(@NotBlank @Size(min = 10, max = 300) String note) { }

    private final CustomerService service;
    private final CurrentCaller caller;

    public CustomerController(CustomerService service, CurrentCaller caller) {
        this.service = service;
        this.caller = caller;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('LOAN_OFFICER', 'TELLER')")
    public CustomerRegistered register(@Valid @RequestBody CustomerRegistration r) {
        return service.register(caller.tenantId(), new CustomerService.Registration(r.idempotencyKey(), r.firstName(),
                r.middleName(), r.lastName(), r.dateOfBirth(), r.idType(), r.idNumber(), r.kraPin(), r.phoneNumber(),
                r.email(), r.branchCode(), r.consentGiven()), caller.actor());
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('LOAN_OFFICER', 'TELLER', 'BRANCH_MANAGER', 'OPERATIONS', 'SUPERVISOR')")
    public List<CustomerSummary> search(@RequestParam(required = false) String q,
                                        @RequestParam(required = false) String status) {
        return service.search(caller.tenantId(), q, status);
    }

    @GetMapping("/{customerNumber}")
    @PreAuthorize("hasAnyRole('LOAN_OFFICER', 'TELLER', 'BRANCH_MANAGER', 'OPERATIONS', 'SUPERVISOR')")
    public CustomerDetail detail(@PathVariable String customerNumber) {
        return service.detail(caller.tenantId(), customerNumber);
    }

    @PostMapping("/{customerNumber}/verify")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER', 'OPERATIONS')")
    public CustomerDetail verify(@PathVariable String customerNumber, @Valid @RequestBody CustomerVerification v) {
        return service.verify(caller.tenantId(), customerNumber, v.note(), caller.actor());
    }
}
