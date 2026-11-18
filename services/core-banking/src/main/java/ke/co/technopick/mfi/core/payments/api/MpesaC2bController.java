package ke.co.technopick.mfi.core.payments.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;
import ke.co.technopick.mfi.core.payments.application.MpesaC2bService;
import ke.co.technopick.mfi.core.payments.application.MpesaC2bService.Applied;
import ke.co.technopick.mfi.core.payments.application.MpesaC2bService.C2bPayment;
import ke.co.technopick.mfi.core.payments.application.MpesaC2bService.Validation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal API for the integration runtime (role INTEGRATION). The integration runtime derives
 * the tenant from the authenticated callback path token and verified short code, then passes it
 * here; no other caller may name a tenant (ADR-0007).
 */
@RestController
@RequestMapping(path = "/api/v1/payments/mpesa/c2b", produces = "application/json")
@PreAuthorize("hasRole('INTEGRATION')")
public class MpesaC2bController {

    private static final String AMOUNT = "^\\d{1,15}(\\.\\d{1,2})?$";

    public record ValidateRequest(@NotNull UUID tenantId, @NotBlank String shortCode, String billRef,
                                  @NotBlank @Pattern(regexp = AMOUNT) String amount,
                                  @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency) { }

    public record ValidateResponse(String resultCode, String resultDesc) { }

    public record ApplyRequest(@NotNull UUID tenantId, @NotBlank @Pattern(regexp = "^[A-Z0-9]{8,20}$") String transId,
                               @NotBlank String shortCode, @NotBlank @Pattern(regexp = AMOUNT) String amount,
                               @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency, String billRef,
                               String msisdnMasked, @NotNull OffsetDateTime transTime, String correlationId) { }

    public record ApplyResponse(UUID paymentId, String outcome, UUID journalEntryId, String accountNumber,
                                boolean replayed) { }

    private final MpesaC2bService service;

    public MpesaC2bController(MpesaC2bService service) {
        this.service = service;
    }

    @PostMapping("/validation")
    public ValidateResponse validate(@Valid @RequestBody ValidateRequest r) {
        Validation v = service.validate(r.tenantId(), r.shortCode(), r.billRef(),
                MpesaC2bService.kes(r.amount(), Currency.getInstance(r.currency())));
        return new ValidateResponse(v.resultCode(), v.resultDesc());
    }

    @PostMapping
    public ApplyResponse apply(@Valid @RequestBody ApplyRequest r) {
        Applied a = service.apply(new C2bPayment(r.tenantId(), r.transId(), r.shortCode(),
                MpesaC2bService.kes(r.amount(), Currency.getInstance(r.currency())), r.billRef(), r.msisdnMasked(),
                r.transTime(), Objects.requireNonNullElse(r.correlationId(), r.transId())));
        return new ApplyResponse(a.paymentId(), a.outcome().name(), a.journalEntryId(), a.accountNumber(), a.replayed());
    }
}
