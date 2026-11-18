# ADR-0013: Operations close-out of unsettled M-Pesa items

- Status: Accepted (increment 12, 2026-11-18)
- Context: some STK and B2C items cannot be settled by the system: the status query says "paid" but
  has no receipt, a B2C request timed out in Safaricom's queue, or a send outcome was never known.
  People settle these from the M-Pesa org portal; that must not become a back door.

## Decision
- An OPERATIONS user proposes an outcome for one item: **PAID** (receipt / transaction id and
  amount, required) or **NOT_PAID** (no receipt or amount), with written evidence (at least 10
  characters, e.g. the portal statement line). A SUPERVISOR who is not the proposer approves or
  rejects. Database-enforced (V13): different people, one pending-or-approved proposal per item,
  PAID/NOT_PAID field rules, decisions final, nothing deleted.
- **Approval applies the outcome through the normal provider paths**, not a separate posting:
  - STK PAID -> the STK result path with the receipt: credited exactly once (a receipt already
    applied via C2B is a replay). STK NOT_PAID -> FAILED `MANUAL`; a later genuine success still
    wins.
  - B2C PAID -> the B2C result path: posted and the loan activated only if the amount matches the
    approved amount (otherwise the AMOUNT_MISMATCH alarm, as for a callback). B2C NOT_PAID ->
    FAILED `MANUAL`; the loan stays APPROVED and a new disbursement may be requested; a later
    genuine success raises SUCCESS_AFTER_FAILURE.
- What can be proposed: STK requests that are DISPATCHED / SUBMITTED (PAID also for FAILED, since a
  receipt proves payment); B2C disbursements DISPATCHED / SUBMITTED flagged TIMEOUT_UNKNOWN or
  SEND_OUTCOME_UNKNOWN. **AMOUNT_MISMATCH and SUCCESS_AFTER_FAILURE are refused here**: money left in
  a way the ledger does not yet reflect, which needs a finance-owned adjusting entry (not built).
- API: `POST /api/v1/payments/mpesa/resolutions`, `GET` (pending), `POST /{id}/approve|reject`.
- Stable OpenAPI operation ids (`Controller_method`) were introduced at the same time; the contract
  test now refuses generator-numbered ids, which shifted whenever a controller was added.

## Not done yet
Finance adjusting-entry flow for AMOUNT_MISMATCH / SUCCESS_AFTER_FAILURE; B2C transaction status
query; staff web UI for these lists and actions (APIs only today).

## Verified (CI)
Core (PostgreSQL 17): missing receipt and weak evidence refused, one live proposal per item,
proposer cannot approve even with the supervisor role, operations cannot approve, STK PAID credits
once, decided proposal cannot be decided again, rejected then approved NOT_PAID closes the
request without money, B2C proposal refused until the item is flagged, B2C PAID after a queue
timeout posts and activates the loan, nothing left pending. Database: 3 checks (M0-M5).
