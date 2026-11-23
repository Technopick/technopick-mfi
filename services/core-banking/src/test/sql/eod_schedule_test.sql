-- =============================================================================
-- Tests for V26__eod_schedule_and_attempts.sql, as mfi_app.
-- =============================================================================
\set ON_ERROR_STOP on
\set QUIET on
\set tA '''00000000-0000-0000-0000-0000000000a1'''
\set tB '''00000000-0000-0000-0000-0000000000b2'''

-- V1: schedule limits are enforced by the database, whatever the application sends.
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
DECLARE bad text[] := ARRAY[
  'INSERT INTO ledger.eod_schedule (tenant_id, overrun_minutes, updated_by) VALUES (tenancy.current_tenant(), 4, ''x'')',
  'INSERT INTO ledger.eod_schedule (tenant_id, max_attempts, updated_by) VALUES (tenancy.current_tenant(), 11, ''x'')',
  'INSERT INTO ledger.eod_schedule (tenant_id, retry_minutes, updated_by) VALUES (tenancy.current_tenant(), 241, ''x'')',
  'INSERT INTO ledger.eod_schedule (tenant_id, max_catch_up_days, updated_by) VALUES (tenancy.current_tenant(), 8, ''x'')',
  'INSERT INTO ledger.eod_schedule (tenant_id, run_at, updated_by) VALUES (tenancy.current_tenant(), ''21:00:30'', ''x'')'];
  q text;
BEGIN
  FOREACH q IN ARRAY bad LOOP
    BEGIN
      EXECUTE q;
      RAISE EXCEPTION 'TEST FAILED V1: accepted %', q;
    EXCEPTION WHEN check_violation THEN NULL;
    END;
  END LOOP;
  BEGIN
    INSERT INTO ledger.eod_schedule (tenant_id, updated_by) VALUES ('00000000-0000-0000-0000-0000000000b2', 'x');
    RAISE EXCEPTION 'TEST FAILED V1: wrote another tenant''s schedule';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
END $$;
INSERT INTO ledger.eod_schedule (tenant_id, enabled, run_at, updated_by) VALUES (:tA::uuid, true, '21:00', 'user:supervisor');
COMMIT;
\echo PASS V1 schedule limits and tenant isolation enforced by the database

-- V2: an attempt moves once from RUNNING to an outcome; nothing else about it changes; never deleted.
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO ledger.eod_attempt (tenant_id, business_date, trigger, actor, started_at)
VALUES (:tA::uuid, '2026-11-20', 'SCHEDULED', 'system:eod-scheduler', '2026-11-20 21:00+03');
DO $$
DECLARE id bigint;
BEGIN
  SELECT max(attempt_id) INTO id FROM ledger.eod_attempt;
  BEGIN
    UPDATE ledger.eod_attempt SET outcome = 'FAILED', finished_at = '2026-11-20 21:01+03' WHERE attempt_id = id;
    RAISE EXCEPTION 'TEST FAILED V2a: failure without a reason';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    UPDATE ledger.eod_attempt SET outcome = 'COMPLETED', finished_at = '2026-11-20 20:59+03' WHERE attempt_id = id;
    RAISE EXCEPTION 'TEST FAILED V2b: finished before it started';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    UPDATE ledger.eod_attempt SET actor = 'user:someone-else' WHERE attempt_id = id;
    RAISE EXCEPTION 'TEST FAILED V2c: actor rewritten';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'EOD_ATTEMPT_IMMUTABLE%' THEN RAISE; END IF;
  END;
  UPDATE ledger.eod_attempt SET outcome = 'FAILED', reason = 'disk full', finished_at = '2026-11-20 21:02+03' WHERE attempt_id = id;
  BEGIN
    UPDATE ledger.eod_attempt SET outcome = 'COMPLETED', reason = NULL WHERE attempt_id = id;
    RAISE EXCEPTION 'TEST FAILED V2d: final outcome rewritten';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'EOD_ATTEMPT_IMMUTABLE%' THEN RAISE; END IF;
  END;
  BEGIN
    DELETE FROM ledger.eod_attempt WHERE attempt_id = id;
    RAISE EXCEPTION 'TEST FAILED V2e: attempt deleted';
  EXCEPTION WHEN raise_exception OR insufficient_privilege THEN NULL;
  END;
END $$;
COMMIT;
\echo PASS V2 attempts end once, keep who and when, and are never deleted

-- V3: another tenant sees none of it.
BEGIN;
SET LOCAL app.tenant_id = :tB;
DO $$
BEGIN
  IF (SELECT count(*) FROM ledger.eod_attempt) <> 0 OR (SELECT count(*) FROM ledger.eod_schedule) <> 0 THEN
    RAISE EXCEPTION 'TEST FAILED V3: tenant B sees tenant A''s end-of-day schedule or attempts';
  END IF;
END $$;
COMMIT;
\echo PASS V3 end-of-day schedule and attempts are isolated per tenant
