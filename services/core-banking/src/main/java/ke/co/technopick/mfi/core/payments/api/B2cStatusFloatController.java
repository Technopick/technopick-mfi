package ke.co.technopick.mfi.core.payments.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import ke.co.technopick.mfi.core.identity.api.CurrentCaller;
import ke.co.technopick.mfi.core.payments.application.B2cFloatService;
import ke.co.technopick.mfi.core.payments.application.B2cFloatService.BalanceCheckDue;
import ke.co.technopick.mfi.core.payments.application.B2cFloatService.BalanceCheckView;
import ke.co.technopick.mfi.core.payments.application.B2cFloatService.FloatAccountView;
import ke.co.technopick.mfi.core.payments.application.B2cFloatService.FloatSettings;
import ke.co.technopick.mfi.core.payments.application.B2cStatusQueryService;
import ke.co.technopick.mfi.core.payments.application.B2cStatusQueryService.StatusAnswer;
import ke.co.technopick.mfi.core.payments.application.B2cStatusQueryService.StatusQueryDue;
import ke.co.technopick.mfi.core.payments.application.B2cStatusQueryService.StatusQueryView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * B2C transaction status queries and float checks (ADR-0039). The integration runtime collects
 * what is due and reports what M-Pesa answered; staff ask M-Pesa on demand and see the float.
 */
@RestController
@RequestMapping(path = "/api/v1/payments/mpesa/b2c", produces = "application/json")
public class B2cStatusFloatController {

    public record B2cDueRequest(@NotNull UUID tenantId, @Min(1) @Max(200) Integer limit) { }

    public record B2cTenantRequest(@NotNull UUID tenantId) { }

    public record B2cSendFailure(@NotNull UUID tenantId, @Size(max = 200) String description) { }

    public record StatusQueryResultRequest(@NotNull UUID tenantId, @NotBlank @Size(max = 20) String resultCode,
                                           @Size(max = 200) String resultDesc, @Size(max = 40) String transactionStatus,
                                           @Pattern(regexp = "^[A-Z0-9]{8,20}$") String receiptNo,
                                           @Pattern(regexp = "^\\d{1,15}(\\.\\d{1,2})?$") String amount) { }

    public record BalanceCheckResultRequest(@NotNull UUID tenantId, @NotBlank @Size(max = 20) String resultCode,
                                            @Size(max = 200) String resultDesc, @Size(max = 2000) String accountBalance) { }

    public record FloatSettingsRequest(@NotBlank @Size(max = 60) String floatAccountName,
                                       @NotNull @DecimalMin("0") @Digits(integer = 13, fraction = 2) BigDecimal lowFloatThreshold,
                                       @NotNull @Min(0) @Max(23) Integer dailyCheckHour) { }

    private final B2cStatusQueryService queries;
    private final B2cFloatService floats;
    private final CurrentCaller caller;

    public B2cStatusFloatController(B2cStatusQueryService queries, B2cFloatService floats, CurrentCaller caller) {
        this.queries = queries;
        this.floats = floats;
        this.caller = caller;
    }

    // ------------------------------------------------------------------ status queries

    @PostMapping("/status-queries/due")
    @PreAuthorize("hasRole('INTEGRATION')")
    public List<StatusQueryDue> queriesDue(@Valid @RequestBody B2cDueRequest r) {
        return queries.due(r.tenantId(), r.limit() == null ? 50 : r.limit());
    }

    @PostMapping("/status-queries/{queryId}/sent")
    @PreAuthorize("hasRole('INTEGRATION')")
    public StatusQueryView querySent(@PathVariable UUID queryId, @Valid @RequestBody B2cTenantRequest r) {
        return queries.sent(r.tenantId(), queryId);
    }

    @PostMapping("/status-queries/{queryId}/send-failure")
    @PreAuthorize("hasRole('INTEGRATION')")
    public StatusQueryView querySendFailure(@PathVariable UUID queryId, @Valid @RequestBody B2cSendFailure r) {
        return queries.sendFailed(r.tenantId(), queryId, r.description());
    }

    @PostMapping("/status-queries/{queryId}/no-answer")
    @PreAuthorize("hasRole('INTEGRATION')")
    public StatusQueryView queryNoAnswer(@PathVariable UUID queryId, @Valid @RequestBody B2cTenantRequest r) {
        return queries.noAnswer(r.tenantId(), queryId);
    }

