-- =============================================================================
-- Tests for V4__business_date_eod_arrears.sql, run as mfi_app after lending_test.sql.
-- Loan bbbb...01 (KES 100,000, disbursed 2026-11-18) is ACTIVE with principal 92,544.04.
-- =============================================================================
\set ON_ERROR_STOP on
\set QUIET on
\set tA '''00000000-0000-0000-0000-0000000000a1'''
\set tB '''00000000-0000-0000-0000-0000000000b2'''
\set loan '''bbbbbbbb-0000-0000-0000-000000000001'''

BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO tenancy.business_date (tenant_id, current_date_) VALUES (:tA::uuid, '2026-11-19');
COMMIT;

-- ---------- E1: cannot advance without a completed EOD ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  UPDATE tenancy.business_date SET current_date_ = '2026-11-20';
  RAISE EXCEPTION 'TEST FAILED E1';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'EOD_NOT_COMPLETED%' THEN RAISE; END IF;
END $$;
ROLLBACK;
\echo PASS E1 business date cannot advance before EOD completes

-- ---------- E2: EOD only for the current business date ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  INSERT INTO ledger.eod_run (tenant_id, business_date) VALUES ('00000000-0000-0000-0000-0000000000a1', '2026-11-21');
  RAISE EXCEPTION 'TEST FAILED E2';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'EOD_WRONG_DATE%' THEN RAISE; END IF;
END $$;
ROLLBACK;
\echo PASS E2 EOD for a date other than the business date rejected

-- ---------- E3: a day's accrual: entry + record together ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO ledger.eod_run (tenant_id, business_date) VALUES (:tA::uuid, '2026-11-19');
INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
VALUES (:tA::uuid, '99999999-0000-0000-0000-000000000001', 'loan:bbbbbbbb-0000-0000-0000-000000000001:ACCRUE:2026-11-19',
        '2026-11-19', 'LOAN_EOD', 'eod-2026-11-19', 'system:eod');
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
  (:tA::uuid, '99999999-0000-0000-0000-000000000001', 1, '1210-INTEREST-RECEIVABLE', 'D', 66.67, 'KES', 'NBO01'),
  (:tA::uuid, '99999999-0000-0000-0000-000000000001', 2, '4000-INTEREST-INCOME',     'C', 66.67, 'KES', 'NBO01');
INSERT INTO lending.loan_accrual (tenant_id, loan_id, installment_no, business_date, amount, entry_id)
VALUES (:tA::uuid, :loan::uuid, 1, '2026-11-19', 66.67, '99999999-0000-0000-0000-000000000001');
COMMIT;
\echo PASS E3 accrual of 66.67 recorded with its journal entry

-- ---------- E4: same installment and date cannot accrue twice ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  INSERT INTO lending.loan_accrual (tenant_id, loan_id, installment_no, business_date, amount, entry_id)
  VALUES ('00000000-0000-0000-0000-0000000000a1', 'bbbbbbbb-0000-0000-0000-000000000001', 1, '2026-11-19', 66.67,
          '99999999-0000-0000-0000-000000000001');
  RAISE EXCEPTION 'TEST FAILED E4';
EXCEPTION WHEN unique_violation THEN NULL;
END $$;
ROLLBACK;
\echo PASS E4 duplicate accrual for the same day rejected

-- ---------- E5: record tied to the wrong entry rejected ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  INSERT INTO lending.loan_accrual (tenant_id, loan_id, installment_no, business_date, amount, entry_id)
  VALUES ('00000000-0000-0000-0000-0000000000a1', 'bbbbbbbb-0000-0000-0000-000000000001', 2, '2026-11-19', 66.67,
          '77777777-0000-0000-0000-000000000002');  -- the repayment entry
  RAISE EXCEPTION 'TEST FAILED E5';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'EOD_ENTRY_MISMATCH%' THEN RAISE; END IF;
END $$;
ROLLBACK;
\echo PASS E5 accrual linked to an unrelated entry rejected

