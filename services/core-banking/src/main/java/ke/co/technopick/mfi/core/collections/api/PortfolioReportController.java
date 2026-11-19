package ke.co.technopick.mfi.core.collections.api;

import java.time.LocalDate;
import ke.co.technopick.mfi.core.collections.application.PortfolioReportService;
import ke.co.technopick.mfi.core.collections.application.PortfolioReportService.PortfolioReport;
import ke.co.technopick.mfi.core.identity.api.CurrentCaller;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Portfolio at risk (ADR-0021), from end-of-day arrears snapshots. */
@RestController
@RequestMapping(path = "/api/v1/reports", produces = "application/json")
public class PortfolioReportController {

    private final PortfolioReportService service;
    private final CurrentCaller caller;

    public PortfolioReportController(PortfolioReportService service, CurrentCaller caller) {
        this.service = service;
        this.caller = caller;
    }

    @GetMapping("/portfolio-at-risk")
    @PreAuthorize("hasAnyRole('BRANCH_MANAGER', 'SUPERVISOR', 'OPERATIONS', 'LOAN_OFFICER')")
    public PortfolioReport portfolioAtRisk(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(required = false) String branchCode) {
        return service.report(caller.tenantId(), asOf, branchCode);
    }
}
