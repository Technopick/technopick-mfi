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

## What is verified (2026-11-18, first CI run)

GitHub Actions run 44250639856 on `main`: all four jobs green.

| Item | Result | Where |
| --- | --- | --- |
| `mvn verify` on JDK 25 (Temurin), `-Xlint:all -Werror` | BUILD SUCCESS | ubuntu-latest and windows-latest |
| Unit tests (JUnit 5) incl. seeded property tests | 87/87 pass on each OS (16 canonical-model + 71 core-banking) | both runners |
| ArchUnit rules: framework-free domain, no float/double in money code, no module cycles, lending isolation | 4/4 pass | both runners |
| Ledger, savings, lending and EOD database rules | 70/70 checks pass | PostgreSQL 17 service container |
| Two-session race tests (holds vs holds, hold vs debit) | pass | PostgreSQL 17 |
| PowerShell install/uninstall scripts | parse cleanly | windows-latest |
| Mutation runs (balance trigger, row lock, allocation check, EOD check removed) | each makes its test fail | local PostgreSQL 16 |

Not yet verified: Spring Boot application layer, jPOS switch, Camel routes, Docker images,
executing (not just parsing) the Windows service scripts on Windows Server, load tests.

## Non-negotiables
Financial accuracy, immutable posted ledger history, double-entry, tenant isolation,
security, auditability, no lost or duplicated switched transactions, complete
reconciliation, offline-safe field operations, verifiable production readiness.
