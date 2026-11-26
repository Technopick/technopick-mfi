#!/usr/bin/env bash
# =============================================================================
# TEST-ONLY backup and restore drill (ADR-0045). Builds a throwaway database from the
# migrations and the TEST-ONLY load-test seed, backs it up with mfi-backup.sh as the
# least-privileged backup role (encrypted with a throwaway key), restores it with
# mfi-restore-verify.sh into a new database, and then proves the checks catch damage:
# a changed byte in the backup, a changed narrative, a changed audit row, and an attempt
# to restore over an existing database. Everything it creates is dropped at the end.
#
# Usage:  backup-restore-drill.sh [savings-accounts] [loans]     (default 2000 800)
# Env:    PGHOST, PGPORT, PGUSER, PGPASSWORD   administrator of a TEST server, never production
#         MFI_DRILL_BACKUP_HOST                host the backup role connects through (default PGHOST)
# =============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASH_DIR="$HERE/.."
REPO="$(cd "$HERE/../../../.." && pwd)"
MIGRATIONS="$REPO/services/core-banking/src/main/resources/db/migration"
SEED="$REPO/services/core-banking/src/test/resources/perf/perf-seed-TEST-ONLY.sql"
NS="${1:-2000}"
NL="${2:-800}"
T="00000000-0000-0000-0000-0000000000c3"
SRC="mfi_drill_src_$$"
DST="mfi_drill_dst_$$"
DST2="mfi_drill_dst2_$$"
WORK="$(mktemp -d)"
export GNUPGHOME="$WORK/gnupg"
BACKUP_PW="$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9')"
failures=0
ok() { echo "DRILL PASS $*"; }
bad() { echo "DRILL FAIL $*"; failures=$((failures + 1)); }
admin() { psql -X -q -v ON_ERROR_STOP=1 "$@"; }

cleanup() {
  for d in "$SRC" "$DST" "$DST2"; do admin -d postgres -c "DROP DATABASE IF EXISTS $d" >/dev/null 2>&1 || true; done
  admin -d postgres -c "DROP ROLE IF EXISTS mfi_drill_reader" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

echo "== roles (TEST-ONLY passwords generated for this run)"
admin -d postgres <<SQL
DO \$\$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mfi_app') THEN
    CREATE ROLE mfi_app LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mfi_relay') THEN
    CREATE ROLE mfi_relay LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mfi_backup') THEN
    CREATE ROLE mfi_backup LOGIN NOSUPERUSER BYPASSRLS NOCREATEDB NOCREATEROLE; END IF;
END \$\$;
ALTER ROLE mfi_backup PASSWORD '$BACKUP_PW';
GRANT pg_read_all_data TO mfi_backup;
CREATE ROLE mfi_drill_reader NOLOGIN;
CREATE DATABASE $SRC;
SQL

echo "== migrations (recorded in a Flyway-shaped history, as Flyway would)"
admin -d "$SRC" -c "CREATE TABLE public.flyway_schema_history (installed_rank int PRIMARY KEY, version varchar(50),
  description varchar(200) NOT NULL, type varchar(20) NOT NULL, script varchar(1000) NOT NULL, checksum int,
  installed_by varchar(100) NOT NULL, installed_on timestamp NOT NULL DEFAULT now(), execution_time int NOT NULL,
  success boolean NOT NULL)"
rank=0
for f in $(ls "$MIGRATIONS"/V*__*.sql | sort -V); do
  rank=$((rank + 1))
  admin -d "$SRC" -f "$f"
  v="$(basename "$f" | sed -E 's/^V([0-9]+)__.*/\1/')"
  admin -d "$SRC" -c "INSERT INTO public.flyway_schema_history VALUES ($rank, '$v', 'drill', 'SQL', '$(basename "$f")',
    NULL, 'drill', now(), 0, true)"
done
echo "   $rank migrations"

echo "== TEST-ONLY data: $NS savings accounts, $NL loans, audit trail"
sed -e "s/{T}/$T/g" -e "s/{NS}/$NS/g" -e "s/{NL}/$NL/g" "$SEED" > "$WORK/seed.sql"
admin -d "$SRC" -f "$WORK/seed.sql"
admin -d "$SRC" <<SQL
SELECT set_config('app.tenant_id', '$T', false);
INSERT INTO audit.staff_action (tenant_id, chain_no, occurred_at, actor, roles, method, route, path, status, duration_ms,
                                request_id, prev_hash, hash)
SELECT '$T', 0, timestamptz '2026-11-28 09:00:00+03' + g * interval '1 minute', 'drill:teller-' || (g % 4), 'TELLER', 'POST',
       '/api/v1/teller/deposits', '/api/v1/teller/deposits', 200, 12, 'drill-' || g, '', ''
  FROM generate_series(1, 50) g;
GRANT USAGE ON SCHEMA ledger TO mfi_drill_reader;
SQL
admin -d "$SRC" -A -t -c "SELECT 'source: ' || (SELECT count(*) FROM ledger.journal_line) || ' journal lines, '
  || pg_size_pretty(pg_database_size(current_database()))"

