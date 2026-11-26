# ADR-0041: Fixed (term) deposits

- Status: Accepted (increment 41, 2026-11-26). Rates, terms, limits, the early withdrawal rate and
  the withholding tax rate on deposit interest are finance and board decisions; the TEST-ONLY
  figures in tests (12% for one month, 6% if withdrawn early, 15% tax) are not advice. The tax
  treatment must be confirmed against current KRA rules before production.

## Context
Saccos and MFIs take fixed deposits: a member locks an amount for a term at a better rate than
savings, and at maturity takes the money, or rolls it over. Deposits are a liability, interest
must be recognised as it is earned (accrual), and the deposit register must agree with the ledger.

## Decision
- **Products** (finance only): term in months, annual rate, rate paid if withdrawn early (not above
  the rate), whether early withdrawal is allowed, minimum and maximum amount, withholding tax rate,
  and four accounts: deposits (LIABILITY), interest payable (LIABILITY), interest expense (EXPENSE)
  and tax payable (LIABILITY), all in one currency. Accounts and currency never change; rates and
  limits may change and apply only to new deposits and rollovers. Products are retired, not deleted.
- **Placement** (teller, loan officer, branch manager): from one of the member's own active savings
  accounts, from available funds, on the business date. The deposit copies the product's terms and
  matures the same day of the month, term months later (end-of-month dates are clamped). One entry:
  debit the savings control on that account, credit deposits, in the account's branch. Numbered
  FD00000001 onwards per tenant. Idempotent by key. No maker-checker: it moves the member's own
  money between their own products. The member chooses what happens at maturity: pay principal and
  interest to savings, roll over the principal (interest to savings), or roll over both; staff may
  change this while the deposit is active.
- **Interest** accrues at each close for each night held: the cumulative interest at the close of
  day d is round(principal x rate x nights / 36500, 2), nights = min(d, maturity - 1) - start + 1;
  each close posts the difference, one entry per product and branch (debit interest expense, credit
  interest payable). Simple interest, actual/365, no compounding within a term.
- **Maturity** happens at the close of the day before the maturity date, so the money is in savings
  when the member arrives on the maturity date. Interest earned is the full term's interest (equal
  to what accrued); tax = round(interest x tax rate / 100, 2). A rollover opens a new deposit from
  the maturity date at the product's current terms, keeping the instruction. If the product is
  retired or the member or account is no longer active, the deposit is paid out instead.
- **Early withdrawal** (asked by a teller, loan officer, branch manager or operations; decided by a
  branch manager or supervisor who did not ask): the whole deposit, before the day of maturity. It
  earns the early rate for the nights held; interest accrued above that is reversed to expense; the
  principal and net interest go to the savings account at once. A rejection needs a reason.
  Partial withdrawals are not offered.
- **The database enforces the rules** (V38): product account classes, copied terms and maturity
  date, placement only on the business date and within limits, the placement entry, accrual
  arithmetic and batch totals, accruals and maturities only inside a running end of day, closure
  arithmetic and its entry line by line, rollover principal, maker-checker on early withdrawal,
  immutability, and nothing else posting to deposits or interest payable (control accounts). A
  savings account cannot be closed while a deposit pays to it.
- **Reconciliation**: TERM_DEPOSIT_PRINCIPAL (deposits account equals active principal) and
  TERM_DEPOSIT_INTEREST (interest payable equals interest accrued on active deposits), live and at
  every close.

## Not built yet
- Interest paid periodically during the term (monthly or quarterly), compound or tiered rates,
  partial early withdrawal, a penalty fee in place of a reduced rate, deposits funded or paid out by
  M-Pesa or cash, deposit certificates, using a deposit as loan security (lien), SMS on placement
  and maturity, fixed deposits in member self-service and USSD, and a holiday calendar for maturity.

## Consequences
- Fixed deposits appear as a liability and their interest as an expense in the statements, and
  reconcile daily.
- End of day gains one step; its summary reports deposits accrued, interest accrued, deposits
  matured and rolled over, and the step's time.
