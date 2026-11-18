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

/**
 * Runs STK and B2C housekeeping for every active tenant (moves to the worker with other jobs).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "mfi.jobs.enabled", havingValue = "true")
public class StkHousekeepingJob {

    private static final Logger LOG = LoggerFactory.getLogger(StkHousekeepingJob.class);

    private final MpesaStkService service;
    private final B2cDisbursementService b2c;
    private final Duration pendingTimeout;
    private final Duration unknownAfter;

    public StkHousekeepingJob(MpesaStkService service, B2cDisbursementService b2c,
                              @Value("${mfi.payments.stk.pending-timeout:PT10M}") Duration pendingTimeout,
                              @Value("${mfi.payments.stk.unknown-after:PT10M}") Duration unknownAfter) {
        this.service = service;
        this.b2c = b2c;
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
                int flagged = b2c.housekeep(tenant, unknownAfter);
                if (flagged > 0) {
                    LOG.warn("tenant {}: {} B2C disbursements with unknown send outcome flagged", tenant, flagged);
                }
            } catch (RuntimeException e) {
                LOG.error("M-Pesa housekeeping failed for tenant {}", tenant, e);
            }
        }
    }
}