echo "== throwaway encryption key (TEST-ONLY)"
mkdir -m 700 "$GNUPGHOME"
gpg --batch --quiet --passphrase '' --quick-generate-key "MFI drill TEST-ONLY <drill@technopick.invalid>" rsa3072 encrypt never

echo "== backup as mfi_backup (BYPASSRLS + pg_read_all_data only)"
mkdir "$WORK/backups"
PGHOST="${MFI_DRILL_BACKUP_HOST:-${PGHOST:-localhost}}" PGUSER=mfi_backup PGPASSWORD="$BACKUP_PW" PGDATABASE="$SRC" MFI_BACKUP_DIR="$WORK/backups" \
  MFI_BACKUP_GPG_RECIPIENT="drill@technopick.invalid" MFI_BACKUP_METRICS_FILE="$WORK/mfi_backup.prom" bash "$BASH_DIR/mfi-backup.sh"
B="$(ls -d "$WORK"/backups/mfi-* | head -1)"
grep -qE "^mfi_backup_last_success_timestamp_seconds\{database=\"$SRC\"\} [0-9]{10}$" "$WORK/mfi_backup.prom" \
  && ok "success reported for monitoring ($(grep -c '^mfi_' "$WORK/mfi_backup.prom") figures)" || { cat "$WORK/mfi_backup.prom"; bad "no backup metrics"; }
[[ -f "$B/mfi.dump.gpg" && ! -f "$B/mfi.dump" ]] && ok "the dump is stored encrypted only" || bad "the dump is not encrypted"
cat "$B/manifest.txt"

echo "== a role that exists only in the backup is recreated on restore"
admin -d "$SRC" -c "REVOKE USAGE ON SCHEMA ledger FROM mfi_drill_reader"
admin -d postgres -c "DROP ROLE mfi_drill_reader"

echo "== restore into a new database"
if bash "$BASH_DIR/mfi-restore-verify.sh" "$B" "$DST"; then ok "restore verified"; else bad "restore of an intact backup failed"; fi
[[ "$(admin -d "$DST" -A -t -c "SELECT has_schema_privilege('mfi_drill_reader', 'ledger', 'USAGE')")" == "t" ]] \
  && ok "missing role recreated with its grants" || bad "missing role not recreated"

echo "== the source, still unchanged, matches the backup"
if bash "$BASH_DIR/mfi-restore-verify.sh" --verify-only "$B" "$SRC" > "$WORK/src.txt"; then ok "source matches its backup"
else cat "$WORK/src.txt"; bad "source does not match its own backup"; fi

echo "== never over an existing database"
if bash "$BASH_DIR/mfi-restore-verify.sh" "$B" "$DST" > "$WORK/over.txt" 2>&1; then bad "restored over an existing database"
elif grep -q "already exists" "$WORK/over.txt"; then ok "refused to restore over $DST"; else cat "$WORK/over.txt"; bad "unexpected failure"; fi

echo "== a changed byte in the backup is caught"
cp -r "$B" "$WORK/damaged"
printf 'X' | dd of="$WORK/damaged/mfi.dump.gpg" bs=1 seek=4000 conv=notrunc status=none
if bash "$BASH_DIR/mfi-restore-verify.sh" "$WORK/damaged" "$DST2" > "$WORK/damaged.txt" 2>&1; then bad "damaged backup accepted"
elif grep -q "checksums do not match" "$WORK/damaged.txt"; then ok "damaged backup refused before restoring"; else cat "$WORK/damaged.txt"; bad "unexpected failure"; fi

echo "== silent changes after restore are caught (TEST-ONLY tampering, triggers bypassed as superuser)"
admin -d "$DST" <<'SQL'
SET session_replication_role = replica;
UPDATE ledger.journal_line SET narrative = 'changed' WHERE (tenant_id, entry_id, line_no) =
  (SELECT tenant_id, entry_id, line_no FROM ledger.journal_line ORDER BY entry_id, line_no LIMIT 1);
SQL
bash "$BASH_DIR/mfi-restore-verify.sh" --verify-only "$B" "$DST" > "$WORK/narr.txt" || true
grep -q "^FAIL fingerprint" "$WORK/narr.txt" && grep -q "digest|ledger.journal_line" "$WORK/narr.txt" \
  && ok "a changed narrative (totals unchanged) is caught by the row digest" || { cat "$WORK/narr.txt"; bad "changed narrative not caught"; }
admin -d "$DST" <<'SQL'
SET session_replication_role = replica;
UPDATE audit.staff_action SET actor = 'someone-else' WHERE chain_no = 7;
SQL
bash "$BASH_DIR/mfi-restore-verify.sh" --verify-only "$B" "$DST" > "$WORK/audit.txt" || true
grep -q "^FAIL audit chain TEST-PERF: broken at link 7" "$WORK/audit.txt" \
  && ok "a changed audit row is caught by the chain check" || { cat "$WORK/audit.txt"; bad "changed audit row not caught"; }

echo
if [[ $failures -gt 0 ]]; then echo "DRILL RESULT: $failures failure(s)"; exit 1; fi
echo "DRILL RESULT: all checks passed"
