package ke.co.technopick.mfi.core.ops.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Refreshes the operational figures every minute and verifies audit chains every hour (ADR-0045). */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "mfi.jobs.enabled", havingValue = "true")
public class OperationalHealthJob {

    private final OperationalHealth health;

    public OperationalHealthJob(OperationalHealth health) {
        this.health = health;
    }

    @Scheduled(fixedDelayString = "${mfi.ops.metrics-interval:PT1M}", initialDelayString = "PT20S")
    public void figures() {
        try {
            health.refresh();
        } catch (RuntimeException e) {
            // already logged and counted; the stale timestamp raises the alert
        }
    }

    @Scheduled(fixedDelayString = "${mfi.ops.audit-chain-interval:PT1H}", initialDelayString = "PT2M")
    public void auditChains() {
        try {
            health.refreshAuditChains();
        } catch (RuntimeException e) {
            // already logged and counted
        }
    }
}
