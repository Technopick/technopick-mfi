package ke.co.technopick.mfi.core.regulatory.api;

import java.time.LocalDate;
import java.util.List;
import ke.co.technopick.mfi.core.identity.api.CurrentCaller;
import ke.co.technopick.mfi.core.regulatory.application.PrudentialReturnService;
import ke.co.technopick.mfi.core.regulatory.application.PrudentialReturnService.ClassifiedLoan;
import ke.co.technopick.mfi.core.regulatory.application.PrudentialReturnService.PrudentialReturn;
import ke.co.technopick.mfi.core.regulatory.application.PrudentialReturnService.PrudentialSettingsView;
import ke.co.technopick.mfi.core.regulatory.application.PrudentialSettingsService;
import ke.co.technopick.mfi.core.regulatory.application.PrudentialSettingsService.PrudentialSettingsRequest;
import ke.co.technopick.mfi.core.regulatory.application.PrudentialSettingsService.RegulatoryMapping;
import ke.co.technopick.mfi.core.regulatory.application.PrudentialSettingsService.RegulatoryMappingRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** SASRA prudential returns (ADR-0030): finance prepares them; supervisors may read them. */
@RestController
@RequestMapping(path = "/api/v1/regulatory", produces = "application/json")
public class RegulatoryController {

    private final PrudentialReturnService returns;
    private final PrudentialSettingsService settings;
    private final CurrentCaller caller;

    public RegulatoryController(PrudentialReturnService returns, PrudentialSettingsService settings, CurrentCaller caller) {
        this.returns = returns;
        this.settings = settings;
        this.caller = caller;
    }

    @GetMapping("/prudential")
    @PreAuthorize("hasAnyRole('FINANCE', 'SUPERVISOR')")
    public PrudentialReturn prudential(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return returns.prudentialReturn(caller.tenantId(), asOf);
    }

    @GetMapping("/prudential/loans")
    @PreAuthorize("hasAnyRole('FINANCE', 'SUPERVISOR')")
    public List<ClassifiedLoan> classifiedLoans(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
                                                @RequestParam(required = false) String loanClass) {
        return returns.classifiedLoans(caller.tenantId(), asOf, loanClass);
    }

    @GetMapping("/settings")
    @PreAuthorize("hasAnyRole('FINANCE', 'SUPERVISOR')")
    public PrudentialSettingsView settings() {
        return settings.settings(caller.tenantId());
    }

    @PutMapping(path = "/settings", consumes = "application/json")
    @PreAuthorize("hasRole('FINANCE')")
    public PrudentialSettingsView updateSettings(@RequestBody PrudentialSettingsRequest request) {
        return settings.updateSettings(caller.tenantId(), caller.actor(), request);
    }

    @GetMapping("/mapping")
    @PreAuthorize("hasAnyRole('FINANCE', 'SUPERVISOR')")
    public RegulatoryMapping mapping() {
        return settings.mapping(caller.tenantId());
    }

    @PutMapping(path = "/mapping", consumes = "application/json")
    @PreAuthorize("hasRole('FINANCE')")
    public RegulatoryMapping updateMapping(@RequestBody RegulatoryMappingRequest request) {
        return settings.updateMapping(caller.tenantId(), caller.actor(), request);
    }
}
