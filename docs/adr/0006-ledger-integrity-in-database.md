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

## Addendum (V2, 2026-11-18): deposit accounts and holds
- GL control accounts flagged `requires_subledger` only accept lines naming a deposit
  account, and subledger lines must post to that account's control GL - so the savings
  subledger reconciles to the control account by construction (test H15).
- Holds: ACTIVE -> CAPTURED | RELEASED | EXPIRED, terminal states final, CAPTURED only with
  an entry that debits the held account, no delete/truncate.
- Available balance (ledger + overdraft - active holds) can never go negative: authorization
  holds are checked on insert and debits at commit, both under a `FOR NO KEY UPDATE` lock on
  the account row. Earmark and legal holds may exceed available but still block debits.
- Verified: 15 functional checks + 2 two-session race tests; a mutation run without the row
  lock lets both racing holds through (1,400 held on 1,000) and the race test fails.
- Known gap: lowering an overdraft limit is not yet re-checked against existing holds
  (to be enforced with the maker-checker limit-change flow).

## Addendum (V3, 2026-11-18): loan products, loans and repayments
- Product versions: created DRAFT, activated only by a checker different from the maker,
  then frozen (ACTIVE -> RETIRED only). Loans pin a version and must be inside its limits.
- Loans: approved by someone other than the creator; terms never change; APPROVED -> ACTIVE
  only with this loan's own disbursement entry debiting principal in full.
- Schedules, repayments and allocations are insert-only; every schedule version sums to the
  principal; allocations + unallocated = payment; a provider receipt is applied once; a
  repayment must equal its journal entry.
- Verified: 19 checks (L1-L19), incl. loan principal reconciling to GL principal receivable;
  mutation with the allocation check disabled makes L15 fail.

## Addendum (V4, 2026-11-18): business date and end of day
- Business date advances one day at a time and only after a COMPLETED EOD run; completed runs
  are final; accrual/penalty records only for the current date while its run is RUNNING.
- Accrual and penalty records are tied to this loan's own entry for that event, agree with
  the posted amounts, and accrual never exceeds the installment's scheduled interest.
- Arrears snapshots are insert-only with bucket consistent with DPD; PAR view per branch.
- Verified: 13 checks (E1-E13); mutation with the EOD-completion check disabled makes E1 fail.
