package ke.co.technopick.mfi.core.payments.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Currency;
import java.util.UUID;
import ke.co.technopick.mfi.core.identity.api.CurrentCaller;
import ke.co.technopick.mfi.core.payments.application.MpesaStkService;
import ke.co.technopick.mfi.core.payments.application.MpesaStkService.Created;
import ke.co.technopick.mfi.core.payments.application.MpesaStkService.Dispatch;
import ke.co.technopick.mfi.core.payments.application.MpesaStkService.View;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * STK Push requests. Staff create and read requests (tenant from the token). The integration
 * runtime (role INTEGRATION) dispatches them and reports provider outcomes, naming the tenant it
 * derived from its own configuration and the authenticated callback path (ADR-0007, ADR-0011).
 */
@RestController
@RequestMapping(path = "/api/v1/payments/mpesa/stk-requests", produces = "application/json")
public class MpesaStkController {

    private static final String WHOLE = "^\\d{1,9}(\\.0{1,2})?$";

    public record CreateRequest(@NotBlank @Size(max = 80) String idempotencyKey, String shortCode,
                                @NotBlank @Size(max = 16) String phoneNumber,
                                @NotBlank @Pattern(regexp = WHOLE, message = "whole shillings only") String amount,
                                @NotBlank @Size(max = 12) String targetReference) { }

    public record TenantOnly(@NotNull UUID tenantId) { }

    public record Submitted(@NotNull UUID tenantId, @Size(max = 60) String merchantRequestId,
                            @NotBlank @Size(max = 60) String checkoutRequestId) { }

    public record SendFailure(@NotNull UUID tenantId, @NotBlank @Size(max = 14) String code,
                              @Size(max = 200) String description) { }

    public record ResultRequest(@NotNull UUID tenantId, @NotBlank @Size(max = 60) String checkoutRequestId,
                                @Size(max = 60) String merchantRequestId, @NotBlank @Size(max = 20) String resultCode,
                                @Size(max = 200) String resultDesc,
                                @Pattern(regexp = "^[A-Z0-9]{8,20}$") String receipt,
                                @Pattern(regexp = "^\\d{1,15}(\\.\\d{1,2})?$") String amount,
                                OffsetDateTime transactionTime) { }

    public record StaleRequest(@NotNull UUID tenantId, @jakarta.validation.constraints.PositiveOrZero long olderThanSeconds,
                               @jakarta.validation.constraints.Positive int limit) { }

    public record StaleItems(List<MpesaStkService.StaleItem> items) { }

    public record QueryResult(@NotNull UUID tenantId, @NotBlank @Size(max = 60) String checkoutRequestId,
                              @Size(max = 20) String resultCode, @Size(max = 200) String resultDesc) { }

    private final MpesaStkService service;
    private final CurrentCaller caller;

    public MpesaStkController(MpesaStkService service, CurrentCaller caller) {
        this.service = service;
        this.caller = caller;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('TELLER', 'LOAN_OFFICER', 'OPERATIONS')")
    public Created create(@Valid @RequestBody CreateRequest r) {
        return service.request(caller.tenantId(), r.idempotencyKey(), r.shortCode(), r.phoneNumber(),
                MpesaStkService.amount(r.amount(), Currency.getInstance("KES")), r.targetReference(), caller.actor());
    }

    @GetMapping("/{requestId}")
    @PreAuthorize("hasAnyRole('TELLER', 'LOAN_OFFICER', 'OPERATIONS', 'SUPERVISOR')")
    public View get(@PathVariable UUID requestId) {
        return service.view(caller.tenantId(), requestId);
    }

    @PostMapping("/{requestId}/dispatch")
    @PreAuthorize("hasRole('INTEGRATION')")
    public Dispatch dispatch(@PathVariable UUID requestId, @Valid @RequestBody TenantOnly r) {
        return service.dispatch(r.tenantId(), requestId);
    }

    @PostMapping("/{requestId}/submitted")
    @PreAuthorize("hasRole('INTEGRATION')")
    public View submitted(@PathVariable UUID requestId, @Valid @RequestBody Submitted r) {
        return service.submitted(r.tenantId(), requestId, r.merchantRequestId(), r.checkoutRequestId());
    }

    @PostMapping("/{requestId}/send-failure")
    @PreAuthorize("hasRole('INTEGRATION')")
    public View sendFailure(@PathVariable UUID requestId, @Valid @RequestBody SendFailure r) {
        return service.sendFailed(r.tenantId(), requestId, r.code(), r.description());
    }

    @PostMapping("/{requestId}/result")
    @PreAuthorize("hasRole('INTEGRATION')")
    public View result(@PathVariable UUID requestId, @Valid @RequestBody ResultRequest r) {
        return service.result(r.tenantId(), requestId, new MpesaStkService.Result(r.checkoutRequestId(),
                r.merchantRequestId(), r.resultCode(), r.resultDesc(), r.receipt(),
                r.amount() == null ? null : MpesaStkService.amount(r.amount(), Currency.getInstance("KES")),
                r.transactionTime()), "stk:" + requestId);
    }

    @GetMapping("/attention")
    @PreAuthorize("hasAnyRole('OPERATIONS', 'SUPERVISOR')")
    public List<MpesaStkService.AttentionItem> attention() {
        return service.attention(caller.tenantId());
    }

    @PostMapping("/reconciliation/stale")
    @PreAuthorize("hasRole('INTEGRATION')")
    public StaleItems stale(@Valid @RequestBody StaleRequest r) {
        return new StaleItems(service.stale(r.tenantId(), Duration.ofSeconds(r.olderThanSeconds()), r.limit()));
    }

    @PostMapping("/{requestId}/query-result")
    @PreAuthorize("hasRole('INTEGRATION')")
    public View queryResult(@PathVariable UUID requestId, @Valid @RequestBody QueryResult r) {
        return service.queryResult(r.tenantId(), requestId, r.checkoutRequestId(), r.resultCode(), r.resultDesc(),
                "stk:" + requestId);
    }
}
