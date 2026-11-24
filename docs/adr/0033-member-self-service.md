# ADR-0033: Member self-service API and member tokens

- Status: Accepted (increment 33, 2026-11-24). Member enrolment, login and OTP depend on the
  identity provider decision, which is still open.

## Decision
- **Separate role and API.** A member signs in with a token whose roles hold `MEMBER` and whose
  `customer_number` claim (configurable: `mfi.security.member-claim`) names their own customer
  record. Such a token gets only `ROLE_MEMBER`, whatever other roles it claims. It reaches only
  `/api/v1/member/**`; every other API refuses it, including endpoints that only require an
  authenticated caller. Staff tokens are refused on the member API.
- **Only their own data.** Every member call looks the member up from the token (never from the
  request), refuses a member who is not ACTIVE, and answers "not found" for any account, loan or
  payment that is not theirs, so a member cannot learn that someone else's number exists.
  Endpoints: overview (`/me`: accounts, loans, next due), savings statement, loan schedule and
  payoff quote, payment prompt and its status.
- **Paying by M-Pesa prompt (STK push).** The prompt always goes to the member's registered phone,
  never a number in the request, and only for their own savings account or loan, in whole
  shillings. The database enforces this (V30): an STK request linked to a member must be from an
  active member, to their phone, for their own account or loan, and the link never changes. The
  member's idempotency keys are namespaced by customer, so they can never replay another member's
  request. Payment application is the existing STK and C2B path (ADR-0013 onwards).
- **Limits.** At most 3 new payment prompts per member in 10 minutes, counted in the database so
  the limit holds across servers (`mfi.member.payment-prompts-per-window`,
  `mfi.member.payment-prompt-window-minutes`); replays are always answered. A per-member request
  limit (`mfi.member.requests-per-minute`, default 60) is kept in memory on each server, so with N
  servers the effective limit is up to N times higher; the gateway should also rate-limit. Both
  answer 429 with Retry-After.
- **Audit.** Every member request, including reads, is in the request audit trail under
  `member:<subject>`, the same actor recorded on the payment prompt.
- **Every endpoint declares its roles.** An architecture test now fails the build if any
  controller method has no `@PreAuthorize` on it or its class.

## Not built yet (needs decisions)
- Enrolment, login and OTP for members, and how the identity provider issues the
  `customer_number` claim; a separate audience for member tokens is recommended.
- The USSD gateway and mobile app themselves, and consent wording for self-service.
