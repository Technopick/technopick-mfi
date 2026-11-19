package ke.co.technopick.mfi.core.accounting.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ke.co.technopick.mfi.core.accounting.application.AccountingPeriodService;
import ke.co.technopick.mfi.core.accounting.application.AccountingPeriodService.PeriodView;
import ke.co.technopick.mfi.core.accounting.application.ManualJournalService;
import ke.co.technopick.mfi.core.accounting.application.ManualJournalService.LineInput;
import ke.co.technopick.mfi.core.accounting.application.ManualJournalService.ManualJournalView;
import ke.co.technopick.mfi.core.identity.api.CurrentCaller;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual journals and accounting periods (ADR-0025). FINANCE proposes journals and opens and
 * closes periods; FINANCE or SUPERVISOR - never the proposer - posts or rejects a journal.
 */
@RestController
@RequestMapping(path = "/api/v1/accounting", produces = "application/json")
public class FinanceController {

    private static final String AMOUNT = "^\\d{1,15}(\\.\\d{1,2})?$";

    public record JournalLineRequest(@NotBlank @Size(max = 40) String accountCode, @NotNull ManualJournalService.LineSide side,
                                     @NotBlank @Pattern(regexp = AMOUNT) String amount, @NotBlank @Size(max = 16) String branchCode,
                                     @Size(max = 200) String narrative) { }

    public record ManualJournalRequest(@NotBlank @Size(max = 80) String idempotencyKey, LocalDate businessDate,
                                       @NotBlank @Size(min = 10, max = 300) String narrative,
                                       @NotNull @Size(min = 2, max = 50) List<@Valid JournalLineRequest> lines) { }

    public record ManualJournalDecision(@Size(max = 300) String reason) { }

    public record PeriodCloseRequest(@Size(max = 500) String note) { }

    private final ManualJournalService journals;
    private final AccountingPeriodService periods;
    private final CurrentCaller caller;

    public FinanceController(ManualJournalService journals, AccountingPeriodService periods, CurrentCaller caller) {
        this.journals = journals;
        this.periods = periods;
        this.caller = caller;
    }

    @PostMapping("/manual-journals")
    @PreAuthorize("hasRole('FINANCE')")
    public ManualJournalView propose(@Valid @RequestBody ManualJournalRequest r) {
        return journals.propose(caller.tenantId(), r.idempotencyKey(), r.businessDate(), r.narrative(),
                r.lines().stream().map(l -> new LineInput(l.accountCode().strip(), l.side(), new BigDecimal(l.amount()),
                        l.branchCode().strip(), l.narrative())).toList(),
                caller.actor());
    }

    @GetMapping("/manual-journals")
    @PreAuthorize("hasAnyRole('FINANCE', 'SUPERVISOR', 'OPERATIONS')")
    public List<ManualJournalView> list(@RequestParam(required = false) @Pattern(regexp = "PENDING|POSTED|REJECTED") String status) {
        return journals.list(caller.tenantId(), status);
    }

    @PostMapping("/manual-journals/{journalId}/post")
    @PreAuthorize("hasAnyRole('FINANCE', 'SUPERVISOR')")
    public ManualJournalView post(@PathVariable UUID journalId, @Valid @RequestBody(required = false) ManualJournalDecision r) {
        return journals.decide(caller.tenantId(), journalId, true, r == null ? null : r.reason(), caller.actor());
    }

    @PostMapping("/manual-journals/{journalId}/reject")
    @PreAuthorize("hasAnyRole('FINANCE', 'SUPERVISOR')")
    public ManualJournalView reject(@PathVariable UUID journalId, @Valid @RequestBody ManualJournalDecision r) {
        return journals.decide(caller.tenantId(), journalId, false, r.reason(), caller.actor());
    }

    @GetMapping("/periods")
    @PreAuthorize("hasAnyRole('FINANCE', 'SUPERVISOR', 'OPERATIONS')")
    public List<PeriodView> periods() {
        return periods.periods(caller.tenantId());
    }

    @PostMapping("/periods/next")
    @PreAuthorize("hasRole('FINANCE')")
    public PeriodView openNext() {
        return periods.openNext(caller.tenantId(), caller.actor());
    }

    @PostMapping("/periods/{periodId}/close")
    @PreAuthorize("hasRole('FINANCE')")
    public PeriodView close(@PathVariable @Pattern(regexp = "\\d{4}-\\d{2}") String periodId,
                            @Valid @RequestBody(required = false) PeriodCloseRequest r) {
        return periods.close(caller.tenantId(), periodId, r == null ? null : r.note(), caller.actor());
    }
}
