# ADR-0017: Savings accounts and teller cash

- Status: Accepted (increment 17, 2026-11-19)

## Decision
- **Opening**: TELLER, LOAN_OFFICER or OPERATIONS opens a savings account for an ACTIVE (verified)
  customer and a savings product; idempotent by key. The database refuses accounts for unverified
  customers and never lets an account change owner or number (V16).
- **Account numbers**: `2` + 8-digit per-tenant sequence + Luhn check digit (e.g. `2000000014`), 10
  digits, so they fit M-Pesa account references and most single-digit typos cannot hit another
  customer's account. Paybill payments to the number credit the account as before.
- **Teller cash**: a TELLER posts deposits and withdrawals: Dr/Cr the branch cash GL account
  (`savings.branch_cash_account`) against the product's savings control with the account as
  subledger, one balanced entry per transaction, idempotent by key. Withdrawals are checked against
  the available balance (holds and overdraft included) in the application and again by the database
  at commit.
- **Approval thresholds** (`savings.teller_policy`, per tenant and currency): withdrawals above
  `withdrawal_approval_above` and deposits above `deposit_approval_above` are captured as
  PENDING_APPROVAL with no posting; a different BRANCH_MANAGER or SUPERVISOR approves (posts) or
  rejects with a reason. The **database** decides whether approval is required, so the application
  cannot skip it. A teller may also ask for approval voluntarily. The staff app tells the teller not
  to pay out or accept cash until approved.
- **Statements** come from the ledger: opening balance, every subledger posting with a running
  balance, totals, closing balance; teller narratives, M-Pesa receipts otherwise; at most 366 days.

## Not done yet
Teller tills and end-of-day cash balancing (till per teller, vault transfers, cash count and
variance); reserving funds for pending withdrawals (today approval re-checks the balance);
account closure, dormancy and reactivation; interest on savings; fees; statement PDF; regulatory
cash-transaction reporting (large cash deposits under POCAMLA thresholds).

## Verified (CI)
Core: open for verified customer only, idempotent deposit and key reuse conflict, withdrawal over
available refused, 60,000 withdrawal waits for approval, capturing teller cannot approve even with
the supervisor role, double approval refused, voluntary approval rejected with a reason moves no
money, statement totals and running balances, paybill credit to the new account. Database:
D1-D7. Browser: teller opens account, deposits 100,000, 60,000 withdrawal waits, teller sees no
approve action, supervisor approves, statement shows 100,000 in, 60,000 out, 40,000 balance.
