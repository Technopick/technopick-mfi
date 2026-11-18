# ADR-0011: M-Pesa STK Push (Lipa na M-Pesa Online)

- Status: Accepted (increment 10, 2026-11-18)
- Context: staff need to send a payment prompt to a customer's phone for a deposit or a loan
  repayment. The provider is asynchronous: the request is accepted, the customer acts on their
  phone, and the result arrives later by callback, possibly twice, possibly before we have
  recorded that the request was accepted, and possibly not at all.

## Decision
- **Core owns the request** (`payments.stk_request`, V10). Staff (TELLER, LOAN_OFFICER, OPERATIONS)
  create it with an idempotency key; core checks the phone number (Kenyan MSISDN, normalised to
  2547/2541...), whole-shilling amount within the paybill maximum, and that the target is a deposit
  account that accepts credits or an ACTIVE loan. Core writes an outbox event
  `MpesaStkPushRequested` carrying **only the request id** (no personal data in the broker).
- **At-most-once send over at-least-once delivery.** The integration runtime consumes the event
  from RabbitMQ and claims the request in core (`/dispatch`, PENDING -> DISPATCHED). Only the
  first claim succeeds and only it receives the phone number; a redelivered event gets 409 and is
  dropped, so a customer is never prompted twice for one request.
- **Callback URL carries our request id**: `https://<host>/daraja/stk/<paybill-token>/callback/<requestId>`.
  Same authentication as C2B (source range + secret token -> tenant). A result can therefore be
  matched even if the integration runtime stopped before reporting the CheckoutRequestID.
- **Outcome classification when sending.** HTTP 4xx, a non-zero ResponseCode, or failure to obtain
  an OAuth token = definitive refusal (no prompt shown) -> core `send-failure` -> FAILED. Timeouts
  and 5xx = UNKNOWN -> the request stays DISPATCHED for the callback or reconciliation; it is never
  marked failed on a guess, because the customer may still pay.
- **Results** are stored before acknowledgement (unique per CheckoutRequestID) and applied in core
  with retries and dead letters, as for C2B. Success applies the receipt through the same
  exactly-once path as C2B (`MPESA_STK` channel; account credit or loan repayment). A success
  result always wins, even over an earlier FAILED, because money has moved. If the provider reports
  a different amount than requested, the provider amount is applied and logged.
- **One receipt, one credit across channels.** A unique index on (tenant, receipt) across
  `MPESA_C2B` and `MPESA_STK` plus a cross-channel lookup means a receipt also reported as a C2B
  confirmation is replayed, not credited twice.
- **Data minimisation.** The full phone number is erased (database-enforced) once the request is
  final; only the masked number remains. The phone number in the provider callback is not
  forwarded to core.
- **Lifecycle (database-enforced):** PENDING -> DISPATCHED -> SUBMITTED -> COMPLETED | FAILED;
  DISPATCHED -> COMPLETED | FAILED; FAILED -> COMPLETED. COMPLETED is final; details and the
  CheckoutRequestID never change; nothing is deleted.
- **Test-only simulator.** `simulators/daraja-simulator` implements OAuth, STK Push (with password
  check), STK query and callbacks, scripted by phone-number suffix. It is a test-scope dependency
  only, its standalone mode needs `DARAJA_SIMULATOR_ACK=TEST_ONLY_NOT_FOR_PRODUCTION`, it binds to
  127.0.0.1, and its receipts start with `SIM`. The integration runtime refuses a plain-http Daraja
  or callback URL unless `mfi.daraja.allow-insecure-test-endpoint=true` (tests only).

## Reconciliation (increment 11, V11)
- Core housekeeping (every minute): PENDING older than 10 minutes -> FAILED `EXPIRED` (never
  dispatched, so no prompt was sent); DISPATCHED without a CheckoutRequestID older than 10 minutes
  -> flagged `SEND_OUTCOME_UNKNOWN` (cannot be queried).
- Integration reconciler (every minute): STK query for requests with no result after 3 minutes.
  A non-zero ResultCode closes the request as FAILED; "still processing" is counted and after 12
  checks the request is flagged `RECONCILE_EXHAUSTED`; ResultCode 0 flags `PAID_AWAITING_RECEIPT`
  and **credits nothing**, because the query has no receipt number - a late callback or a C2B
  confirmation of the receipt completes it exactly once.
- Operations list: `GET /api/v1/payments/mpesa/stk-requests/attention` (OPERATIONS, SUPERVISOR).

## Not done yet (known gaps)
- An operations action to complete a PAID_AWAITING_RECEIPT request with the receipt from the
  M-Pesa org portal (maker-checker).
- Daraja sandbox run and Safaricom go-live checklist; production callback source ranges.
- B2C (disbursements, refunds) - next increment, with its own maker-checker and reconciliation.

## Verified (CI)
Core (PostgreSQL 17): input validation, role checks, idempotent replay and key reuse conflict,
event carries no phone number, single dispatch, CheckoutRequestID mismatch refused, success
credits once, repeat result and the same receipt via C2B are replays, phone number erased,
cancelled result before the submission report, tenant isolation. Database: 6 checks (K0-K6).
Integration (PostgreSQL 17 + RabbitMQ 4 + simulator over HTTP + stub core): paid, cancelled,
duplicate callback stored and applied once, provider refusal reported as send failure, provider
500 not reported as failure, redelivered event does not prompt twice, callback authentication,
plain-http Daraja refused.
