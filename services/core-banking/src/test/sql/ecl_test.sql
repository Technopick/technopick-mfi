-- =============================================================================
-- Tests for V33__ifrs9_ecl_provisioning.sql. Runs as the owner only to record a closed month end
-- (30 and 31 Oct 2026 closed) for tenant A; every check then runs as mfi_app. Everything is rolled back.
-- =============================================================================
\set ON_ERROR_STOP on
\set QUIET on

BEGIN;
INSERT INTO ledger.gl_account (tenant_id, account_code, name, account_class, currency) VALUES
  ('00000000-0000-0000-0000-0000000000a1', 'ECL-ALLOW', 'Loan loss allowance (test)', 'ASSET', 'KES'),
  ('00000000-0000-0000-0000-0000000000a1', 'ECL-EXP', 'Impairment expense (test)', 'EXPENSE', 'KES'),
  ('00000000-0000-0000-0000-0000000000a1', 'ECL-INC', 'Some income (test)', 'INCOME', 'KES');
ALTER TABLE ledger.eod_run DISABLE TRIGGER eod_run_guard;
INSERT INTO ledger.eod_run (tenant_id, business_date, status, finished_at) VALUES ('00000000-0000-0000-0000-0000000000a1', '2026-10-30', 'COMPLETED', clock_timestamp()),
  ('00000000-0000-0000-0000-0000000000a1', '2026-10-31', 'COMPLETED', clock_timestamp());
ALTER TABLE ledger.eod_run ENABLE TRIGGER eod_run_guard;
SET LOCAL ROLE mfi_app;
SET LOCAL app.tenant_id = '00000000-0000-0000-0000-0000000000a1';

-- EC1: the allowance is an asset account of its own and becomes a control account; the expense an expense account.
DO $$
BEGIN
  BEGIN
    INSERT INTO ledger.ecl_settings (tenant_id, allowance_account, expense_account, updated_by)
    VALUES (tenancy.current_tenant(), 'ECL-INC', 'ECL-EXP', 'fin:a');
    RAISE EXCEPTION 'TEST FAILED EC1a: income account accepted as allowance';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'ECL_ACCOUNT_INVALID%' THEN RAISE; END IF;
  END;
  BEGIN
    INSERT INTO ledger.ecl_settings (tenant_id, allowance_account, expense_account, updated_by)
    VALUES (tenancy.current_tenant(), 'ECL-ALLOW', 'ECL-INC', 'fin:a');
    RAISE EXCEPTION 'TEST FAILED EC1b: income account accepted as expense';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'ECL_ACCOUNT_INVALID%' THEN RAISE; END IF;
  END;
  BEGIN
    INSERT INTO ledger.ecl_settings (tenant_id, allowance_account, expense_account, updated_by)
    VALUES (tenancy.current_tenant(), (SELECT gl_principal FROM lending.loan_product_version LIMIT 1), 'ECL-EXP', 'fin:a');
    RAISE EXCEPTION 'TEST FAILED EC1c: loan principal control account accepted as allowance';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'ECL_ACCOUNT_INVALID%' THEN RAISE; END IF;
  END;
  BEGIN
    INSERT INTO ledger.ecl_settings (tenant_id, allowance_account, expense_account, stage2_dpd, stage3_dpd, updated_by)
    VALUES (tenancy.current_tenant(), 'ECL-ALLOW', 'ECL-EXP', 90, 30, 'fin:a');
    RAISE EXCEPTION 'TEST FAILED EC1d: stage 3 before stage 2';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  INSERT INTO ledger.ecl_settings (tenant_id, allowance_account, expense_account, updated_by)
  VALUES (tenancy.current_tenant(), 'ECL-ALLOW', 'ECL-EXP', 'fin:a');
  IF NOT ledger.is_control_account(tenancy.current_tenant(), 'ECL-ALLOW') THEN
    RAISE EXCEPTION 'TEST FAILED EC1e: allowance is not a control account';
  END IF;
END $$;
\echo PASS EC1 ECL allowance is an asset account of its own and a control account, expense an expense account

