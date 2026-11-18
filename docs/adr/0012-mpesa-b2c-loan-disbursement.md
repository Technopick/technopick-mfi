# ADR-0012: Loan disbursement by M-Pesa B2C

- Status: Accepted (increment 11, 2026-11-18). Built and tested against the TEST-ONLY simulator
  only; no connection to Daraja sandbox or production has been made.
- Context: money leaves the institution. The main risks are paying twice, paying without the
  ledger knowing, and activating a loan that was never paid out.

## Decision
- **Maker-checker.** A LOAN_OFFICER or OPERATIONS user requests the disbursement of an APPROVED loan
  to a phone number; a BRANCH_MANAGER or SUPERVISOR who is not the requester approves or rejects.
  The database enforces the different-person rule, that only APPROVED loans can be disbursed, and
  **at most one live disbursement per loan** (a new one only after a definitive failure or a
  rejection). Only approval emits an event (`MpesaDisbursementApproved`, request id only).
- **Amount** = principal less the product's upfront fee, in whole shillings (B2C pays whole
  shillings; a fee that leaves cents is refused with a clear message), within the B2C account limit.
- **At-most-once send.** The integration runtime claims the approved disbursement in core
  (APPROVED -> DISPATCHED) before calling Daraja; a redelivered event gets 409 and sends nothing.
  Our disbursement id is the OriginatorConversationID, and the result and timeout URLs carry it;
  a callback whose OriginatorConversationID differs from the URL is refused.
- **SecurityCredential**: the initiator password encrypted with Safaricom's certificate (RSA,
  PKCS#1 v1.5) at send time; initiator credentials and the certificate come from configuration and
  the secret store, and are never logged (`toString` hides them).
- **Failure only on definitive answers**: HTTP 4xx / non-zero ResponseCode / failure to prepare the
  request (credential or token) = not sent -> FAILED; a result with non-zero ResultCode -> FAILED.
  Timeouts, 5xx, and the queue-timeout callback = **unknown**: the disbursement is flagged for a
  person (`TIMEOUT_UNKNOWN`, `SEND_OUTCOME_UNKNOWN` after 10 minutes) and **never resent
  automatically**.
- **Posting and activation only on a matching success**, in one transaction: Dr loan principal
  receivable (gross), Cr B2C clearing (net paid), Cr fee income (fee) via the existing disbursement
  posting rule; loan APPROVED -> ACTIVE linked to that entry. The database refuses COMPLETED unless
  the loan was activated by this disbursement's own entry.
- **Alarms instead of guesses**: a success for a different amount is not posted (`AMOUNT_MISMATCH`);
  a success after a recorded failure is not posted (`SUCCESS_AFTER_FAILURE`, logged at ERROR).
  Both appear in the attention list for operations.
- Lifecycle (database-enforced): REQUESTED -> APPROVED | REJECTED; APPROVED -> DISPATCHED ->
  SUBMITTED -> COMPLETED | FAILED; DISPATCHED -> COMPLETED | FAILED. COMPLETED/REJECTED final;
  FAILED final except raising the alarm. Phone number erased when final. Nothing deleted.

## Not done yet (known gaps)
- B2C transaction status query (needs its own async result flow) to settle unknown outcomes
  automatically; until then operations settle flagged items from the M-Pesa org portal.
- Booking Safaricom's B2C charges and reconciling the B2C utility float balance
  (`B2CUtilityAccountAvailableFunds` is received but not yet used).
- A procedure (and API) for operations to close an AMOUNT_MISMATCH / SUCCESS_AFTER_FAILURE item
  with maker-checker; disbursement to bank accounts; loan origination API (loans are created by
  other means today).
- Finance sign-off on posting the fee to income at disbursement (IFRS 9 question already open).

## Verified (CI)
Core (PostgreSQL 17): roles, requester cannot approve even with the approver role, one live
disbursement per loan, dispatch only after approval and only once, event without phone number,
ConversationID mismatch refused, success posts exact lines (D principal 100,000.00 / C B2C
clearing 98,000.00 / C fee 2,000.00) and activates the loan, replay and different-transaction
replay, failure keeps the loan APPROVED and allows a new request, rejection, success after failure
and amount mismatch raise alarms without posting, timeout flagged, attention list, tenant
isolation. Database: 7 checks (B0-B7). Integration (PostgreSQL + RabbitMQ + simulator over HTTP):
credential decrypted and accepted by the simulator, paid / insufficient float / queue timeout /
duplicate result / refusal / provider 500, redelivered event sends nothing, misrouted callback
refused, receiver details not forwarded.
