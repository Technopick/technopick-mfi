-- =============================================================================
-- Tests for V40__loan_limits_and_guarantors.sql, run as mfi_app at the end of the suite. TEST-ONLY.
-- Borrower CU26-0000087 (member_exit_test.sql) saves 10,000.00; product of version cc..01 lends
-- 3 x own cover with guarantees for the part above it. Guarantor CU26-0000001 pledges from account
-- 2000000014. Loan A (25,000) needs 15,000 guaranteed; it is disbursed, repaid and closed, which
-- releases the guarantee.
-- =============================================================================
\set ON_ERROR_STOP on
\set QUIET on
\set tA '''00000000-0000-0000-0000-0000000000a1'''
\set tB '''00000000-0000-0000-0000-0000000000b2'''

CREATE FUNCTION pg_temp.application(p_id uuid, p_key text, p_principal numeric) RETURNS void LANGUAGE sql AS $$
    INSERT INTO lending.loan_application (tenant_id, application_id, idempotency_key, customer_ref, customer_id, product_version_id,
        branch_code, currency, principal, term_months, first_due_date, purpose, submitted_by)
    SELECT tenancy.current_tenant(), p_id, p_key, 'CU26-0000087', 'c0000000-0000-0000-0000-0000000000a7',
           'cccccccc-0000-0000-0000-000000000001', 'NBO01', 'KES', p_principal, 3, current_date_ + 30, 'Stock for the shop',
           'user:officer' FROM tenancy.business_date;
$$;
-- pledges p_amount from account 2000000014 (member CU26-0000001) on p_application
CREATE FUNCTION pg_temp.pledge(p_id uuid, p_application uuid, p_amount numeric) RETURNS void LANGUAGE sql AS $$
    INSERT INTO savings.account_hold (tenant_id, hold_id, account_id, kind, amount, currency, reference, idempotency_key)
    VALUES (tenancy.current_tenant(), p_id, 'aa000000-0000-0000-0000-0000000000d1', 'EARMARK', p_amount, 'KES',
            'guarantee:' || p_id, 'guarantee-hold:' || p_id);
    INSERT INTO lending.loan_guarantee (tenant_id, guarantee_id, idempotency_key, application_id, guarantor_customer_id,
        savings_account_id, amount, consent_reference, hold_id, pledged_by)
    VALUES (tenancy.current_tenant(), p_id, 'guarantee:' || p_id, p_application, 'c0000000-0000-0000-0000-000000000001',
            'aa000000-0000-0000-0000-0000000000d1', p_amount, 'Form G-1 signed', p_id, 'user:officer');
$$;
-- approves p_application by creating its loan (as LoanOriginationService does)
CREATE FUNCTION pg_temp.approve(p_application uuid, p_loan uuid, p_principal numeric, p_number text) RETURNS void LANGUAGE sql AS $$
    INSERT INTO lending.loan (tenant_id, loan_id, loan_number, customer_ref, customer_id, product_version_id, branch_code, currency,
        principal, term_months, created_by, approved_by)
    VALUES (tenancy.current_tenant(), p_loan, p_number, 'CU26-0000087', 'c0000000-0000-0000-0000-0000000000a7',
            'cccccccc-0000-0000-0000-000000000001', 'NBO01', 'KES', p_principal, 3, 'user:officer', 'user:manager');
    UPDATE lending.loan_application SET status = 'APPROVED', decided_by = 'user:manager', loan_id = p_loan
     WHERE application_id = p_application;
$$;

-- ---------- LG0 setup ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO savings.deposit_account (tenant_id, account_id, account_number, customer_ref, customer_id, product_code, currency,
    branch_code, gl_control_account, opened_on)
SELECT :tA::uuid, 'ac000000-0000-0000-0000-0000000000f1', savings.next_account_number(), 'CU26-0000087',
       'c0000000-0000-0000-0000-0000000000a7', 'SAVT', 'KES', 'NBO01', '2111-TEST-SAVINGS', current_date_ FROM tenancy.business_date;
INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
SELECT :tA::uuid, 'f0000000-0000-0000-0000-000000000001', 'limits-test-funding', current_date_, 'TELLER', 'c', 'user:teller'
  FROM tenancy.business_date;
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code, subledger_account_id) VALUES
  (:tA::uuid, 'f0000000-0000-0000-0000-000000000001', 1, '1199-TEST-FUNDING', 'D', 10000, 'KES', 'NBO01', NULL),
  (:tA::uuid, 'f0000000-0000-0000-0000-000000000001', 2, '2111-TEST-SAVINGS', 'C', 10000, 'KES', 'NBO01', 'ac000000-0000-0000-0000-0000000000f1');
