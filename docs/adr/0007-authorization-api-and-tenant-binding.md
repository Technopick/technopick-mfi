# ADR-0007: Authorization API and per-transaction tenant binding

- Status: Accepted (increment 5, 2026-11-18)

## Decision
- Every database transaction binds its tenant with `SET LOCAL app.tenant_id` (`TenantSession`),
  so PostgreSQL RLS filters all reads and writes. The setting is transaction-local and cannot
  leak through the connection pool. Binding outside a transaction throws.
- Flyway runs as the schema owner; the application connects as `mfi_app` (no BYPASSRLS).
- Staff and customer APIs take the tenant only from the validated JWT claim (`tenant_id`).
- The switch-facing authorization API (`/api/v1/authorizations`, role SWITCH, mTLS in
  production) accepts a tenant chosen by the switch from authenticated terminal/participant/BIN
  routing tables. It is never accepted from any other caller.
- Authorize, complete and reverse are each one transaction holding the account (and hold) row
  lock; each is idempotent: authorize on the ISO duplicate key, complete and reverse on the
  hold id (deterministic journal entry ids).
- Money crosses the API as decimal strings, never JSON numbers.

## Verified (CI, PostgreSQL 17 via Testcontainers)
Approve, idempotent replay, insufficient funds, cross-tenant ACCOUNT_NOT_FOUND, completion
(with amount check and replay), reversal (with replay), ledger netting to zero, tenant B teller
404, 401 without token, 403 with wrong role, and 10 concurrent 700 requests on 1,000 giving
exactly one approval.

## Open
Declined authorizations are not yet journaled (switch transaction journal, Phase 6); hold
expiry job not yet wired; OpenAPI contract (springdoc) to be generated in increment 6.
