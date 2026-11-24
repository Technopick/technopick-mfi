package ke.co.technopick.mfi.core.provisioning.api;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ke.co.technopick.mfi.core.identity.api.CurrentCaller;
import ke.co.technopick.mfi.core.provisioning.application.EclService;
import ke.co.technopick.mfi.core.provisioning.application.EclService.EclCalculation;
import ke.co.technopick.mfi.core.provisioning.application.EclService.EclRates;
import ke.co.technopick.mfi.core.provisioning.application.EclService.EclRunView;
import ke.co.technopick.mfi.core.provisioning.application.EclService.EclSettingsRequest;
import ke.co.technopick.mfi.core.provisioning.application.EclService.EclSettingsView;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** IFRS 9 loan loss provisioning for finance (ADR-0036). */
@RestController
@RequestMapping(path = "/api/v1/provisioning", produces = "application/json")
@PreAuthorize("hasRole('FINANCE')")
public class EclController {

    public record EclRunRequest(@NotBlank String idempotencyKey, LocalDate asOf) { }

    public record EclDecision(String reason) { }

    private final EclService ecl;
    private final CurrentCaller caller;

    public EclController(EclService ecl, CurrentCaller caller) {
        this.ecl = ecl;
        this.caller = caller;
    }

    @GetMapping("/settings")
    public EclSettingsView settings() {
        return ecl.settings(caller.tenantId());
    }

    @PutMapping("/settings")
    public EclSettingsView saveSettings(@RequestBody EclSettingsRequest r) {
        return ecl.saveSettings(caller.tenantId(), r, caller.actor());
    }

    @GetMapping("/rates")
    public EclRates rates() {
        return ecl.rates(caller.tenantId());
    }

    @PutMapping("/rates")
    public EclRates saveRates(@RequestBody EclRates r) {
        return ecl.saveRates(caller.tenantId(), r, caller.actor());
    }

    @GetMapping("/preview")
    public EclCalculation preview(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ecl.preview(caller.tenantId(), asOf);
    }

    @GetMapping("/runs")
    public List<EclRunView> runs() {
        return ecl.runs(caller.tenantId());
    }

    @PostMapping("/runs")
    public EclRunView request(@RequestBody EclRunRequest r) {
        return ecl.request(caller.tenantId(), r.idempotencyKey(), r.asOf(), caller.actor());
    }

    @GetMapping("/runs/{runId}")
    public EclRunView run(@PathVariable UUID runId) {
        return ecl.run(caller.tenantId(), runId);
    }

    @GetMapping(path = "/runs/{runId}/lines", produces = "text/csv")
    public ResponseEntity<String> lines(@PathVariable UUID runId) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ecl-" + runId + ".csv\"")
            .body(ecl.linesCsv(caller.tenantId(), runId));
    }

    @PostMapping("/runs/{runId}/approve")
    public EclRunView approve(@PathVariable UUID runId, @RequestBody(required = false) EclDecision d) {
        return ecl.decide(caller.tenantId(), runId, true, d == null ? null : d.reason(), caller.actor());
    }

    @PostMapping("/runs/{runId}/reject")
    public EclRunView reject(@PathVariable UUID runId, @RequestBody EclDecision d) {
        return ecl.decide(caller.tenantId(), runId, false, d.reason(), caller.actor());
    }
}
