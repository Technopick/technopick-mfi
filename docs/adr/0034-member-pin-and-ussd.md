# ADR-0034: Member PIN and the USSD channel

- Status: Accepted (increment 34, 2026-11-24). The aggregator, short code, PIN policy and
  consent wording for self-service need business and compliance sign-off before any live use.

## Decision
- **The member proves who they are with their registered phone and a PIN.** The aggregator
  reports the phone number; exactly one active member must have it, or the session is refused.
  A 4-digit PIN is checked once per session.
- **Staff never see or choose a PIN.** A teller, branch manager or operations user asks for a PIN
  setup (first time) or reset, with how the member was identified; a branch manager or supervisor
  who did not ask approves it. Approval opens a window (24 hours by default,
  `mfi.ussd.pin-setup-hours`) in which the member, from their registered phone, types their ID
  number and chooses a PIN twice. Any earlier PIN stops working at approval.
- **PIN rules.** 4 digits; not all the same, not a straight sequence, not the birth year, not the
  end of the phone number. Stored only as a bcrypt hash (cost 10). 3 wrong PINs, or 3 wrong ID
  numbers during setup, lock it (`mfi.ussd.max-pin-attempts`); a correct PIN clears the count. A
  locked PIN needs an approved reset. Members can change their PIN in the menu.
- **The database enforces** (V31): setups are maker-checker and only for active members, one open
  request per member; a PIN window opens only from an approval made in the last ten minutes for
  that member and lasts at most 7 days; only bcrypt hashes can be stored; a locked PIN stays locked
  until a new approved setup; a USSD session keeps its phone and member; channel events are never
  changed. Nothing is ever deleted.
- **Menus**: balances, mini statement (last 5 movements), loans (next instalment and amount to
  clear today), pay a loan, deposit to savings, change PIN. Payments are M-Pesa prompts through the
  member service (ADR-0033): the member's own phone, own account or loan, whole shillings, the
  prompt limit, and one payment per session (the session id is the idempotency key). Screens stay
  within 182 characters.
- **Sessions** are refused after 180 seconds (`mfi.ussd.session-max-seconds`) or 200 requests.
- **Integration runtime**: `/ussd/<token>` accepts the form callback (sessionId, serviceCode,
  phoneNumber, text) used by Kenyan aggregators, only from configured source ranges and with a
  secret path token that names the tenant and service code, and forwards it to core
  (`/api/v1/channels/ussd/sessions`, role INTEGRATION). If core cannot answer, the member gets a
  polite END. The typed text holds the PIN and ID number, so it is never logged or stored.
- **Audit**: each session and what happened in it (signed in, wrong PIN, locked, PIN set or
  changed, views, payment prompts and refusals) is recorded in `member.ussd_event`, never with
  the PIN or ID number, and shown on the member's customer page. Staff PIN actions are in the
  staff audit trail.
- **TEST-ONLY handset simulator** in the simulator image, used by the browser journey.

## Not built yet (needs decisions)
- Which aggregator (and its callback source ranges, contract and short code), and whether the
  member also gets an SMS when a PIN is set, changed or locked (needs an SMS provider).
- Daily limits on USSD payments beyond the prompt rate limit; Swahili menus; SIM-swap checks with
  the mobile network before allowing PIN setup (recommended for production).