    @PostMapping("/status-queries/{queryId}/result")
    @PreAuthorize("hasRole('INTEGRATION')")
    public StatusQueryView queryResult(@PathVariable UUID queryId, @Valid @RequestBody StatusQueryResultRequest r) {
        return queries.answer(r.tenantId(), queryId, new StatusAnswer(r.resultCode(), r.resultDesc(), r.transactionStatus(),
                r.receiptNo(), r.amount() == null ? null : new BigDecimal(r.amount())));
    }

    @PostMapping("/disbursements/{disbursementId}/status-queries")
    @PreAuthorize("hasAnyRole('OPERATIONS', 'SUPERVISOR')")
    public StatusQueryView askNow(@PathVariable UUID disbursementId) {
        return queries.request(caller.tenantId(), disbursementId, caller.actor());
    }

    @GetMapping("/disbursements/{disbursementId}/status-queries")
    @PreAuthorize("hasAnyRole('LOAN_OFFICER', 'OPERATIONS', 'BRANCH_MANAGER', 'SUPERVISOR')")
    public List<StatusQueryView> queriesFor(@PathVariable UUID disbursementId) {
        return queries.forDisbursement(caller.tenantId(), disbursementId);
    }

    // ------------------------------------------------------------------ float

    @PostMapping("/balance-checks/due")
    @PreAuthorize("hasRole('INTEGRATION')")
    public List<BalanceCheckDue> checksDue(@Valid @RequestBody B2cTenantRequest r) {
        return floats.due(r.tenantId());
    }

    @PostMapping("/balance-checks/{checkId}/sent")
    @PreAuthorize("hasRole('INTEGRATION')")
    public BalanceCheckView checkSent(@PathVariable UUID checkId, @Valid @RequestBody B2cTenantRequest r) {
        return floats.sent(r.tenantId(), checkId);
    }

    @PostMapping("/balance-checks/{checkId}/send-failure")
    @PreAuthorize("hasRole('INTEGRATION')")
    public BalanceCheckView checkSendFailure(@PathVariable UUID checkId, @Valid @RequestBody B2cSendFailure r) {
        return floats.sendFailed(r.tenantId(), checkId, r.description());
    }

    @PostMapping("/balance-checks/{checkId}/no-answer")
    @PreAuthorize("hasRole('INTEGRATION')")
    public BalanceCheckView checkNoAnswer(@PathVariable UUID checkId, @Valid @RequestBody B2cTenantRequest r) {
        return floats.noAnswer(r.tenantId(), checkId);
    }

    @PostMapping("/balance-checks/{checkId}/result")
    @PreAuthorize("hasRole('INTEGRATION')")
    public BalanceCheckView checkResult(@PathVariable UUID checkId, @Valid @RequestBody BalanceCheckResultRequest r) {
        return floats.answer(r.tenantId(), checkId, r.resultCode(), r.resultDesc(), r.accountBalance());
    }

    @GetMapping("/float")
    @PreAuthorize("hasAnyRole('OPERATIONS', 'FINANCE', 'SUPERVISOR')")
    public List<FloatAccountView> floatOverview() {
        return floats.overview(caller.tenantId());
    }

    @PostMapping("/float/{shortCode}/checks")
    @PreAuthorize("hasAnyRole('OPERATIONS', 'FINANCE', 'SUPERVISOR')")
    public BalanceCheckView checkNow(@PathVariable @Pattern(regexp = "^[0-9]{5,10}$") String shortCode) {
        return floats.request(caller.tenantId(), shortCode, caller.actor());
    }

    @PutMapping(path = "/float/{shortCode}/settings", consumes = "application/json")
    @PreAuthorize("hasRole('FINANCE')")
    public FloatSettings saveSettings(@PathVariable @Pattern(regexp = "^[0-9]{5,10}$") String shortCode,
                                      @Valid @RequestBody FloatSettingsRequest r) {
        return floats.saveSettings(caller.tenantId(), shortCode, r.floatAccountName(), r.lowFloatThreshold(),
                r.dailyCheckHour(), caller.actor());
    }
}
