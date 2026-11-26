# Runbook: backup and restore (ADR-0045)

Scripts: `infrastructure/scripts/bash/mfi-backup.sh`, `infrastructure/scripts/bash/mfi-restore-verify.sh`.
Drill: `infrastructure/scripts/bash/tests/backup-restore-drill.sh` (TEST-ONLY, run in CI on every push).

## What a backup is
A folder `mfi-<database>-<UTC time>` holding:

| File | What it is |
|------|------------|
| `mfi.dump` or `mfi.dump.gpg` | `pg_dump` custom format, encrypted when a recipient key is set |
| `roles.sql` | role definitions without passwords |
| `fingerprint.txt` | exact row counts of every table, ledger totals per tenant and currency, the balance of every account and branch, each tenant's business date and audit chain head, and a hash of every table's rows |
| `manifest.txt` | source, time, schema version, tool versions, sizes |
| `SHA256SUMS` | checksums of the files above |

The fingerprint is read in the same database snapshot as the dump, so it describes exactly the data
in the dump even while tellers, M-Pesa and end of day keep working. A folder still named
`*.incomplete` is a failed backup; never restore from it.

This is a **logical, nightly** backup. On its own, a restore loses everything since the last backup.
See "Point-in-time recovery" below.

## Set-up (once per database server)
1. A backup role that can read everything and nothing else (a database administrator runs this):
   ```sql
   CREATE ROLE mfi_backup LOGIN NOSUPERUSER BYPASSRLS NOCREATEDB NOCREATEROLE;
   GRANT pg_read_all_data TO mfi_backup;
   ```
   Its password goes in the secret store and `~/.pgpass` of the backup user on the backup host,
   never in a script, a ticket or chat. `BYPASSRLS` is needed because every tenant table forces
   row-level security; the role can still change nothing.
2. Use `pg_dump` of the **same major version** as the server (17).
3. Encryption: create a key pair for backups. The public key is imported on the backup host; the
   private key is kept offline by two named people (a key ceremony; needs approval before it is
   done for real). Without the private key no backup can be restored, so test a restore with it
   immediately after the ceremony.
4. Schedule it nightly after end of day, for example with a systemd timer:
   ```ini
   # /etc/systemd/system/mfi-backup.service
   [Service]
   Type=oneshot
   User=mfibackup
   Environment=PGHOST=db.internal PGUSER=mfi_backup PGDATABASE=mfi
   Environment=MFI_BACKUP_DIR=/srv/backups/mfi MFI_BACKUP_GPG_RECIPIENT=backups@example.invalid
   Environment=MFI_BACKUP_METRICS_FILE=/var/lib/node_exporter/textfile/mfi_backup.prom
   ExecStart=/opt/mfi/scripts/bash/mfi-backup.sh
   # /etc/systemd/system/mfi-backup.timer
   [Timer]
   OnCalendar=*-*-* 23:30 Africa/Nairobi
   Persistent=true
   ```
5. Copy each finished folder off the server (another site or object storage with object lock).
   A backup on the same disk as the database is not a backup.
6. `MFI_BACKUP_METRICS_FILE` lets node_exporter report success; alert `MfiBackupMissing` fires after
   26 hours without one.
7. Pruning old folders is **off** unless `MFI_BACKUP_KEEP_DAYS` is set; agree the retention period
   with compliance first (it removes data).

## Restore
Always into a **new** database; the script refuses an existing name.
```bash
export PGHOST=<server> PGUSER=<administrator>       # password from the secret store / .pgpass
./mfi-restore-verify.sh /srv/backups/mfi/mfi-mfi-20261126T203000Z mfi_restored_20261126
```
It checks the checksums, creates any missing roles (without passwords), decrypts, restores in one
transaction, compares the restored data with the fingerprint line by line, and runs independent
checks (migrations, every journal entry balanced, every journal entry has lines, every audit chain
verifies, every tenant has a business date). It ends with `RESULT: restore verified` or
`RESULT: FAILED` and the differences.

After a verified restore, to make it live (needs approval; affects real customers):
1. Stop the core banking, integration and switch services.
2. Set the application role passwords from the secret store (restored roles have none).
3. Point the services at the restored database (or rename databases), start them, and run end of
   day only after checking the business date of each tenant.
4. Anything after the backup (M-Pesa receipts, teller work) must be recovered from M-Pesa
   statements (statement reconciliation, ADR-0038), teller slips and the integration logs.

To compare a database with a backup without changing anything:
`./mfi-restore-verify.sh --verify-only <backup-folder> <database>`.

## Monthly restore test (required)
Restore the latest real backup on a separate, access-controlled server with the script above, keep
its output with the date and the name of the person who ran it, then drop the restored database.
A backup that has never been restored is not known to work.

## What the CI drill proves (TEST-ONLY data)
On every push, PostgreSQL 17: migrations and a TEST-ONLY book (2,000 accounts, 800 loans, an audit
trail) are backed up as `mfi_backup`, encrypted with a throwaway key, and restored into a new
database, which matches. It also shows that the untouched source still matches, that a role existing
only in the backup is recreated with its grants, that restoring over an existing database is refused,
that a single changed byte in the backup is refused before restoring, that a changed narrative (totals
unchanged) is caught by the row hashes, and that a changed audit row is caught by the chain check.

## Point-in-time recovery (recommended, not built yet)
To lose at most minutes instead of a day, the database server should also archive its write-ahead
log continuously (for example pgBackRest with a weekly full and daily incremental backup and WAL
archiving to the off-site store). That is a hosting decision; it is not set up or tested here. The
nightly logical backup stays as an independent copy that can be checked line by line.
