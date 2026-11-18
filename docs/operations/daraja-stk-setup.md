# Runbook: M-Pesa STK Push setup (per tenant paybill)

Prerequisite: the paybill is already set up for C2B (see `daraja-c2b-setup.md`).

1. **Daraja app** (Safaricom developer portal / go-live): obtain the consumer key and secret and the
   Lipa na M-Pesa Online passkey for the short code. Store them in the secret store only - never
   in chat, tickets, or the repository.
2. **Integration runtime configuration** (environment / secret store):
   - `MFI_DARAJA_BASE_URL` - the Daraja API base URL for the environment (https).
   - `MFI_DARAJA_CONSUMER_KEY`, `MFI_DARAJA_CONSUMER_SECRET`.
   - `MFI_DARAJA_CALLBACK_BASE_URL` - the public https URL of the integration runtime.
   - per paybill: `mfi.daraja.paybills[n].passkey` (from the secret store), alongside the existing
     token, tenant id and short code.
   - Never set `mfi.daraja.allow-insecure-test-endpoint` outside test environments.
3. **Network**: allow outbound https to Daraja from the integration runtime; inbound callbacks are
   already restricted to Safaricom source ranges (`mfi.daraja.allowed-cidrs`).
4. **Broker**: the runtime declares the durable queue `mfi.integration.mpesa-stk-requests` bound to
   `mfi.events` for `MpesaStkPushRequested.#` on first connection.
5. **Smoke test (sandbox only)**: request a KES 1 prompt to a sandbox test number for a test
   account; confirm the request goes PENDING -> DISPATCHED -> SUBMITTED -> COMPLETED and the account
   is credited once. Do not test against live customers.

## Daily checks until the reconciliation job exists (ADR-0011 gap)
- Requests in PENDING for more than 10 minutes: the event was not dispatched (broker or core
  outage). Ask the staff member to issue a new request; the old one never prompted the customer.
- Requests in DISPATCHED or SUBMITTED for more than 10 minutes: outcome unknown. Check the
  Daraja portal or STK query for the CheckoutRequestID before telling the customer anything; if
  the customer paid, the C2B confirmation (if configured) or a late callback will credit once.

## Closing flagged requests (ADR-0011, ADR-0013)
`GET /api/v1/payments/mpesa/stk-requests/attention` lists requests needing a person. For
`PAID_AWAITING_RECEIPT`, `RECONCILE_EXHAUSTED` or `SEND_OUTCOME_UNKNOWN`, look the payment up in the
M-Pesa org portal and propose PAID (receipt + amount) or NOT_PAID with the evidence via
`POST /api/v1/payments/mpesa/resolutions`; a different supervisor approves. A PAID approval credits
the customer exactly once, even if the receipt also arrives later by callback or C2B.
