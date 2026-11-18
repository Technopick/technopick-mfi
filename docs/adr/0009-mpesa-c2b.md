# ADR-0009: M-Pesa C2B (paybill) - callbacks, application and suspense

- Status: Accepted (increment 8, 2026-11-18); validation-on-error default needs business confirmation

## Decision
- Callback URL per tenant paybill: `https://<integration-host>/daraja/c2b/<secret-token>/{validation|confirmation}`.
  A callback is trusted only if it comes from a configured Safaricom source range AND carries a
  configured token; the tenant comes from the token; the payload short code must match the
  token's paybill. Unknown token -> 404, disallowed source -> 403.
- Validation: synchronous ask to core (`/api/v1/payments/mpesa/c2b/validation`) behind a
  Resilience4j circuit breaker with a 3 s timeout. Core answers 0 (accept), C2B00012 (unknown
  or closed/blocked account), C2B00013 (amount over the paybill maximum), C2B00015 (unknown
  short code). If core cannot be asked, the configured fallback applies: REJECT (C2B00016) by
  default, so a customer is never debited for a payment we could not check.
- Confirmation: stored durably in `integration.provider_callback` (unique per TransID) BEFORE
  acknowledging Daraja; duplicates are acknowledged without a second row. A background route
  applies stored callbacks in core, one per short transaction (SKIP LOCKED), retrying with back-off
  up to `max-attempts`; after that the callback stays FAILED as a dead letter for operator replay.
- Core applies each TransID at most once per tenant: credit the matching deposit account
  (Dr paybill clearing / Cr savings control with subledger), otherwise Dr clearing / Cr suspense
  as UNMATCHED. Money is always on the ledger; suspense clearance is a later maker-checker flow.
- Only a masked MSISDN (first 4, last 3) is forwarded to core; the raw callback stays in the
  integration store.

## Verified (CI)
Core (PostgreSQL 17): all four validation codes, apply once, replay returns the same payment,
unmatched to suspense (suspense balance 200.00), outbox events, role check.
Integration (PostgreSQL 17 + stub core): wrong token 404, foreign source 403, mismatched short
code refused and not stored, duplicate confirmation stored once, core outage -> FAILED then
APPLIED on retry with exactly one core call, bearer token used, full MSISDN never forwarded,
validation fallback C2B00016 when core is down.

## Open
STK Push and B2C (next increment); loan repayment by loan number; suspense clearance UI;
dead-letter replay endpoint; Safaricom source ranges and production registration.
