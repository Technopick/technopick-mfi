-- =============================================================================
-- Tests for V36__mpesa_b2c_status_and_float.sql, run as mfi_app after mpesa_b2c_test.sql.
-- Uses B2C short code 600000 and its clearing account from mpesa_b2c_test.sql.
-- =============================================================================
\set ON_ERROR_STOP on
\set QUIET on
\set tA '''00000000-0000-0000-0000-0000000000a1'''
\set tB '''00000000-0000-0000-0000-0000000000b2'''

BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO lending.loan (tenant_id, loan_id, loan_number, customer_ref, product_version_id, branch_code, currency,
                          principal, term_months, created_by, approved_by)
VALUES (:tA::uuid, 'b2c00000-0000-0000-0000-000000000009', 'LN-B2C-9', 'CUST-9', 'cccccccc-0000-0000-0000-000000000001',
        'NBO01', 'KES', 10000, 12, 'user:officer', 'user:manager');
INSERT INTO lending.schedule_installment (tenant_id, loan_id, schedule_version, installment_no, due_date, principal, interest)
VALUES (:tA::uuid, 'b2c00000-0000-0000-0000-000000000009', 1, 1, '2026-12-18', 10000, 200);
INSERT INTO payments.b2c_disbursement (tenant_id, disbursement_id, idempotency_key, loan_id, short_code, amount, currency,
                                       msisdn, msisdn_masked, requested_by)
VALUES (:tA::uuid, 'd2c00000-0000-0000-0000-000000000009', 'b-9', 'b2c00000-0000-0000-0000-000000000009', '600000', 9800, 'KES',
        '254712345678', '2547*****678', 'user:officer');
UPDATE payments.b2c_disbursement SET status = 'APPROVED', decided_by = 'user:manager'
 WHERE disbursement_id = 'd2c00000-0000-0000-0000-000000000009';
-- Q1: an approved disbursement was never sent, so there is nothing to ask about
DO $$
BEGIN
  INSERT INTO payments.b2c_status_query (tenant_id, query_id, disbursement_id, reason, requested_by)
  VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'd2c00000-0000-0000-0000-000000000009', 'MANUAL', 'user:ops');
  RAISE EXCEPTION 'TEST FAILED Q1a';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'B2C_QUERY_INVALID%' THEN RAISE; END IF;
END $$;
UPDATE payments.b2c_disbursement SET status = 'DISPATCHED' WHERE disbursement_id = 'd2c00000-0000-0000-0000-000000000009';
INSERT INTO payments.b2c_status_query (tenant_id, query_id, disbursement_id, reason, requested_by)
VALUES (:tA::uuid, 'e2c00000-0000-0000-0000-000000000001', 'd2c00000-0000-0000-0000-000000000009', 'SEND_OUTCOME_UNKNOWN', 'system');
DO $$
BEGIN
  BEGIN
    INSERT INTO payments.b2c_status_query (tenant_id, query_id, disbursement_id, reason, requested_by)
    VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'd2c00000-0000-0000-0000-000000000009', 'MANUAL', 'user:ops');
    RAISE EXCEPTION 'TEST FAILED Q1b';
  EXCEPTION WHEN unique_violation THEN NULL;
  END;
  BEGIN
    -- a completed disbursement's outcome is known
    INSERT INTO payments.b2c_status_query (tenant_id, query_id, disbursement_id, reason, requested_by)
    VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'd2c00000-0000-0000-0000-000000000001', 'MANUAL', 'user:ops');
    RAISE EXCEPTION 'TEST FAILED Q1c';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'B2C_QUERY_INVALID%' THEN RAISE; END IF;
  END;
  BEGIN
    UPDATE payments.b2c_status_query SET status = 'ANSWERED', answered_at = now(), result_code = '0', outcome = 'NOT_CONFIRMED'
     WHERE query_id = 'e2c00000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED Q2a';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'B2C_QUERY_STATUS%' THEN RAISE; END IF;
  END;
  BEGIN
    UPDATE payments.b2c_status_query SET status = 'SENT' WHERE query_id = 'e2c00000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED Q2b';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
END $$;
UPDATE payments.b2c_status_query SET status = 'SENT', sent_at = now() WHERE query_id = 'e2c00000-0000-0000-0000-000000000001';
DO $$
BEGIN
  BEGIN
    UPDATE payments.b2c_status_query SET status = 'ANSWERED', answered_at = now(), result_code = '0'
     WHERE query_id = 'e2c00000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED Q3a';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    UPDATE payments.b2c_status_query SET reason = 'MANUAL' WHERE query_id = 'e2c00000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED Q2c';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'B2C_QUERY_IMMUTABLE%' THEN RAISE; END IF;
  END;
END $$;
UPDATE payments.b2c_status_query SET status = 'ANSWERED', answered_at = now(), result_code = '0', result_desc = 'found',
       reported_status = 'Completed', reported_receipt = 'SIMQ000001', reported_amount = 9800, outcome = 'NOT_CONFIRMED'
 WHERE query_id = 'e2c00000-0000-0000-0000-000000000001';
DO $$
BEGIN
  BEGIN
    UPDATE payments.b2c_status_query SET result_desc = 'changed' WHERE query_id = 'e2c00000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED Q2d';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'B2C_QUERY_IMMUTABLE%' THEN RAISE; END IF;
  END;
  BEGIN
    DELETE FROM payments.b2c_status_query;
    RAISE EXCEPTION 'TEST FAILED Q2e';
  EXCEPTION WHEN insufficient_privilege OR raise_exception THEN
    IF SQLSTATE = 'P0001' AND SQLERRM NOT LIKE 'B2C_QUERY_IMMUTABLE%' THEN RAISE; END IF;
  END;
