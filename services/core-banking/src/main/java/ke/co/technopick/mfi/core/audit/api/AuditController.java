package ke.co.technopick.mfi.core.audit.api;

import java.time.OffsetDateTime;
import java.util.List;
import ke.co.technopick.mfi.core.audit.application.AuditTrailService;
import ke.co.technopick.mfi.core.audit.application.AuditTrailService.ChainStatus;
import ke.co.technopick.mfi.core.audit.application.AuditTrailService.StaffAction;
import ke.co.technopick.mfi.core.identity.api.CurrentCaller;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Audit trail of staff actions (ADR-0027), for supervisors. Reading it is itself audited. */
@RestController
@RequestMapping(path = "/api/v1/audit", produces = "application/json")
@PreAuthorize("hasRole('SUPERVISOR')")
public class AuditController {

    private final AuditTrailService service;
    private final CurrentCaller caller;

    public AuditController(AuditTrailService service, CurrentCaller caller) {
        this.service = service;
        this.caller = caller;
    }

    @GetMapping("/staff-actions")
    public List<StaffAction> search(@RequestParam(required = false) String actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String pathContains,
            @RequestParam(required = false) Boolean refusedOnly) {
        return service.search(caller.tenantId(), actor, from, to, method, pathContains, refusedOnly);
    }

    @GetMapping("/staff-actions/verify")
    public ChainStatus verify() {
        return service.verify(caller.tenantId());
    }
}
