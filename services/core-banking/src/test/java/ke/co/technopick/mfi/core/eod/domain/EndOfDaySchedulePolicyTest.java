package ke.co.technopick.mfi.core.eod.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import ke.co.technopick.mfi.core.eod.domain.EndOfDaySchedulePolicy.Action;
import ke.co.technopick.mfi.core.eod.domain.EndOfDaySchedulePolicy.Alert;
import ke.co.technopick.mfi.core.eod.domain.EndOfDaySchedulePolicy.Facts;
import ke.co.technopick.mfi.core.eod.domain.EndOfDaySchedulePolicy.Schedule;
import ke.co.technopick.mfi.core.eod.domain.EndOfDaySchedulePolicy.Severity;
import org.junit.jupiter.api.Test;

class EndOfDaySchedulePolicyTest {

    static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    static final LocalDate D = LocalDate.of(2026, 11, 28);
    static final Schedule ON = new Schedule(true, LocalTime.of(21, 0), 60, 3, 15, 2);

    static Instant at(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(NAIROBI).toInstant();
    }

    static Facts idle() {
        return new Facts(D, null, false, null, null, null, null, 0);
    }

    static List<String> codes(List<Alert> alerts) {
        return alerts.stream().map(Alert::code).toList();
    }

    @Test
    void cutOffIsLocalTimeInTheTenantsZone() {
        assertEquals(Instant.parse("2026-11-28T18:00:00Z"), EndOfDaySchedulePolicy.cutOff(D, LocalTime.of(21, 0), NAIROBI));
    }

    @Test
    void datesDueCountsEveryMissedCutOff() {
        LocalTime nine = LocalTime.of(21, 0);
        assertEquals(0, EndOfDaySchedulePolicy.datesDue(D, nine, NAIROBI, at("2026-11-28T20:59")));
        assertEquals(1, EndOfDaySchedulePolicy.datesDue(D, nine, NAIROBI, at("2026-11-28T21:00")));
        assertEquals(1, EndOfDaySchedulePolicy.datesDue(D, nine, NAIROBI, at("2026-11-29T08:00")));
        assertEquals(2, EndOfDaySchedulePolicy.datesDue(D, nine, NAIROBI, at("2026-11-29T21:00")));
        assertEquals(4, EndOfDaySchedulePolicy.datesDue(D, nine, NAIROBI, at("2026-12-01T22:00")));
        // A cut-off before noon is in the early hours after the business date.
        LocalTime oneAm = LocalTime.of(1, 0);
        assertEquals(Instant.parse("2026-11-28T22:00:00Z"), EndOfDaySchedulePolicy.cutOff(D, oneAm, NAIROBI));
        assertEquals(0, EndOfDaySchedulePolicy.datesDue(D, oneAm, NAIROBI, at("2026-11-28T01:00")));
        assertEquals(0, EndOfDaySchedulePolicy.datesDue(D, oneAm, NAIROBI, at("2026-11-28T23:59")));
        assertEquals(1, EndOfDaySchedulePolicy.datesDue(D, oneAm, NAIROBI, at("2026-11-29T01:00")));
        assertEquals(1, EndOfDaySchedulePolicy.datesDue(D, oneAm, NAIROBI, at("2026-11-29T23:00")));
        assertEquals(2, EndOfDaySchedulePolicy.datesDue(D, oneAm, NAIROBI, at("2026-11-30T01:00")));
    }

    @Test
    void decidesWhenToRun() {
        assertEquals(Action.NOT_ENABLED, EndOfDaySchedulePolicy.decide(Schedule.defaults(), NAIROBI, at("2026-11-29T08:00"), idle()));
        assertEquals(Action.NOT_DUE, EndOfDaySchedulePolicy.decide(ON, NAIROBI, at("2026-11-28T20:59"), idle()));
        assertEquals(Action.RUN, EndOfDaySchedulePolicy.decide(ON, NAIROBI, at("2026-11-28T21:00"), idle()));
        Facts running = new Facts(D, "RUNNING", true, at("2026-11-28T21:00"), null, null, null, 0);
        assertEquals(Action.IN_PROGRESS, EndOfDaySchedulePolicy.decide(ON, NAIROBI, at("2026-11-28T21:05"), running));
        // Two nights late is within a catch-up of 2 days; three is not.
        assertEquals(Action.RUN, EndOfDaySchedulePolicy.decide(ON, NAIROBI, at("2026-11-30T21:00"), idle()));
        assertEquals(Action.TOO_FAR_BEHIND, EndOfDaySchedulePolicy.decide(ON, NAIROBI, at("2026-12-01T21:00"), idle()));
    }

