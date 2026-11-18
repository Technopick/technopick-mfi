package ke.co.technopick.mfi.core.payments.application;

import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs {@link MpesaStkService#housekeep} for every active tenant (moves to the worker with other jobs). */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "mfi.jobs.enabled", havingValue = "true")
public class StkHousekeepingJob {

    private static final Logger LOG = LoggerFactory.getLogger(StkHousekeepingJob.class);

    private final MpesaStkService service;
    private final Duration pendingTimeout;
    private final Duration unknownAfter;

    public StkHousekeepingJob(MpesaStkService service,
                              @Value("${mfi.payments.stk.pending-timeout:PT10M}") Duration pendingTimeout,
                              @Value("${mfi.payments.stk.unknown-after:PT10M}") Duration unknownAfter) {
        this.service = service;
        this.pendingTimeout = pendingTimeout;
        this.unknownAfter = unknownAfter;
    }

    @Scheduled(fixedDelayString = "${mfi.jobs.stk-housekeeping-interval:PT1M}")
    public void run() {
        for (UUID tenant : service.activeTenants()) {
            try {
                int[] n = service.housekeep(tenant, pendingTimeout, unknownAfter);
                if (n[0] + n[1] > 0) {
                    LOG.info("tenant {}: {} STK requests expired, {} flagged for operations", tenant, n[0], n[1]);
                }
            } catch (RuntimeException e) {
                LOG.error("STK housekeeping failed for tenant {}", tenant, e);
            }
        }
    }
}
