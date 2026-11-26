#!/usr/bin/env bash
# =============================================================================
# Restores a backup made by mfi-backup.sh into a NEW database and proves it is complete
# (ADR-0045, runbook docs/operations/runbooks/backup-and-restore.md).
#
#   1. checksums of every file in the backup folder
#   2. roles that do not exist yet are created (without passwords; set them from the secret store)
#   3. a new, empty database is created - an existing database is never touched
#   4. pg_restore of the dump (decrypted first if it is encrypted; the private key must be in gpg)
#   5. the restored database's fingerprint must equal the one taken with the backup, line for line
#   6. independent checks: migrations, every journal entry balanced, audit chains intact
#
# Usage:  mfi-restore-verify.sh <backup-folder> <new-database-name>
#         mfi-restore-verify.sh --verify-only <backup-folder> <existing-database-name>
#           (steps 1, 5 and 6 only: compare a database with a backup, change nothing)
# Env:    PGHOST, PGPORT, PGUSER, PGPASSWORD   an administrator able to CREATE DATABASE and roles
#
# Prints PASS/FAIL lines; exit status 0 only if everything passed.
# =============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SQL="$HERE/../sql"
verify_only=false
if [[ "${1:-}" == "--verify-only" ]]; then verify_only=true; shift; fi
[[ $# -eq 2 ]] || { echo "usage: $0 [--verify-only] <backup-folder> <database>" >&2; exit 2; }
backup="$(cd "$1" && pwd)"
target="$2"
[[ "$target" =~ ^[a-z][a-z0-9_]{0,62}$ ]] || { echo "database name must be lower-case letters, digits and _" >&2; exit 2; }

started=$(date +%s)
failures=0
log() { echo "$(date -u +%H:%M:%S) $*"; }
pass() { echo "PASS $*"; }
fail() { echo "FAIL $*"; failures=$((failures + 1)); }
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

log "backup $backup -> database $target$($verify_only && echo ' (verify only)')"

# 1. checksums
if (cd "$backup" && sha256sum --quiet --check SHA256SUMS); then
  pass "checksums: $(wc -l < "$backup/SHA256SUMS") files"
else
  fail "checksums do not match; the backup is damaged or was changed"
  exit 1
fi

exists=$(psql -X -A -t -d postgres -v ON_ERROR_STOP=1 -c "SELECT count(*) FROM pg_database WHERE datname = '$target'")
if ! $verify_only; then
  # 3 (checked first). never restore over an existing database
  [[ "$exists" == "0" ]] || { fail "database $target already exists; restore only into a new database"; exit 1; }

  # 2. roles that are missing (existing roles are left exactly as they are)
  missing=0
  while IFS= read -r role; do
    if [[ "$(psql -X -A -t -d postgres -c "SELECT count(*) FROM pg_roles WHERE rolname = '$role'")" == "0" ]]; then
      grep -E "^(CREATE|ALTER) ROLE $role[ ;]|^GRANT [a-z_0-9]+ TO $role[ ;]" "$backup/roles.sql" \
        | psql -X -q -v ON_ERROR_STOP=1 -d postgres
      log "created role $role (no password)"
      missing=$((missing + 1))
    fi
  done < <(sed -nE 's/^CREATE ROLE ([a-z_][a-z0-9_]*);$/\1/p' "$backup/roles.sql")
  pass "roles: $missing created, others already present"

  # 4. restore
  dump="$backup/mfi.dump"
  if [[ -f "$backup/mfi.dump.gpg" ]]; then
    log "decrypting the dump"
    gpg --batch --quiet --output "$work/mfi.dump" --decrypt "$backup/mfi.dump.gpg"
    dump="$work/mfi.dump"
  fi
  psql -X -q -v ON_ERROR_STOP=1 -d postgres -c "CREATE DATABASE $target"
  log "restoring"
  if pg_restore --exit-on-error --single-transaction -d "$target" "$dump"; then
    pass "pg_restore into $target"
  else
    fail "pg_restore into $target"
    exit 1
  fi
else
  [[ "$exists" == "1" ]] || { fail "database $target does not exist"; exit 1; }
fi

# 5. fingerprint
psql -X -q -A -t -v ON_ERROR_STOP=1 -d "$target" -f "$SQL/backup-fingerprint.sql" > "$work/fingerprint.txt"
if diff -q "$backup/fingerprint.txt" "$work/fingerprint.txt" > /dev/null; then
  pass "fingerprint: $(wc -l < "$work/fingerprint.txt") lines identical (rows, ledger totals, balances, audit heads, row digests)"
else
  fail "fingerprint differs from the backup's:"
  { diff "$backup/fingerprint.txt" "$work/fingerprint.txt" || true; } | head -20 | sed 's/^/     /'
fi

# 6. independent checks
while IFS= read -r line; do
  case "$line" in
    PASS*) echo "$line" ;;
    FAIL*) echo "$line"; failures=$((failures + 1)) ;;
  esac
done < <(psql -X -q -A -t -v ON_ERROR_STOP=1 -d "$target" -f "$SQL/restore-checks.sql")

log "finished in $(( $(date +%s) - started )) s"
if [[ $failures -gt 0 ]]; then
  echo "RESULT: FAILED ($failures)"
  exit 1
fi
echo "RESULT: restore verified"
