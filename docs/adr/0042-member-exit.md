# ADR-0042: Member exit

- Status: Accepted (increment 42, 2026-11-26). The notice period, what must be cleared before a
  member leaves and how refunds are paid are by-law and board decisions; the TEST-ONLY values
  (2 days in the API test, none on the browser stack; 60 by default) are not advice.

## Context
Members leave a Sacco by giving notice. Before they go, their obligations must be cleared, and what
the Sacco owes them (deposits and interest earned so far) must be settled and paid. Share capital is
not withdrawable: a leaving member transfers their shares to another member.

## Decision
- **Settings** (finance): notice period in days and the member refunds payable account (LIABILITY).
  The account does not change while refunds are unpaid.
- **Request** (teller, loan officer, branch manager, operations): for an active or blocked member,
  with the member's reason, on the business date; the notice period is copied and the earliest exit
  date fixed. One open request per member; it can be cancelled with a reason.
- **What stops a member leaving** (`membership.blockers`, shown on the screen before asking):
  unfinished loans, written-off loans still owed, credit on closed loans not yet refunded, open loan
  applications, active fixed deposits, shares not yet transferred, pending share transfers, holds,
  teller transactions waiting for approval, and overdrawn accounts.
- **Approval** (branch manager or supervisor who did not ask), on or after the earliest exit date
  and only when nothing stops it. One entry settles every open savings account: the balance, and the
  savings interest accrued since the last monthly credit less withholding tax at the product's rate,
  move to member refunds payable (per branch). The accounts close and the member becomes EXITED,
  which is final: no new accounts, loans, deposits or shares.
- **Refund payment** (recorded by finance with method, bank or cash account and reference; approved
  by finance or a supervisor who did not record it): the full refund, once, from an ASSET account
  that is not a control account. Methods: bank transfer, cheque, cash, M-Pesa (recorded, not sent).
- **The database enforces the rules** (V39): EXITED only through an approved exit and never
  changed, notice, blockers, settlement amounts per account, the exit entry and the payment entry
  line by line, maker-checker, and nothing else posting to refunds payable (control account).
- **Reconciliation**: MEMBER_REFUNDS_PAYABLE equals unpaid refunds; savings interest payable
  accounts for interest settled at exit.

## Not built yet
- Paying refunds through M-Pesa B2C or into another member's account, deducting fees or exit
  charges, offsetting savings against loans at exit, guarantor obligations (guarantees are not yet
  modelled), deceased-member claims and nominees, re-joining, and SMS to the member.

## Consequences
- A member's history stays in place after they leave; the database refuses any posting to their
  closed accounts. How an M-Pesa payment to a closed account is handled has not been tested yet.
- Unpaid refunds are visible as a liability and in reconciliation until finance records the payment.
