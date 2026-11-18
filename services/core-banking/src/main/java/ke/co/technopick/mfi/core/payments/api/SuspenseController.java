package ke.co.technopick.mfi.core.payments.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import ke.co.technopick.mfi.core.identity.api.CurrentCaller;
import ke.co.technopick.mfi.core.payments.application.SuspenseService;
import ke.co.technopick.mfi.core.payments.application.SuspenseService.Decision;
import ke.co.technopick.mfi.core.payments.application.SuspenseService.OpenItem;
import ke.co.technopick.mfi.core.payments.application.SuspenseService.TargetType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Staff API for clearing suspense (tenant from the token; maker OPERATIONS, checker SUPERVISOR). */
@RestController
@RequestMapping(path = "/api/v1/payments/suspense", produces = "application/json")
public class SuspenseController {

    public record ResolutionRequest(@NotNull TargetType targetType, @NotBlank @Size(max = 40) String targetReference,
                                    @NotBlank @Size(max = 300) String reason) { }

    public record ResolutionCreated(UUID resolutionId) { }

    private final SuspenseService service;
    private final CurrentCaller caller;

    public SuspenseController(SuspenseService service, CurrentCaller caller) {
        this.service = service;
        this.caller = caller;
    }

    @GetMapping("/items")
    @PreAuthorize("hasAnyRole('OPERATIONS', 'SUPERVISOR')")
    public List<OpenItem> openItems() {
        return service.openItems(caller.tenantId());
    }

    @PostMapping("/items/{paymentId}/resolutions")
    @PreAuthorize("hasRole('OPERATIONS')")
    public ResolutionCreated request(@PathVariable UUID paymentId, @Valid @RequestBody ResolutionRequest r) {
        return new ResolutionCreated(service.request(caller.tenantId(), paymentId, r.targetType(), r.targetReference(),
                r.reason(), caller.actor()));
    }

    @PostMapping("/resolutions/{resolutionId}/approve")
    @PreAuthorize("hasRole('SUPERVISOR')")
    public Decision approve(@PathVariable UUID resolutionId) {
        return service.decide(caller.tenantId(), resolutionId, true, caller.actor(), "suspense:" + resolutionId);
    }

    @PostMapping("/resolutions/{resolutionId}/reject")
    @PreAuthorize("hasRole('SUPERVISOR')")
    public Decision reject(@PathVariable UUID resolutionId) {
        return service.decide(caller.tenantId(), resolutionId, false, caller.actor(), "suspense:" + resolutionId);
    }
}
