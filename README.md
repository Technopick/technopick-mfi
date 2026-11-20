# Technopick Microfinance Core Banking & Switching Platform

Multi-tenant core banking for Kenyan MFIs and SACCOs with an integrated ISO 8583 / ISO 20022
financial switch. Java 25, Spring Boot 4.1, Apache Camel 4.22 LTS, jPOS 3, PostgreSQL.

**Status: Phase 1, increment 1. Not production-ready.** See "What is verified" below.

## Layout

```
shared/canonical-model/   Money + standard-neutral transaction model (JDK only)
services/core-banking/    Modular monolith: accounting + lending domain, Flyway migrations
services/switch/          jPOS switch (Phase 1 increment 3: sign-on/echo vs simulator)
services/integration/     Camel runtime: outbox relay to RabbitMQ (provider routes next)
services/worker/          Batch / EOD / outbox relay
simulators/               TEST-ONLY simulators (daraja-simulator: OAuth, STK Push/query, B2C, callbacks)
infrastructure/           docker (local dev), CI, Linux + Windows Server deployment
docs/                     ADRs, architecture, switching, operations, compliance
```

## Build and test

Requires JDK 25 and Maven 3.9+ with access to Maven Central.

```bash
mvn -B verify                         # unit + architecture tests
mvn -B verify -Pjdk21-compat          # same, on a JDK 21 machine (dev only)
```

Ledger database invariants (needs a PostgreSQL 16+ admin connection):

```bash
# Linux/macOS, as a role that can CREATE DATABASE and CREATE ROLE
bash services/core-banking/src/test/sql/run-ledger-db-tests.sh
```

Local dependencies (development only, placeholder credentials):

```bash
docker compose -f infrastructure/docker/docker-compose.yml up -d
```

Windows Server service install (per deployable): see
`infrastructure/scripts/powershell/Install-MfiService.ps1` and ADR-0005.

## What is verified (2026-11-19, increment 17)

GitHub Actions on `main`: all four jobs green.