-- ---------- E6: accrual record that disagrees with its entry rejected ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  INSERT INTO lending.loan_accrual (tenant_id, loan_id, installment_no, business_date, amount, entry_id)
  VALUES ('00000000-0000-0000-0000-0000000000a1', 'bbbbbbbb-0000-0000-0000-000000000001', 2, '2026-11-19', 1.00,
          '99999999-0000-0000-0000-000000000001');  -- entry total stays 66.67, records now 67.67
  SET CONSTRAINTS ALL IMMEDIATE;
  RAISE EXCEPTION 'TEST FAILED E6';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'EOD_AMOUNT_MISMATCH%' THEN RAISE; END IF;
END $$;
ROLLBACK;
\echo PASS E6 accrual records that disagree with the posted entry rejected

-- ---------- E7: penalty charged once per installment and date ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
VALUES (:tA::uuid, '99999999-0000-0000-0000-000000000002', 'loan:bbbbbbbb-0000-0000-0000-000000000001:PENALTY:1:2026-11-19',
        '2026-11-19', 'LOAN_EOD', 'eod', 'system:eod');
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
  (:tA::uuid, '99999999-0000-0000-0000-000000000002', 1, '1230-PENALTY-RECEIVABLE', 'D', 200.00, 'KES', 'NBO01'),
  (:tA::uuid, '99999999-0000-0000-0000-000000000002', 2, '4300-PENALTY-INCOME',     'C', 200.00, 'KES', 'NBO01');
INSERT INTO lending.penalty_charge (tenant_id, loan_id, installment_no, business_date, amount, entry_id)
VALUES (:tA::uuid, :loan::uuid, 1, '2026-11-19', 200.00, '99999999-0000-0000-0000-000000000002');
DO $$
BEGIN
  INSERT INTO lending.penalty_charge (tenant_id, loan_id, installment_no, business_date, amount, entry_id)
  VALUES ('00000000-0000-0000-0000-0000000000a1', 'bbbbbbbb-0000-0000-0000-000000000001', 1, '2026-11-19', 200.00,
          '99999999-0000-0000-0000-000000000002');
  RAISE EXCEPTION 'TEST FAILED E7';
EXCEPTION WHEN unique_violation THEN NULL;
END $$;
ROLLBACK;
\echo PASS E7 penalty recorded with its entry; repeat for same day rejected

-- ---------- E8: over-accrual beyond scheduled interest rejected ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
  VALUES ('00000000-0000-0000-0000-0000000000a1', '99999999-0000-0000-0000-000000000003',
          'loan:bbbbbbbb-0000-0000-0000-000000000001:ACCRUE:2026-11-19-x', '2026-11-19', 'LOAN_EOD', 'c', 'u');
  -- Uses installment 12 (scheduled interest 185.41) to accrue 185.42.
  INSERT INTO lending.loan_accrual (tenant_id, loan_id, installment_no, business_date, amount, entry_id)
  VALUES ('00000000-0000-0000-0000-0000000000a1', 'bbbbbbbb-0000-0000-0000-000000000001', 12, '2026-11-19', 185.42,
          '99999999-0000-0000-0000-000000000003');
  RAISE EXCEPTION 'TEST FAILED E8 (key check should fire first)';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'EOD_ENTRY_MISMATCH%' THEN RAISE; END IF;
END $$;
ROLLBACK;
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  -- Correctly keyed entry for the day, but 185.42 on installment 12 exceeds its 185.41.
  INSERT INTO lending.loan_accrual (tenant_id, loan_id, installment_no, business_date, amount, entry_id)
  VALUES ('00000000-0000-0000-0000-0000000000a1', 'bbbbbbbb-0000-0000-0000-000000000001', 12, '2026-11-19', 185.42,
          '99999999-0000-0000-0000-000000000001');
  SET CONSTRAINTS ALL IMMEDIATE;
  RAISE EXCEPTION 'TEST FAILED E8';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'EOD_OVER_ACCRUAL%' THEN RAISE; END IF;
END $$;
ROLLBACK;
\echo PASS E8 accrual beyond the scheduled interest of the installment rejected

