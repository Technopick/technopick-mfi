# ADR-0028: Load and failure testing as recorded evidence

- Status: Accepted (increment 28, 2026-11-23)

## Decision
- **A repeatable test, not a one-off benchmark.** `PerformanceLoadTest` (JUnit tag `performance`)
  seeds a synthetic TEST-ONLY book (default 50,000 members with savings accounts, 20,000 active
  loans with 240,000 instalments) and drives the real application in-process. It is excluded from
  normal builds and runs from the manual `performance` workflow, which uploads the report. Book
  size and traffic are workflow inputs.
- **Every phase checks correctness as well as time.** Concurrent tellers and M-Pesa confirmations
  on the same accounts must leave each balance equal to its opening balance plus the accepted
  operations, with zero errors; tills must count to the cent. A 16-copy burst of one TransID must
  produce one posting (concurrent copies may get `409 DUPLICATE_IN_FLIGHT`, and a retry returns
  the original). End of day with a failure injected half-way through the loan book must stop as
  FAILED without moving the date, then resume with one arrears snapshot per loan, no duplicate
  postings, no reconciliation differences and a zero trial balance.
- **Crash windows in the integration runtime** are covered by integration tests run on every push:
  a response lost after core applied the payment (re-sent with the same TransID), a node dying
  while holding a callback (the lock goes with its connection; another node applies it), and two
  nodes draining a backlog (each callback sent once).
- **End of day reports step timings** (`timings` in the summary, logged, shown on the staff
  End of day page), so production runs give the same evidence.
- **Fix found by the test (V25):** the deferred accrual-entry check was quadratic. It now runs once
  per entry per transaction, which is equally strict because deferred checks fire after all rows
  are written. Database test J proves a stale or forged marker cannot skip it.

## What the figures do and do not show
- Measured on a 4-CPU GitHub runner with the database in a container on the same machine:
  about 300 postings per second with 8 concurrent clients, and an end of day of about 1.6 minutes
  (3.7 minutes at month end) for this book. See `docs/operations/performance/`.
- HTTP transport, TLS, the identity provider, RabbitMQ and network latency to a separate database
  server are not included. Production sizing needs the same test on production-like hardware.
- Remaining end-of-day cost is per-item work: about 3.7 ms per loan and 2.7 ms per account
  credited at month end, each in its own transaction. Acceptable for this book; batching is the
  next step if books grow tenfold.
