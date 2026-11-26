# Runbook: monitoring and alerts (ADR-0045)

Rules: `infrastructure/monitoring/prometheus/mfi-alerts.yml` (unit-tested in CI by
`mfi-alerts.test.yml`). End-of-day alerts are explained in `end-of-day.md`; backups in
`backup-and-restore.md`.

## Set-up
- **Metrics endpoint**: each core banking instance serves `/actuator/prometheus`. It answers only a
  token carrying the role `MONITORING` (no tenant claim needed). Create a client-credentials client
  in the identity provider for the Prometheus server with `roles: ["MONITORING"]`; its secret goes in
  the secret store. That token gets 403 on every business API.
- **Scrape** (Prometheus):
  ```yaml
  scrape_configs:
    - job_name: mfi-core
      metrics_path: /actuator/prometheus
      scheme: https
      oauth2:
        client_id: mfi-monitoring
        client_secret_file: /etc/prometheus/secrets/mfi-monitoring
        token_url: https://idp.example.invalid/realms/technopick/protocol/openid-connect/token
      static_configs:
        - targets: ["core-1.internal:8080", "core-2.internal:8080"]
    - job_name: node            # node_exporter with --collector.textfile.directory for backups
      static_configs:
        - targets: ["db-1.internal:9100"]
  rule_files: ["mfi-alerts.yml"]
  ```
- **Routing** (Alertmanager): `severity="critical"` pages on-call at any hour; `severity="warning"` goes
  to the operations queue in working hours. Who is on call is a hosting and staffing decision.
- Figures per tenant are read every minute (`mfi.ops.metrics-interval`), audit chains every hour
  (`mfi.ops.audit-chain-interval`), from the database's `ops` functions. These return counts only:
  no names, phone numbers, account numbers or amounts leave the database through monitoring.
  Scraping does not touch the database.

## Figures published (label `tenant` = tenant code)
| Metric | Meaning |
|--------|---------|
| `mfi_eod_days_behind` | today in the tenant's time zone minus the open business date (0 or -1 when up to date) |
| `mfi_eod_running_seconds` | how long a running end of day has been running (0 when none) |
| `mfi_eod_last_attempt_failed` | 1 when the last end-of-day attempt failed, was blocked or interrupted |
| `mfi_reconciliation_differences` | control accounts not matching their records at the last close |
| `mfi_outbox_unpublished`, `mfi_outbox_oldest_unpublished_seconds` | events waiting to be published |
| `mfi_sms_overdue`, `mfi_sms_failed_24h` | SMS overdue by 15 minutes; SMS failed in the last day |
| `mfi_b2c_outcome_unknown` | M-Pesa loan payouts with no outcome after 15 minutes |
| `mfi_audit_chain_broken`, `mfi_audit_chain_links` | 1 when a tenant's audit chain does not verify; its length |
| `mfi_ops_snapshot_last_success_seconds` | when the figures were last read (no tenant label) |
| `mfi_backup_last_success_timestamp_seconds` | from `mfi-backup.sh` via node_exporter (label `database`) |

Standard metrics also come out: HTTP requests (`http_server_requests_seconds_*`), connection pool
(`hikaricp_*`), JVM memory, threads and garbage collection.

## Alerts

### MfiCoreDown
An instance has not answered the scrape for 2 minutes. Check the service (`systemctl status`, or the
Windows service), its log, and `/actuator/health/readiness`. With two or more instances behind the
load balancer, members and tellers are still served by the others; with one, nothing works.

### MfiHttpErrors
Over 5% of requests fail with 5xx for 10 minutes. Look at the application log for the errors' cause
(database unreachable, identity provider unreachable, a bad release). Roll back a release that
started it.

### MfiDatabasePoolExhausted
Requests wait for database connections. Usually a slow query or a lock: check `pg_stat_activity` for
long-running statements and lock waits. End of day holds a lock for its whole run, which is expected.

### MfiOperationalFiguresStale
The figures have not been read for 10 minutes, so the tenant alerts below cannot fire. The log shows
`OPS_METRICS:` warnings with the reason, usually the database.

### MfiReconciliationDifference
**Critical: money.** At the last close a control account in the ledger did not match its records
(savings, loans, shares, deposits and so on). Do not post manual journals to "fix" it. Finance opens
the Reconciliation page for that tenant and date, finds the first day the difference appeared, and
the entries of that day. Tell the platform team; a difference means a bug or a change made outside
the application.

### MfiB2cOutcomeUnknown
A loan payout went to M-Pesa and no result came back for 45 minutes. The system asks M-Pesa
automatically (ADR-0039); if it still has no answer, operations uses the B2C close-out page. Do not
pay the member again until the first payout is known to have failed.

### MfiAuditChainBroken
**Critical: possible tampering.** A tenant's audit trail no longer verifies from a given link: a row
was changed or removed outside the application. Do not change anything. Preserve the database (take
a backup now and keep it), tell the security officer and the platform team, and compare with the
last backup that verifies (`mfi-restore-verify.sh --verify-only`). The log line
`AUDIT_CHAIN_BROKEN` names the tenant and link.

### MfiOutboxStuck
Events (SMS sends, M-Pesa requests, notifications) have waited over 10 minutes to be published. Check
the integration runtime and RabbitMQ. Nothing is lost: events are published when they come back.

### MfiSmsOverdue
Many SMS are overdue, or many failed. Check the SMS provider's status and the integration runtime log.
Payment confirmations are delayed meanwhile; members may call the branch.
