# ADR-0021: Loan closure, overpaid credit and portfolio at risk

- Status: Accepted (increment 21, 2026-11-19)

## Decision
- **A loan closes when it is fully repaid**: every installment of the current schedule paid in
  principal and interest, and every penalty charged paid. Closure happens right after the
  repayment that finishes the loan, or during end of day. The database (V20) refuses a closure
  while anything is unpaid and requires a closure record, which keeps the credit still held.
- **Overpaid money pays installments as they fall due.** Repayment allocation only pays what is
  due, so money paid ahead was held on the overpayment account and never used. End of day now
  applies the loan's credit to whatever is due that day, as an INTERNAL repayment from the
  overpayment account allocated like any other payment (interest paid ahead of accrual follows
  the ADR-0020 rule). The database refuses applying more credit than the loan holds.
- **Repayments only to ACTIVE loans**, checked when the repayment is recorded; the commit-time
  check now also accepts a loan that closed in the same transaction.
- **Credit left after closure stays with the loan.** It is not refunded or moved automatically:
  the refund route (M-Pesa B2C, savings account, cash) and its approvals are the open
  overpayment-policy decision. The portfolio report lists these loans so they are not lost.
- **Portfolio at risk report** (`GET /api/v1/reports/portfolio-at-risk`, staff app page): PAR1,
  PAR30, PAR90 by branch from the latest (or a chosen) end-of-day snapshot, the loans in arrears
  with days past due and classification, and closed loans still holding credit. Read-only, for
  branch managers, supervisors, operations and loan officers.

## Not decided / not built
- Early settlement (paying off future installments with an interest rebate) - product decision.
- Non-accrual (interest in suspense) for non-performing loans: the threshold and treatment are a
  compliance decision (prudential guidelines vs IFRS 9 stage 3); not built yet.
- Refund of credit held, write-off, rescheduling.
