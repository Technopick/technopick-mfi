# ADR-0025: Manual journals and accounting period close

- Status: Accepted (increment 25, 2026-11-19)

## Decision
- **Manual journals are maker-checker.** FINANCE proposes a journal (2-50 lines, balanced per
  currency, dated today or earlier in an open period, with a narrative); a different FINANCE or
  SUPERVISOR user posts it or rejects it with a reason. The posted entry (source MANUAL) must be
  exactly the approved lines; decided journals are final.
- **No manual journal on a control account.** Customer savings (subledger accounts), loan
  principal, interest and penalty receivable, loan overpayments, savings interest payable and
  till cash change only through their business flows. Otherwise a manual entry would make the
  reconciliation (ADR-0024) report a difference that no business record explains. Corrections to
  those go through reversal or the business transaction itself.
- **Periods are calendar months**, named YYYY-MM and opened one after another (FINANCE: "open
  next month", at most two months ahead). End of day refuses to move into a date with no open
  period, so a missing period stops the day visibly rather than failing postings later.
- **Closing a month** (FINANCE): only after end of day has closed its last day, with no manual
  journal dated in it still pending; if that day's recorded reconciliation has differences the
  closer must explain them in a note. A closed month accepts no postings (V1) and cannot be
  reopened in the application.

## Needs sign-off
- Whether closing a month should itself need a second person, and who may reopen a month
  (currently only a database administrator, outside the application).
- Journal approval limits by amount.

## Not built
Recurring journals, reversing journals (auto-reverse next period), attachments and journal
templates, year-end close (retained earnings), income statement and balance sheet layouts.
