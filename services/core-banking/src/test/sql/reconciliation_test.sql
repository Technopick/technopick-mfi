-- =============================================================================
-- Tests for V22__reconciliation_results.sql, run as mfi_app after write_off_test.sql
-- (tenant A's end of day for 2026-11-21 is RUNNING).
-- =============================================================================
\set ON_ERROR_STOP on
\set QUIET on
\set tA '''00000000-0000-0000-0000-0000000000a1'''
\set tB '''00000000-0000-0000-0000-0000000000b2'''

BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO ledger.reconciliation_result (tenant_id, business_date, check_code, account_code, currency, ledger_balance,
    subledger_balance, items)
VALUES (:tA::uuid, '2026-11-21', 'LOAN_PRINCIPAL', '1200-LOANS-PRINCIPAL', 'KES', 100, 90, 1);
DO $$
BEGIN
  IF (SELECT difference FROM ledger.reconciliation_result WHERE business_date = '2026-11-21') <> 10 THEN
    RAISE EXCEPTION 'TEST FAILED G2: difference is ledger less subledger';
  END IF;
  BEGIN
    INSERT INTO ledger.reconciliation_result (tenant_id, business_date, check_code, account_code, currency, ledger_balance,
        subledger_balance, items)
    VALUES ('00000000-0000-0000-0000-0000000000a1', '2026-11-20', 'LOAN_PRINCIPAL', '1200-LOANS-PRINCIPAL', 'KES', 1, 1, 1);
    RAISE EXCEPTION 'TEST FAILED G1a: result for a day not being closed';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'EOD_WRONG_DATE%' THEN RAISE; END IF;
  END;
  BEGIN
    UPDATE ledger.reconciliation_result SET subledger_balance = 100;
    RAISE EXCEPTION 'TEST FAILED G1b';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
END $$;
ROLLBACK;
\echo PASS G1 G2 results only during that day end of day, difference = ledger - subledger, never edited

-- The application's reconciliation query (ReconciliationService.CHECKS), run against this data.
BEGIN;
SET LOCAL app.tenant_id = :tA;
\i :recon_sql
COMMIT;
\echo PASS G3 reconciliation query runs against the full test data
