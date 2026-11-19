package ke.co.technopick.mfi.core.accounting.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ke.co.technopick.mfi.core.accounting.application.LedgerReportService;
import ke.co.technopick.mfi.core.accounting.application.LedgerReportService.AccountActivity;
import ke.co.technopick.mfi.core.accounting.application.LedgerReportService.EntryView;
import ke.co.technopick.mfi.core.accounting.application.LedgerReportService.GlAccount;
import ke.co.technopick.mfi.core.accounting.application.LedgerReportService.TrialBalance;
import ke.co.technopick.mfi.core.accounting.application.ReconciliationService;
import ke.co.technopick.mfi.core.accounting.application.ReconciliationService.Reconciliation;
import ke.co.technopick.mfi.core.identity.api.CurrentCaller;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** General ledger, read-only (ADR-0023): FINANCE, SUPERVISOR and OPERATIONS. */
@RestController
@RequestMapping(path = "/api/v1/accounting", produces = "application/json")
@PreAuthorize("hasAnyRole('FINANCE', 'SUPERVISOR', 'OPERATIONS')")
public class LedgerController {

    private final LedgerReportService service;
    private final ReconciliationService reconciliation;
    private final CurrentCaller caller;

    public LedgerController(LedgerReportService service, ReconciliationService reconciliation, CurrentCaller caller) {
        this.service = service;
        this.reconciliation = reconciliation;
        this.caller = caller;
    }

    @GetMapping("/gl-accounts")
    public List<GlAccount> accounts() {
        return service.accounts(caller.tenantId());
    }

    @GetMapping("/trial-balance")
    public TrialBalance trialBalance(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(required = false) String branchCode) {
        return service.trialBalance(caller.tenantId(), asOf, branchCode);
    }

    @GetMapping("/gl-accounts/{accountCode}/activity")
    public AccountActivity activity(@PathVariable String accountCode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String branchCode) {
        return service.activity(caller.tenantId(), accountCode, from, to, branchCode);
    }

    @GetMapping("/journal-entries/{entryId}")
    public EntryView entry(@PathVariable UUID entryId) {
        return service.entry(caller.tenantId(), entryId);
    }

    /** Subledger-to-control checks: live when no date is given, otherwise as recorded by that day's end of day. */
    @GetMapping("/reconciliation")
    public Reconciliation reconciliation(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        return reconciliation.view(caller.tenantId(), businessDate);
    }
}
