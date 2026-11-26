#!/usr/bin/env bash
# Runs the ledger database invariant tests against a throwaway PostgreSQL database.
#
# Usage: run-ledger-db-tests.sh
# Env:   PGHOST, PGPORT, PGUSER  admin connection (able to CREATE DATABASE and CREATE ROLE)
#        MFI_APP_HOST           host the app role connects through (default: PGHOST or localhost)
#        MFI_TEST_APP_PASSWORD (optional; a random one is generated if unset)
#
# In CI this runs against a PostgreSQL service container; Testcontainers-based JUnit
# tests will wrap the same SQL once the networked build is available.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MIGRATIONS="$HERE/../../main/resources/db/migration"
DB="mfi_ledger_test_$$"
APP_PW="${MFI_TEST_APP_PASSWORD:-$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9')}"

cleanup() {
  psql -v ON_ERROR_STOP=0 -q -d postgres -c "DROP DATABASE IF EXISTS $DB" >/dev/null 2>&1 || true
}
trap cleanup EXIT

psql -v ON_ERROR_STOP=1 -q -d postgres <<SQL
DO \$\$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mfi_app') THEN
    CREATE ROLE mfi_app LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mfi_relay') THEN
    CREATE ROLE mfi_relay LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
  END IF;
END \$\$;
ALTER ROLE mfi_app PASSWORD '$APP_PW';
CREATE DATABASE $DB;
SQL

echo "== applying migrations to $DB"
for f in $(ls "$MIGRATIONS"/V*__*.sql | sort -V); do
  echo "   $(basename "$f")"
  psql -v ON_ERROR_STOP=1 -q -d "$DB" -f "$f"
done

echo "== seeding tenants (platform administration, as owner)"
psql -v ON_ERROR_STOP=1 -q -d "$DB" <<'SQL'
INSERT INTO tenancy.tenant (tenant_id, code, legal_name, licence_type, deposits_allowed, base_currency) VALUES
 ('00000000-0000-0000-0000-0000000000a1', 'TEST-MFB-A', 'TEST ONLY Microfinance Bank A', 'MICROFINANCE_BANK', true, 'KES'),
 ('00000000-0000-0000-0000-0000000000b2', 'TEST-SACCO-B', 'TEST ONLY Sacco B', 'DEPOSIT_TAKING_SACCO', true, 'KES');
SQL

echo "== invariant tests as application role mfi_app"
PGPASSWORD="$APP_PW" psql -h "${MFI_APP_HOST:-${PGHOST:-localhost}}" -U mfi_app -d "$DB" -f "$HERE/ledger_invariants_test.sql"

echo "== savings and hold tests as application role mfi_app"
PGPASSWORD="$APP_PW" psql -h "${MFI_APP_HOST:-${PGHOST:-localhost}}" -U mfi_app -d "$DB" -f "$HERE/savings_holds_test.sql"

echo "== lending tests as application role mfi_app"
PGPASSWORD="$APP_PW" psql -h "${MFI_APP_HOST:-${PGHOST:-localhost}}" -U mfi_app -d "$DB" -f "$HERE/lending_test.sql"

echo "== business date and EOD tests as application role mfi_app"
PGPASSWORD="$APP_PW" psql -h "${MFI_APP_HOST:-${PGHOST:-localhost}}" -U mfi_app -d "$DB" -f "$HERE/eod_test.sql"

echo "== M-Pesa STK Push tests as application role mfi_app"
PGPASSWORD="$APP_PW" psql -h "${MFI_APP_HOST:-${PGHOST:-localhost}}" -U mfi_app -d "$DB" -f "$HERE/mpesa_stk_test.sql"

echo "== M-Pesa B2C disbursement tests as application role mfi_app"
PGPASSWORD="$APP_PW" psql -h "${MFI_APP_HOST:-${PGHOST:-localhost}}" -U mfi_app -d "$DB" -f "$HERE/mpesa_b2c_test.sql"