    @Test
    void retriesAfterTheIntervalAndGivesUpAfterMaxAttempts() {
        Facts failedOnce = new Facts(D, "FAILED", false, null, "FAILED", "boom", at("2026-11-28T21:02"), 1);
        assertEquals(Action.WAIT_RETRY, EndOfDaySchedulePolicy.decide(ON, NAIROBI, at("2026-11-28T21:16"), failedOnce));
        assertEquals(Action.RUN, EndOfDaySchedulePolicy.decide(ON, NAIROBI, at("2026-11-28T21:17"), failedOnce));
        Facts failedThrice = new Facts(D, "FAILED", false, null, "FAILED", "boom", at("2026-11-28T21:40"), 3);
        assertEquals(Action.GIVE_UP, EndOfDaySchedulePolicy.decide(ON, NAIROBI, at("2026-11-29T06:00"), failedThrice));
        // Blocked attempts are not failures: they retry at the interval without a limit.
        Facts blocked = new Facts(D, null, false, null, "BLOCKED", "tills open", at("2026-11-28T23:00"), 0);
        assertEquals(Action.RUN, EndOfDaySchedulePolicy.decide(ON, NAIROBI, at("2026-11-28T23:15"), blocked));
    }

    @Test
    void alertsNameWhatNeedsAPerson() {
        assertTrue(EndOfDaySchedulePolicy.alerts(ON, NAIROBI, at("2026-11-28T21:30"), idle()).isEmpty());
        assertEquals(List.of("EOD_LATE"), codes(EndOfDaySchedulePolicy.alerts(ON, NAIROBI, at("2026-11-28T22:01"), idle())));
        // Switched off: nobody expects the day closed automatically, so no lateness alert.
        assertTrue(EndOfDaySchedulePolicy.alerts(Schedule.defaults(), NAIROBI, at("2026-11-29T22:01"), idle()).isEmpty());

        Facts interrupted = new Facts(D, "RUNNING", false, null, null, null, null, 0);
        List<Alert> a = EndOfDaySchedulePolicy.alerts(ON, NAIROBI, at("2026-11-28T21:10"), interrupted);
        assertEquals(List.of("EOD_INTERRUPTED"), codes(a));
        assertEquals(Severity.CRITICAL, a.get(0).severity());

        Facts longRun = new Facts(D, "RUNNING", true, at("2026-11-28T21:00"), null, null, null, 0);
        assertTrue(EndOfDaySchedulePolicy.alerts(ON, NAIROBI, at("2026-11-28T22:00"), longRun).isEmpty());
        a = EndOfDaySchedulePolicy.alerts(ON, NAIROBI, at("2026-11-28T22:01"), longRun);
        assertEquals(List.of("EOD_OVERRUN"), codes(a));
        assertTrue(a.get(0).message().contains("61 minutes"));

        Facts failed = new Facts(D, "FAILED", false, null, "FAILED", "disk full", at("2026-11-28T21:02"), 1);
        a = EndOfDaySchedulePolicy.alerts(ON, NAIROBI, at("2026-11-28T21:05"), failed);
        assertEquals(List.of("EOD_FAILED"), codes(a));
        assertEquals(Severity.WARNING, a.get(0).severity());
        Facts exhausted = new Facts(D, "FAILED", false, null, "FAILED", "disk full", at("2026-11-28T21:40"), 3);
        a = EndOfDaySchedulePolicy.alerts(ON, NAIROBI, at("2026-11-28T23:00"), exhausted);
        assertEquals(List.of("EOD_FAILED", "EOD_LATE"), codes(a));
        assertEquals(Severity.CRITICAL, a.get(0).severity());
        assertTrue(a.get(0).message().contains("Automatic retries stopped after 3 attempts"));

        Facts blocked = new Facts(D, null, false, null, "BLOCKED", "close and balance every till first", at("2026-11-28T21:00"), 0);
        assertEquals(List.of("EOD_BLOCKED"), codes(EndOfDaySchedulePolicy.alerts(ON, NAIROBI, at("2026-11-28T21:01"), blocked)));

        a = EndOfDaySchedulePolicy.alerts(ON, NAIROBI, at("2026-12-01T21:00"), idle());
        assertEquals(List.of("EOD_BEHIND"), codes(a));
        assertTrue(a.get(0).message().contains("3 day(s) behind"));
    }

    @Test
    void scheduleLimitsAreEnforced() {
        assertThrows(IllegalArgumentException.class, () -> new Schedule(true, LocalTime.of(21, 0), 4, 3, 15, 2));
        assertThrows(IllegalArgumentException.class, () -> new Schedule(true, LocalTime.of(21, 0), 60, 0, 15, 2));
        assertThrows(IllegalArgumentException.class, () -> new Schedule(true, LocalTime.of(21, 0), 60, 3, 4, 2));
        assertThrows(IllegalArgumentException.class, () -> new Schedule(true, LocalTime.of(21, 0), 60, 3, 15, 8));
        assertThrows(IllegalArgumentException.class, () -> new Schedule(true, LocalTime.of(21, 0, 30), 60, 3, 15, 2));
    }
}
