# Runbook: end of day

Who: operations (OPERATIONS role) runs and recovers end of day; a supervisor sets the schedule;
finance opens accounting periods. Decisions behind this runbook: ADR-0019, ADR-0020, ADR-0029.

## How it normally runs
- With the schedule on (End of day page, "Automatic schedule"), each business date closes at the
  set local time in the tenant's time zone. A time before noon closes the day in the early hours
  after it. Each run is listed under "Recent attempts".
- With the schedule off, operations closes the day from the End of day page.
- Only one end-of-day run per tenant can be active at a time, on any server. A run that is really
  in progress is never started a second time, however long it takes.
- End of day is resumable: every step is committed as it goes, and running again never posts
  anything twice.

## Where alerts appear
- At the top of the End of day page (for operations, branch managers and supervisors).
- In the core-banking log, one line when an alert appears and one when it clears:
  `EOD_ALERT raised code=<CODE> severity=<WARNING|CRITICAL> tenant=<id> message=...` and
  `EOD_ALERT cleared code=<CODE> tenant=<id>`. Route CRITICAL (logged at ERROR) to on-call paging and
  WARNING to the operations channel. Every attempt also logs `EOD attempt <n> ... ended <OUTCOME>`.
- `GET /api/v1/end-of-day/alerts` returns the same list for monitoring tools.

## What to do for each alert

| Code | Meaning | Action |
|---|---|---|
| `EOD_BLOCKED` (warning) | The day cannot start: tills still open, or no open accounting period for the next day. The reason names which. | Tellers close and balance their tills (supervisor approves any difference), or finance opens the period. The schedule retries every few minutes; or close the day by hand. |
| `EOD_FAILED` (warning) | An attempt failed; the reason is shown. The schedule will retry. | Read the reason and the log around `EOD attempt <n>`. If it is temporary (database restart, network), let the retry run. |
| `EOD_FAILED` (critical) | Automatic retries are used up for this date. | Fix the cause (see below), then press "Close business day". It resumes where it stopped. |
| `EOD_INTERRUPTED` (critical) | The process running end of day ended mid-run (restart, crash, deployment). Nothing is running now. | Press "Resume closing business day". With the schedule on, the next pass resumes it automatically. |
| `EOD_OVERRUN` (warning) | A run is still going after the expected time. | Do not restart the service to "unstick" it unless the database shows no progress. Check the log for loan and savings progress, and database load. If a restart is needed, the run is reported as interrupted and resumes. |
| `EOD_LATE` (warning) | The day is past its cut-off plus the expected run time and still open (for example, the scheduler is not running, or the day is blocked or failed). | Check the other alerts and "Recent attempts". If there are no attempts at all, check that jobs are enabled (`MFI_JOBS_ENABLED`) on at least one core-banking server. |
| `EOD_BEHIND` (critical) | The business date is more days behind than automatic catch-up allows. Nothing runs automatically. | Operations and finance agree how to close the missed days: usually run end of day once per missed day from the page, checking reconciliation after each. Raise the catch-up limit only if that is the agreed decision. |

## Fixing a failed run
1. Read the reason on the page (also stored in the attempt). Messages that start with a code such
   as `EOD_AMOUNT_MISMATCH` or `LEDGER_UNBALANCED` come from database integrity checks. They mean
   the data or the code is wrong, not the infrastructure: stop and escalate to engineering with the
   attempt number. Do not edit data to make the check pass.
2. Infrastructure failures (connections, timeouts, disk) can be retried once the platform is
   healthy.
3. After the day closes, open Finance, then Reconciliation, and confirm there are 0 differences for
   that date.

## Never
- Never change `ledger.eod_run`, `ledger.eod_attempt` or the business date by hand to move past a
  problem. The database refuses most such changes, and the rest would break the audit record.
- Never run end of day against production from a test tool or script. Use the page, or the
  schedule.
