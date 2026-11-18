# Session report - 2026-11-18 - Phase 0 + Phase 1 increment 1

## Completed
- Phase 0 discovery and architecture package (Claude Doc: "Technopick MFI Core Banking &
  Switching - Phase 0 Discovery & Architecture").
- Repository scaffold, root BOM, module POMs, ADR-0001..0006.
- `shared/canonical-model`: Money, AccountRef (no PAN kind), TransactionType,
  MessageFunction, ResponseReason, CounterpartyKeys, DuplicateKey, TransactionRequest.
- `core-banking` domain: JournalEntry/JournalLine (balance per currency + branch, reversal),
  ScheduleCalculator (reducing balance, flat) with spec `docs/architecture/loan-calculations.md`.
- Migration `V1__tenancy_and_ledger.sql`: tenancy, CoA, periods, journals, DB-enforced
  ledger rules, RLS, least-privilege grants.
- Local dev compose, CI workflow (Linux + Windows matrix, DB tests, PowerShell parse),
  WinSW template + install/uninstall PowerShell scripts.

## Tests and results
| Suite | Result |
| --- | --- |
| Java unit tests (4 classes, 30 tests) | 30 passed, 0 failed (via local JUnit-API shim) |
| Ledger DB tests (16 app-role + 4 owner checks) | 20 passed |
| Mutation check (balance triggers disabled) | T2 failed as expected - tests are effective |
| ArchitectureRulesTest (ArchUnit) | Not run - library unavailable offline |
| `mvn verify`, CI, Docker images, PowerShell | Not run - no registry access |

## Commands
```bash
bash services/core-banking/src/test/sql/run-ledger-db-tests.sh
mvn -B verify            # once Maven Central is reachable
```

## Environment variables (placeholders only)
`PGHOST`, `PGPORT`, `PGUSER`, `PGPASSWORD` (admin, test DB creation), `MFI_APP_HOST`,
`MFI_TEST_APP_PASSWORD` (optional).

## Known defects / risks
1. No networked build yet: dependency and image versions are unverified (ADR-0002).
2. jPOS AGPLv3 licensing must be resolved before any switch distribution or SaaS use.
3. Accounting examples and calculation spec await finance sign-off.
4. PowerShell scripts untested on Windows Server.

## Next step (Phase 1 increment 2)
Run `mvn verify` in CI; add the Spring Boot application shell (OIDC resource server,
tenant context filter issuing `SET LOCAL app.tenant_id`, Flyway, actuator, problem details)
and a Testcontainers test that runs the SQL invariant suite through the JDBC layer.
