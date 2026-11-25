package ke.co.technopick.mfi.core.shares.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ke.co.technopick.mfi.core.common.NotFoundException;
import ke.co.technopick.mfi.core.identity.api.CurrentCaller;
import ke.co.technopick.mfi.core.shares.application.ShareService;
import ke.co.technopick.mfi.core.shares.application.ShareService.DividendLineView;
import ke.co.technopick.mfi.core.shares.application.ShareService.DividendPreview;
import ke.co.technopick.mfi.core.shares.application.ShareService.DividendRunView;
import ke.co.technopick.mfi.core.shares.application.ShareService.MemberShares;
import ke.co.technopick.mfi.core.shares.application.ShareService.ShareRegister;
import ke.co.technopick.mfi.core.shares.application.ShareService.ShareSettingsView;
import ke.co.technopick.mfi.core.shares.application.ShareService.ShareTransferView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Member share capital (ADR-0040). */
@RestController
@RequestMapping(path = "/api/v1/shares", produces = "application/json")
public class ShareController {

    static final String MEMBER = "^CU[0-9]{2}-[0-9]{7}$";

    public record ShareSettingsRequest(@NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal parValue,
                                       @NotNull @DecimalMin("0") @Digits(integer = 13, fraction = 2) BigDecimal minimumHolding,
                                       @NotBlank @Size(max = 40) String shareCapitalAccount,
                                       @NotBlank @Size(max = 40) String dividendSourceAccount,
                                       @NotBlank @Size(max = 40) String dividendsPayableAccount,
                                       @NotBlank @Size(max = 40) String dividendTaxAccount,
                                       @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal dividendTaxRate) { }

    public record SharePurchaseRequest(@NotBlank @Size(max = 64) String idempotencyKey,
                                       @NotBlank @Pattern(regexp = MEMBER) String customerNumber,
                                       @NotBlank @Pattern(regexp = "^[0-9]{10}$") String accountNumber,
                                       @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount) { }

    public record ShareTransferRequest(@NotBlank @Size(max = 64) String idempotencyKey,
                                       @NotBlank @Pattern(regexp = MEMBER) String fromCustomerNumber,
                                       @NotBlank @Pattern(regexp = MEMBER) String toCustomerNumber,
                                       @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount,
                                       @NotBlank @Size(min = 10, max = 300) String reason) { }

    public record ShareDecisionRequest(@Size(max = 300) String reason) { }

    public record DividendPreviewRequest(@NotNull @Min(2000) @Max(2100) Integer financialYear, @NotNull LocalDate recordDate,
                                         @NotNull @DecimalMin("0.0001") @DecimalMax("100") @Digits(integer = 3, fraction = 4) BigDecimal ratePct) { }

    public record DividendRunRequest(@NotBlank @Size(max = 64) String idempotencyKey,
                                     @NotNull @Min(2000) @Max(2100) Integer financialYear, @NotNull LocalDate recordDate,
                                     @NotNull @DecimalMin("0.0001") @DecimalMax("100") @Digits(integer = 3, fraction = 4) BigDecimal ratePct) { }

    private final ShareService shares;
    private final CurrentCaller caller;

    public ShareController(ShareService shares, CurrentCaller caller) {
        this.shares = shares;
        this.caller = caller;
    }

    @GetMapping("/settings")
    @PreAuthorize("hasAnyRole('FINANCE', 'SUPERVISOR', 'BRANCH_MANAGER', 'TELLER', 'LOAN_OFFICER', 'OPERATIONS')")
    public ShareSettingsView settings() {
        return shares.settings(caller.tenantId()).orElseThrow(() -> new NotFoundException("share capital is not set up"));
    }

    @PutMapping(path = "/settings", consumes = "application/json")
    @PreAuthorize("hasRole('FINANCE')")
    public ShareSettingsView saveSettings(@Valid @RequestBody ShareSettingsRequest r) {
        return shares.saveSettings(caller.tenantId(), r.parValue(), r.minimumHolding(), r.shareCapitalAccount().strip(),
                r.dividendSourceAccount().strip(), r.dividendsPayableAccount().strip(), r.dividendTaxAccount().strip(),
                r.dividendTaxRate(), caller.actor());
    }

    @GetMapping("/members/{customerNumber}")
    @PreAuthorize("hasAnyRole('TELLER', 'LOAN_OFFICER', 'BRANCH_MANAGER', 'OPERATIONS', 'SUPERVISOR', 'FINANCE')")
    public MemberShares member(@PathVariable @Pattern(regexp = MEMBER) String customerNumber) {
        return shares.member(caller.tenantId(), customerNumber);
    }

