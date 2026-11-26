#!/usr/bin/env bash
# =============================================================================
# Logical backup of the core banking database (ADR-0045, runbook docs/operations/runbooks/
# backup-and-restore.md).
#
# Writes one folder per backup:
#   roles.sql          role definitions, without passwords (passwords live in the secret store)
#   mfi.dump           pg_dump custom format (or mfi.dump.gpg when encrypted)
#   fingerprint.txt    row counts, ledger totals, account balances, audit chain heads and per-table
#                      row digests, read in the SAME snapshot as the dump
#   manifest.txt       when, from where, sizes, tool versions
#   SHA256SUMS         checksums of the files above
#
# Usage:  mfi-backup.sh
# Env:    PGHOST, PGPORT, PGUSER, PGPASSWORD or ~/.pgpass   connection (role mfi_backup: BYPASSRLS
#                                                          and pg_read_all_data; never mfi_app)
#         PGDATABASE                  database (default mfi)
#         MFI_BACKUP_DIR              where backup folders go (required)
#         MFI_BACKUP_GPG_RECIPIENT    optional: encrypt the dump to this public key
#         MFI_BACKUP_KEEP_DAYS        optional: remove backup folders older than this many days
#                                     (only folders this script made; off unless set)
#         MFI_BACKUP_METRICS_FILE     optional: a .prom file for node_exporter's textfile collector,
#                                     rewritten after each successful backup (alert MfiBackupMissing)
#
# Exit status is non-zero if anything fails; a failed backup leaves its folder named *.incomplete.
# =============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SQL="$HERE/../sql"
DB="${PGDATABASE:-mfi}"
: "${MFI_BACKUP_DIR:?set MFI_BACKUP_DIR to the folder backups are written to}"

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
name="mfi-$DB-$stamp"
work="$MFI_BACKUP_DIR/$name.incomplete"
mkdir -p "$work"
umask 077
started=$(date +%s)
log() { echo "$(date -u +%H:%M:%S) $*"; }

log "backup of $DB on ${PGHOST:-local socket} into $MFI_BACKUP_DIR/$name"

log "roles (no passwords)"
pg_dumpall --roles-only --no-role-passwords -l "$DB" > "$work/roles.sql"

# One read-only transaction holds the snapshot: the fingerprint is read in it, and pg_dump is
# given the same snapshot, so both describe exactly the same data even while the system runs.
coproc PSQL { psql -X -q -A -t -v ON_ERROR_STOP=1 -d "$DB" 2>&1; }
exec {to}>&"${PSQL[1]}" {from}<&"${PSQL[0]}"
send() { printf '%s\n' "$1" >&"$to"; }
read_until_marker() {
  local line
  while IFS= read -r line <&"$from"; do
    [[ "$line" == "--marker--" ]] && return 0
    printf '%s\n' "$line"
  done
  return 1
}
send "BEGIN ISOLATION LEVEL REPEATABLE READ, READ ONLY;"
send "SELECT pg_export_snapshot();"
send "SELECT '--marker--';"
snapshot="$(read_until_marker | tail -n 1)"
[[ "$snapshot" =~ ^[0-9A-F-]+$ ]] || { log "could not export a snapshot: $snapshot"; exit 1; }
log "snapshot $snapshot"

log "fingerprint"
send "\\i $SQL/backup-fingerprint.sql"
send "SELECT '--marker--';"
read_until_marker > "$work/fingerprint.txt"
if grep -q '^ERROR\|^psql:' "$work/fingerprint.txt"; then
  log "fingerprint failed:"; grep '^ERROR\|^psql:' "$work/fingerprint.txt"; exit 1
fi
log "  $(wc -l < "$work/fingerprint.txt") lines"

log "dump"
pg_dump --format=custom --compress=6 --snapshot="$snapshot" --no-sync -d "$DB" -f "$work/mfi.dump"

send "COMMIT;"
send "\\q"
exec {to}>&- {from}<&-
wait "$PSQL_PID" || true

if [[ -n "${MFI_BACKUP_GPG_RECIPIENT:-}" ]]; then
  log "encrypting the dump to $MFI_BACKUP_GPG_RECIPIENT"
  gpg --batch --yes --trust-model always --recipient "$MFI_BACKUP_GPG_RECIPIENT" \
      --output "$work/mfi.dump.gpg" --encrypt "$work/mfi.dump"
  rm -f "$work/mfi.dump"
fi

{
  echo "database=$DB"
  echo "host=${PGHOST:-local}"
  echo "taken_at_utc=$stamp"
  echo "snapshot=$snapshot"
  echo "pg_dump=$(pg_dump --version)"
  echo "server=$(psql -X -A -t -d "$DB" -c 'SHOW server_version')"
  echo "schema_version=$(grep '^flyway|version|' "$work/fingerprint.txt" | cut -d'|' -f3)"
  echo "encrypted=$([[ -n "${MFI_BACKUP_GPG_RECIPIENT:-}" ]] && echo yes || echo no)"
  for f in "$work"/roles.sql "$work"/mfi.dump*; do echo "size $(basename "$f")=$(stat -c %s "$f")"; done
} > "$work/manifest.txt"

(cd "$work" && sha256sum roles.sql mfi.dump* fingerprint.txt manifest.txt > SHA256SUMS)
mv "$work" "$MFI_BACKUP_DIR/$name"
took=$(( $(date +%s) - started ))
log "done in $took s: $MFI_BACKUP_DIR/$name"

if [[ -n "${MFI_BACKUP_METRICS_FILE:-}" ]]; then
  bytes=$(du -sb "$MFI_BACKUP_DIR/$name" | cut -f1)
  tmp="$MFI_BACKUP_METRICS_FILE.$$"
  {
    echo "# HELP mfi_backup_last_success_timestamp_seconds When the last backup that completed was taken."
    echo "# TYPE mfi_backup_last_success_timestamp_seconds gauge"
    echo "mfi_backup_last_success_timestamp_seconds{database=\"$DB\"} $started"
    echo "# HELP mfi_backup_last_duration_seconds How long the last completed backup took."
    echo "# TYPE mfi_backup_last_duration_seconds gauge"
    echo "mfi_backup_last_duration_seconds{database=\"$DB\"} $took"
    echo "# HELP mfi_backup_last_size_bytes Size of the last completed backup folder."
    echo "# TYPE mfi_backup_last_size_bytes gauge"
    echo "mfi_backup_last_size_bytes{database=\"$DB\"} $bytes"
  } > "$tmp"
  chmod 644 "$tmp"
  mv "$tmp" "$MFI_BACKUP_METRICS_FILE"
fi

if [[ -n "${MFI_BACKUP_KEEP_DAYS:-}" ]]; then
  [[ "$MFI_BACKUP_KEEP_DAYS" =~ ^[0-9]+$ && "$MFI_BACKUP_KEEP_DAYS" -ge 1 ]] || { log "MFI_BACKUP_KEEP_DAYS must be a whole number of days"; exit 1; }
  # only complete folders this script named, and never the one just written
  find "$MFI_BACKUP_DIR" -mindepth 1 -maxdepth 1 -type d -name "mfi-$DB-*Z" ! -name "$name" \
       -mtime +"$MFI_BACKUP_KEEP_DAYS" -print -exec rm -rf {} + | sed 's/^/removed old backup: /'
fi
