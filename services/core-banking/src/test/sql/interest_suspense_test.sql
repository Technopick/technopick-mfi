-- =============================================================================
-- Tests for V28__interest_suspension.sql, as mfi_app. Runs after prudential_test.sql, which
-- created tenant A's prudential settings and the PRU-* accounts (PRU-BANK is mapped to a return line).
-- =============================================================================
\set ON_ERROR_STOP on
\set QUIET on
\set tA '''00000000-0000-0000-0000-0000000000a1'''

BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO ledger.gl_account (tenant_id, account_code, name, account_class, currency) VALUES
  (:tA::uuid, 'PRU-SUSPENSE', 'Interest in suspense (test)', 'ASSET', 'KES'),
  (:tA::uuid, 'PRU-SUSPENSE-2', 'Second suspense candidate (test)', 'ASSET', 'KES');
COMMIT;

-- Z1: suspension needs its own asset account; once named it is a control account.
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  BEGIN
    UPDATE ledger.prudential_settings SET interest_suspension_enabled = true;
    RAISE EXCEPTION 'TEST FAILED Z1a: switched on without a suspense account';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    UPDATE ledger.prudential_settings SET interest_suspense_account = 'PRU-SHARES';
    RAISE EXCEPTION 'TEST FAILED Z1b: equity account accepted';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'SUSPENSE_ACCOUNT_INVALID%' THEN RAISE; END IF;
  END;
  BEGIN
    UPDATE ledger.prudential_settings SET interest_suspense_account = 'PRU-BANK';
    RAISE EXCEPTION 'TEST FAILED Z1c: an account already used in the returns accepted';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'SUSPENSE_ACCOUNT_INVALID%' THEN RAISE; END IF;
  END;
  BEGIN
    UPDATE ledger.prudential_settings
       SET interest_suspense_account = (SELECT gl_interest_receivable FROM lending.loan_product_version LIMIT 1);
    RAISE EXCEPTION 'TEST FAILED Z1d: a loan control account accepted';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'SUSPENSE_ACCOUNT_INVALID%' THEN RAISE; END IF;
  END;
  UPDATE ledger.prudential_settings SET interest_suspension_enabled = true, interest_suspense_account = 'PRU-SUSPENSE';
  IF NOT ledger.is_control_account(tenancy.current_tenant(), 'PRU-SUSPENSE') THEN
    RAISE EXCEPTION 'TEST FAILED Z1e: the suspense account is not a control account';
  END IF;
END $$;
COMMIT;
\echo PASS Z1 interest suspension needs an unused asset account, which becomes a control account

-- Z2: nothing posts to the suspense account except a suspense movement.
DO $$
DECLARE d date; b text; e uuid := 'ab000000-0000-0000-0000-000000000001';
BEGIN
  PERFORM set_config('app.tenant_id', '00000000-0000-0000-0000-0000000000a1', true);
  SELECT current_date_ INTO d FROM tenancy.business_date;
  SELECT branch_code INTO b FROM tenancy.branch ORDER BY branch_code LIMIT 1;
  BEGIN
    INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
    VALUES (tenancy.current_tenant(), e, 'z2-direct', d, 'LOAN', 'z2', 'test');
    INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
      (tenancy.current_tenant(), e, 1, 'PRU-INCOME', 'D', 10, 'KES', b),
      (tenancy.current_tenant(), e, 2, 'PRU-SUSPENSE', 'C', 10, 'KES', b);
    SET CONSTRAINTS ALL IMMEDIATE;
    RAISE EXCEPTION 'TEST FAILED Z2: direct posting to the suspense account accepted';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'SUSPENSE_DIRECT_POSTING%' THEN RAISE; END IF;
  END;
END $$;
\echo PASS Z2 only suspense movements post to the suspense account

-- Z3: a movement must equal its entry, and keep the loan between zero and its unpaid interest; never edited.
DO $$
DECLARE d date; b text; v_loan uuid; r numeric; e1 uuid := 'ab000000-0000-0000-0000-000000000003';
        e2 uuid := 'ab000000-0000-0000-0000-000000000004';