INSERT INTO lending.loan_limit_rule (tenant_id, product_code, savings_multiple, include_shares, deduct_outstanding, guarantees_required,
    updated_by)
SELECT :tA::uuid, product_code, 3, true, true, true, 'user:fin' FROM lending.loan_product_version
 WHERE version_id = 'cccccccc-0000-0000-0000-000000000001';
DO $$
DECLARE l record;
BEGIN
  SELECT * INTO l FROM lending.loan_limit(tenancy.current_tenant(), 'c0000000-0000-0000-0000-0000000000a7',
      (SELECT product_code FROM lending.loan_product_version WHERE version_id = 'cccccccc-0000-0000-0000-000000000001'), NULL);
  IF l.savings <> 10000 OR l.shares <> 0 OR l.outstanding <> 0 OR l.own_cover <> 10000 OR l.limit_amount <> 30000
     OR NOT l.guarantees_required THEN
    RAISE EXCEPTION 'TEST FAILED LG1a: %', l;
  END IF;
  -- no rule, no limit
  IF EXISTS (SELECT 1 FROM lending.loan_limit(tenancy.current_tenant(), 'c0000000-0000-0000-0000-0000000000a7', 'NO-SUCH', NULL)) THEN
    RAISE EXCEPTION 'TEST FAILED LG1b';
  END IF;
END $$;
COMMIT;
\echo PASS LG1 limit is 3 x own cover (savings 10,000) = 30,000; products without a rule have none

-- ---------- LG3 pledges ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  PERFORM pg_temp.application('f1000000-0000-0000-0000-00000000000a', 'lg-a', 25000);
  PERFORM pg_temp.application('f1000000-0000-0000-0000-00000000000b', 'lg-b', 40000);
  -- the borrower cannot guarantee their own loan
  BEGIN
    INSERT INTO savings.account_hold (tenant_id, hold_id, account_id, kind, amount, currency, reference, idempotency_key)
    VALUES (tenancy.current_tenant(), 'f2000000-0000-0000-0000-000000000099', 'ac000000-0000-0000-0000-0000000000f1', 'EARMARK', 100,
            'KES', 'self', 'self-hold');
    INSERT INTO lending.loan_guarantee (tenant_id, guarantee_id, idempotency_key, application_id, guarantor_customer_id,
        savings_account_id, amount, consent_reference, hold_id, pledged_by)
    VALUES (tenancy.current_tenant(), 'f2000000-0000-0000-0000-000000000099', 'self', 'f1000000-0000-0000-0000-00000000000a',
            'c0000000-0000-0000-0000-0000000000a7', 'ac000000-0000-0000-0000-0000000000f1', 100, 'Form G-1 signed',
            'f2000000-0000-0000-0000-000000000099', 'user:officer');
    RAISE EXCEPTION 'TEST FAILED LG3a';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'GUARANTEE_SELF%' THEN RAISE; END IF;
  END;
  -- the hold must match the pledge
  BEGIN
    INSERT INTO savings.account_hold (tenant_id, hold_id, account_id, kind, amount, currency, reference, idempotency_key)
    VALUES (tenancy.current_tenant(), 'f2000000-0000-0000-0000-000000000098', 'aa000000-0000-0000-0000-0000000000d1', 'EARMARK', 100,
            'KES', 'short', 'short-hold');
    INSERT INTO lending.loan_guarantee (tenant_id, guarantee_id, idempotency_key, application_id, guarantor_customer_id,
        savings_account_id, amount, consent_reference, hold_id, pledged_by)
    VALUES (tenancy.current_tenant(), 'f2000000-0000-0000-0000-000000000098', 'short', 'f1000000-0000-0000-0000-00000000000a',
            'c0000000-0000-0000-0000-000000000001', 'aa000000-0000-0000-0000-0000000000d1', 5000, 'Form G-1 signed',
            'f2000000-0000-0000-0000-000000000098', 'user:officer');
    RAISE EXCEPTION 'TEST FAILED LG3b';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'GUARANTEE_INVALID%' THEN RAISE; END IF;
  END;
  -- the guarantor must have the money available
  BEGIN
    PERFORM pg_temp.pledge('f2000000-0000-0000-0000-000000000097', 'f1000000-0000-0000-0000-00000000000a', 90000000);
    RAISE EXCEPTION 'TEST FAILED LG3c';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'SAVINGS_INSUFFICIENT_AVAILABLE%' THEN RAISE; END IF;
  END;
  PERFORM pg_temp.pledge('f2000000-0000-0000-0000-000000000001', 'f1000000-0000-0000-0000-00000000000a', 10000);
  -- one pledge per guarantor per application
  BEGIN
    PERFORM pg_temp.pledge('f2000000-0000-0000-0000-000000000002', 'f1000000-0000-0000-0000-00000000000a', 1000);
    RAISE EXCEPTION 'TEST FAILED LG3d';
  EXCEPTION WHEN unique_violation THEN NULL;
  END;
  -- a guarantee's hold is not released on its own
  BEGIN
    UPDATE savings.account_hold SET status = 'RELEASED' WHERE hold_id = 'f2000000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED LG3e';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'GUARANTEE_LOCKED%' THEN RAISE; END IF;
  END;
