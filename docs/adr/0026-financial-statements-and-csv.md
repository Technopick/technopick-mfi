# ADR-0026: Financial statements and CSV export

- Status: Accepted (increment 26, 2026-11-20)

## Decision
- **Income statement** (`GET /api/v1/accounting/income-statement`): income and expense account
  movements between two business dates (default: the current month to date, at most 366 days),
  optionally for one branch, with totals and net profit per currency.
- **Balance sheet** (`GET /api/v1/accounting/balance-sheet`): asset, liability and equity
  balances at a business date (default today). There is no year-end close yet, so income less
  expenses since the first posting is shown as "earnings not yet closed to equity"; with it,
  assets equal liabilities plus equity, and the screen says whether that holds.
- Both are computed from journal lines, read-only, for FINANCE, SUPERVISOR and OPERATIONS.
- **CSV export is done in the browser** from the data the page already shows (trial balance,
  income statement, balance sheet), so it needs no separate download endpoint or token handling.
  Text cells that a spreadsheet would run as a formula (starting with = + - @) are prefixed with
  an apostrophe; numbers stay numeric. Files are UTF-8 with a byte-order mark for Excel.

## Not built
Statement layouts that group accounts (current / non-current, operating / other), comparative
periods, regulatory returns (SASRA / CBK formats), year-end close to retained earnings, PDF.
