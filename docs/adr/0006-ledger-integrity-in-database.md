# ADR-0006: Ledger integrity enforced in PostgreSQL, not only in Java

- Status: Accepted
- Date: 2026-11-18

## Decision
`V1__tenancy_and_ledger.sql` enforces, inside the database transaction:
balance per currency and branch (deferred constraint triggers), immutability of posted
entries and lines (UPDATE/DELETE/TRUNCATE triggers + no grants), open-period check,
per-tenant idempotency keys, single and mirrored reversals, and row-level security with
FORCE so even the table owner is filtered. The app connects as `mfi_app`
(NOSUPERUSER, NOBYPASSRLS), with SELECT/INSERT only on journals.

Java (`JournalEntry`) validates the same rules first for fast, clear errors; the database
is the final guard against bugs, retries and direct SQL.

## Verified (2026-11-18, PostgreSQL 16.15)
20 checks pass (`src/test/sql`), and a mutation run with the balance triggers disabled
makes T2 fail, proving the tests detect the defect they target.

## Consequences
- Superusers bypass RLS: production must never connect the app as a superuser.
- `btree_gist` must be installed by the DBA if the migration role cannot create extensions.