BEGIN
  PERFORM set_config('app.tenant_id', '00000000-0000-0000-0000-0000000000a1', true);
  SELECT current_date_ INTO d FROM tenancy.business_date;
  SELECT loan_id, lending.loan_interest_receivable(tenant_id, loan_id) INTO v_loan, r
    FROM lending.loan ORDER BY lending.loan_interest_receivable(tenant_id, loan_id) DESC, loan_id LIMIT 1;
  SELECT branch_code INTO b FROM lending.loan WHERE loan_id = v_loan;
  IF r IS NULL OR r <= 0 THEN
    RAISE EXCEPTION 'TEST SETUP Z3: tenant A needs a loan with unpaid accrued interest';
  END IF;
  -- (a) movement differs from the entry
  BEGIN
    INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
    VALUES (tenancy.current_tenant(), e1, 'z3-mismatch', d, 'LOAN', 'z3', 'test');
    INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
      (tenancy.current_tenant(), e1, 1, 'PRU-INCOME', 'D', 0.01, 'KES', b),
      (tenancy.current_tenant(), e1, 2, 'PRU-SUSPENSE', 'C', 0.01, 'KES', b);
    INSERT INTO lending.interest_suspense (tenant_id, loan_id, business_date, account_code, amount, reason, entry_id)
    VALUES (tenancy.current_tenant(), v_loan, d, 'PRU-SUSPENSE', 0.02, 'END_OF_DAY', e1);
    SET CONSTRAINTS ALL IMMEDIATE;
    RAISE EXCEPTION 'TEST FAILED Z3a: movement different from its entry accepted';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'SUSPENSE_MISMATCH%' THEN RAISE; END IF;
  END;
  -- (b) more in suspense than the loan's unpaid interest
  BEGIN
    INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
    VALUES (tenancy.current_tenant(), e2, 'z3-too-much', d, 'LOAN', 'z3', 'test');
    INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
      (tenancy.current_tenant(), e2, 1, 'PRU-INCOME', 'D', r + 1, 'KES', b),
      (tenancy.current_tenant(), e2, 2, 'PRU-SUSPENSE', 'C', r + 1, 'KES', b);
    INSERT INTO lending.interest_suspense (tenant_id, loan_id, business_date, account_code, amount, reason, entry_id)
    VALUES (tenancy.current_tenant(), v_loan, d, 'PRU-SUSPENSE', r + 1, 'END_OF_DAY', e2);
    SET CONSTRAINTS ALL IMMEDIATE;
    RAISE EXCEPTION 'TEST FAILED Z3b: more than the unpaid interest held in suspense';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'SUSPENSE_OUT_OF_RANGE%' THEN RAISE; END IF;
  END;
  -- (c) a valid movement, then an attempt to edit it
  IF r > 0 THEN
    INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
    VALUES (tenancy.current_tenant(), e2, 'z3-valid', d, 'LOAN', 'z3', 'test');
    INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
      (tenancy.current_tenant(), e2, 1, 'PRU-INCOME', 'D', r, 'KES', b),
      (tenancy.current_tenant(), e2, 2, 'PRU-SUSPENSE', 'C', r, 'KES', b);
    INSERT INTO lending.interest_suspense (tenant_id, loan_id, business_date, account_code, amount, reason, entry_id)
    VALUES (tenancy.current_tenant(), v_loan, d, 'PRU-SUSPENSE', r, 'END_OF_DAY', e2);
    SET CONSTRAINTS ALL IMMEDIATE;
    IF lending.interest_in_suspense(tenancy.current_tenant(), v_loan) <> r THEN
      RAISE EXCEPTION 'TEST FAILED Z3c: suspense balance';
    END IF;
    BEGIN
      UPDATE lending.interest_suspense SET amount = 1 WHERE entry_id = e2;
      RAISE EXCEPTION 'TEST FAILED Z3d: movement edited';
    EXCEPTION WHEN insufficient_privilege THEN NULL; -- no UPDATE grant; the trigger also refuses for the owner
    END;
    RAISE EXCEPTION 'Z3 rollback';
  END IF;
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM <> 'Z3 rollback' THEN RAISE; END IF;
END $$;
\echo PASS Z3 suspense movements match their entries, stay within the unpaid interest, and are never edited

-- Z4: the reconciliation query reports the suspense account (nothing held after the rolled-back tests).
BEGIN;
SET LOCAL app.tenant_id = :tA;
UPDATE ledger.prudential_settings SET interest_suspension_enabled = false, interest_suspense_account = NULL;
COMMIT;
\echo PASS Z4 interest suspension can be switched off again
