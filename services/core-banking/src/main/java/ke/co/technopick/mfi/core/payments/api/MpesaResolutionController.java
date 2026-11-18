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
import ke.co.technopick.mfi.core.payments.application.MpesaC2bService;
import ke.co.technopick.mfi.core.payments.application.MpesaResolutionService;
import ke.co.technopick.mfi.core.payments.application.MpesaResolutionService.MpesaResolutionView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operations close-out of unsettled M-Pesa items (ADR-0013): OPERATIONS proposes, a different SUPERVISOR decides. */
@RestController
@RequestMapping(path = "/api/v1/payments/mpesa/resolutions", produces = "application/json")
public class MpesaResolutionController {

    public record MpesaResolutionProposal(@NotNull MpesaResolutionService.ResolutionItemType itemType, @NotNull UUID itemId,
                                          @NotNull MpesaResolutionService.ResolutionOutcome outcome,
                                          @Pattern(regexp = "^[A-Z0-9]{8,20}$") String providerRef,
                                          @Pattern(regexp = "^\\d{1,15}(\\.\\d{1,2})?$") String amount,
                                          @NotBlank @Size(min = 10, max = 500) String evidence) { }

    public record MpesaResolutionCreated(UUID resolutionId) { }

    private final MpesaResolutionService service;
    private final CurrentCaller caller;

    public MpesaResolutionController(MpesaResolutionService service, CurrentCaller caller) {
        this.service = service;
        this.caller = caller;
    }

    @PostMapping
    @PreAuthorize("hasRole('OPERATIONS')")
    public MpesaResolutionCreated propose(@Valid @RequestBody MpesaResolutionProposal p) {
        return new MpesaResolutionCreated(service.propose(caller.tenantId(), p.itemType(), p.itemId(), p.outcome(),
                p.providerRef(), p.amount() == null ? null : MpesaC2bService.kes(p.amount(), Currency.getInstance("KES")),
                p.evidence(), caller.actor()));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OPERATIONS', 'SUPERVISOR')")
    public List<MpesaResolutionView> pending() {
        return service.pending(caller.tenantId());
    }

    @PostMapping("/{resolutionId}/approve")
    @PreAuthorize("hasRole('SUPERVISOR')")
    public MpesaResolutionView approve(@PathVariable UUID resolutionId) {
        return service.decide(caller.tenantId(), resolutionId, true, caller.actor());
    }

    @PostMapping("/{resolutionId}/reject")
    @PreAuthorize("hasRole('SUPERVISOR')")
    public MpesaResolutionView reject(@PathVariable UUID resolutionId) {
        return service.decide(caller.tenantId(), resolutionId, false, caller.actor());
    }
}
