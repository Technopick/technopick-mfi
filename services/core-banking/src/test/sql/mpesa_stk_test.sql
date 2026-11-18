-- =============================================================================
-- Tests for V10__mpesa_stk_push.sql, run as the application role (mfi_app) after the other
-- application-role tests in the same throwaway database.
-- =============================================================================
\set ON_ERROR_STOP on
\set QUIET on
\set tA '''00000000-0000-0000-0000-0000000000a1'''
\set tB '''00000000-0000-0000-0000-0000000000b2'''

-- ---------- Setup: a paybill with its clearing and suspense accounts ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO ledger.gl_account (tenant_id, account_code, name, account_class, currency) VALUES
  (:tA::uuid, '1120-STK-TEST-CLEARING', 'TEST paybill clearing', 'ASSET', 'KES'),
  (:tA::uuid, '2900-STK-TEST-SUSPENSE', 'TEST suspense', 'LIABILITY', 'KES')
  ON CONFLICT DO NOTHING;
INSERT INTO payments.mpesa_paybill (tenant_id, short_code, branch_code, gl_clearing, gl_suspense)
VALUES (:tA::uuid, '174379', 'NBO01', '1120-STK-TEST-CLEARING', '2900-STK-TEST-SUSPENSE');
INSERT INTO payments.stk_request (tenant_id, request_id, idempotency_key, short_code, target_reference, amount, currency,
                                  msisdn, msisdn_masked, description, requested_by)
VALUES (:tA::uuid, 'dddddddd-0000-0000-0000-000000000001', 'k-1', '174379', '0100000001', 500, 'KES',
        '254712345678', '2547*****678', 'Deposit', 'user:teller-1');
COMMIT;
\echo PASS K0 STK request recorded as PENDING

-- ---------- K2: whole shillings and a Kenyan MSISDN only ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  INSERT INTO payments.stk_request (tenant_id, request_id, idempotency_key, short_code, target_reference, amount, currency,
                                    msisdn, msisdn_masked, description, requested_by)
  VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'k-2', '174379', '0100000001', 10.50, 'KES',
          '254712345678', 'x', 'Deposit', 'u');
  RAISE EXCEPTION 'TEST FAILED K2a';
EXCEPTION WHEN check_violation THEN NULL;
END $$;
DO $$
BEGIN
  INSERT INTO payments.stk_request (tenant_id, request_id, idempotency_key, short_code, target_reference, amount, currency,
                                    msisdn, msisdn_masked, description, requested_by)
  VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'k-3', '174379', '0100000001', 10, 'KES',
          '0712345678', 'x', 'Deposit', 'u');
  RAISE EXCEPTION 'TEST FAILED K2b';
EXCEPTION WHEN check_violation THEN NULL;
END $$;
DO $$
BEGIN
  INSERT INTO payments.stk_request (tenant_id, request_id, idempotency_key, short_code, target_reference, amount, currency,
                                    msisdn, msisdn_masked, description, requested_by)
  VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'k-1', '174379', '0100000002', 10, 'KES',
          '254712345678', 'x', 'Deposit', 'u');
  RAISE EXCEPTION 'TEST FAILED K2c';
EXCEPTION WHEN unique_violation THEN NULL;
END $$;
ROLLBACK;
\echo PASS K2 fractional amount, non-MSISDN phone and reused idempotency key rejected

-- ---------- K3: lifecycle only moves forward ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  UPDATE payments.stk_request SET status = 'SUBMITTED' WHERE request_id = 'dddddddd-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'TEST FAILED K3a';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'STK_REQUEST_STATUS%' THEN RAISE; END IF;
END $$;
UPDATE payments.stk_request SET status = 'DISPATCHED' WHERE request_id = 'dddddddd-0000-0000-0000-000000000001';
UPDATE payments.stk_request SET status = 'SUBMITTED', merchant_request_id = 'm-1', checkout_request_id = 'ws_CO_1'
 WHERE request_id = 'dddddddd-0000-0000-0000-000000000001';
DO $$
BEGIN
  UPDATE payments.stk_request SET status = 'COMPLETED' WHERE request_id = 'dddddddd-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'TEST FAILED K3b';
EXCEPTION WHEN check_violation THEN NULL;
END $$;
DO $$
BEGIN
  UPDATE payments.stk_request SET status = 'FAILED', result_code = '1032' WHERE request_id = 'dddddddd-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'TEST FAILED K3c: final state kept the phone number';
EXCEPTION WHEN check_violation THEN NULL;
END $$;
UPDATE payments.stk_request SET status = 'FAILED', result_code = '1032', result_desc = 'Request cancelled by user', msisdn = NULL
 WHERE request_id = 'dddddddd-0000-0000-0000-000000000001';
DO $$
BEGIN
  UPDATE payments.stk_request SET status = 'SUBMITTED' WHERE request_id = 'dddddddd-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'TEST FAILED K3d';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'STK_REQUEST_STATUS%' THEN RAISE; END IF;
