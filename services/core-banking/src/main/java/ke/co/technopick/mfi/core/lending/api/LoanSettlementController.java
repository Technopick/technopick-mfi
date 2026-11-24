package ke.co.technopick.mfi.core.lending.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import ke.co.technopick.mfi.core.identity.api.CurrentCaller;
import ke.co.technopick.mfi.core.lending.application.CreditRefundService;
import ke.co.technopick.mfi.core.lending.application.CreditRefundService.RefundView;
import ke.co.technopick.mfi.core.lending.application.LoanSettlementService;
import ke.co.technopick.mfi.core.lending.application.LoanSettlementService.SettlementQuote;
import ke.co.technopick.mfi.core.lending.application.LoanSettlementService.SettlementView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Early settlement and refunds of loan credit (ADR-0032). Anyone who serves members can see a
 * payoff quote; a branch manager or operations settles; a refund is asked for by one of them and
 * decided by a supervisor who did not ask for it.
 */
@RestController
@RequestMapping(path = "/api/v1/lending", produces = "application/json")
public class LoanSettlementController {

    public record SettlementRequest(@NotBlank @Size(max = 80) String idempotencyKey) { }

    public record CreditRefundRequest(@NotBlank @Size(max = 80) String idempotencyKey, @NotBlank @Size(max = 34) String accountNumber,
                                      @NotBlank @Size(max = 20) String amount, @NotBlank @Size(min = 10, max = 300) String reason) { }

    public record CreditRefundDecision(@Size(max = 300) String reason) { }

    private final LoanSettlementService settlements;
    private final CreditRefundService refunds;
    private final CurrentCaller caller;

    public LoanSettlementController(LoanSettlementService settlements, CreditRefundService refunds, CurrentCaller caller) {
        this.settlements = settlements;
        this.refunds = refunds;
        this.caller = caller;
    }

    @GetMapping("/loans/{loanNumber}/settlement")
    @PreAuthorize("hasAnyRole('LOAN_OFFICER', 'BRANCH_MANAGER', 'OPERATIONS', 'SUPERVISOR')")
    public SettlementQuote quote(@PathVariable @Size(max = 34) String loanNumber) {
        return settlements.quote(caller.tenantId(), loanNumber);
    }

    @PostMapping(path = "/loans/{loanNumber}/settlement", consumes = "application/json")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER', 'OPERATIONS')")
    public SettlementView settle(@PathVariable @Size(max = 34) String loanNumber, @Valid @RequestBody SettlementRequest r) {
        return settlements.settle(caller.tenantId(), loanNumber, r.idempotencyKey(), caller.actor());
    }

    @PostMapping(path = "/loans/{loanNumber}/credit-refunds", consumes = "application/json")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER', 'OPERATIONS')")
    public RefundView requestRefund(@PathVariable @Size(max = 34) String loanNumber, @Valid @RequestBody CreditRefundRequest r) {
        return refunds.request(caller.tenantId(), loanNumber, r.idempotencyKey(), r.accountNumber(), r.amount(), r.reason(),
                caller.actor());
    }

    @GetMapping("/credit-refunds")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER', 'OPERATIONS', 'SUPERVISOR')")
    public List<RefundView> refunds(@RequestParam(required = false) @Pattern(regexp = "REQUESTED|APPROVED|REJECTED") String status,
                                    @RequestParam(required = false) @Size(max = 34) String loanNumber) {
        return refunds.list(caller.tenantId(), status, loanNumber);
    }

    @PostMapping("/credit-refunds/{refundId}/approve")
    @PreAuthorize("hasRole('SUPERVISOR')")
    public RefundView approve(@PathVariable UUID refundId, @Valid @RequestBody(required = false) CreditRefundDecision r) {
        return refunds.decide(caller.tenantId(), refundId, true, r == null ? null : r.reason(), caller.actor());
    }

    @PostMapping(path = "/credit-refunds/{refundId}/reject", consumes = "application/json")
    @PreAuthorize("hasRole('SUPERVISOR')")
    public RefundView reject(@PathVariable UUID refundId, @Valid @RequestBody CreditRefundDecision r) {
        return refunds.decide(caller.tenantId(), refundId, false, r.reason(), caller.actor());
    }
}
