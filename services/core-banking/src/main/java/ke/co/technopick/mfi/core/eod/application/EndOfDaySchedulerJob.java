package ke.co.technopick.mfi.core.eod.application;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs the end-of-day scheduler every minute (ADR-0029); every node may run it, the run lock keeps one run per tenant. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "mfi.jobs.enabled", havingValue = "true")
public class EndOfDaySchedulerJob {

    private final EndOfDayScheduler scheduler;
    private final Clock clock;

    public EndOfDaySchedulerJob(EndOfDayScheduler scheduler, Clock clock) {
        this.scheduler = scheduler;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${mfi.jobs.eod-scheduler-interval:PT1M}", initialDelayString = "PT30S")
    public void run() {
        scheduler.tick(clock.instant());
    }
}