END $$;
COMMIT;
\echo PASS LG3 pledges by another active member from their own account, locked by a matching hold they can afford

-- ---------- LG2 approval within the limit and covered ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  BEGIN
    PERFORM pg_temp.approve('f1000000-0000-0000-0000-00000000000a', 'f3000000-0000-0000-0000-00000000000a', 25000, 'LN26-8000001');
    RAISE EXCEPTION 'TEST FAILED LG2a';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'LOAN_GUARANTEE_SHORT%' THEN RAISE; END IF;
  END;
  BEGIN
    PERFORM pg_temp.approve('f1000000-0000-0000-0000-00000000000b', 'f3000000-0000-0000-0000-00000000000b', 40000, 'LN26-8000002');
    RAISE EXCEPTION 'TEST FAILED LG2b';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'LOAN_LIMIT_EXCEEDED%' THEN RAISE; END IF;
  END;
  -- withdraw the 10,000 pledge (its hold is released with it) and pledge 15,000
  UPDATE lending.loan_guarantee SET status = 'WITHDRAWN', ended_by = 'user:officer', end_reason = 'Guarantor will pledge more'
   WHERE guarantee_id = 'f2000000-0000-0000-0000-000000000001';
  IF (SELECT status FROM savings.account_hold WHERE hold_id = 'f2000000-0000-0000-0000-000000000001') <> 'RELEASED' THEN
    RAISE EXCEPTION 'TEST FAILED LG2c: hold not released with the pledge';
  END IF;
  PERFORM pg_temp.pledge('f2000000-0000-0000-0000-000000000003', 'f1000000-0000-0000-0000-00000000000a', 15000);
  PERFORM pg_temp.pledge('f2000000-0000-0000-0000-000000000004', 'f1000000-0000-0000-0000-00000000000b', 500);
  PERFORM pg_temp.approve('f1000000-0000-0000-0000-00000000000a', 'f3000000-0000-0000-0000-00000000000a', 25000, 'LN26-8000001');
  -- declining B releases its pledge
  UPDATE lending.loan_application SET status = 'DECLINED', decided_by = 'user:manager', decision_reason = 'Above the limit'
   WHERE application_id = 'f1000000-0000-0000-0000-00000000000b';
  IF (SELECT status FROM lending.loan_guarantee WHERE guarantee_id = 'f2000000-0000-0000-0000-000000000004') <> 'RELEASED'
     OR (SELECT status FROM savings.account_hold WHERE hold_id = 'f2000000-0000-0000-0000-000000000004') <> 'RELEASED' THEN
    RAISE EXCEPTION 'TEST FAILED LG2d: declined application keeps its pledge';
  END IF;
  -- after approval the guarantee holds
  BEGIN
    UPDATE lending.loan_guarantee SET status = 'WITHDRAWN', ended_by = 'user:officer', end_reason = 'Changed mind'
     WHERE guarantee_id = 'f2000000-0000-0000-0000-000000000003';
    RAISE EXCEPTION 'TEST FAILED LG2e';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'GUARANTEE_LOCKED%' THEN RAISE; END IF;
  END;
  -- the guarantor's own cover and exit reflect the pledge
  IF (SELECT pledged_for_others FROM lending.loan_limit(tenancy.current_tenant(), 'c0000000-0000-0000-0000-000000000001',
        (SELECT product_code FROM lending.loan_product_version WHERE version_id = 'cccccccc-0000-0000-0000-000000000001'), NULL)) <> 15000 THEN
    RAISE EXCEPTION 'TEST FAILED LG2f';
  END IF;
  IF NOT ('1 guarantee(s) for other members'' loans (15000.00)' = ANY (membership.blockers(tenancy.current_tenant(),
            'c0000000-0000-0000-0000-000000000001'))) THEN
    RAISE EXCEPTION 'TEST FAILED LG2g: %', membership.blockers(tenancy.current_tenant(), 'c0000000-0000-0000-0000-000000000001');
  END IF;
END $$;
COMMIT;
\echo PASS LG2 approval needs the limit and enough guarantees; pledges withdrawn while open, released on decline, locked after approval

