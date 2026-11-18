package ke.co.technopick.mfi.core.payments.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import ke.co.technopick.mfi.core.identity.api.CurrentCaller;
import ke.co.technopick.mfi.core.payments.application.B2cDisbursementService;
import ke.co.technopick.mfi.core.payments.application.B2cDisbursementService.Created;
import ke.co.technopick.mfi.core.payments.application.B2cDisbursementService.Dispatch;
import ke.co.technopick.mfi.core.payments.application.B2cDisbursementService.View;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Loan disbursement by M-Pesa B2C (ADR-0012). Staff request (LOAN_OFFICER, OPERATIONS) and a
 * different person approves (BRANCH_MANAGER, SUPERVISOR); tenant from the token. The integration
 * runtime (INTEGRATION) dispatches and reports provider outcomes, naming the tenant (ADR-0007).
 */
@RestController
@RequestMapping(path = "/api/v1/payments/mpesa/b2c/disbursements", produces = "application/json")
public class B2cDisbursementController {

    public record CreateRequest(@NotBlank @Size(max = 80) String idempotencyKey,
                                @NotBlank @Size(max = 34) String loanNumber,
                                @NotBlank @Size(max = 16) String phoneNumber, String shortCode) { }

    public record TenantOnly(@NotNull UUID tenantId) { }

    public record Submitted(@NotNull UUID tenantId, @NotBlank @Size(max = 60) String conversationId) { }

    public record SendFailure(@NotNull UUID tenantId, @NotBlank @Size(max = 14) String code,
                              @Size(max = 200) String description) { }

    public record ResultRequest(@NotNull UUID tenantId, @Size(max = 60) String conversationId,
                                @NotBlank @Size(max = 20) String resultCode, @Size(max = 200) String resultDesc,
                                @Pattern(regexp = "^[A-Z0-9]{8,20}$") String transactionId,
                                @Pattern(regexp = "^\\d{1,15}(\\.\\d{1,2})?$") String amount) { }

    private final B2cDisbursementService service;
    private final CurrentCaller caller;

    public B2cDisbursementController(B2cDisbursementService service, CurrentCaller caller) {
        this.service = service;
        this.caller = caller;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('LOAN_OFFICER', 'OPERATIONS')")
    public Created create(@Valid @RequestBody CreateRequest r) {
        return service.request(caller.tenantId(), r.idempotencyKey(), r.loanNumber(), r.phoneNumber(), r.shortCode(),
                caller.actor());
    }

    @GetMapping("/attention")
    @PreAuthorize("hasAnyRole('OPERATIONS', 'SUPERVISOR')")
    public List<View> attention() {
        return service.attention(caller.tenantId());
    }

    @GetMapping("/{disbursementId}")
    @PreAuthorize("hasAnyRole('LOAN_OFFICER', 'OPERATIONS', 'BRANCH_MANAGER', 'SUPERVISOR')")
    public View get(@PathVariable UUID disbursementId) {
        return service.view(caller.tenantId(), disbursementId);
    }

    @PostMapping("/{disbursementId}/approve")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER', 'SUPERVISOR')")
    public View approve(@PathVariable UUID disbursementId) {
        return service.decide(caller.tenantId(), disbursementId, true, caller.actor());
    }

    @PostMapping("/{disbursementId}/reject")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER', 'SUPERVISOR')")
    public View reject(@PathVariable UUID disbursementId) {
        return service.decide(caller.tenantId(), disbursementId, false, caller.actor());
    }

    @PostMapping("/{disbursementId}/dispatch")
    @PreAuthorize("hasRole('INTEGRATION')")
    public Dispatch dispatch(@PathVariable UUID disbursementId, @Valid @RequestBody TenantOnly r) {
        return service.dispatch(r.tenantId(), disbursementId);
    }

    @PostMapping("/{disbursementId}/submitted")
    @PreAuthorize("hasRole('INTEGRATION')")
    public View submitted(@PathVariable UUID disbursementId, @Valid @RequestBody Submitted r) {
        return service.submitted(r.tenantId(), disbursementId, r.conversationId());
    }

    @PostMapping("/{disbursementId}/send-failure")
    @PreAuthorize("hasRole('INTEGRATION')")
    public View sendFailure(@PathVariable UUID disbursementId, @Valid @RequestBody SendFailure r) {
        return service.sendFailed(r.tenantId(), disbursementId, r.code(), r.description());
    }

    @PostMapping("/{disbursementId}/result")
    @PreAuthorize("hasRole('INTEGRATION')")
    public View result(@PathVariable UUID disbursementId, @Valid @RequestBody ResultRequest r) {
        return service.result(r.tenantId(), disbursementId, new B2cDisbursementService.Result(r.conversationId(),
                r.resultCode(), r.resultDesc(), r.transactionId(),
                r.amount() == null ? null : B2cDisbursementService.amount(r.amount(), Currency.getInstance("KES"))),
                "b2c:" + disbursementId);
    }

    @PostMapping("/{disbursementId}/timeout")
    @PreAuthorize("hasRole('INTEGRATION')")
    public View timeout(@PathVariable UUID disbursementId, @Valid @RequestBody TenantOnly r) {
        return service.timeout(r.tenantId(), disbursementId);
    }
}
