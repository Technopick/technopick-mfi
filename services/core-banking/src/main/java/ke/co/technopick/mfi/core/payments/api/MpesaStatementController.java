package ke.co.technopick.mfi.core.payments.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ke.co.technopick.mfi.core.identity.api.CurrentCaller;
import ke.co.technopick.mfi.core.payments.application.MpesaStatementService;
import ke.co.technopick.mfi.core.payments.application.MpesaStatementService.StatementAccount;
import ke.co.technopick.mfi.core.payments.application.MpesaStatementService.StatementDetail;
import ke.co.technopick.mfi.core.payments.application.MpesaStatementService.StatementLineView;
import ke.co.technopick.mfi.core.payments.application.MpesaStatementService.StatementRematch;
import ke.co.technopick.mfi.core.payments.application.MpesaStatementService.StatementUploadView;
import ke.co.technopick.mfi.core.payments.domain.MpesaStatement.AccountKind;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * M-Pesa statement reconciliation (ADR-0038). FINANCE uploads statements and resolves exceptions;
 * FINANCE or SUPERVISOR - never the uploader or anyone who resolved a line - signs the day off.
 */
@RestController
@RequestMapping(path = "/api/v1/payments/mpesa/statements", produces = "application/json")
public class MpesaStatementController {

    /** The statement CSV as text; up to about 8 MB (a busy paybill day is well under 2 MB). */
    public record StatementUploadRequest(@NotNull AccountKind accountKind, @NotBlank @Pattern(regexp = "^[0-9]{5,10}$") String shortCode,
                                         @NotNull LocalDate statementDate, @NotBlank @Size(max = 200) String fileName,
                                         @NotBlank @Size(max = 8_000_000) String csv) { }

    public record StatementExplainRequest(@NotEmpty @Size(max = 500) List<@NotNull @Min(1) Integer> lineNos,
                                          @NotBlank @Size(min = 10, max = 300) String note) { }

    public record StatementLinkRequest(@NotEmpty @Size(max = 500) List<@NotNull @Min(1) Integer> lineNos,
                                       @NotNull UUID journalEntryId, @NotBlank @Size(min = 10, max = 300) String note) { }

    public record StatementSignOffRequest(@Size(max = 500) String note) { }

    public record StatementWithdrawRequest(@NotBlank @Size(min = 10, max = 500) String reason) { }

    private final MpesaStatementService statements;
    private final CurrentCaller caller;

    public MpesaStatementController(MpesaStatementService statements, CurrentCaller caller) {
        this.statements = statements;
        this.caller = caller;
    }

    @GetMapping("/accounts")
    @PreAuthorize("hasAnyRole('FINANCE', 'SUPERVISOR')")
    public List<StatementAccount> accounts() {
        return statements.accounts(caller.tenantId());
    }

    @PostMapping(consumes = "application/json")
    @PreAuthorize("hasRole('FINANCE')")
    public StatementDetail upload(@Valid @RequestBody StatementUploadRequest r) {
        return statements.upload(caller.tenantId(), r.accountKind(), r.shortCode(), r.statementDate(), r.fileName().strip(),
                r.csv(), caller.actor());
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('FINANCE', 'SUPERVISOR')")
    public List<StatementUploadView> list(@RequestParam(required = false) @Pattern(regexp = "OPEN|SIGNED_OFF|WITHDRAWN") String status) {
        return statements.list(caller.tenantId(), status);
    }

    @GetMapping("/{uploadId}")
    @PreAuthorize("hasAnyRole('FINANCE', 'SUPERVISOR')")
    public StatementDetail get(@PathVariable UUID uploadId) {
        return statements.get(caller.tenantId(), uploadId);
    }

    @GetMapping("/{uploadId}/lines")
    @PreAuthorize("hasAnyRole('FINANCE', 'SUPERVISOR')")
    public List<StatementLineView> lines(@PathVariable UUID uploadId,
                                         @RequestParam(required = false) @Pattern(regexp = "MATCHED|EXCEPTION|RESOLVED|IGNORED") String status,
                                         @RequestParam(defaultValue = "500") @Min(1) @Max(5000) int limit) {
        return statements.lines(caller.tenantId(), uploadId, status, limit);
    }

    @PostMapping("/{uploadId}/rematch")
    @PreAuthorize("hasRole('FINANCE')")
    public StatementRematch rematch(@PathVariable UUID uploadId) {
        return statements.rematch(caller.tenantId(), uploadId);
    }

    @PostMapping(path = "/{uploadId}/explain", consumes = "application/json")
    @PreAuthorize("hasRole('FINANCE')")
    public StatementDetail explain(@PathVariable UUID uploadId, @Valid @RequestBody StatementExplainRequest r) {
        return statements.explain(caller.tenantId(), uploadId, r.lineNos(), r.note(), caller.actor());
    }

    @PostMapping(path = "/{uploadId}/link", consumes = "application/json")
    @PreAuthorize("hasRole('FINANCE')")
    public StatementDetail link(@PathVariable UUID uploadId, @Valid @RequestBody StatementLinkRequest r) {
        return statements.link(caller.tenantId(), uploadId, r.lineNos(), r.journalEntryId(), r.note(), caller.actor());
    }

    @PostMapping(path = "/{uploadId}/sign-off", consumes = "application/json")
    @PreAuthorize("hasAnyRole('FINANCE', 'SUPERVISOR')")
    public StatementDetail signOff(@PathVariable UUID uploadId, @Valid @RequestBody(required = false) StatementSignOffRequest r) {
        return statements.signOff(caller.tenantId(), uploadId, r == null ? null : r.note(), caller.actor());
    }

    @PostMapping(path = "/{uploadId}/withdraw", consumes = "application/json")
    @PreAuthorize("hasRole('FINANCE')")
    public StatementDetail withdraw(@PathVariable UUID uploadId, @Valid @RequestBody StatementWithdrawRequest r) {
        return statements.withdraw(caller.tenantId(), uploadId, r.reason(), caller.actor());
    }
}
