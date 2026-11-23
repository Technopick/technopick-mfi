package ke.co.technopick.mfi.core.eod.api;

import java.util.List;
import ke.co.technopick.mfi.core.eod.application.EndOfDayScheduler;
import ke.co.technopick.mfi.core.eod.application.EndOfDayScheduler.EndOfDayAlert;
import ke.co.technopick.mfi.core.eod.application.EndOfDayScheduler.EndOfDayAttempt;
import ke.co.technopick.mfi.core.eod.application.EndOfDayScheduler.EndOfDaySchedule;
import ke.co.technopick.mfi.core.eod.application.EndOfDayScheduler.EndOfDayScheduleRequest;
import ke.co.technopick.mfi.core.eod.application.EndOfDayService;
import ke.co.technopick.mfi.core.eod.application.EndOfDayService.EndOfDayStatus;
import ke.co.technopick.mfi.core.eod.application.EndOfDayService.EndOfDaySummary;
import ke.co.technopick.mfi.core.identity.api.CurrentCaller;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * End of day (ADR-0019, ADR-0029): OPERATIONS closes the business day, or the schedule does;
 * managers and supervisors see where it stands, its attempts and anything that needs a person;
 * supervisors set the schedule.
 */
@RestController
@RequestMapping(path = "/api/v1/end-of-day", produces = "application/json")
public class EndOfDayController {

    private final EndOfDayService service;
    private final EndOfDayScheduler scheduler;
    private final CurrentCaller caller;

    public EndOfDayController(EndOfDayService service, EndOfDayScheduler scheduler, CurrentCaller caller) {
        this.service = service;
        this.scheduler = scheduler;
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
        return scheduler.runManually(caller.tenantId(), caller.actor());
    }

    @GetMapping("/schedule")
    @PreAuthorize("hasAnyRole('OPERATIONS', 'BRANCH_MANAGER', 'SUPERVISOR')")
    public EndOfDaySchedule schedule() {
        return scheduler.schedule(caller.tenantId());
    }

    @PutMapping(path = "/schedule", consumes = "application/json")
    @PreAuthorize("hasRole('SUPERVISOR')")
    public EndOfDaySchedule updateSchedule(@RequestBody EndOfDayScheduleRequest request) {
        return scheduler.updateSchedule(caller.tenantId(), caller.actor(), request);
    }

    @GetMapping("/attempts")
    @PreAuthorize("hasAnyRole('OPERATIONS', 'BRANCH_MANAGER', 'SUPERVISOR')")
    public List<EndOfDayAttempt> attempts(@RequestParam(defaultValue = "20") int limit) {
        return scheduler.attempts(caller.tenantId(), limit);
    }

    @GetMapping("/alerts")
    @PreAuthorize("hasAnyRole('OPERATIONS', 'BRANCH_MANAGER', 'SUPERVISOR')")
    public List<EndOfDayAlert> alerts() {
        return scheduler.alerts(caller.tenantId());
    }
}
