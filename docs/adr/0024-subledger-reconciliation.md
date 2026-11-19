# ADR-0024: Subledger-to-control reconciliation

- Status: Accepted (increment 24, 2026-11-19)

## Decision
- **Six checks** compare a control account's ledger balance with the same figure built from the
  business records (natural sign, per GL account and currency):
  savings balances vs savings control; savings interest accrued less credited vs interest
  payable; loan principal disbursed less repaid and written off vs principal receivable; per
  installment interest accrued less paid, less written off vs interest receivable; penalties
  charged less paid and written off vs penalty receivable; credit held for customers vs loan
  overpayments.
- **Recorded at every end of day** (V22, immutable, written only during that day's run) and
  reported in the end-of-day result; **available live** at any time to FINANCE, SUPERVISOR and
  OPERATIONS (`GET /api/v1/accounting/reconciliation`, staff app Reconciliation page).
- **A difference does not stop end of day.** It is recorded, reported and highlighted; stopping
  the day would also stop customer service. Whether a difference should block end of day is a
  finance decision.

## Evidence
The API test closes three days with savings, loans, a write-off and a recovery with no
differences, then shows a stray 500 principal posting caught as a 500.00 difference. The browser
journeys (teller, M-Pesa payout, loan) end with every control account matching.

## Not built
Reconciliation of M-Pesa clearing and suspense against provider statements, till cash against
counts (covered by till closing), and automatic alerts beyond the outbox event payload.
