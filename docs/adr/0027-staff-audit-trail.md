# ADR-0027: Tamper-evident audit trail of staff actions

- Status: Accepted (increment 27, 2026-11-20)

## Decision
- **What is recorded:** every staff change (POST, PUT, PATCH, DELETE) and every read of
  customer, account, loan or audit data, whatever the outcome - refused (403) and failed requests
  included. Each record holds the tenant, time, actor, roles, method, route template, actual path
  (with its identifiers), HTTP status, duration, client address and request id (`X-Request-Id`,
  echoed in the response).
- **What is not recorded:** request bodies, and the values of query parameters other than dates,
  status and branch (a customer search by ID number is recorded as `q=***`). Data protection:
  the trail says who looked at which record, without copying the personal data itself.
- **Where it runs:** inside the security filter chain, after authorization, so the caller is
  known; written in its own transaction after the request, so a rolled-back business change is
  still recorded as attempted. Integration callbacks are not staff actions and keep their own
  stored record (`provider_callback`).
- **Tamper evidence (V24):** append-only for the application; the database chains each tenant's
  rows with SHA-256 over the previous hash and the row, in the order rows take a per-tenant lock.
  `audit.verify_chain` names the first record that no longer verifies, so a row edited or
  removed by someone with database rights is detected (tested by editing a row with the
  triggers disabled).
- **Who reads it:** SUPERVISOR, searching by staff member, path and refused-only, with a chain
  check on the page. Reading the trail is itself recorded.

## Limits and decisions needed
- If writing the audit row fails after the response, the failure is logged as
  `AUDIT_WRITE_FAILED` (to alert on) but the request is not undone. Making the business change
  depend on the audit write is possible for writes; it is a trade-off for the business to choose.
- Behind a reverse proxy the recorded address is the proxy's until trusted proxy headers are
  configured per environment.
- Retention period, export to an external log store / SIEM, and whether an AUDITOR role separate
  from supervisors should read the trail.
