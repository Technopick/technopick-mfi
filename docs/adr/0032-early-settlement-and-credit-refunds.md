# ADR-0032: Early settlement and refunds of loan credit

- Status: Accepted (increment 32, 2026-11-24). The payoff rule and the refund channels need
  finance sign-off.

## Decision
- **Payoff** on the current business date = principal outstanding + interest accrued up to the
  last close and still unpaid + unpaid penalties. Scheduled interest that has not accrued is not
  charged; the quote shows it as "interest not charged". Interest for the current day is not
  charged, because it accrues only at the close. There is no early-settlement fee or extra
  rebate rule: because interest accrues daily, charging only accrued interest is the rebate.
  Interest already paid ahead of accrual is not refunded.
- **How a member settles**: they pay what the quote says is still to pay to the loan number. Money
  beyond what is due is held as credit on the loan (ADR-0021). A branch manager or operations then
  settles, which needs the credit to cover the payoff. Settlement is one transaction:
  - a new schedule version where every unpaid instalment is due today and each instalment's
    interest is what accrued on it (or what was paid on it, if more);
  - an internal repayment of the payoff from the credit, allocated like any other payment;
  - the loan closing, which releases any interest in suspense to income (ADR-0031).
  Any credit left stays on the closed loan.
- **Database checks** at commit: a settlement must close its loan; its schedule version must be
  the latest, with every instalment due by the settlement date and no unaccrued interest. A
  schedule version after the first exists only for a settlement. Settlements are never changed.
- **Refunds of credit on closed loans** go to one of the member's own savings accounts, requested
  by a branch manager or operations and approved by a supervisor who did not request it.
  Approval moves the money from the loan's overpayment account to the savings account. The loan's
  credit (`lending.loan_credit`) now subtracts approved refunds. At commit the database re-checks
  the closed loan, ownership and status of the account, the amount against the credit, and the
  posting.

## Not covered (open)
- Refunds by M-Pesa B2C (needs the B2C flow, built for disbursements, to carry a refund purpose
  through the integration runtime) and in cash at a till. Credit on written-off loans is not
  refundable here.
- Settlement from a savings balance in one step; partial prepayment that shortens the term or
  lowers instalments (rescheduling).
- Finance to confirm: no settlement fee, no refund of interest paid ahead, interest to the last
  close, and who may settle and approve refunds.