-- EC2: runs only for closed month ends, maker-checker, exact line arithmetic, one open run, decided runs never change.
DO $$
DECLARE r uuid := gen_random_uuid(); r2 uuid := gen_random_uuid(); v_loan uuid; product text; branch text;
BEGIN
  SELECT l.loan_id, v.product_code, l.branch_code INTO v_loan, product, branch FROM lending.loan l
    JOIN lending.loan_product_version v ON v.tenant_id = l.tenant_id AND v.version_id = l.product_version_id LIMIT 1;
  IF v_loan IS NULL THEN RAISE EXCEPTION 'TEST SETUP EC2: tenant A needs a loan'; END IF;
  BEGIN
    INSERT INTO ledger.ecl_run (tenant_id, run_id, idempotency_key, as_of, allowance_account, expense_account, stage2_dpd,
        stage3_dpd, loans, required_total, previous_total, change, requested_by)
    VALUES (tenancy.current_tenant(), gen_random_uuid(), 'ec-a', '2026-09-30', 'ECL-ALLOW', 'ECL-EXP', 30, 90, 0, 0, 0, 0, 'fin:a');
    RAISE EXCEPTION 'TEST FAILED EC2a: run for a day never closed';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'ECL_DAY_NOT_CLOSED%' THEN RAISE; END IF;
  END;
  BEGIN
    INSERT INTO ledger.ecl_run (tenant_id, run_id, idempotency_key, as_of, allowance_account, expense_account, stage2_dpd,
        stage3_dpd, loans, required_total, previous_total, change, requested_by)
    VALUES (tenancy.current_tenant(), gen_random_uuid(), 'ec-b', '2026-10-30', 'ECL-ALLOW', 'ECL-EXP', 30, 90, 0, 0, 0, 0, 'fin:a');
    RAISE EXCEPTION 'TEST FAILED EC2b: run for a day that is not a month end';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  INSERT INTO ledger.ecl_run (tenant_id, run_id, idempotency_key, as_of, allowance_account, expense_account, stage2_dpd,
      stage3_dpd, loans, required_total, previous_total, change, requested_by)
  VALUES (tenancy.current_tenant(), r, 'ec-c', '2026-10-31', 'ECL-ALLOW', 'ECL-EXP', 30, 90, 1, 120.00, 0, 120.00, 'fin:a');
  BEGIN
    INSERT INTO ledger.ecl_run_line (tenant_id, run_id, loan_id, product_code, branch_code, days_past_due, stage, exposure, rate_pct, ecl)
    VALUES (tenancy.current_tenant(), r, v_loan, product, branch, 0, 1, 12000.00, 1.0000, 100.00);
    RAISE EXCEPTION 'TEST FAILED EC2c: line ECL is not exposure times rate';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  INSERT INTO ledger.ecl_run_line (tenant_id, run_id, loan_id, product_code, branch_code, days_past_due, stage, exposure, rate_pct, ecl)
  VALUES (tenancy.current_tenant(), r, v_loan, product, branch, 0, 1, 12000.00, 1.0000, 120.00);
  BEGIN
    INSERT INTO ledger.ecl_run (tenant_id, run_id, idempotency_key, as_of, allowance_account, expense_account, stage2_dpd,
        stage3_dpd, loans, required_total, previous_total, change, requested_by)
    VALUES (tenancy.current_tenant(), r2, 'ec-d', '2026-10-31', 'ECL-ALLOW', 'ECL-EXP', 30, 90, 0, 0, 0, 0, 'fin:a');
    RAISE EXCEPTION 'TEST FAILED EC2d: second open run';
  EXCEPTION WHEN unique_violation THEN NULL;
  END;
  BEGIN
    UPDATE ledger.ecl_run SET status = 'POSTED', decided_by = 'fin:a', decided_at = clock_timestamp(), entry_id = gen_random_uuid()
     WHERE run_id = r;
    RAISE EXCEPTION 'TEST FAILED EC2e: requester posted own run';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    UPDATE ledger.ecl_run SET status = 'POSTED', decided_by = 'fin:b', decided_at = clock_timestamp() WHERE run_id = r;
    RAISE EXCEPTION 'TEST FAILED EC2f: posted a change without a journal entry';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    UPDATE ledger.ecl_run SET required_total = 0, change = 0 WHERE run_id = r;
    RAISE EXCEPTION 'TEST FAILED EC2g: run figures changed';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'ECL_IMMUTABLE%' THEN RAISE; END IF;
  END;
  UPDATE ledger.ecl_run SET status = 'REJECTED', decided_by = 'fin:b', decided_at = clock_timestamp(), decision_reason = 'rates'
   WHERE run_id = r;
  BEGIN
    INSERT INTO ledger.ecl_run_line (tenant_id, run_id, loan_id, product_code, branch_code, days_past_due, stage, exposure, rate_pct, ecl)
    VALUES (tenancy.current_tenant(), r, (SELECT loan_id FROM lending.loan WHERE loan_id <> v_loan LIMIT 1), product, branch, 0, 1, 0, 1, 0);
    RAISE EXCEPTION 'TEST FAILED EC2h: line added to a decided run';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'ECL_IMMUTABLE%' THEN RAISE; END IF;
  END;
  BEGIN
    UPDATE ledger.ecl_run SET status = 'REQUESTED', decided_by = NULL, decided_at = NULL WHERE run_id = r;
    RAISE EXCEPTION 'TEST FAILED EC2i: rejected run reopened';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'ECL_IMMUTABLE%' THEN RAISE; END IF;
  END;
END $$;
\echo PASS EC2 ECL runs need a closed month end, maker-checker and exact line arithmetic; decided runs never change

-- EC3: a run whose total is not the sum of its lines is refused at commit.
SAVEPOINT ec3;
INSERT INTO ledger.ecl_run (tenant_id, run_id, idempotency_key, as_of, allowance_account, expense_account, stage2_dpd,
    stage3_dpd, loans, required_total, previous_total, change, requested_by)
VALUES (tenancy.current_tenant(), gen_random_uuid(), 'ec-e', '2026-10-31', 'ECL-ALLOW', 'ECL-EXP', 30, 90, 3, 999.00, 0, 999.00, 'fin:a');
DO $$
BEGIN
  SET CONSTRAINTS ALL IMMEDIATE;
  RAISE EXCEPTION 'TEST FAILED EC3: run total without matching lines accepted';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'ECL_TOTAL_MISMATCH%' THEN RAISE; END IF;
END $$;
ROLLBACK TO SAVEPOINT ec3;
\echo PASS EC3 ECL run totals must equal the sum of their lines
ROLLBACK;