echo "== M-Pesa manual resolution tests as application role mfi_app"
PGPASSWORD="$APP_PW" psql -h "${MFI_APP_HOST:-${PGHOST:-localhost}}" -U mfi_app -d "$DB" -f "$HERE/mpesa_resolution_test.sql"

echo "== Customer and KYC tests as application role mfi_app"
PGPASSWORD="$APP_PW" psql -h "${MFI_APP_HOST:-${PGHOST:-localhost}}" -U mfi_app -d "$DB" -f "$HERE/customer_test.sql"

echo "== teller configuration (platform administration, as owner)"
psql -v ON_ERROR_STOP=1 -q -d "$DB" <<'SQL'
INSERT INTO ledger.gl_account (tenant_id, account_code, name, account_class, currency, requires_subledger) VALUES
 ('00000000-0000-0000-0000-0000000000a1', '2111-TEST-SAVINGS', 'TEST savings control', 'LIABILITY', 'KES', true),
 ('00000000-0000-0000-0000-0000000000a1', '1001-TEST-CASH', 'TEST branch cash', 'ASSET', 'KES', false),
 ('00000000-0000-0000-0000-0000000000a1', '1002-TEST-TILL-1', 'TEST till 1', 'ASSET', 'KES', false),
 ('00000000-0000-0000-0000-0000000000a1', '1003-TEST-TILL-2', 'TEST till 2', 'ASSET', 'KES', false),
 ('00000000-0000-0000-0000-0000000000a1', '5200-TEST-SHORTAGE', 'TEST teller shortages', 'EXPENSE', 'KES', false),
 ('00000000-0000-0000-0000-0000000000a1', '2950-TEST-OVERAGE', 'TEST teller overages', 'LIABILITY', 'KES', false),
 ('00000000-0000-0000-0000-0000000000a1', '5300-TEST-INT-EXP', 'TEST savings interest expense', 'EXPENSE', 'KES', false),
 ('00000000-0000-0000-0000-0000000000a1', '2120-TEST-INT-PAY', 'TEST savings interest payable', 'LIABILITY', 'KES', false),
 ('00000000-0000-0000-0000-0000000000a1', '2960-TEST-WHT', 'TEST withholding tax payable', 'LIABILITY', 'KES', false);
INSERT INTO savings.deposit_product (tenant_id, product_code, name, currency, gl_control_account, interest_rate,
    interest_min_balance, withholding_tax_rate, gl_interest_expense, gl_interest_payable, gl_withholding_tax) VALUES
 ('00000000-0000-0000-0000-0000000000a1', 'SAVT', 'TEST savings', 'KES', '2111-TEST-SAVINGS', 7.3, 1000, 15,
  '5300-TEST-INT-EXP', '2120-TEST-INT-PAY', '2960-TEST-WHT');
INSERT INTO savings.branch_cash_account (tenant_id, branch_code, currency, gl_cash) VALUES
 ('00000000-0000-0000-0000-0000000000a1', 'NBO01', 'KES', '1001-TEST-CASH');
UPDATE savings.branch_cash_account SET gl_shortage = '5200-TEST-SHORTAGE', gl_overage = '2950-TEST-OVERAGE';
INSERT INTO savings.till (tenant_id, till_id, till_code, branch_code, currency, gl_cash) VALUES
 ('00000000-0000-0000-0000-0000000000a1', '7e000000-0000-0000-0000-000000000001', 'NBO01-T1', 'NBO01', 'KES', '1002-TEST-TILL-1'),
 ('00000000-0000-0000-0000-0000000000a1', '7e000000-0000-0000-0000-000000000002', 'NBO01-T2', 'NBO01', 'KES', '1003-TEST-TILL-2');
INSERT INTO savings.teller_policy (tenant_id, currency, withdrawal_approval_above, deposit_approval_above) VALUES
 ('00000000-0000-0000-0000-0000000000a1', 'KES', 50000, 1000000);
SQL

