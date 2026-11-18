-- =============================================================================
-- Tests for V13__mpesa_manual_resolution.sql, run as mfi_app.
-- =============================================================================
\set ON_ERROR_STOP on
\set QUIET on
\set tA '''00000000-0000-0000-0000-0000000000a1'''
\set tB '''00000000-0000-0000-0000-0000000000b2'''

BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO payments.mpesa_resolution (tenant_id, resolution_id, item_type, item_id, outcome, provider_ref, amount, evidence,
                                       requested_by)
VALUES (:tA::uuid, 'eeeeeeee-0000-0000-0000-000000000001', 'STK_REQUEST', 'dddddddd-0000-0000-0000-000000000009', 'PAID',
        'SIMRES0001', 50, 'M-Pesa org portal statement line 18/11 14:02', 'user:ops-1');
COMMIT;
\echo PASS M0 resolution proposed

BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  INSERT INTO payments.mpesa_resolution (tenant_id, resolution_id, item_type, item_id, outcome, evidence, requested_by)
  VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'STK_REQUEST', gen_random_uuid(), 'PAID',
          'portal statement line', 'user:ops-1');
  RAISE EXCEPTION 'TEST FAILED M3a';
EXCEPTION WHEN check_violation THEN NULL;
END $$;
DO $$
BEGIN
  INSERT INTO payments.mpesa_resolution (tenant_id, resolution_id, item_type, item_id, outcome, provider_ref, amount, evidence,
                                         requested_by)
  VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'B2C_DISBURSEMENT', gen_random_uuid(), 'NOT_PAID',
          'SIMRES0002', 10, 'portal statement line', 'user:ops-1');
  RAISE EXCEPTION 'TEST FAILED M3b';
EXCEPTION WHEN check_violation THEN NULL;
END $$;
DO $$
BEGIN
  INSERT INTO payments.mpesa_resolution (tenant_id, resolution_id, item_type, item_id, outcome, evidence, requested_by)
  VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'B2C_DISBURSEMENT', gen_random_uuid(), 'NOT_PAID',
          'seen', 'user:ops-1');
  RAISE EXCEPTION 'TEST FAILED M3c: evidence too short accepted';
EXCEPTION WHEN check_violation THEN NULL;
END $$;
DO $$
BEGIN
  INSERT INTO payments.mpesa_resolution (tenant_id, resolution_id, item_type, item_id, outcome, evidence, requested_by)
  VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'STK_REQUEST', 'dddddddd-0000-0000-0000-000000000009',
          'NOT_PAID', 'customer says not paid', 'user:ops-2');
  RAISE EXCEPTION 'TEST FAILED M2';
EXCEPTION WHEN unique_violation THEN NULL;
END $$;
DO $$
BEGIN
  UPDATE payments.mpesa_resolution SET status = 'APPROVED', decided_by = 'user:ops-1', result_status = 'COMPLETED'
   WHERE resolution_id = 'eeeeeeee-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'TEST FAILED M1';
EXCEPTION WHEN check_violation THEN NULL;
END $$;
DO $$
BEGIN
  UPDATE payments.mpesa_resolution SET amount = 5000 WHERE resolution_id = 'eeeeeeee-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'TEST FAILED M4a';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'RESOLUTION_IMMUTABLE%' THEN RAISE; END IF;
END $$;
UPDATE payments.mpesa_resolution SET status = 'APPROVED', decided_by = 'user:sup-1', result_status = 'COMPLETED'
 WHERE resolution_id = 'eeeeeeee-0000-0000-0000-000000000001';
DO $$
BEGIN
  UPDATE payments.mpesa_resolution SET status = 'REJECTED' WHERE resolution_id = 'eeeeeeee-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'TEST FAILED M4b';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'RESOLUTION_FINAL%' THEN RAISE; END IF;
END $$;
DO $$
BEGIN
  DELETE FROM payments.mpesa_resolution WHERE resolution_id = 'eeeeeeee-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'TEST FAILED M4c';
EXCEPTION WHEN insufficient_privilege THEN NULL;
END $$;
COMMIT;
\echo PASS M1-M4 maker-checker, one live resolution per item, evidence and receipt rules, decisions final, no deletes

BEGIN;
SET LOCAL app.tenant_id = :tB;
DO $$
BEGIN
  IF (SELECT count(*) FROM payments.mpesa_resolution) <> 0 THEN
    RAISE EXCEPTION 'TEST FAILED M5: tenant B sees tenant A resolutions';
  END IF;
END $$;
COMMIT;
\echo PASS M5 tenant B cannot see tenant A resolutions
