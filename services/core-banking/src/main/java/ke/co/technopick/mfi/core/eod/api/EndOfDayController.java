package ke.co.technopick.mfi.core.eod.api;

import ke.co.technopick.mfi.core.eod.application.EndOfDayService;
import ke.co.technopick.mfi.core.eod.application.EndOfDayService.EndOfDayStatus;
import ke.co.technopick.mfi.core.eod.application.EndOfDayService.EndOfDaySummary;
import ke.co.technopick.mfi.core.identity.api.CurrentCaller;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** End of day (ADR-0019): OPERATIONS closes the business day; managers can see where it stands. */
@RestController
@RequestMapping(path = "/api/v1/end-of-day", produces = "application/json")
public class EndOfDayController {

    private final EndOfDayService service;
    private final CurrentCaller caller;

    public EndOfDayController(EndOfDayService service, CurrentCaller caller) {
        this.service = service;
        this.caller = caller;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OPERATIONS', 'BRANCH_MANAGER', 'SUPERVISOR')")
    public EndOfDayStatus status() {
        return service.status(caller.tenantId());
    }

    @PostMapping("/run")
    @PreAuthorize("hasRole('OPERATIONS')")
    public EndOfDaySummary run() {
        return service.run(caller.tenantId(), caller.actor());
    }
}
