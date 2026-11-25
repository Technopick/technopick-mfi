# ADR-0040: Member share capital and dividends

- Status: Accepted (increment 40, 2026-11-25). The share value, minimum holding, dividend rate and
  the withholding tax rate on dividends are board and finance decisions; the TEST-ONLY figures used
  in tests (par 100.00, minimum 1,000.00, tax 5%) are not advice. The tax treatment must be
  confirmed against current KRA rules before production.

## Context
Saccos and many MFIs are owned by their members through share capital. Members buy shares, may
transfer them to another member, and receive a dividend on them after the year end. Share capital
is equity, is not withdrawable like savings, and counts in core capital for prudential returns,
so the register and the ledger must agree exactly.

## Decision
- **Shares are held as an amount** in whole multiples of the share value (par), per member, in a
  register of movements (`shares.share_transaction`, V37): PURCHASE, TRANSFER_OUT, TRANSFER_IN.
  A holding is the sum of movements up to a date (`shares.holding`). Movements are never changed or
  deleted.
- **Settings** (finance only): par value, minimum holding, the share capital account (EQUITY), the
  account dividends are paid from (EQUITY, normally retained earnings), dividends payable and
  withholding tax payable (LIABILITY), and the tax rate. Once any share exists the par value and the
  share capital account are fixed.
- **Purchase** (teller, loan officer, branch manager): paid only from one of the member's own active
  savings accounts, from available funds, on the business date. One entry: debit the savings
  control on that account, credit share capital, both in the account's branch. An active member
  only; the resulting holding must reach the minimum. Idempotent by key. No maker-checker: it moves
  the member's own money between their own products, like a withdrawal within limits.
- **Transfer** between members (asked by a loan officer, branch manager or operations; decided by a
  branch manager or supervisor who did not ask): register only, no journal, since share capital in
  total does not change. The seller must keep at least the minimum, checked when asked and again,
  under lock, when approved. A rejection needs a reason.
- **Dividend runs** (asked by finance, decided by finance or a supervisor who did not ask): for a
  financial year and a record date that end of day has closed. Each member holding shares at the
  record date gets gross = shares x rate (rounded to the cent), tax = gross x tax rate (rounded),
  net = gross - tax. Net goes to the member's oldest active savings account, or to dividends
  payable if they have none. Approval posts one entry dated the business date: debit the source
  account by gross, credit tax by the tax, credit payable, and credit each member's account by its
  net, per branch. One non-rejected run per financial year. The accounts are frozen on the run when
  it is asked for.
- **The database enforces the rules** (V37): whole shares, own active account, member active, no
  negative holding or holding below the minimum, purchase entries exactly matching the movement,
  transfers in balanced pairs only after approval, dividend lines matching the register and the
  arithmetic, run totals matching lines and the entry, and nothing else posting to share capital
  (it is a control account for manual journals as well).
- **Reconciliation**: new check SHARE_CAPITAL, ledger (credit-positive) equal to the register total,
  live and at every end of day.

## Not built yet
- Withdrawing shares on exit (refund or transfer on leaving), share purchase by M-Pesa, loan limits
  as a multiple of shares, paying dividends payable out later, interest on member deposits
  (separate from savings interest), and share certificates.
- Branch of a dividend line is the account's branch, or the member's branch for payable.

## Consequences
- Share capital appears on the balance sheet as equity and reconciles daily.
- A member's dividend depends only on the holding at the record date, so purchases or transfers
  after that date do not change a run already asked for; finance should ask for the run promptly
  after the year-end close.
