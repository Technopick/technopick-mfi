# ADR-0019: End of day and savings interest

- Status: Accepted (increment 19, 2026-11-19)

## Context
The database has had business dates and end-of-day (EOD) runs since V4, but nothing in the
application closed a day, so the business date never moved. Savings products had no interest.

## Decision
- **End of day is an explicit OPERATIONS action** (`POST /api/v1/end-of-day/run`); BRANCH_MANAGER
  and SUPERVISOR can see its state. The run executes in steps, each in its own transaction:
  start or resume the run, accrue savings interest for each product and branch, credit interest
  on the last day of the month, then complete the run and move the business date forward one day.
  A failed step marks the run FAILED with the reason; running again resumes without reposting
  (accruals are skipped per account and date; entries have deterministic idempotency keys).
- **The day closes only when every till is closed and balanced.** Checked by the service and by
  the database (`EOD_TILLS_OPEN`).
- **Loan end of day is not wired yet.** The loan planner (accrual, penalties, arrears) exists in
  the domain but not as an EOD step. Because loan accrual records can only be written for the
  current business date, closing a day without them would lose them. The run therefore refuses
  tenants with active loans until loan end of day is built (next increment).
- **Interest terms on the product**: annual rate (percent), minimum balance, withholding tax rate,
  and the expense, payable and tax GL accounts. The database refuses a rate without the accounts.
- **Accrual (savings-interest/v1)**: each EOD, for each account that is not closed and whose ledger
  balance at the business date is positive and at least the minimum: daily amount = balance x
  rate / 100 / 365 (ACT/365 fixed) to 10 decimals. The amount posted is the rounded cumulative
  accrual of the open period less what the period already posted, so cent rounding never drifts.
  One entry per product, branch and day: Dr interest expense / Cr interest payable.
- **Crediting**: on the last day of each month, each account's accruals since its previous
  credit are paid: Dr interest payable (gross) / Cr the customer account (net) / Cr withholding
  tax payable (tax = gross x tax rate, rounded half-up to cents). The statement line reads
  "Interest <start> to <end> (gross ..., tax ...)".
- **The database re-derives everything** (V18): the balance from the ledger, the rate from the
  product, the daily amount, the rounding, the tax, the period coverage and the entry amounts;
  accrual and posting records never change.
- Events: `BusinessDayClosed`, `SavingsInterestPosted` in the outbox.

## Consequences / open items
- Rates, minimum balances, the posting frequency (monthly) and the withholding tax rate and its
  rounding are business and tax decisions: values in test environments are TEST-ONLY and need
  finance and tax sign-off before real use.
- Not yet built: interest on account closure, rate changes within a period by effective date,
  tiered rates, tax exemptions per customer, automatic scheduled EOD, and the loan EOD step.
- Accrual reads each account's balance with a ledger aggregate; fine for pilot volumes, to be
  replaced by a daily balance snapshot before large tenants.
