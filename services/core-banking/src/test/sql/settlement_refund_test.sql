-- =============================================================================
-- Tests for V29__early_settlement_and_credit_refunds.sql, as mfi_app.
-- =============================================================================
\set ON_ERROR_STOP on
\set QUIET on

-- SR1: a later schedule version exists only for a settlement, and a settlement must close its loan.
DO $$
DECLARE v_loan uuid; v_rep uuid;
BEGIN
  PERFORM set_config('app.tenant_id', '00000000-0000-0000-0000-0000000000a1', true);
  SELECT l.loan_id INTO v_loan FROM lending.loan l WHERE l.status = 'ACTIVE'
     AND EXISTS (SELECT 1 FROM lending.repayment r WHERE r.loan_id = l.loan_id) ORDER BY l.loan_id LIMIT 1;
  IF v_loan IS NULL THEN
    RAISE EXCEPTION 'TEST SETUP SR1: tenant A needs an active loan with a repayment';
  END IF;
  SELECT repayment_id INTO v_rep FROM lending.repayment WHERE loan_id = v_loan LIMIT 1;
  BEGIN
    INSERT INTO lending.schedule_installment (tenant_id, loan_id, schedule_version, installment_no, due_date, principal, interest)
    SELECT tenant_id, loan_id, 99, installment_no, due_date, principal, 0 FROM lending.schedule_installment
     WHERE loan_id = v_loan AND schedule_version = 1;
    SET CONSTRAINTS ALL IMMEDIATE;
    RAISE EXCEPTION 'TEST FAILED SR1a: a new schedule version without a settlement was accepted';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'SCHEDULE_VERSION_UNEXPLAINED%' THEN RAISE; END IF;
  END;
  BEGIN
    INSERT INTO lending.settlement (tenant_id, settlement_id, idempotency_key, loan_id, business_date, schedule_version,
        principal, interest, penalty, payoff, interest_waived, credit_left, repayment_id, settled_by)
    VALUES (tenancy.current_tenant(), gen_random_uuid(), 'sr1', v_loan, '2026-11-20', 2, 100, 0, 0, 100, 0, 0, v_rep, 'test');
    SET CONSTRAINTS ALL IMMEDIATE;
    RAISE EXCEPTION 'TEST FAILED SR1b: a settlement that left the loan open was accepted';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'SETTLEMENT_INCOMPLETE%' THEN RAISE; END IF;
  END;
  BEGIN
    INSERT INTO lending.settlement (tenant_id, settlement_id, idempotency_key, loan_id, business_date, schedule_version,
        principal, interest, penalty, payoff, interest_waived, credit_left, repayment_id, settled_by)
    VALUES (tenancy.current_tenant(), gen_random_uuid(), 'sr1c', v_loan, '2026-11-20', 2, 100, 0, 0, 90, 0, 0, v_rep, 'test');
    RAISE EXCEPTION 'TEST FAILED SR1c: payoff different from its parts accepted';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
END $$;
\echo PASS SR1 later schedule versions need a settlement, and a settlement must close the loan

-- SR2: refunds only of credit on closed loans, and only as a request (the API test covers ownership and approval).
DO $$
DECLARE v_loan uuid; v_cust uuid; v_acct uuid;
BEGIN
  PERFORM set_config('app.tenant_id', '00000000-0000-0000-0000-0000000000a1', true);
  SELECT loan_id, customer_id INTO v_loan, v_cust FROM lending.loan WHERE status = 'ACTIVE' ORDER BY loan_id LIMIT 1;
  SELECT account_id INTO v_acct FROM savings.deposit_account WHERE status = 'ACTIVE' ORDER BY account_id LIMIT 1;
  BEGIN
    INSERT INTO lending.credit_refund (tenant_id, refund_id, idempotency_key, loan_id, amount, method, deposit_account_id, reason,
        requested_by)
    VALUES (tenancy.current_tenant(), gen_random_uuid(), 'sr2a', v_loan, 10, 'SAVINGS', v_acct, 'member asked for the refund', 'u1');
    RAISE EXCEPTION 'TEST FAILED SR2a: refund on an active loan accepted';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'REFUND_NOT_ALLOWED%' THEN RAISE; END IF;
  END;
  BEGIN
    INSERT INTO lending.credit_refund (tenant_id, refund_id, idempotency_key, loan_id, amount, method, deposit_account_id, reason,
        requested_by, status, decided_by, decided_at, business_date, entry_id)
    VALUES (tenancy.current_tenant(), gen_random_uuid(), 'sr2b', v_loan, 10, 'SAVINGS', v_acct, 'member asked for the refund', 'u1',
        'APPROVED', 'u2', now(), '2026-11-20', NULL);
    RAISE EXCEPTION 'TEST FAILED SR2b: refund inserted already approved';
  EXCEPTION WHEN raise_exception OR check_violation THEN NULL;
  END;
END $$;
\echo PASS SR2 refunds start as requests, and only for credit on closed loans