-- ---------- E9: arrears snapshot; bucket must match DPD; insert-only ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO lending.arrears_snapshot (tenant_id, loan_id, as_of, days_past_due, bucket, classification, arrears_amount, outstanding_principal)
VALUES (:tA::uuid, :loan::uuid, '2026-11-19', 0, 'CURRENT', 'NORMAL', 0, 92544.04);
DO $$
BEGIN
  INSERT INTO lending.arrears_snapshot (tenant_id, loan_id, as_of, days_past_due, bucket, classification, arrears_amount, outstanding_principal)
  VALUES ('00000000-0000-0000-0000-0000000000a1', 'bbbbbbbb-0000-0000-0000-000000000001', '2026-11-20', 45, 'DPD_1_30', 'WATCH', 10, 92544.04);
  RAISE EXCEPTION 'TEST FAILED E9 bucket';
EXCEPTION WHEN check_violation THEN NULL;
END $$;
DO $$
BEGIN
  UPDATE lending.arrears_snapshot SET days_past_due = 0 WHERE loan_id = 'bbbbbbbb-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'TEST FAILED E9 update';
EXCEPTION WHEN insufficient_privilege THEN NULL;
END $$;
COMMIT;
\echo PASS E9 snapshot stored; bucket inconsistent with DPD rejected; snapshots cannot be edited

-- ---------- E10: PAR view ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
DECLARE p record;
BEGIN
  SELECT * INTO p FROM lending.par_by_branch WHERE as_of = '2026-11-19' AND branch_code = 'NBO01';
  IF p.loans <> 1 OR p.portfolio <> 92544.04 OR p.par_1 <> 0 OR p.at_risk_1 IS NOT NULL THEN
    RAISE EXCEPTION 'TEST FAILED E10: %', row_to_json(p);
  END IF;
END $$;
COMMIT;
\echo PASS E10 PAR view: 1 loan, portfolio 92544.04, PAR1 0.0000

-- ---------- E11: complete EOD, then advance exactly one day ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
UPDATE ledger.eod_run SET status = 'COMPLETED', loans_processed = 1 WHERE business_date = '2026-11-19';
DO $$
BEGIN
  UPDATE tenancy.business_date SET current_date_ = '2026-11-21';
  RAISE EXCEPTION 'TEST FAILED E11 skip';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'EOD_BUSINESS_DATE%' THEN RAISE; END IF;
END $$;
UPDATE tenancy.business_date SET current_date_ = '2026-11-20';
DO $$
BEGIN
  UPDATE ledger.eod_run SET status = 'RUNNING' WHERE business_date = '2026-11-19';
  RAISE EXCEPTION 'TEST FAILED E11 reopen';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'EOD_RUN_STATUS%' THEN RAISE; END IF;
END $$;
COMMIT;
\echo PASS E11 skipping a date rejected; advance by one day after completion; completed run is final

-- ---------- E12: records for a past date rejected once the date has moved on ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  INSERT INTO lending.loan_accrual (tenant_id, loan_id, installment_no, business_date, amount, entry_id)
  VALUES ('00000000-0000-0000-0000-0000000000a1', 'bbbbbbbb-0000-0000-0000-000000000001', 2, '2026-11-19', 1.00,
          '99999999-0000-0000-0000-000000000001');
  RAISE EXCEPTION 'TEST FAILED E12';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'EOD_WRONG_DATE%' THEN RAISE; END IF;
END $$;
ROLLBACK;
\echo PASS E12 back-dated EOD record rejected

-- ---------- E13: cross-tenant ----------
BEGIN;
SET LOCAL app.tenant_id = :tB;
DO $$
DECLARE n int;
BEGIN
  SELECT count(*) INTO n FROM lending.loan_accrual;     IF n <> 0 THEN RAISE EXCEPTION 'TEST FAILED E13 accruals'; END IF;
  SELECT count(*) INTO n FROM lending.arrears_snapshot; IF n <> 0 THEN RAISE EXCEPTION 'TEST FAILED E13 snapshots'; END IF;
  SELECT count(*) INTO n FROM lending.par_by_branch;    IF n <> 0 THEN RAISE EXCEPTION 'TEST FAILED E13 par'; END IF;
  SELECT count(*) INTO n FROM ledger.eod_run;           IF n <> 0 THEN RAISE EXCEPTION 'TEST FAILED E13 runs'; END IF;
END $$;
ROLLBACK;
\echo PASS E13 tenant B sees no EOD, accrual, snapshot or PAR data

\echo ALL EOD TESTS PASSED