echo "== savings accounts and teller tests as application role mfi_app"
PGPASSWORD="$APP_PW" psql -h "${MFI_APP_HOST:-${PGHOST:-localhost}}" -U mfi_app -d "$DB" -f "$HERE/savings_teller_test.sql"

echo "== teller till tests as application role mfi_app"
PGPASSWORD="$APP_PW" psql -h "${MFI_APP_HOST:-${PGHOST:-localhost}}" -U mfi_app -d "$DB" -f "$HERE/till_test.sql"

echo "== savings interest tests as application role mfi_app"
PGPASSWORD="$APP_PW" psql -h "${MFI_APP_HOST:-${PGHOST:-localhost}}" -U mfi_app -d "$DB" -f "$HERE/savings_interest_test.sql"

echo "== Loan origination tests as application role mfi_app"
PGPASSWORD="$APP_PW" psql -h "${MFI_APP_HOST:-${PGHOST:-localhost}}" -U mfi_app -d "$DB" -f "$HERE/loan_origination_test.sql"

echo "== loan closure and credit tests as application role mfi_app"
PGPASSWORD="$APP_PW" psql -h "${MFI_APP_HOST:-${PGHOST:-localhost}}" -U mfi_app -d "$DB" -f "$HERE/loan_closure_test.sql"

echo "== write-off policy (platform administration, as owner)"
psql -v ON_ERROR_STOP=1 -q -d "$DB" <<'SQL'
INSERT INTO ledger.gl_account (tenant_id, account_code, name, account_class, currency, requires_subledger) VALUES
 ('00000000-0000-0000-0000-0000000000a1', '4900-TEST-RECOVERIES', 'TEST recoveries of written-off loans', 'INCOME', 'KES', false);
INSERT INTO lending.write_off_policy (tenant_id, min_days_past_due) VALUES ('00000000-0000-0000-0000-0000000000a1', 90);
SQL

echo "== write-off tests as application role mfi_app"
PGPASSWORD="$APP_PW" psql -h "${MFI_APP_HOST:-${PGHOST:-localhost}}" -U mfi_app -d "$DB" -f "$HERE/write_off_test.sql"

echo "== reconciliation tests as application role mfi_app"
RECON_SQL="$(mktemp /tmp/recon-XXXXXX.sql)"
python3 - "$HERE/../../main/java/ke/co/technopick/mfi/core/accounting/application/ReconciliationService.java" "$RECON_SQL" <<'PY'
import sys
s = open(sys.argv[1]).read()
start = s.index('CHECKS = """') + len('CHECKS = """')
open(sys.argv[2], 'w').write(s[start:s.index('""";', start)].strip() + ";\n")
PY
chmod 644 "$RECON_SQL"
PGPASSWORD="$APP_PW" psql -h "${MFI_APP_HOST:-${PGHOST:-localhost}}" -U mfi_app -d "$DB" -v recon_sql="$RECON_SQL" -f "$HERE/reconciliation_test.sql"
rm -f "$RECON_SQL"

echo "== concurrency: two sessions racing for the same funds"
bash "$HERE/concurrency_hold_race_test.sh" "$DB" "$APP_PW" "${MFI_APP_HOST:-${PGHOST:-localhost}}"

echo "== immutability tests as schema owner"
psql -d "$DB" -f "$HERE/owner_immutability_test.sql"
echo "== manual journal and period close tests as application role mfi_app"
PGPASSWORD="$APP_PW" psql -h "${MFI_APP_HOST:-${PGHOST:-localhost}}" -U mfi_app -d "$DB" -f "$HERE/journals_periods_test.sql"

echo "== audit trail tests as application role mfi_app"
PGPASSWORD="$APP_PW" psql -h "${MFI_APP_HOST:-${PGHOST:-localhost}}" -U mfi_app -d "$DB" -f "$HERE/audit_test.sql"

echo "== audit trail tamper detection as owner"
psql -d "$DB" -f "$HERE/audit_tamper_test.sql"

