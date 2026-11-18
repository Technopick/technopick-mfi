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
simulators/               TEST-ONLY ISO 8583/20022 and provider simulators
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

## What is verified (2026-11-18, increment 9)

GitHub Actions on `main`: all four jobs green.

| Item | Result | Where |
| --- | --- | --- |
| `mvn verify` on JDK 25, `-Xlint:all -Werror` | BUILD SUCCESS | ubuntu-latest and windows-latest |
| Unit tests (JUnit 5) incl. seeded property tests | 88/88 on each OS | both runners |
| ArchUnit rules (framework-free domain, no float/double money, no module cycles) | 4/4 | both runners |
| Spring Boot 4 application end to end (HTTP, security, service, JDBC, PostgreSQL 17 with RLS) | 6 integration tests pass: lifecycle, auth/roles, 10-way concurrent overspend, hold expiry, OpenAPI contract drift | ubuntu (Testcontainers) |
| M-Pesa C2B: Daraja callbacks (token + source range auth, store-then-ack, retries, dead letters) and core application (exactly once, suspense when unmatched) | core: 1 end-to-end test; integration: 3 tests | ubuntu (Testcontainers + stub core) |
| Loan repayment by loan number via paybill (allocation, accrued vs unaccrued interest, overpayment, exactly once) and suspense clearance with maker-checker (to savings or loan) | core: 2 end-to-end tests | ubuntu (Testcontainers PostgreSQL 17) |
| Camel integration runtime: transactional outbox relay to RabbitMQ (exactly-once publish per event, broker failure and retry, relay role cannot rewrite events) | 2 integration tests pass | ubuntu (PostgreSQL 17 + RabbitMQ 4.1 Testcontainers) |
| Ledger, savings, lending and EOD database rules | 70/70 checks | PostgreSQL 17 service container |
| PowerShell install/uninstall scripts | parse cleanly | windows-latest |
| Mutation runs (4 safeguards removed) | each makes its test fail | local PostgreSQL 16 |

API contract: `shared/contracts/openapi/core-banking-v1.json` (OpenAPI 3.1); CI fails if the
code and the committed contract diverge.

Not yet verified: suspense refunds and splitting, jPOS switch, Daraja STK Push / B2C and sandbox certification, bank/CRB/SMS routes, Docker images, running the Windows service
scripts on Windows Server, real OIDC provider (tests use mock JWTs), load tests.

## Non-negotiables
Financial accuracy, immutable posted ledger history, double-entry, tenant isolation,
security, auditability, no lost or duplicated switched transactions, complete
reconciliation, offline-safe field operations, verifiable production readiness.
