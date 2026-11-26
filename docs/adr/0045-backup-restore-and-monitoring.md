# ADR-0045: Verified backups and operational monitoring

- Status: Accepted (increment 45, 2026-11-26). The backup schedule, retention, off-site store,
  encryption key custody, point-in-time recovery, alert thresholds and on-call routing are hosting
  and operations decisions still open; nothing here has been set up on a real server.

## Context
Before any pilot the Sacco must be able to get its books back after losing a server, and someone must
hear about a stuck end of day, a reconciliation difference or a tampered audit trail before members
or the regulator do. The platform had a health endpoint and nothing else: no backup procedure, no
proof that a restore is complete, and no metrics beyond the framework's.

## Decision
- **Logical nightly backup** (`mfi-backup.sh`): `pg_dump` in custom format, run as a dedicated
  `mfi_backup` role (`BYPASSRLS` + `pg_read_all_data`, no write rights), optionally encrypted to a
  public key. Role definitions are saved without passwords.
- **Fingerprint in the same snapshot**: the script exports the snapshot of one read-only transaction,
  reads a fingerprint in it (exact row counts of every table; ledger debits, credits and entries per
  tenant and currency; the balance of every account and branch; business dates; audit chain heads; an
  order-free hash of every table's rows) and gives the same snapshot to `pg_dump`. The fingerprint
  therefore describes exactly what is in the dump, even on a busy system.
- **Restore only into a new database, and prove it** (`mfi-restore-verify.sh`): checksums first; then
  restore in one transaction; then the restored fingerprint must equal the backup's line for line; then
  independent checks (no failed migrations, every journal entry balanced and with lines, every audit
  chain verifies, every tenant has a business date). `--verify-only` compares any database with a
  backup without changing it. Restoring over an existing database is refused.
- **Drill in CI on every push** (TEST-ONLY data, PostgreSQL 17), including proof that damage is
  caught: a changed byte in the backup, a changed narrative with totals unchanged, a changed audit row.
- **Operational figures** (V42): `ops.health_snapshot()` and `ops.audit_chain_status()` bind each
  active tenant in turn and return counts only (no customer, account or amount data). The core reads
  them every minute (audit chains hourly) and publishes Prometheus gauges labelled by tenant code.
  Scrapes never reach the database.
- **Metrics access**: `/actuator/prometheus` answers only the `MONITORING` role (a service account);
  that role has no business API access. Health probes stay open.
- **Alert rules** (`mfi-alerts.yml`), unit-tested with `promtool` in CI: instance down, 5xx rate,
  connection pool, figures stale, end of day behind or failed or running long, reconciliation
  difference (critical), audit chain broken (critical), M-Pesa payouts with unknown outcome, outbox
  stuck, SMS overdue or failing, and no successful backup in 26 hours (the backup script writes a
  node_exporter textfile).

## Not built yet
- Point-in-time recovery (WAL archiving, e.g. pgBackRest), off-site copying, a PowerShell version of
  the scripts for Windows Server tenants, a standby database and failover, database server metrics
  (postgres_exporter), dashboards, log shipping, and the Prometheus/Alertmanager servers themselves.
- The real key ceremony for backup encryption (needs approval).

## Consequences
- A restore is either proven complete or reported with the exact differences; "the restore ran" is
  no longer the test.
- Monitoring can see every tenant's state without being able to read any customer's data.
- A logical backup loses everything since the last run; until point-in-time recovery exists, the
  last day must be rebuilt from M-Pesa statements, teller slips and integration logs.
