# Technopick Microfinance Core Banking & Switching Platform

Multi-tenant core banking for Kenyan MFIs and SACCOs with an integrated ISO 8583 / ISO 20022
financial switch. Java 25, Spring Boot 4.1, Apache Camel 4.22 LTS, jPOS 3, PostgreSQL.

**Status: Phase 1, increment 1. Not production-ready.** See "What is verified" below.

## Layout

```
shared/canonical-model/   Money + standard-neutral transaction model (JDK only)
services/core-banking/    Modular monolith: accounting + lending domain, Flyway migrations
services/switch/          jPOS switch (Phase 1 increment 3: sign-on/echo vs simulator)
services/integration/     Camel runtime (Phase 1 increment 4: first tested route)
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

## What is verified (2026-11-18, increment 4)

| Item | Result | How |
| --- | --- | --- |
| Domain code compiles with `-Xlint:all -Werror` | Yes | `javac --release 21` (JDK 21.0.13) |
| 83 unit tests (Money, canonical model, journals, schedules, repayment allocation, holds, authorization, product versions, posting rules, accrual, arrears/PAR, penalties, EOD planner; incl. seeded property tests) | 83/83 pass | JUnit 5 test sources run through a local JUnit-API shim, because Maven Central was unreachable from the build workspace |
| Ledger, savings and lending DB rules (balance, immutability, closed period, idempotency, reversals, subledger/control, holds, available balance, product versioning, maker-checker, loans, schedules, repayments, business date/EOD, accrual/penalty records, arrears snapshots, PAR, RLS) | 70/70 checks pass incl. two-session race tests; mutation runs detect a disabled balance trigger, a missing row lock, a disabled allocation check and a disabled EOD-completion check | PostgreSQL 16.15, app role `mfi_app` without BYPASSRLS |
| `mvn verify`, ArchUnit rules, Docker images, CI workflow, PowerShell scripts | **Not yet run** | Need a networked build (GitHub Actions or a developer PC) |

## Non-negotiables
Financial accuracy, immutable posted ledger history, double-entry, tenant isolation,
security, auditability, no lost or duplicated switched transactions, complete
reconciliation, offline-safe field operations, verifiable production readiness.
