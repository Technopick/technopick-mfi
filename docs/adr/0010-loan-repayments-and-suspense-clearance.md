# ADR-0010: Loan repayments by loan number, and suspense clearance under maker-checker

- Status: Accepted (increment 9, 2026-11-18)
- Context: ADR-0009 sends unmatched paybill receipts to suspense, and the ACCEPT validation
  fallback means more of them land there. Customers also pay loans by quoting the loan number.

## Decision
- **Routing of a paybill receipt (core, in one transaction):** BillRefNumber (trimmed, upper-cased)
  matching a deposit account that accepts credits -> credit the account; else matching an ACTIVE
  loan number -> loan repayment; else suspense (UNMATCHED). Validation accepts both account and
  ACTIVE loan numbers. `payments.provider_payment` records `loan_id` and status `LOAN_REPAYMENT`
  (database check: exactly one target per status).
- **Loan repayment (`LoanRepaymentService`, joins the caller's transaction):** the loan row is
  locked; dues are computed for installments due on or before the business date from the latest
  schedule version minus allocations already made, plus unpaid penalties; the product version's
  allocation order applies; any excess goes to the overpayment liability. Posting: Dr the
  channel's clearing (paybill clearing, or suspense when clearing suspense) / Cr principal
  receivable, interest receivable (only up to interest already accrued at EOD), interest income
  (the part paid before it was accrued), fee and penalty receivables, overpayments. Interest is
  never credited to receivable beyond what was accrued, so the receivable cannot go negative.
  A `LoanRepaymentReceived` outbox event is written in the same transaction.
- **Suspense clearance:** an OPERATIONS user requests a resolution (target deposit account or
  ACTIVE loan, reason). A SUPERVISOR who is not the requester approves or rejects. Approval posts
  Dr suspense / Cr target (account control with subledger, or the loan repayment posting above
  with channel `SUSPENSE`) and records the journal entry on the resolution. The database enforces
  the same rules independently (V9): one live resolution per receipt, decider differs from
  requester, decisions are final, approved resolutions carry their journal entry, only UNMATCHED
  receipts can be resolved. Open items come from `payments.open_suspense_item` with age in days.
- Lending posts through the accounting module's `JournalPosting` port, not its persistence
  (ArchUnit rule).

## Not decided / out of scope
- Partial splitting of one receipt across several targets; refunds from suspense back to the
  payer (needs B2C, next increment); ageing thresholds and escalation for old suspense items.
- Overpayment application to future installments or refund policy (product decision).

## Verified (CI)
Paybill receipt quoting `ln-000001` for 10,000.00 against a 100,000.00 / 12-month / 24% reducing
loan: interest 2,000.00 and principal 7,455.96 allocated, 544.04 to overpayments, ledger lines
exact, replay does not double-apply. Suspense: list restricted to OPERATIONS/SUPERVISOR, a second
live resolution is refused (409), the requester cannot approve even with the supervisor role
(403), double approval is refused (409), cleared to a savings account (balance 300.00) and to a
loan (channel SUSPENSE, no overpayment), and both items leave the open list.