-- ---------- LG4 the loan is disbursed, repaid and closed: the guarantee is released ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO lending.schedule_installment (tenant_id, loan_id, schedule_version, installment_no, due_date, principal, interest)
SELECT :tA::uuid, 'f3000000-0000-0000-0000-00000000000a', 1, k, current_date_ + 30 * k, CASE k WHEN 1 THEN 8334 ELSE 8333 END, 0
  FROM tenancy.business_date, generate_series(1, 3) k;
INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
SELECT :tA::uuid, 'f4000000-0000-0000-0000-000000000001', 'loan:f3000000-0000-0000-0000-00000000000a:DISBURSE:LG', current_date_,
       'LOAN', 'c', 'u' FROM tenancy.business_date;
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
  (:tA::uuid, 'f4000000-0000-0000-0000-000000000001', 1, '1200-LOANS-PRINCIPAL', 'D', 25000, 'KES', 'NBO01'),
  (:tA::uuid, 'f4000000-0000-0000-0000-000000000001', 2, '1120-MPESA-C2B-CLEARING', 'C', 25000, 'KES', 'NBO01');
UPDATE lending.loan SET status = 'ACTIVE', disbursement_entry_id = 'f4000000-0000-0000-0000-000000000001'
 WHERE loan_id = 'f3000000-0000-0000-0000-00000000000a';
INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
SELECT :tA::uuid, 'f4000000-0000-0000-0000-000000000002', 'loan:f3000000-0000-0000-0000-00000000000a:REPAY:LG', current_date_,
       'LOAN', 'c', 'u' FROM tenancy.business_date;
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
  (:tA::uuid, 'f4000000-0000-0000-0000-000000000002', 1, '1120-MPESA-C2B-CLEARING', 'D', 25000, 'KES', 'NBO01'),
  (:tA::uuid, 'f4000000-0000-0000-0000-000000000002', 2, '1200-LOANS-PRINCIPAL', 'C', 25000, 'KES', 'NBO01');
INSERT INTO lending.repayment (tenant_id, repayment_id, loan_id, amount, unallocated, currency, channel, provider_ref,
                               journal_entry_id, received_at)
VALUES (:tA::uuid, 'f4000000-0000-0000-0000-0000000000a1', 'f3000000-0000-0000-0000-00000000000a', 25000, 0, 'KES', 'MPESA_C2B',
        'LG-RCPT-1', 'f4000000-0000-0000-0000-000000000002', now());
INSERT INTO lending.repayment_allocation (tenant_id, repayment_id, line_no, installment_no, component, amount) VALUES
  (:tA::uuid, 'f4000000-0000-0000-0000-0000000000a1', 1, 1, 'PRINCIPAL', 8334),
  (:tA::uuid, 'f4000000-0000-0000-0000-0000000000a1', 2, 2, 'PRINCIPAL', 8333),
  (:tA::uuid, 'f4000000-0000-0000-0000-0000000000a1', 3, 3, 'PRINCIPAL', 8333);
INSERT INTO lending.loan_closure (tenant_id, loan_id, closed_on, reason, credit_balance, closed_by)
SELECT :tA::uuid, 'f3000000-0000-0000-0000-00000000000a', current_date_, 'REPAID', 0, 'user:system' FROM tenancy.business_date;
UPDATE lending.loan SET status = 'CLOSED' WHERE loan_id = 'f3000000-0000-0000-0000-00000000000a';
DO $$
BEGIN
  IF (SELECT status FROM lending.loan_guarantee WHERE guarantee_id = 'f2000000-0000-0000-0000-000000000003') <> 'RELEASED'
     OR (SELECT status FROM savings.account_hold WHERE hold_id = 'f2000000-0000-0000-0000-000000000003') <> 'RELEASED'
     OR (SELECT end_reason FROM lending.loan_guarantee WHERE guarantee_id = 'f2000000-0000-0000-0000-000000000003') <> 'Loan repaid' THEN
    RAISE EXCEPTION 'TEST FAILED LG4a: closing the loan did not release the guarantee';
  END IF;
  BEGIN
    DELETE FROM lending.loan_guarantee WHERE guarantee_id = 'f2000000-0000-0000-0000-000000000003';
    RAISE EXCEPTION 'TEST FAILED LG4b';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
END $$;
COMMIT;
\echo PASS LG4 closing the loan releases its guarantees and their holds; guarantees are never deleted

BEGIN;
SET LOCAL app.tenant_id = :tB;
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM lending.loan_guarantee) OR EXISTS (SELECT 1 FROM lending.loan_limit_rule) THEN
    RAISE EXCEPTION 'TEST FAILED LG-RLS';
  END IF;
END $$;
COMMIT;
\echo PASS LG-RLS guarantees and limit rules are invisible to other tenants
