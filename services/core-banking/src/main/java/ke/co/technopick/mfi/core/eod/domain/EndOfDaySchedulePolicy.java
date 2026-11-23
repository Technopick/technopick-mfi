package ke.co.technopick.mfi.core.eod.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * When the scheduler closes a tenant's day, and which conditions need a person (ADR-0029). Pure:
 * the caller supplies the time and the facts, so every rule is tested without a clock or database.
 *
 * <p>Business date D is due once D at {@code runAt} has passed in the tenant's time zone; a time
 * before noon means the early hours after D (01:00 closes D at 01:00 on D + 1). If the
 * platform was down, the days still due are closed one at a time, oldest first, but only up to
 * {@code maxCatchUpDays} late: beyond that a person decides. A failed scheduled attempt is retried
 * after {@code retryMinutes}, at most {@code maxAttempts} times per business date; an attempt
 * blocked by open tills or a missing period is retried at the same interval without limit.
 */
public final class EndOfDaySchedulePolicy {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm");

    private EndOfDaySchedulePolicy() {
    }

    public record Schedule(boolean enabled, LocalTime runAt, int overrunMinutes, int maxAttempts, int retryMinutes,
                           int maxCatchUpDays) {
        public Schedule {
            Objects.requireNonNull(runAt, "runAt");
            if (overrunMinutes < 5 || overrunMinutes > 720) {
                throw new IllegalArgumentException("overrunMinutes must be between 5 and 720");
            }
            if (maxAttempts < 1 || maxAttempts > 10) {
                throw new IllegalArgumentException("maxAttempts must be between 1 and 10");
            }
            if (retryMinutes < 5 || retryMinutes > 240) {
                throw new IllegalArgumentException("retryMinutes must be between 5 and 240");
            }
            if (maxCatchUpDays < 0 || maxCatchUpDays > 7) {
                throw new IllegalArgumentException("maxCatchUpDays must be between 0 and 7");
            }
            if (runAt.getSecond() != 0 || runAt.getNano() != 0) {
                throw new IllegalArgumentException("runAt must be a whole minute");
            }
        }

        public static Schedule defaults() {
            return new Schedule(false, LocalTime.of(21, 0), 60, 3, 15, 3);
        }
    }

    /**
     * What is known about the current business date.
     *
     * @param businessDate       the date the next run would close
     * @param runStatus          ledger.eod_run status for that date, or null if no run started
     * @param runInProgress      some process holds the tenant's end-of-day lock
     * @param liveAttemptStarted when the attempt now in progress started, if known
     * @param lastOutcome        outcome of the latest finished attempt for this date, or null
     * @param lastReason         its reason
     * @param lastFinished       when it finished
     * @param scheduledFailures  scheduled attempts for this date that FAILED
     */
    public record Facts(LocalDate businessDate, String runStatus, boolean runInProgress, Instant liveAttemptStarted,
                        String lastOutcome, String lastReason, Instant lastFinished, int scheduledFailures) { }

    public enum Action { NOT_ENABLED, IN_PROGRESS, NOT_DUE, TOO_FAR_BEHIND, GIVE_UP, WAIT_RETRY, RUN }

    public enum Severity { WARNING, CRITICAL }

    public record Alert(String code, Severity severity, String message) { }

    /** The instant business date {@code d} is due to close. */
    public static Instant cutOff(LocalDate d, LocalTime runAt, ZoneId zone) {
        return (runAt.isBefore(LocalTime.NOON) ? d.plusDays(1) : d).atTime(runAt).atZone(zone).toInstant();
    }

    /**
     * How many business dates, starting with {@code d}, have passed their cut-off: 0 when {@code d} is
     * not yet due, 1 on a normal night, 2 when one night was missed, and so on.
     */
    public static int datesDue(LocalDate d, LocalTime runAt, ZoneId zone, Instant now) {
        if (now.isBefore(cutOff(d, runAt, zone))) {
            return 0;
        }
        LocalDate candidate = now.atZone(zone).toLocalDate().minusDays(runAt.isBefore(LocalTime.NOON) ? 1 : 0);
        LocalDate lastDue = now.isBefore(cutOff(candidate, runAt, zone)) ? candidate.minusDays(1) : candidate;
        return (int) Math.max(1, lastDue.toEpochDay() - d.toEpochDay() + 1); // lastDue >= d because d is due
    }

