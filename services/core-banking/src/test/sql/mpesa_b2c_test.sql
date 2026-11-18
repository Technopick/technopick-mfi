-- =============================================================================
-- Tests for V12__mpesa_b2c_disbursement.sql, run as mfi_app after lending_test.sql (which leaves
-- product version cccccccc-...-01 ACTIVE, loan LN-000001 ACTIVE and the loan GL accounts).
-- =============================================================================
\set ON_ERROR_STOP on
\set QUIET on
\set tA '''00000000-0000-0000-0000-0000000000a1'''
\set tB '''00000000-0000-0000-0000-0000000000b2'''

BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO payments.mpesa_b2c_account (tenant_id, short_code, branch_code, gl_clearing)
VALUES (:tA::uuid, '600000', 'NBO01', '1110-MPESA-B2C-CLEARING');
INSERT INTO lending.loan (tenant_id, loan_id, loan_number, customer_ref, product_version_id, branch_code, currency,
                          principal, term_months, created_by, approved_by) VALUES
  (:tA::uuid, 'b2c00000-0000-0000-0000-000000000001', 'LN-B2C-1', 'CUST-9', 'cccccccc-0000-0000-0000-000000000001', 'NBO01', 'KES', 10000, 12, 'user:officer', 'user:manager'),
  (:tA::uuid, 'b2c00000-0000-0000-0000-000000000002', 'LN-B2C-2', 'CUST-9', 'cccccccc-0000-0000-0000-000000000001', 'NBO01', 'KES', 10000, 12, 'user:officer', 'user:manager');
INSERT INTO lending.schedule_installment (tenant_id, loan_id, schedule_version, installment_no, due_date, principal, interest) VALUES
  (:tA::uuid, 'b2c00000-0000-0000-0000-000000000001', 1, 1, '2026-12-18', 10000, 200),
  (:tA::uuid, 'b2c00000-0000-0000-0000-000000000002', 1, 1, '2026-12-18', 10000, 200);
INSERT INTO payments.b2c_disbursement (tenant_id, disbursement_id, idempotency_key, loan_id, short_code, amount, currency,
                                       msisdn, msisdn_masked, requested_by) VALUES
  (:tA::uuid, 'd2c00000-0000-0000-0000-000000000001', 'b-1', 'b2c00000-0000-0000-0000-000000000001', '600000', 9800, 'KES',
   '254712345678', '2547*****678', 'user:officer'),
  (:tA::uuid, 'd2c00000-0000-0000-0000-000000000002', 'b-2', 'b2c00000-0000-0000-0000-000000000002', '600000', 9800, 'KES',
   '254712345678', '2547*****678', 'user:officer');
COMMIT;
\echo PASS B0 disbursements requested for approved loans

-- ---------- B6: only APPROVED loans; whole shillings ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  INSERT INTO payments.b2c_disbursement (tenant_id, disbursement_id, idempotency_key, loan_id, short_code, amount, currency,
                                         msisdn, msisdn_masked, requested_by)
  VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'b-x', 'bbbbbbbb-0000-0000-0000-000000000001', '600000',
          9800, 'KES', '254712345678', 'x', 'user:officer');
  RAISE EXCEPTION 'TEST FAILED B6a';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'B2C_LOAN_NOT_APPROVED%' THEN RAISE; END IF;
END $$;
DO $$
BEGIN
  UPDATE payments.b2c_disbursement SET status = 'REJECTED', decided_by = 'user:manager'
   WHERE disbursement_id = 'd2c00000-0000-0000-0000-000000000002';
  INSERT INTO payments.b2c_disbursement (tenant_id, disbursement_id, idempotency_key, loan_id, short_code, amount, currency,
                                         msisdn, msisdn_masked, requested_by)
  VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'b-y', 'b2c00000-0000-0000-0000-000000000002', '600000',
          9800.50, 'KES', '254712345678', 'x', 'user:officer');
  RAISE EXCEPTION 'TEST FAILED B6b';
EXCEPTION WHEN check_violation THEN NULL;
END $$;
ROLLBACK;
\echo PASS B6 active loan and fractional amount rejected

-- ---------- B1-B3: maker-checker, one live per loan, forward-only ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  UPDATE payments.b2c_disbursement SET status = 'APPROVED', decided_by = 'user:officer'
   WHERE disbursement_id = 'd2c00000-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'TEST FAILED B1';
EXCEPTION WHEN check_violation THEN NULL;
END $$;
DO $$
BEGIN
  UPDATE payments.b2c_disbursement SET status = 'DISPATCHED', decided_by = 'user:manager'
   WHERE disbursement_id = 'd2c00000-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'TEST FAILED B3a';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'B2C_STATUS%' THEN RAISE; END IF;
END $$;
UPDATE payments.b2c_disbursement SET status = 'APPROVED', decided_by = 'user:manager'
 WHERE disbursement_id = 'd2c00000-0000-0000-0000-000000000001';
DO $$
BEGIN
  INSERT INTO payments.b2c_disbursement (tenant_id, disbursement_id, idempotency_key, loan_id, short_code, amount, currency,
                                         msisdn, msisdn_masked, requested_by)
  VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'b-3', 'b2c00000-0000-0000-0000-000000000001', '600000',
          9800, 'KES', '254712345678', 'x', 'user:officer');
  RAISE EXCEPTION 'TEST FAILED B2';
EXCEPTION WHEN unique_violation THEN NULL;
END $$;
DO $$
BEGIN
  UPDATE payments.b2c_disbursement SET amount = 1000 WHERE disbursement_id = 'd2c00000-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'TEST FAILED B5a';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'B2C_IMMUTABLE%' THEN RAISE; END IF;
