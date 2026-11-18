package ke.co.technopick.mfi.core.savings.application;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Schedules {@link HoldExpiryService} for every active tenant. Runs in core banking for now;
 * moves to the worker deployable with the rest of the batch jobs (ADR-0001).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "mfi.jobs.enabled", havingValue = "true")
public class HoldExpiryJob {

    private static final Logger LOG = LoggerFactory.getLogger(HoldExpiryJob.class);

    private final HoldExpiryService service;

    public HoldExpiryJob(HoldExpiryService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${mfi.jobs.hold-expiry-interval}")
    public void run() {
        for (UUID tenant : service.activeTenants()) {
            try {
                // Drain in batches; each batch is its own short transaction.
                while (service.expireDue(tenant) == HoldExpiryService.BATCH) {
                    Thread.onSpinWait();
                }
            } catch (RuntimeException e) {
                // One tenant's failure must not stop the others; alerting is on the error log.
                LOG.error("hold expiry failed for tenant {}", tenant, e);
            }
        }
    }
}
