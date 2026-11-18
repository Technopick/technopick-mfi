#!/usr/bin/env bash
# Two sessions race for the same KES 1,000 on account 0100000002 (seeded by
# savings_holds_test.sql). Session 1 holds 700 and keeps its transaction open for 2 s;
# session 2 starts 0.5 s later and tries to hold (C1) or directly debit (C2) another 700.
# Exactly one must succeed. Usage: concurrency_hold_race_test.sh <db> <app-password> <host>
set -euo pipefail
DB="$1"; PW="$2"; HOST="$3"
TENANT="00000000-0000-0000-0000-0000000000a1"
ACC="aaaaaaaa-0000-0000-0000-000000000002"
APP=(env PGPASSWORD="$PW" psql -h "$HOST" -U mfi_app -d "$DB" -v ON_ERROR_STOP=1 -qAt)

hold_sql() { # $1 key, $2 sleep seconds
  cat <<SQL
BEGIN;
SET LOCAL app.tenant_id = '$TENANT';
INSERT INTO savings.account_hold (tenant_id, hold_id, account_id, kind, amount, currency, idempotency_key, expires_at)
VALUES ('$TENANT', gen_random_uuid(), '$ACC', 'AUTHORIZATION', 700.00, 'KES', '$1', now() + interval '1 day');
SELECT pg_sleep($2);
COMMIT;
SQL
}

debit_sql() {
  cat <<SQL
BEGIN;
SET LOCAL app.tenant_id = '$TENANT';
INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
VALUES ('$TENANT', '66666666-0000-0000-0000-000000000001', 'race-debit', '2026-11-18', 'TELLER', 'c', 'u');
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code, subledger_account_id) VALUES
 ('$TENANT', '66666666-0000-0000-0000-000000000001', 1, '2110-SAVINGS-CONTROL', 'D', 700.00, 'KES', 'NBO01', '$ACC'),
 ('$TENANT', '66666666-0000-0000-0000-000000000001', 2, '1000-VAULT-CASH',      'C', 700.00, 'KES', 'NBO01', NULL);
COMMIT;
SQL
}

active_holds() {
  "${APP[@]}" -c "BEGIN; SET LOCAL app.tenant_id = '$TENANT';
    SELECT coalesce(sum(amount),0) FROM savings.account_hold WHERE account_id = '$ACC' AND status = 'ACTIVE'; COMMIT;" | grep -E '^[0-9.]+$'
}

release_all() {
  "${APP[@]}" -c "BEGIN; SET LOCAL app.tenant_id = '$TENANT';
    UPDATE savings.account_hold SET status = 'RELEASED' WHERE account_id = '$ACC' AND status = 'ACTIVE'; COMMIT;" >/dev/null
}

# C1: hold vs hold
hold_sql race-h1 2 | "${APP[@]}" >/dev/null 2>/tmp/race1.err & P1=$!
sleep 0.5
set +e
hold_sql race-h2 0 | "${APP[@]}" >/dev/null 2>/tmp/race2.err; R2=$?
wait $P1; R1=$?
set -e
HELD=$(active_holds)
if [[ $R1 -eq 0 && $R2 -ne 0 && "$HELD" == "700.0000" ]] && grep -q SAVINGS_INSUFFICIENT_AVAILABLE /tmp/race2.err; then
  echo "PASS C1 concurrent holds: first wins, second waits then is rejected (active holds = $HELD)"
else
  echo "TEST FAILED C1: r1=$R1 r2=$R2 held=$HELD"; cat /tmp/race2.err; exit 1
fi
release_all

# C2: hold vs direct debit
hold_sql race-h3 2 | "${APP[@]}" >/dev/null 2>/tmp/race1.err & P1=$!
sleep 0.5
set +e
debit_sql | "${APP[@]}" >/dev/null 2>/tmp/race2.err; R2=$?
wait $P1; R1=$?
set -e
if [[ $R1 -eq 0 && $R2 -ne 0 ]] && grep -q SAVINGS_INSUFFICIENT_AVAILABLE /tmp/race2.err; then
  echo "PASS C2 concurrent hold and debit: debit rejected at commit after the hold committed"
else
  echo "TEST FAILED C2: r1=$R1 r2=$R2"; cat /tmp/race2.err; exit 1
fi