echo "== end-of-day schedule and attempt tests as application role mfi_app"
PGPASSWORD="$APP_PW" psql -h "${MFI_APP_HOST:-${PGHOST:-localhost}}" -U mfi_app -d "$DB" -f "$HERE/eod_schedule_test.sql"

echo "== prudential settings and regulatory mapping tests as application role mfi_app"
PGPASSWORD="$APP_PW" psql -h "${MFI_APP_HOST:-${PGHOST:-localhost}}" -U mfi_app -d "$DB" -f "$HERE/prudential_test.sql"

echo "== interest suspension tests as application role mfi_app"
PGPASSWORD="$APP_PW" psql -h "${MFI_APP_HOST:-${PGHOST:-localhost}}" -U mfi_app -d "$DB" -f "$HERE/interest_suspense_test.sql"

echo "== early settlement and credit refund tests as application role mfi_app"
PGPASSWORD="$APP_PW" psql -h "${MFI_APP_HOST:-${PGHOST:-localhost}}" -U mfi_app -d "$DB" -f "$HERE/settlement_refund_test.sql"

echo "== member self-service tests as application role mfi_app"
PGPASSWORD="$APP_PW" psql -h "${MFI_APP_HOST:-${PGHOST:-localhost}}" -U mfi_app -d "$DB" -f "$HERE/member_test.sql"

echo "== member PIN and USSD tests as application role mfi_app"
PGPASSWORD="$APP_PW" psql -h "${MFI_APP_HOST:-${PGHOST:-localhost}}" -U mfi_app -d "$DB" -f "$HERE/pin_test.sql"

echo "== SMS notification tests as application role mfi_app"
PGPASSWORD="$APP_PW" psql -h "${MFI_APP_HOST:-${PGHOST:-localhost}}" -U mfi_app -d "$DB" -f "$HERE/sms_test.sql"

echo "== IFRS 9 provisioning tests (owner records a closed month end, then mfi_app)"
psql -v ON_ERROR_STOP=1 -q -d "$DB" -f "$HERE/ecl_test.sql"

echo "== loan rescheduling tests as application role mfi_app"
PGPASSWORD="$APP_PW" psql -h "${MFI_APP_HOST:-${PGHOST:-localhost}}" -U mfi_app -d "$DB" -f "$HERE/reschedule_test.sql"

echo "== M-Pesa statement reconciliation tests as application role mfi_app"
PGPASSWORD="$APP_PW" psql -h "${MFI_APP_HOST:-${PGHOST:-localhost}}" -U mfi_app -d "$DB" -f "$HERE/statement_test.sql"

echo "== M-Pesa B2C status query and float tests as application role mfi_app"
PGPASSWORD="$APP_PW" psql -h "${MFI_APP_HOST:-${PGHOST:-localhost}}" -U mfi_app -d "$DB" -f "$HERE/b2c_status_float_test.sql"

echo "== Share capital tests as application role mfi_app"
PGPASSWORD="$APP_PW" psql -h "${MFI_APP_HOST:-${PGHOST:-localhost}}" -U mfi_app -d "$DB" -f "$HERE/shares_test.sql"

echo "== Fixed deposit tests as application role mfi_app"
PGPASSWORD="$APP_PW" psql -h "${MFI_APP_HOST:-${PGHOST:-localhost}}" -U mfi_app -d "$DB" -f "$HERE/termdep_test.sql"

echo "== Member exit tests as application role mfi_app"
PGPASSWORD="$APP_PW" psql -h "${MFI_APP_HOST:-${PGHOST:-localhost}}" -U mfi_app -d "$DB" -f "$HERE/member_exit_test.sql"

echo "== Loan limit and guarantor tests as application role mfi_app"
PGPASSWORD="$APP_PW" psql -h "${MFI_APP_HOST:-${PGHOST:-localhost}}" -U mfi_app -d "$DB" -f "$HERE/loan_limits_test.sql"

echo "== done"
