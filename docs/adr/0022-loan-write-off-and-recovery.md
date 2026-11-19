# ADR-0022: Loan write-off and recoveries

- Status: Accepted (increment 22, 2026-11-19)

## Decision
- **Maker-checker.** A BRANCH_MANAGER or OPERATIONS user requests a write-off with a reason; a
  SUPERVISOR who did not request it approves or rejects it (a rejection needs a reason). One
  pending or approved write-off per loan; decisions are final.
- **Eligibility.** The loan is ACTIVE, the last end-of-day arrears snapshot shows at least the
  tenant's `write_off_policy.min_days_past_due`, and the loan holds no overpaid credit. With no
  policy configured, nothing can be written off.
- **What is written off** is computed at approval, not at request (end of day may run in
  between): principal outstanding, interest receivable (per installment, accrued less paid) and
  unpaid penalties. Entry: Dr write-off expense / Cr principal, interest receivable and penalty
  receivable. The loan becomes WRITTEN_OFF in the same transaction; the database refuses either
  without the other and re-derives every amount (V21). Unaccrued future interest is not owed and
  is not written off.
- **Recoveries.** An M-Pesa receipt for a written-off loan is accepted at validation and posted
  as a recovery (Dr paybill clearing / Cr the product's recovery account) as long as it does not
  take recoveries beyond the amount written off; a larger receipt is rejected at validation, or
  goes to suspense if it arrives anyway. The product version must name a recovery account before
  any of its loans can be written off.
- Events: `LoanWrittenOff`, `WrittenOffLoanRecovery`.

## Needs sign-off
- Minimum days past due and approval limits by amount (finance; currently a single supervisor
  approves any amount).
- Accounting for recoveries (income vs credit to the impairment allowance) and how write-offs
  interact with IFRS 9 provisioning, which is not built.
- Credit-bureau reporting of written-off loans (CRB provider not chosen).