    public static Action decide(Schedule s, ZoneId zone, Instant now, Facts f) {
        if (!s.enabled()) {
            return Action.NOT_ENABLED;
        }
        if (f.runInProgress()) {
            return Action.IN_PROGRESS;
        }
        int due = datesDue(f.businessDate(), s.runAt(), zone, now);
        if (due == 0) {
            return Action.NOT_DUE;
        }
        if (due - 1 > s.maxCatchUpDays()) {
            return Action.TOO_FAR_BEHIND;
        }
        if (f.scheduledFailures() >= s.maxAttempts()) {
            return Action.GIVE_UP;
        }
        if (f.lastFinished() != null && now.isBefore(f.lastFinished().plus(Duration.ofMinutes(s.retryMinutes())))) {
            return Action.WAIT_RETRY;
        }
        return Action.RUN;
    }

    /** Conditions that need a person, most serious first. Empty when nothing needs attention. */
    public static List<Alert> alerts(Schedule s, ZoneId zone, Instant now, Facts f) {
        List<Alert> critical = new ArrayList<>();
        List<Alert> warning = new ArrayList<>();
        LocalDate d = f.businessDate();
        if ("RUNNING".equals(f.runStatus()) && !f.runInProgress()) {
            critical.add(new Alert("EOD_INTERRUPTED", Severity.CRITICAL, "End of day for " + d
                    + " stopped part-way because the process running it ended. Run end of day to resume it;"
                    + " nothing already done is repeated."));
        }
        if (f.runInProgress() && f.liveAttemptStarted() != null
                && now.isAfter(f.liveAttemptStarted().plus(Duration.ofMinutes(s.overrunMinutes())))) {
            long minutes = Duration.between(f.liveAttemptStarted(), now).toMinutes();
            warning.add(new Alert("EOD_OVERRUN", Severity.WARNING, "End of day for " + d + " has been running for "
                    + minutes + " minutes, longer than the expected " + s.overrunMinutes() + "."));
        }
        if (!f.runInProgress() && "FAILED".equals(f.lastOutcome())) {
            boolean stopped = s.enabled() && f.scheduledFailures() >= s.maxAttempts();
            String text = "End of day for " + d + " failed: " + f.lastReason() + "."
                    + (stopped ? " Automatic retries stopped after " + f.scheduledFailures()
                            + " attempts; fix the cause and run end of day." : "");
            (stopped ? critical : warning).add(new Alert("EOD_FAILED", stopped ? Severity.CRITICAL : Severity.WARNING, text));
        }
        if (!f.runInProgress() && "BLOCKED".equals(f.lastOutcome())) {
            warning.add(new Alert("EOD_BLOCKED", Severity.WARNING, "End of day for " + d + " cannot start: "
                    + f.lastReason() + "."));
        }
        if (s.enabled()) {
            int due = datesDue(d, s.runAt(), zone, now);
            if (due - 1 > s.maxCatchUpDays()) {
                critical.add(new Alert("EOD_BEHIND", Severity.CRITICAL, "The business date " + d + " is " + (due - 1)
                        + " day(s) behind; automatic catch-up stops after " + s.maxCatchUpDays()
                        + ". A person must decide how to close the missed days."));
            } else if (due > 0 && !f.runInProgress()
                    && now.isAfter(cutOff(d, s.runAt(), zone).plus(Duration.ofMinutes(s.overrunMinutes())))) {
                warning.add(new Alert("EOD_LATE", Severity.WARNING, d + " was due to close at "
                        + WHEN.format(cutOff(d, s.runAt(), zone).atZone(zone)) + " (" + zone + ") and is still open."));
            }
        }
        critical.addAll(warning);
        return List.copyOf(critical);
    }
}
