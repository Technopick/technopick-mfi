# Runbook: M-Pesa B2C loan disbursement setup (per tenant)

**Approval needed before any live use**: connecting to Daraja production and sending real money
require the business's explicit go-ahead (build rules). Start in the Daraja sandbox.

1. **Safaricom**: B2C short code (utility account), an API initiator user with the B2C role, and
   the initiator password. Download Safaricom's public certificate for the environment (sandbox and
   production certificates differ). Store the initiator password in the secret store only.
2. **Core banking** (platform administration): insert the tenant's `payments.mpesa_b2c_account`
   (short code, branch, B2C clearing GL account - the float asset, `max_amount`).
3. **Integration runtime** (environment / secret store):
   - `mfi.daraja.b2c-accounts[n]`: token (unguessable, for the callback path), tenant id, short code,
     initiator name, initiator password.
   - `MFI_DARAJA_CERTIFICATE_PEM`: Safaricom's certificate (PEM).
   - Daraja base URL, consumer key/secret and callback base URL as for STK (https only).
4. **Broker**: the runtime declares `mfi.integration.mpesa-b2c-disbursements`, bound to
   `MpesaDisbursementApproved.#`.
5. **Sandbox smoke test**: request a disbursement for a test loan to a sandbox number; approve as a
   different user; confirm REQUESTED -> APPROVED -> DISPATCHED -> SUBMITTED -> COMPLETED, the loan
   ACTIVE, and the journal lines (principal / B2C clearing / fee).

## Daily operations
- `GET /api/v1/payments/mpesa/b2c/disbursements/attention` (OPERATIONS, SUPERVISOR):
  - `TIMEOUT_UNKNOWN`, `SEND_OUTCOME_UNKNOWN`: check the M-Pesa org portal by ConversationID /
    OriginatorConversationID (= disbursement id), then propose PAID (transaction id + amount) or
    NOT_PAID with the evidence via `POST /api/v1/payments/mpesa/resolutions`; a different supervisor
    approves (ADR-0013). **Do not request a new disbursement for the loan until it is resolved** -
    the system blocks it while the item is live.
  - `AMOUNT_MISMATCH`, `SUCCESS_AFTER_FAILURE`: money left but was not posted. Escalate to finance
    the same day; the ledger and the B2C float will differ until resolved.
- Reconcile the B2C utility float against the ledger's B2C clearing account daily (manual until
  automated; Safaricom charges are not yet booked automatically).
