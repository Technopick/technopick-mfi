# ADR-0020: Loan end of day

- Status: Accepted (increment 20, 2026-11-19)

## Decision
- **Loans are the first end-of-day step.** For each ACTIVE loan, in its own transaction, the
  existing `EndOfDayPlanner` accrues interest, charges penalties and takes the arrears snapshot
  for the day being closed. The snapshot marks the loan done, so a resumed run skips it.
- **Inputs** come from the database: the latest schedule, repayment allocations, earlier accruals
  and penalties, and the disbursement date (business date of the disbursement entry) as the
  first period's start. Catch-up is built in: an installment whose period has passed accrues up to
  its full scheduled interest.
- **Interest paid before it accrued is not recognised twice.** A repayment that pays interest
  ahead of the accrual already takes that part to income (ADR-0010 repayment rule). Each accrual
  row now records `recognized_earlier`, and only the rest is posted (Dr interest receivable /
  Cr interest income), so income recognised on an installment is always max(accrued, paid).
  This fixes a double-recognition that would have occurred as soon as loan EOD ran on a loan
  with early payments. The database re-derives the figure from repayments (V19).
- **Penalty and arrears terms live on the product version** (V19): method NONE / FLAT_ONCE /
  DAILY_RATE, grace days, flat amount, daily rate, cap per installment, arrears tolerance.
  Existing versions default to NONE and zero tolerance. The database refuses penalties outside
  the rule (before grace, above the cap, a second flat charge, above rate x unpaid).
- **Snapshots** only for the current business date during its EOD; outstanding principal must
  equal principal less principal repaid. PAR by branch comes from the existing view.
- The end-of-day run no longer refuses tenants with active loans. The summary reports loans
  processed, loan interest accrued, penalties and loans in arrears; `eod_run.loans_processed` is
  set at completion.

## Consequences / open items
- Penalty settings, the arrears classification thresholds and caps on non-performing loans
  (in duplum and similar rules) need legal and compliance confirmation per licence type before
  real use; products default to no penalty.
- Not yet built: stopping accrual on non-performing loans (interest in suspense), write-off,
  loan closure when fully repaid, rescheduling, and a PAR report screen.
