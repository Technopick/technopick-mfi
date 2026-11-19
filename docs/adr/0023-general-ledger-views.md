# ADR-0023: General ledger views for finance

- Status: Accepted (increment 23, 2026-11-19)

## Decision
- **New role FINANCE.** Finance staff read the ledger; SUPERVISOR and OPERATIONS may too. The
  identity provider must issue the role (the TEST realm has a `finance` user).
- **Read-only views computed from journal lines** (`/api/v1/accounting`): chart of accounts; trial
  balance as of a business date (default today's), optionally for one branch, with debit and
  credit columns and per-currency totals that state whether they balance; account activity for up
  to 366 days with opening balance, running balance and the deposit account behind subledger
  lines (first 2,000 lines, flagged when cut); one journal entry with all its lines, and the
  entry it reverses or that reverses it.
- **Natural signs** for balances: debit-positive for assets and expenses, credit-positive for
  liabilities, equity and income.
- A branch-filtered trial balance can be out of balance when entries span branches; the screen
  says so instead of hiding it.
- Staff app: General ledger (trial balance -> account activity -> journal entry).

## Not built
Income statement and balance sheet layouts, period close, manual journals with maker-checker,
exports (CSV/Excel), and reconciliation of subledgers (savings, loans) to their control accounts.
