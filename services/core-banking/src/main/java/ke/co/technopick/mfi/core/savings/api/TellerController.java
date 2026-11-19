package ke.co.technopick.mfi.core.savings.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import ke.co.technopick.mfi.core.identity.api.CurrentCaller;
import ke.co.technopick.mfi.core.savings.application.TellerService;
import ke.co.technopick.mfi.core.savings.application.TellerService.TellerTransactionView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Teller cash deposits and withdrawals (ADR-0017). TELLER captures; amounts above the tenant
 * policy wait for a different BRANCH_MANAGER or SUPERVISOR.
 */
@RestController
@RequestMapping(path = "/api/v1/savings/teller/transactions", produces = "application/json")
public class TellerController {

    public record TellerTransactionRequest(@NotBlank @Size(max = 80) String idempotencyKey,
                                           @NotBlank @Size(max = 34) String accountNumber,
                                           @NotNull TellerService.Kind kind,
                                           @NotBlank @Pattern(regexp = "^\\d{1,15}(\\.\\d{1,2})?$") String amount,
                                           @Size(max = 140) String narrative, boolean requestApproval) { }

    public record TellerDecision(@Size(max = 300) String reason) { }

    private final TellerService service;
    private final CurrentCaller caller;

    public TellerController(TellerService service, CurrentCaller caller) {
        this.service = service;
        this.caller = caller;
    }

    @PostMapping
    @PreAuthorize("hasRole('TELLER')")
    public TellerTransactionView transact(@Valid @RequestBody TellerTransactionRequest r) {
        return service.transact(caller.tenantId(), r.idempotencyKey(), r.accountNumber(), r.kind(),
                new BigDecimal(r.amount()), r.narrative(), r.requestApproval(), caller.actor());
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('TELLER', 'BRANCH_MANAGER', 'SUPERVISOR')")
    public List<TellerTransactionView> pending() {
        return service.pending(caller.tenantId());
    }

    @PostMapping("/{txnId}/approve")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER', 'SUPERVISOR')")
    public TellerTransactionView approve(@PathVariable UUID txnId, @Valid @RequestBody(required = false) TellerDecision d) {
        return service.decide(caller.tenantId(), txnId, true, d == null ? null : d.reason(), caller.actor());
    }

    @PostMapping("/{txnId}/reject")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER', 'SUPERVISOR')")
    public TellerTransactionView reject(@PathVariable UUID txnId, @Valid @RequestBody TellerDecision d) {
        return service.decide(caller.tenantId(), txnId, false, d.reason(), caller.actor());
    }
}
