# ADR-0035: SMS notifications to members

- Status: Accepted (increment 35, 2026-11-24). No live SMS provider is connected; that needs the
  provider decision and approval. The Swahili wording is a draft for review by the business.

## Decision
- **The message table is its own outbox.** `notify.sms_message` is written in the transaction of
  the event it reports, so a message exists if and only if the event committed:
  - an M-Pesa receipt applied to a member's savings account or loan (C2B or STK);
  - the end-of-day close: instalments due in three days, and instalments due that day still
    unpaid, sent the next morning at the tenant's reminder hour (default 08:00);
  - a PIN setup approved (how to choose the PIN), and a PIN set, changed or locked on USSD.
  Queueing runs in a savepoint: a fault in SMS code is logged and never undoes the payment, close
  or PIN change. One message per event (dedupe key); nothing is ever deleted.
- **Sending.** The integration runtime claims due messages (`/api/v1/notifications/sms/claim`,
  role INTEGRATION), sends each through an `SmsGateway`, and reports sent or failed; the provider's
  delivery report comes back to `/sms/delivery/<token>/<tenant>/<message>` (source ranges and a
  secret path token) and is forwarded to core. Core decides retries: back-off of 1, 5, 15 and 60
  minutes, 5 attempts (`mfi.sms.max-attempts`), then FAILED. A claim never reported (the sender
  stopped) is queued again after 5 minutes (`mfi.sms.stale-claim`), so a member may rarely get a
  message twice; that is preferred to never. A polling sender was chosen over another RabbitMQ
  route so that retries, back-off and crash recovery live in one place.
- **Provider-neutral.** `HttpSmsGateway` speaks a small JSON contract implemented by the TEST-ONLY
  simulator; a thin adapter for the chosen provider replaces it. API key and delivery token come
  from the secret store. Message bodies are never logged.
- **Content.** English and Swahili templates; plain printable text only (the database refuses
  anything else); at most two SMS segments, payment alerts one. Account numbers are masked
  (`*1234`); messages never contain a PIN or ID number. Each starts with the tenant's signature.
- **Members' choices**, recorded by staff at the member's request: language, payment alerts,
  loan reminders. A message the member opted out of is recorded as not sent (SUPPRESSED) so staff
  can see it. Security (PIN) alerts are always sent. Reminders are not sent to members who are not
  active.
- **Per tenant, off by default.** A supervisor switches SMS on with a signature, sender ID, USSD
  code and reminder hour. While off, nothing is queued.
- **Staff views.** SMS panel on the customer page (messages with status, choices); SMS page for
  operations and supervisors (settings, counts for seven days, messages by status).

## Not built yet (needs decisions)
- The SMS provider (and its sender ID registration, delivery-report source ranges and cost), and
  whether members may opt out of payment alerts under the institution's terms.
- Review of the Swahili wording; SMS for teller cash transactions; resend of a failed message;
  members changing their own choices on USSD.
