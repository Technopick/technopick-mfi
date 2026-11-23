# ADR-0029: Scheduled end of day, one run at a time, and alerts

- Status: Accepted (increment 29, 2026-11-23)

## Decision
- **One run per tenant, decided by the database.** End of day holds a PostgreSQL session-level
  advisory lock on a dedicated connection for the whole run. If the process dies, the connection
  closes and the lock is released. So a run left RUNNING without its lock was interrupted and is
  resumed at once, and a run whose lock is held is never resumed beside itself, however long it
  takes. This replaces the earlier rule that a run older than 10 minutes was interrupted: the load
  test (ADR-0028) showed month end taking several minutes on a modest book, so that rule could
  have started a second run beside a live one.
- **Schedule per tenant (V26 `ledger.eod_schedule`), off by default**, set by a SUPERVISOR: the
  local cut-off time in the tenant's time zone (a time before noon closes the day in the early hours
  after it), the expected run time, retries after a failure (count and interval) and how many
  missed days may be closed automatically. Every core-banking server runs the scheduler every
  minute when jobs are enabled; the lock keeps it to one run.
- **The scheduler's rules** (`EndOfDaySchedulePolicy`, pure and unit-tested): a day is due once its
  cut-off has passed; missed days close one at a time, oldest first, up to the catch-up limit; a
  failure is retried after the interval up to the attempt limit, then left for a person; a day
  blocked by open tills or a missing accounting period is retried at the interval without limit
  (people are expected to fix it the same night).
- **Every start is recorded** (V26 `ledger.eod_attempt`): scheduled or manual, who, when, and one
  outcome: COMPLETED, BLOCKED, FAILED, SKIPPED (already running) or INTERRUPTED. The database lets
  an attempt end only once and never deletes it.
- **Alerts** come from the same rules: EOD_INTERRUPTED, EOD_FAILED (critical once retries are
  exhausted), EOD_BLOCKED, EOD_OVERRUN, EOD_LATE, EOD_BEHIND. They appear on the End of day page,
  from `GET /api/v1/end-of-day/alerts`, and as `EOD_ALERT raised/cleared` log lines (critical at
  ERROR) for log-based paging. The runbook is `docs/operations/runbooks/end-of-day.md`.

## Limits and decisions needed
- The tenant's time zone is used as stored (default Africa/Nairobi); changing it is not exposed.
- Who may change the schedule (now SUPERVISOR, single person) and whether the default should be on
  for new tenants: operations decision. The audit trail records every change.
- Whether a reconciliation difference should block end of day (ADR-0024) is still open; the
  scheduler would then treat it as BLOCKED.
- No holiday calendar: every calendar day is a business day, as before.
- Paging integration (which tool reads the `EOD_ALERT` lines) depends on the hosting decision.
