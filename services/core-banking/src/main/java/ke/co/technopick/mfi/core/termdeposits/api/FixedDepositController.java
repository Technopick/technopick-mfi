package ke.co.technopick.mfi.core.termdeposits.api;

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
import java.util.List;
import java.util.UUID;
import ke.co.technopick.mfi.core.identity.api.CurrentCaller;
import ke.co.technopick.mfi.core.termdeposits.application.FixedDepositService;
import ke.co.technopick.mfi.core.termdeposits.application.FixedDepositService.EarlyWithdrawalView;
import ke.co.technopick.mfi.core.termdeposits.application.FixedDepositService.FixedDepositDetail;
import ke.co.technopick.mfi.core.termdeposits.application.FixedDepositService.FixedDepositPortfolio;
import ke.co.technopick.mfi.core.termdeposits.application.FixedDepositService.FixedDepositProductView;
import ke.co.technopick.mfi.core.termdeposits.application.FixedDepositService.MemberFixedDeposits;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Fixed (term) deposits (ADR-0041). */
@RestController
@RequestMapping(path = "/api/v1/fixed-deposits", produces = "application/json")
public class FixedDepositController {

    static final String INSTRUCTION = "PAYOUT|ROLLOVER_PRINCIPAL|ROLLOVER_ALL";
    static final String STAFF = "hasAnyRole('TELLER', 'LOAN_OFFICER', 'BRANCH_MANAGER', 'OPERATIONS', 'SUPERVISOR', 'FINANCE')";

    public record FixedDepositProductRequest(@NotBlank @Size(min = 3, max = 120) String name,
                                             @NotNull @Min(1) @Max(120) Integer termMonths,
                                             @NotNull @DecimalMin("0.0001") @DecimalMax("50") @Digits(integer = 3, fraction = 4) BigDecimal ratePct,
                                             @NotNull @DecimalMin("0") @DecimalMax("50") @Digits(integer = 3, fraction = 4) BigDecimal earlyRatePct,
                                             @NotNull Boolean earlyWithdrawalAllowed,
                                             @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal minAmount,
                                             @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal maxAmount,
                                             @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal taxRatePct,
                                             @NotBlank @Size(max = 40) String principalAccount,
                                             @NotBlank @Size(max = 40) String interestPayableAccount,
                                             @NotBlank @Size(max = 40) String interestExpenseAccount,
                                             @NotBlank @Size(max = 40) String taxAccount,
                                             @NotBlank @Pattern(regexp = "ACTIVE|RETIRED") String status) { }

    public record FixedDepositPlacementRequest(@NotBlank @Size(max = 64) String idempotencyKey,
                                               @NotBlank @Pattern(regexp = "^CU[0-9]{2}-[0-9]{7}$") String customerNumber,
                                               @NotBlank @Pattern(regexp = "^[0-9]{10}$") String accountNumber,
                                               @NotBlank @Size(max = 32) String productCode,
                                               @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount,
                                               @NotBlank @Pattern(regexp = INSTRUCTION) String maturityInstruction) { }

    public record FixedDepositInstructionRequest(@NotBlank @Pattern(regexp = INSTRUCTION) String maturityInstruction) { }

    public record EarlyWithdrawalRequest(@NotBlank @Size(max = 64) String idempotencyKey,
                                         @NotBlank @Size(min = 10, max = 300) String reason) { }

    public record EarlyWithdrawalDecision(@Size(max = 300) String reason) { }

    private final FixedDepositService deposits;
    private final CurrentCaller caller;

    public FixedDepositController(FixedDepositService deposits, CurrentCaller caller) {
        this.deposits = deposits;
        this.caller = caller;
    }

    @GetMapping("/products")
    @PreAuthorize(STAFF)
    public List<FixedDepositProductView> products() {
        return deposits.products(caller.tenantId());
    }

    @PutMapping(path = "/products/{productCode}", consumes = "application/json")
    @PreAuthorize("hasRole('FINANCE')")
    public FixedDepositProductView saveProduct(@PathVariable @Pattern(regexp = "^[A-Z0-9][A-Z0-9-]{1,31}$") String productCode,
                                               @Valid @RequestBody FixedDepositProductRequest r) {
        if (!productCode.matches("^[A-Z0-9][A-Z0-9-]{1,31}$")) {
            throw new IllegalArgumentException("product codes are 2 to 32 capital letters, digits and dashes");
        }
        return deposits.saveProduct(caller.tenantId(), productCode, r.name().strip(), r.termMonths(), r.ratePct(), r.earlyRatePct(),
                r.earlyWithdrawalAllowed(), r.minAmount(), r.maxAmount(), r.taxRatePct(), r.principalAccount().strip(),
                r.interestPayableAccount().strip(), r.interestExpenseAccount().strip(), r.taxAccount().strip(), r.status(),
                caller.actor());
    }