END $$;
DO $$
BEGIN
  UPDATE payments.b2c_disbursement SET decided_by = 'user:other' WHERE disbursement_id = 'd2c00000-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'TEST FAILED B5b';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'B2C_IMMUTABLE%' THEN RAISE; END IF;
END $$;
UPDATE payments.b2c_disbursement SET status = 'DISPATCHED' WHERE disbursement_id = 'd2c00000-0000-0000-0000-000000000001';
UPDATE payments.b2c_disbursement SET status = 'SUBMITTED', conversation_id = 'AG_TEST_1'
 WHERE disbursement_id = 'd2c00000-0000-0000-0000-000000000001';
COMMIT;
\echo PASS B1 B2 B3 B5 requester cannot approve, one live disbursement per loan, no skipped states, details and decision fixed

-- ---------- B4: completion needs the loan activated by its own entry ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
VALUES (:tA::uuid, '88888888-0000-0000-0000-000000000001',
        'loan:b2c00000-0000-0000-0000-000000000001:DISBURSE:d2c00000-0000-0000-0000-000000000001',
        '2026-11-18', 'LOAN', 'c', 'provider:mpesa');
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
  (:tA::uuid, '88888888-0000-0000-0000-000000000001', 1, '1200-LOANS-PRINCIPAL',    'D', 10000.00, 'KES', 'NBO01'),
  (:tA::uuid, '88888888-0000-0000-0000-000000000001', 2, '1110-MPESA-B2C-CLEARING', 'C',  9800.00, 'KES', 'NBO01'),
  (:tA::uuid, '88888888-0000-0000-0000-000000000001', 3, '4100-FEE-PROCESSING',     'C',   200.00, 'KES', 'NBO01');
DO $$
BEGIN
  UPDATE payments.b2c_disbursement SET status = 'COMPLETED', transaction_id = 'SIMB2C0001', msisdn = NULL,
         journal_entry_id = '88888888-0000-0000-0000-000000000001', result_code = '0'
   WHERE disbursement_id = 'd2c00000-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'TEST FAILED B4';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'B2C_LOAN_NOT_ACTIVATED%' THEN RAISE; END IF;
END $$;
UPDATE lending.loan SET status = 'ACTIVE', disbursement_entry_id = '88888888-0000-0000-0000-000000000001'
 WHERE loan_id = 'b2c00000-0000-0000-0000-000000000001';
UPDATE payments.b2c_disbursement SET status = 'COMPLETED', transaction_id = 'SIMB2C0001', msisdn = NULL,
       journal_entry_id = '88888888-0000-0000-0000-000000000001', result_code = '0'
 WHERE disbursement_id = 'd2c00000-0000-0000-0000-000000000001';
DO $$
BEGIN
  UPDATE payments.b2c_disbursement SET result_desc = 'x' WHERE disbursement_id = 'd2c00000-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'TEST FAILED B3b';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'B2C_FINAL%' THEN RAISE; END IF;
END $$;
COMMIT;
\echo PASS B4 completion requires the loan activated by this disbursement entry; COMPLETED is final

-- ---------- FAILED is final; the alarm can be raised; a new attempt is then allowed ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
UPDATE payments.b2c_disbursement SET status = 'APPROVED', decided_by = 'user:manager'
 WHERE disbursement_id = 'd2c00000-0000-0000-0000-000000000002';
UPDATE payments.b2c_disbursement SET status = 'DISPATCHED' WHERE disbursement_id = 'd2c00000-0000-0000-0000-000000000002';
UPDATE payments.b2c_disbursement SET status = 'FAILED', result_code = '1', result_desc = 'insufficient float', msisdn = NULL
 WHERE disbursement_id = 'd2c00000-0000-0000-0000-000000000002';
DO $$
BEGIN
  UPDATE payments.b2c_disbursement SET status = 'SUBMITTED' WHERE disbursement_id = 'd2c00000-0000-0000-0000-000000000002';
  RAISE EXCEPTION 'TEST FAILED B3c';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'B2C_FINAL%' THEN RAISE; END IF;
END $$;
UPDATE payments.b2c_disbursement SET attention = 'SUCCESS_AFTER_FAILURE', transaction_id = 'SIMB2C0002'
 WHERE disbursement_id = 'd2c00000-0000-0000-0000-000000000002';
INSERT INTO payments.b2c_disbursement (tenant_id, disbursement_id, idempotency_key, loan_id, short_code, amount, currency,
                                       msisdn, msisdn_masked, requested_by)
VALUES (:tA::uuid, 'd2c00000-0000-0000-0000-000000000003', 'b-4', 'b2c00000-0000-0000-0000-000000000002', '600000', 9800, 'KES',
        '254712345678', '2547*****678', 'user:officer');
DO $$
BEGIN
  DELETE FROM payments.b2c_disbursement WHERE disbursement_id = 'd2c00000-0000-0000-0000-000000000003';
  RAISE EXCEPTION 'TEST FAILED B5c';
EXCEPTION WHEN insufficient_privilege THEN NULL;
END $$;
COMMIT;
\echo PASS B3 FAILED is final except the success-after-failure alarm; a new attempt follows a failure; no deletes

BEGIN;
SET LOCAL app.tenant_id = :tB;
DO $$
BEGIN
  IF (SELECT count(*) FROM payments.b2c_disbursement) <> 0 THEN
    RAISE EXCEPTION 'TEST FAILED B7: tenant B sees tenant A disbursements';
  END IF;
END $$;
COMMIT;
\echo PASS B7 tenant B cannot see tenant A disbursements
