# ADR-0039: M-Pesa B2C transaction status queries and float checks

- Status: Accepted (increment 39, 2026-11-25). Request and answer shapes follow the public Daraja
  documentation as commonly described and are exercised only against the TEST-ONLY simulator; they
  must be confirmed in the Daraja sandbox (secrets still pending) before production. Float
  thresholds, the daily check hour and who may change them are finance decisions.

## Context
A loan payout by B2C can end with no usable result: the request may never have reached M-Pesa
(SEND_OUTCOME_UNKNOWN), M-Pesa's queue may have timed out (TIMEOUT_UNKNOWN), or the result
callback may simply not arrive. Until now operations closed these out by hand from the M-Pesa
portal (ADR-0013). Separately, nothing told anyone when the B2C float was running low, so approved
payouts could fail for insufficient funds.

## Decision
- **Status queries** (core table `payments.b2c_status_query`, V36): the integration runtime asks
  core every minute what is due. Core opens a query automatically for a disbursement whose outcome
  is unknown (the two attention codes above, or SUBMITTED with no result for 10 minutes), at most
  5 automatic queries per disbursement, 30 minutes apart; operations or a supervisor can ask at any
  time. One open query per disbursement; queries without an answer in 15 minutes lapse.
- The runtime sends Daraja's Transaction Status request, naming the payment by its transaction id
  when known and otherwise by our OriginatorConversationID (the disbursement id). The answer comes
  back to a callback URL that carries the query id; it is stored before acknowledging and
  forwarded to core with retries, carrying only the result code, status, receipt and amount (no
  party names or numbers).
- **Only a confirmed payment acts**: result 0, status Completed, a receipt and an amount. It goes
  through the normal B2C result path, so the amount must equal what was approved (otherwise the
  disbursement is flagged AMOUNT_MISMATCH and nothing is posted) and the loan is activated by its
  own entry. Any other answer, including "not found", is kept as evidence for the close-out; a
  disbursement is never failed from a query answer, because a payment may still complete later.
- **Float checks** (`payments.b2c_balance_check`, `payments.b2c_float_settings`, V36): once a day
  after the configured hour (tenant time, default 07:00) and on demand, the runtime sends Daraja's
  Account Balance request; core reads the AccountBalance text, takes the configured sub-account
  (default "Utility Account"), and records with it the ledger clearing balance (re-derived by the
  database) and the total of disbursements approved but not yet paid.
- **Alerts** on the B2C float page: approved-but-unpaid exceeds the float, float below the finance
  threshold, float differs from the ledger clearing account, balance older than 26 hours, last
  check failed, no balance yet.
- Database rules: queries only for DISPATCHED or SUBMITTED disbursements, forward-only states,
  answers complete and final, one open query or check at a time, threshold not negative, hour
  0-23, answered balances carry the re-derived ledger balance; nothing is deleted; tenant RLS.

## Consequences
- Most unknown outcomes now resolve themselves within minutes; the manual close-out stays for
  answers that do not confirm a payment.
- The ledger clearing account only matches the float once top-ups and charges are booked (manual
  journals); the difference alert makes missing bookings visible, and the statement
  reconciliation (ADR-0038) is where they are traced.
- Paybill balance checks are not built (paybill configuration has no initiator credentials yet).
- No SMS or e-mail alert is sent for low float; it is shown on the page and the API.