    @GetMapping("/register")
    @PreAuthorize("hasAnyRole('FINANCE', 'SUPERVISOR', 'BRANCH_MANAGER')")
    public ShareRegister register() {
        return shares.register(caller.tenantId());
    }

    @PostMapping(path = "/purchases", consumes = "application/json")
    @PreAuthorize("hasAnyRole('TELLER', 'LOAN_OFFICER', 'BRANCH_MANAGER')")
    public MemberShares purchase(@Valid @RequestBody SharePurchaseRequest r) {
        return shares.purchase(caller.tenantId(), r.idempotencyKey(), r.customerNumber(), r.accountNumber(), r.amount(),
                caller.actor());
    }

    @PostMapping(path = "/transfers", consumes = "application/json")
    @PreAuthorize("hasAnyRole('LOAN_OFFICER', 'BRANCH_MANAGER', 'OPERATIONS')")
    public ShareTransferView requestTransfer(@Valid @RequestBody ShareTransferRequest r) {
        return shares.requestTransfer(caller.tenantId(), r.idempotencyKey(), r.fromCustomerNumber(), r.toCustomerNumber(),
                r.amount(), r.reason(), caller.actor());
    }

    @GetMapping("/transfers")
    @PreAuthorize("hasAnyRole('LOAN_OFFICER', 'BRANCH_MANAGER', 'OPERATIONS', 'SUPERVISOR')")
    public List<ShareTransferView> transfers(@RequestParam(required = false) @Pattern(regexp = "REQUESTED|APPROVED|REJECTED") String status) {
        return shares.transfers(caller.tenantId(), status);
    }

    @PostMapping("/transfers/{transferId}/approve")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER', 'SUPERVISOR')")
    public ShareTransferView approveTransfer(@PathVariable UUID transferId, @Valid @RequestBody(required = false) ShareDecisionRequest r) {
        return shares.decideTransfer(caller.tenantId(), transferId, true, r == null ? null : r.reason(), caller.actor());
    }

    @PostMapping("/transfers/{transferId}/reject")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER', 'SUPERVISOR')")
    public ShareTransferView rejectTransfer(@PathVariable UUID transferId, @Valid @RequestBody ShareDecisionRequest r) {
        return shares.decideTransfer(caller.tenantId(), transferId, false, r.reason(), caller.actor());
    }

    @PostMapping(path = "/dividends/preview", consumes = "application/json")
    @PreAuthorize("hasRole('FINANCE')")
    public DividendPreview previewDividend(@Valid @RequestBody DividendPreviewRequest r) {
        return shares.previewDividend(caller.tenantId(), r.financialYear(), r.recordDate(), r.ratePct());
    }

    @PostMapping(path = "/dividends", consumes = "application/json")
    @PreAuthorize("hasRole('FINANCE')")
    public DividendRunView requestDividend(@Valid @RequestBody DividendRunRequest r) {
        return shares.requestDividend(caller.tenantId(), r.idempotencyKey(), r.financialYear(), r.recordDate(), r.ratePct(),
                caller.actor());
    }

    @GetMapping("/dividends")
    @PreAuthorize("hasAnyRole('FINANCE', 'SUPERVISOR')")
    public List<DividendRunView> dividendRuns() {
        return shares.dividendRuns(caller.tenantId());
    }

    @GetMapping("/dividends/{runId}/lines")
    @PreAuthorize("hasAnyRole('FINANCE', 'SUPERVISOR')")
    public List<DividendLineView> dividendLines(@PathVariable UUID runId) {
        return shares.dividendLines(caller.tenantId(), runId);
    }

    @PostMapping("/dividends/{runId}/approve")
    @PreAuthorize("hasAnyRole('FINANCE', 'SUPERVISOR')")
    public DividendRunView approveDividend(@PathVariable UUID runId, @Valid @RequestBody(required = false) ShareDecisionRequest r) {
        return shares.decideDividend(caller.tenantId(), runId, true, r == null ? null : r.reason(), caller.actor());
    }

    @PostMapping("/dividends/{runId}/reject")
    @PreAuthorize("hasAnyRole('FINANCE', 'SUPERVISOR')")
    public DividendRunView rejectDividend(@PathVariable UUID runId, @Valid @RequestBody ShareDecisionRequest r) {
        return shares.decideDividend(caller.tenantId(), runId, false, r.reason(), caller.actor());
    }
}