END $$;
-- once answered, a new query may be asked
INSERT INTO payments.b2c_status_query (tenant_id, query_id, disbursement_id, reason, requested_by)
VALUES (:tA::uuid, 'e2c00000-0000-0000-0000-000000000002', 'd2c00000-0000-0000-0000-000000000009', 'MANUAL', 'user:ops');
UPDATE payments.b2c_status_query SET status = 'SEND_FAILED', result_desc = 'Daraja unreachable'
 WHERE query_id = 'e2c00000-0000-0000-0000-000000000002';
COMMIT;
\echo PASS Q1 Q2 Q3 status queries only for unknown outcomes, one open at a time, forward-only, answers complete and final

-- ---------- F1-F3: float settings and balance checks ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  BEGIN
    INSERT INTO payments.b2c_float_settings (tenant_id, short_code, low_float_threshold, updated_by)
    VALUES ('00000000-0000-0000-0000-0000000000a1', '600000', -1, 'user:fin');
    RAISE EXCEPTION 'TEST FAILED F1a';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    INSERT INTO payments.b2c_float_settings (tenant_id, short_code, daily_check_hour, updated_by)
    VALUES ('00000000-0000-0000-0000-0000000000a1', '600000', 24, 'user:fin');
    RAISE EXCEPTION 'TEST FAILED F1b';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
END $$;
INSERT INTO payments.b2c_float_settings (tenant_id, short_code, low_float_threshold, updated_by)
VALUES (:tA::uuid, '600000', 50000, 'user:fin');
INSERT INTO payments.b2c_balance_check (tenant_id, check_id, short_code, reason, requested_by)
VALUES (:tA::uuid, 'f2c00000-0000-0000-0000-000000000001', '600000', 'MANUAL', 'user:ops');
DO $$
DECLARE v_ledger numeric;
BEGIN
  BEGIN
    INSERT INTO payments.b2c_balance_check (tenant_id, check_id, short_code, reason, requested_by)
    VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), '600000', 'DAILY', 'system');
    RAISE EXCEPTION 'TEST FAILED F2a';
  EXCEPTION WHEN unique_violation THEN NULL;
  END;
  UPDATE payments.b2c_balance_check SET status = 'SENT', sent_at = now() WHERE check_id = 'f2c00000-0000-0000-0000-000000000001';
  BEGIN
    UPDATE payments.b2c_balance_check SET status = 'ANSWERED', answered_at = now(), result_code = '0'
     WHERE check_id = 'f2c00000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED F3a';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    UPDATE payments.b2c_balance_check SET status = 'ANSWERED', answered_at = now(), result_code = '0',
           float_account = 'Utility Account', float_current = 40000, float_available = 40000,
           accounts = '[]'::jsonb, ledger_balance = 123456789, committed = 0
     WHERE check_id = 'f2c00000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED F3b';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'B2C_FLOAT_INVALID%' THEN RAISE; END IF;
  END;
  v_ledger := payments.clearing_balance('00000000-0000-0000-0000-0000000000a1', '1110-MPESA-B2C-CLEARING',
                                        (SELECT current_date_ FROM tenancy.business_date));
  UPDATE payments.b2c_balance_check SET status = 'ANSWERED', answered_at = now(), result_code = '0',
         float_account = 'Utility Account', float_current = 40000, float_available = 40000,
         accounts = '[{"name":"Utility Account","currency":"KES","current":"40000.00","available":"40000.00"}]'::jsonb,
         ledger_balance = v_ledger, committed = 0
   WHERE check_id = 'f2c00000-0000-0000-0000-000000000001';
  BEGIN
    UPDATE payments.b2c_balance_check SET float_current = 1 WHERE check_id = 'f2c00000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED F2b';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'B2C_FLOAT_IMMUTABLE%' THEN RAISE; END IF;
  END;
END $$;
-- an answer that is an error carries no balances
INSERT INTO payments.b2c_balance_check (tenant_id, check_id, short_code, reason, requested_by)
VALUES (:tA::uuid, 'f2c00000-0000-0000-0000-000000000002', '600000', 'DAILY', 'system');
UPDATE payments.b2c_balance_check SET status = 'SENT', sent_at = now() WHERE check_id = 'f2c00000-0000-0000-0000-000000000002';
UPDATE payments.b2c_balance_check SET status = 'ANSWERED', answered_at = now(), result_code = '2001',
       result_desc = 'The initiator information is invalid.' WHERE check_id = 'f2c00000-0000-0000-0000-000000000002';
COMMIT;
\echo PASS F1 F2 F3 float threshold and hour checked; one open check; answered balances carry the re-derived ledger balance

BEGIN;
SET LOCAL app.tenant_id = :tB;
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM payments.b2c_status_query) OR EXISTS (SELECT 1 FROM payments.b2c_balance_check)
     OR EXISTS (SELECT 1 FROM payments.b2c_float_settings) THEN
    RAISE EXCEPTION 'TEST FAILED RLS';
  END IF;
END $$;
COMMIT;
\echo PASS QF-RLS status queries and float checks are invisible to other tenants