    @GetMapping("/members/{customerNumber}")
    @PreAuthorize(STAFF)
    public MemberFixedDeposits member(@PathVariable String customerNumber) {
        return deposits.member(caller.tenantId(), customerNumber);
    }

    @GetMapping("/deposits/{depositNumber}")
    @PreAuthorize(STAFF)
    public FixedDepositDetail deposit(@PathVariable String depositNumber) {
        return deposits.detail(caller.tenantId(), depositNumber);
    }

    @PostMapping(path = "/deposits", consumes = "application/json")
    @PreAuthorize("hasAnyRole('TELLER', 'LOAN_OFFICER', 'BRANCH_MANAGER')")
    public FixedDepositDetail place(@Valid @RequestBody FixedDepositPlacementRequest r) {
        return deposits.place(caller.tenantId(), r.idempotencyKey(), r.customerNumber(), r.accountNumber(), r.productCode(),
                r.amount(), r.maturityInstruction(), caller.actor());
    }

    @PutMapping(path = "/deposits/{depositNumber}/instruction", consumes = "application/json")
    @PreAuthorize("hasAnyRole('TELLER', 'LOAN_OFFICER', 'BRANCH_MANAGER')")
    public FixedDepositDetail changeInstruction(@PathVariable String depositNumber, @Valid @RequestBody FixedDepositInstructionRequest r) {
        return deposits.changeInstruction(caller.tenantId(), depositNumber, r.maturityInstruction(), caller.actor());
    }

    @PostMapping(path = "/deposits/{depositNumber}/early-withdrawals", consumes = "application/json")
    @PreAuthorize("hasAnyRole('TELLER', 'LOAN_OFFICER', 'BRANCH_MANAGER', 'OPERATIONS')")
    public EarlyWithdrawalView requestEarly(@PathVariable String depositNumber, @Valid @RequestBody EarlyWithdrawalRequest r) {
        return deposits.requestEarly(caller.tenantId(), r.idempotencyKey(), depositNumber, r.reason(), caller.actor());
    }

    @GetMapping("/early-withdrawals")
    @PreAuthorize("hasAnyRole('TELLER', 'LOAN_OFFICER', 'BRANCH_MANAGER', 'OPERATIONS', 'SUPERVISOR')")
    public List<EarlyWithdrawalView> earlyWithdrawals(@RequestParam(required = false) @Pattern(regexp = "REQUESTED|APPROVED|REJECTED") String status) {
        if (status != null && !status.matches("REQUESTED|APPROVED|REJECTED")) {
            throw new IllegalArgumentException("status is REQUESTED, APPROVED or REJECTED");
        }
        return deposits.earlyRequests(caller.tenantId(), status);
    }

    @PostMapping("/early-withdrawals/{requestId}/approve")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER', 'SUPERVISOR')")
    public EarlyWithdrawalView approve(@PathVariable UUID requestId, @Valid @RequestBody(required = false) EarlyWithdrawalDecision r) {
        return deposits.decideEarly(caller.tenantId(), requestId, true, r == null ? null : r.reason(), caller.actor());
    }

    @PostMapping("/early-withdrawals/{requestId}/reject")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER', 'SUPERVISOR')")
    public EarlyWithdrawalView reject(@PathVariable UUID requestId, @Valid @RequestBody EarlyWithdrawalDecision r) {
        return deposits.decideEarly(caller.tenantId(), requestId, false, r.reason(), caller.actor());
    }

    @GetMapping("/portfolio")
    @PreAuthorize("hasAnyRole('FINANCE', 'SUPERVISOR', 'BRANCH_MANAGER', 'OPERATIONS')")
    public FixedDepositPortfolio portfolio(@RequestParam(defaultValue = "30") int days) {
        if (days < 0 || days > 366) {
            throw new IllegalArgumentException("days is 0 to 366");
        }
        return deposits.portfolio(caller.tenantId(), days);
    }
}