END $$;
DO $$
BEGIN
  UPDATE payments.stk_request SET result_code = '0' WHERE request_id = 'dddddddd-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'TEST FAILED K3e';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'STK_REQUEST_FINAL%' THEN RAISE; END IF;
END $$;
COMMIT;
\echo PASS K3 no skipping states, COMPLETED needs a payment, final states erase the phone number, FAILED is not rewritten

-- ---------- K4/K5: details and provider ids never change ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO payments.stk_request (tenant_id, request_id, idempotency_key, short_code, target_reference, amount, currency,
                                  msisdn, msisdn_masked, description, requested_by)
VALUES (:tA::uuid, 'dddddddd-0000-0000-0000-000000000002', 'k-4', '174379', 'LN-000001', 1000, 'KES',
        '254112345678', '2541*****678', 'Repayment', 'user:teller-1');
UPDATE payments.stk_request SET status = 'DISPATCHED', checkout_request_id = 'ws_CO_2'
 WHERE request_id = 'dddddddd-0000-0000-0000-000000000002';
DO $$
BEGIN
  UPDATE payments.stk_request SET amount = 2000 WHERE request_id = 'dddddddd-0000-0000-0000-000000000002';
  RAISE EXCEPTION 'TEST FAILED K4a';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'STK_REQUEST_IMMUTABLE%' THEN RAISE; END IF;
END $$;
DO $$
BEGIN
  UPDATE payments.stk_request SET msisdn = '254799999999' WHERE request_id = 'dddddddd-0000-0000-0000-000000000002';
  RAISE EXCEPTION 'TEST FAILED K4b';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'STK_REQUEST_IMMUTABLE%' THEN RAISE; END IF;
END $$;
DO $$
BEGIN
  UPDATE payments.stk_request SET checkout_request_id = 'ws_CO_other' WHERE request_id = 'dddddddd-0000-0000-0000-000000000002';
  RAISE EXCEPTION 'TEST FAILED K5';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'STK_CHECKOUT_FIXED%' THEN RAISE; END IF;
END $$;
DO $$
BEGIN
  DELETE FROM payments.stk_request WHERE request_id = 'dddddddd-0000-0000-0000-000000000002';
  RAISE EXCEPTION 'TEST FAILED K4c';
EXCEPTION WHEN insufficient_privilege THEN NULL; -- the app role has no DELETE grant at all
END $$;
COMMIT;
\echo PASS K4 K5 amount, phone number and CheckoutRequestID cannot be rewritten; requests are never deleted

-- ---------- K1: one receipt, one application, across channels ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
VALUES (:tA::uuid, '55555555-0000-0000-0000-000000000001', 'mpesa-stk:SIMK1A0001', '2026-11-18', 'MPESA', 'c', 'provider:mpesa');
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
  (:tA::uuid, '55555555-0000-0000-0000-000000000001', 1, '1120-STK-TEST-CLEARING', 'D', 1000, 'KES', 'NBO01'),
  (:tA::uuid, '55555555-0000-0000-0000-000000000001', 2, '2900-STK-TEST-SUSPENSE', 'C', 1000, 'KES', 'NBO01');
INSERT INTO payments.provider_payment (tenant_id, payment_id, provider, provider_ref, short_code, amount, currency,
                                       provider_time, status, journal_entry_id)
VALUES (:tA::uuid, '66666666-0000-0000-0000-000000000001', 'MPESA_STK', 'SIMK1A0001', '174379', 1000, 'KES',
        '2026-11-18T10:00:00+03:00', 'UNMATCHED', '55555555-0000-0000-0000-000000000001');
DO $$
BEGIN
  INSERT INTO payments.provider_payment (tenant_id, payment_id, provider, provider_ref, short_code, amount, currency,
                                         provider_time, status, journal_entry_id)
  VALUES ('00000000-0000-0000-0000-0000000000a1', '66666666-0000-0000-0000-000000000002', 'MPESA_C2B', 'SIMK1A0001',
          '174379', 1000, 'KES', '2026-11-18T10:00:00+03:00', 'UNMATCHED', '55555555-0000-0000-0000-000000000001');
  RAISE EXCEPTION 'TEST FAILED K1';
EXCEPTION WHEN unique_violation THEN NULL;
END $$;
UPDATE payments.stk_request SET status = 'COMPLETED', payment_id = '66666666-0000-0000-0000-000000000001', msisdn = NULL,
       result_code = '0'
 WHERE request_id = 'dddddddd-0000-0000-0000-000000000002';
COMMIT;
\echo PASS K1 the same M-Pesa receipt cannot be applied by both the STK and C2B channels

-- ---------- Tenant isolation ----------
BEGIN;
SET LOCAL app.tenant_id = :tB;
DO $$
BEGIN
  IF (SELECT count(*) FROM payments.stk_request) <> 0 THEN
    RAISE EXCEPTION 'TEST FAILED K6: tenant B sees tenant A STK requests';
  END IF;
END $$;
COMMIT;
\echo PASS K6 tenant B cannot see tenant A STK requests
