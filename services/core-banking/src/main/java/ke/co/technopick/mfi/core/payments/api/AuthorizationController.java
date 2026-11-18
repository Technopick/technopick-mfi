package ke.co.technopick.mfi.core.payments.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;
import ke.co.technopick.mfi.canonical.AccountRef;
import ke.co.technopick.mfi.canonical.CounterpartyKeys;
import ke.co.technopick.mfi.canonical.MessageFunction;
import ke.co.technopick.mfi.canonical.Money;
import ke.co.technopick.mfi.canonical.TransactionRequest;
import ke.co.technopick.mfi.canonical.TransactionType;
import ke.co.technopick.mfi.core.identity.api.CurrentCaller;
import ke.co.technopick.mfi.core.payments.application.AuthorizationService;
import ke.co.technopick.mfi.core.payments.application.AuthorizationService.AuthorizationResult;
import ke.co.technopick.mfi.core.payments.application.AuthorizationService.CompletionResult;
import ke.co.technopick.mfi.core.payments.application.AuthorizationService.ReversalResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal authorization API for the financial switch (role SWITCH, mTLS in production).
 *
 * <p>Tenant: the switch derives the tenant from authenticated terminal / participant / BIN
 * routing tables and passes it here. This is accepted only from the SWITCH service identity;
 * staff and customer APIs take the tenant from their token instead ({@link CurrentCaller}).
 * Money is exchanged as decimal strings, never JSON floating-point numbers.
 */
@RestController
@RequestMapping(path = "/api/v1/authorizations", produces = "application/json")
@PreAuthorize("hasRole('SWITCH')")
public class AuthorizationController {

    private static final String AMOUNT = "^\\d{1,15}(\\.\\d{1,4})?$";

    public record AuthorizeRequest(
            @NotNull UUID tenantId,
            @NotBlank String channel,
            @NotBlank String accountNumber,
            @NotNull TransactionType type,
            @NotBlank @Pattern(regexp = AMOUNT) String amount,
            @Pattern(regexp = AMOUNT) String fee,
            @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
            @NotBlank String acquirerId,
            String terminalId,
            @NotBlank String stan,
            @NotNull Instant transmissionDateTime,
            String rrn,
            String correlationId) { }

    public record AuthorizeResponse(boolean approved, String reason, UUID holdId, String holdAmount,
                                    String availableBalance, boolean replayed) { }

    public record CompleteRequest(@NotNull UUID tenantId, @NotBlank String channel,
                                  @NotBlank @Pattern(regexp = AMOUNT) String amount,
                                  @Pattern(regexp = AMOUNT) String fee, @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
                                  String correlationId) { }

    public record CompleteResponse(UUID holdId, UUID entryId, boolean replayed) { }

    public record ReverseRequest(@NotNull UUID tenantId, String correlationId) { }

    public record ReverseResponse(UUID holdId, String outcome, UUID reversalEntryId, boolean replayed) { }

    private final AuthorizationService service;
    private final CurrentCaller caller;

    public AuthorizationController(AuthorizationService service, CurrentCaller caller) {
        this.service = service;
        this.caller = caller;
    }

    @PostMapping
    public AuthorizeResponse authorize(@Valid @RequestBody AuthorizeRequest r) {
        Currency ccy = Currency.getInstance(r.currency());
        String correlationId = Objects.requireNonNullElse(r.correlationId(), UUID.randomUUID().toString());
        TransactionRequest request = new TransactionRequest(UUID.randomUUID(), correlationId, r.tenantId(), r.type(),
                MessageFunction.REQUEST, money(r.amount(), ccy), money(Objects.requireNonNullElse(r.fee(), "0"), ccy),
                AccountRef.internal(r.accountNumber()), null,
                CounterpartyKeys.iso8583(r.acquirerId(), r.terminalId(), r.stan(), r.rrn(), r.transmissionDateTime()),
                null);
        AuthorizationResult result = service.authorize(r.channel(), request);
        return new AuthorizeResponse(result.reason().isApproved(), result.reason().name(), result.holdId(),
                plain(result.holdAmount()), plain(result.availableBalance()), result.replayed());
    }

    @PostMapping("/{holdId}/completion")
    public CompleteResponse complete(@PathVariable UUID holdId, @Valid @RequestBody CompleteRequest r) {
        Currency ccy = Currency.getInstance(r.currency());
        CompletionResult result = service.complete(r.tenantId(), holdId, r.channel(), money(r.amount(), ccy),
                money(Objects.requireNonNullElse(r.fee(), "0"), ccy), caller.actor(),
                Objects.requireNonNullElse(r.correlationId(), UUID.randomUUID().toString()));
        return new CompleteResponse(result.holdId(), result.entryId(), result.replayed());
    }

    @PostMapping("/{holdId}/reversal")
    public ReverseResponse reverse(@PathVariable UUID holdId, @Valid @RequestBody ReverseRequest r) {
        ReversalResult result = service.reverse(r.tenantId(), holdId, caller.actor(),
                Objects.requireNonNullElse(r.correlationId(), UUID.randomUUID().toString()));
        return new ReverseResponse(result.holdId(), result.outcome().name(), result.reversalEntryId(), result.replayed());
    }

    private static Money money(String value, Currency ccy) {
        return Money.of(new BigDecimal(value), ccy);
    }

    private static String plain(Money m) {
        return m == null ? null : m.amount().toPlainString();
    }
}