| Item | Result | Where |
| --- | --- | --- |
| `mvn verify` on JDK 25, `-Xlint:all -Werror` | BUILD SUCCESS | ubuntu-latest and windows-latest |
| Unit tests (JUnit 5) incl. seeded property tests, ArchUnit (5 rules), Daraja simulator | 91/91 on each OS | both runners |
| ArchUnit rules (framework-free domain, no float/double money, no module cycles) | 4/4 | both runners |
| Spring Boot 4 application end to end (HTTP, security, service, JDBC, PostgreSQL 17 with RLS) | 6 integration tests pass: lifecycle, auth/roles, 10-way concurrent overspend, hold expiry, OpenAPI contract drift | ubuntu (Testcontainers) |
| M-Pesa C2B: Daraja callbacks (token + source range auth, store-then-ack, retries, dead letters) and core application (exactly once, suspense when unmatched) | core: 1 end-to-end test; integration: 3 tests | ubuntu (Testcontainers + stub core) |
| Loan repayment by loan number via paybill (allocation, accrued vs unaccrued interest, overpayment, exactly once) and suspense clearance with maker-checker (to savings or loan) | core: 2 end-to-end tests | ubuntu (Testcontainers PostgreSQL 17) |
| M-Pesa STK Push: staff request, single dispatch, result applied once across STK and C2B, phone number erased when final | core: 1 end-to-end test; integration: 3 tests through outbox, RabbitMQ and the TEST-ONLY Daraja simulator over HTTP | ubuntu (Testcontainers PostgreSQL 17 + RabbitMQ 4) |
| STK reconciliation: expiry of undispatched requests, unknown sends flagged, STK status queries (paid-per-query never credited without a receipt) | core: 1 end-to-end test; integration: 1 test with the simulator | ubuntu |
| M-Pesa B2C loan disbursement: maker-checker, one live disbursement per loan, single send, post + activate only on a matching success, alarms for timeout / mismatch / success-after-failure | core: 1 end-to-end test; integration: 1 test through outbox, RabbitMQ and the simulator (encrypted initiator credential) | ubuntu |
| Operations close-out of unsettled STK / B2C items: proposer and approver differ, applied through the normal exactly-once paths | core: 1 end-to-end test | ubuntu |
| Loan origination: application with quote, maker-checker approval creating loan + schedule, loan numbering; full journey to B2C disbursement and paybill repayment | core: 1 end-to-end test | ubuntu |
| Customers and KYC: consent recorded, adults only, one per ID document, four-eyes verification, masked search; loans need a verified customer; payouts only to the registered M-Pesa number | core: 1 end-to-end test + journey; database: U0-U8 | ubuntu |
| Savings accounts and teller cash: open for verified customers, check-digit account numbers, deposits and withdrawals through the teller's own till, approval above policy thresholds (database-decided), statements from the ledger | core: 1 end-to-end test; database: D1-D7; browser: savings journey | ubuntu |
| Teller tills: one open till per teller, vault transfers approved by another person, closing count against the ledger, shortages/overages posted only after supervisor approval | core: 1 end-to-end test; database: W2-W7; UI: 2 tests; browser: savings journey | ubuntu |
| End of day with savings interest: OPERATIONS closes the day only with every till balanced; daily ACT/365 accrual with cumulative rounding; month-end credit less withholding tax; business date moves forward | core: 1 end-to-end test, 4 unit tests; database: I2-I7; UI: 2 tests | ubuntu |
| Loan end of day: interest accrual with catch-up, penalties within the product rule, arrears snapshots and PAR; interest paid before accrual not recognised twice | core: end-of-day API test, planner unit tests; database: N2-N4, E3-E9 | ubuntu |
| Loan closure and credit: loans close when fully repaid; money paid ahead pays installments as they fall due; credit left on closed loans is held and reported; portfolio-at-risk report and screen | core: end-of-day API test; database: P1-P6; UI: 1 test | ubuntu |
| Loan write-off: maker-checker, eligibility from the last end of day and the tenant policy, exact amounts written off at approval; M-Pesa recoveries up to the amount written off | core: end-of-day API test; database: X1-X5; UI: 1 test | ubuntu |
| General ledger for finance: chart of accounts, trial balance by date and branch, account activity with running balances, journal entry drill-down (FINANCE role) | core: end-of-day API test; UI: 1 test; browser: savings journey | ubuntu |
| Subledger-to-control reconciliation: six checks recorded at every end of day and available live; differences reported, not hidden | core: end-of-day API test (incl. a deliberate difference); database: G1-G3; UI: 1 test; browser: real flows reconcile | ubuntu |
| Manual journals and period close: maker-checker journals on non-control accounts; calendar-month periods opened in order and closed only when the last day is closed and nothing is pending | core: API test; database: Q1-Q4, Y1-Y2; UI: 1 test | ubuntu |
| Financial statements: income statement and balance sheet from the ledger; CSV export of trial balance and statements | core: API test; UI: 3 tests; browser: CSV download checked | ubuntu |
| Staff audit trail: every change and every read of customer, account, loan or audit data, refused attempts included, no bodies; SHA-256 chained by the database with tamper check | core: API test; database: S1-S5; UI: 1 test; browser: supervisor sees an intact trail | ubuntu |
| Staff web app (worklist, applications, disbursements, M-Pesa close-out, suspense, schedule): money as text, four-eyes hints, role-based navigation, API types match contract, runtime deps audited | 11 UI tests; typecheck; production build | ubuntu and windows |
| Whole platform in containers (PostgreSQL, RabbitMQ, Keycloak, core, integration, TEST-ONLY Daraja simulator, staff web behind nginx): a browser signs in as officer, manager, supervisor and teller: loan from KYC to M-Pesa disbursement to ACTIVE, and savings from account opening to cash with approval to statement | Playwright (Chromium), 2 journeys | ubuntu (Docker Compose, `infrastructure/e2e`) |
| Camel integration runtime: transactional outbox relay to RabbitMQ (exactly-once publish per event, broker failure and retry, relay role cannot rewrite events) | 2 integration tests pass | ubuntu (PostgreSQL 17 + RabbitMQ 4.1 Testcontainers) |
| Ledger, savings, teller, lending, origination, customers, EOD, STK, B2C and close-out database rules | 114/114 checks | PostgreSQL 17 service container |
| PowerShell install/uninstall scripts | parse cleanly | windows-latest |
| Mutation runs (4 safeguards removed) | each makes its test fail | local PostgreSQL 16 |

API contract: `shared/contracts/openapi/core-banking-v1.json` (OpenAPI 3.1); CI fails if the
code and the committed contract diverge, or if an operation id is generator-numbered.

Not yet verified: any Daraja sandbox or production run, container images on Windows Server, B2C status query and float reconciliation, suspense refunds and splitting, jPOS switch, bank/CRB/SMS routes, Docker images, running the Windows service
scripts on Windows Server, real OIDC provider (tests use mock JWTs), load tests.

## Non-negotiables
Financial accuracy, immutable posted ledger history, double-entry, tenant isolation,
security, auditability, no lost or duplicated switched transactions, complete
reconciliation, offline-safe field operations, verifiable production readiness.
