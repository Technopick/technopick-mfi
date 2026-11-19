package ke.co.technopick.mfi.core.savings.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import ke.co.technopick.mfi.core.common.NotFoundException;
import ke.co.technopick.mfi.core.identity.api.CurrentCaller;
import ke.co.technopick.mfi.core.savings.application.TillService;
import ke.co.technopick.mfi.core.savings.application.TillService.CashTransferView;
import ke.co.technopick.mfi.core.savings.application.TillService.TillSessionView;
import ke.co.technopick.mfi.core.savings.application.TillService.TillView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Teller tills (ADR-0018). TELLER opens, requests vault transfers and closes their own till;
 * BRANCH_MANAGER / SUPERVISOR (never the same person) approve transfers and closing variances.
 */
@RestController
@RequestMapping(path = "/api/v1/savings", produces = "application/json")
public class TillController {

    private static final String AMOUNT = "^\\d{1,15}(\\.\\d{1,2})?$";

    public record TillOpening(@NotBlank @Size(max = 24) String tillCode) { }

    public record CashTransferRequest(@NotBlank @Size(max = 80) String idempotencyKey, @NotNull TillService.Direction direction,
                                      @NotBlank @Pattern(regexp = AMOUNT) String amount) { }

    public record TillCount(@NotBlank @Pattern(regexp = AMOUNT) String countedAmount) { }

    public record SupervisorNote(@Size(max = 300) String reason) { }

    private final TillService service;
    private final CurrentCaller caller;

    public TillController(TillService service, CurrentCaller caller) {
        this.service = service;
        this.caller = caller;
    }

    @GetMapping("/tills")
    @PreAuthorize("hasAnyRole('TELLER', 'BRANCH_MANAGER', 'SUPERVISOR')")
    public List<TillView> tills() {
        return service.tills(caller.tenantId());
    }

    @PostMapping("/till-sessions")
    @PreAuthorize("hasRole('TELLER')")
    public TillSessionView open(@Valid @RequestBody TillOpening r) {
        return service.open(caller.tenantId(), r.tillCode(), caller.actor());
    }

    @GetMapping("/till-sessions/mine")
    @PreAuthorize("hasRole('TELLER')")
    public TillSessionView mine() {
        return service.mine(caller.tenantId(), caller.actor()).orElseThrow(() -> new NotFoundException("no till open"));
    }

    @GetMapping("/till-sessions/closing")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER', 'SUPERVISOR')")
    public List<TillSessionView> closing() {
        return service.awaitingVarianceApproval(caller.tenantId());
    }

    @PostMapping("/till-sessions/{sessionId}/transfers")
    @PreAuthorize("hasRole('TELLER')")
    public CashTransferView requestTransfer(@PathVariable UUID sessionId, @Valid @RequestBody CashTransferRequest r) {
        return service.requestTransfer(caller.tenantId(), sessionId, r.idempotencyKey(), r.direction(),
                new BigDecimal(r.amount()), caller.actor());
    }

    @PostMapping("/till-sessions/{sessionId}/close")
    @PreAuthorize("hasRole('TELLER')")
    public TillSessionView close(@PathVariable UUID sessionId, @Valid @RequestBody TillCount r) {
        return service.close(caller.tenantId(), sessionId, new BigDecimal(r.countedAmount()), caller.actor());
    }

    @PostMapping("/till-sessions/{sessionId}/approve-variance")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER', 'SUPERVISOR')")
    public TillSessionView approveVariance(@PathVariable UUID sessionId, @Valid @RequestBody SupervisorNote r) {
        return service.approveVariance(caller.tenantId(), sessionId, r.reason(), caller.actor());
    }

    @PostMapping("/till-sessions/{sessionId}/recount")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER', 'SUPERVISOR')")
    public TillSessionView recount(@PathVariable UUID sessionId, @Valid @RequestBody SupervisorNote r) {
        return service.recount(caller.tenantId(), sessionId, r.reason(), caller.actor());
    }

    @GetMapping("/cash-transfers")
    @PreAuthorize("hasAnyRole('TELLER', 'BRANCH_MANAGER', 'SUPERVISOR')")
    public List<CashTransferView> pendingTransfers() {
        return service.pendingTransfers(caller.tenantId());
    }

    @PostMapping("/cash-transfers/{transferId}/approve")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER', 'SUPERVISOR')")
    public CashTransferView approveTransfer(@PathVariable UUID transferId, @Valid @RequestBody(required = false) SupervisorNote r) {
        return service.decideTransfer(caller.tenantId(), transferId, true, r == null ? null : r.reason(), caller.actor());
    }

    @PostMapping("/cash-transfers/{transferId}/reject")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER', 'SUPERVISOR')")
    public CashTransferView rejectTransfer(@PathVariable UUID transferId, @Valid @RequestBody SupervisorNote r) {
        return service.decideTransfer(caller.tenantId(), transferId, false, r.reason(), caller.actor());
    }
}
