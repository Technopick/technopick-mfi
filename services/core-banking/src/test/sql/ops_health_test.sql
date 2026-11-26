-- =============================================================================
-- Tests for V42__operational_health.sql, run as mfi_app after the other tests. TEST-ONLY.
-- =============================================================================
\set ON_ERROR_STOP on
\set QUIET on
\set tA '''00000000-0000-0000-0000-0000000000a1'''
\set tB '''00000000-0000-0000-0000-0000000000b2'''

-- ---------- OH1 one row per figure and active tenant, numbers only ----------
BEGIN;
SET LOCAL app.tenant_id = :tB;
DO $$
DECLARE
  n int;
  v numeric;
BEGIN
  SELECT count(*) INTO n FROM ops.health_snapshot();
  IF n <> 2 * 9 THEN RAISE EXCEPTION 'TEST FAILED OH1 rows %', n; END IF;
  IF (SELECT count(DISTINCT tenant_code) FROM ops.health_snapshot()) <> 2 THEN RAISE EXCEPTION 'TEST FAILED OH1 tenants'; END IF;
  -- tenant A's figures are read although the caller is bound to tenant B
  SELECT value INTO v FROM ops.health_snapshot() WHERE tenant_code = 'TEST-MFB-A' AND metric = 'eod_days_behind';
  IF v IS NULL OR v <> trunc(v) THEN RAISE EXCEPTION 'TEST FAILED OH1 tenant A days behind %', v; END IF;
  -- ... and the caller's binding is unchanged afterwards
  IF current_setting('app.tenant_id') <> '00000000-0000-0000-0000-0000000000b2' THEN
    RAISE EXCEPTION 'TEST FAILED OH1 binding changed to %', current_setting('app.tenant_id');
  END IF;
  IF EXISTS (SELECT 1 FROM tenancy.business_date WHERE tenant_id = '00000000-0000-0000-0000-0000000000a1'::uuid) THEN
    RAISE EXCEPTION 'TEST FAILED OH1 caller sees tenant A rows directly';
  END IF;
END $$;
COMMIT;
\echo PASS OH1 the snapshot covers every active tenant, leaves the caller bound to its own tenant, and RLS still holds

-- ---------- OH2 figures follow the data ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO messaging.outbox_event (event_id, tenant_id, event_type, aggregate_type, aggregate_id, payload, occurred_at)
VALUES ('0e000000-0000-0000-0000-000000000001', :tA::uuid, 'OpsTestEvent', 'test', 'oh2', '{}', now() - interval '10 minutes');
DO $$
DECLARE
  unpublished numeric;
  oldest numeric;
BEGIN
  SELECT value INTO unpublished FROM ops.health_snapshot() WHERE tenant_code = 'TEST-MFB-A' AND metric = 'outbox_unpublished';
  SELECT value INTO oldest FROM ops.health_snapshot() WHERE tenant_code = 'TEST-MFB-A' AND metric = 'outbox_oldest_unpublished_seconds';
  IF unpublished < 1 OR oldest < 600 THEN RAISE EXCEPTION 'TEST FAILED OH2 outbox % %', unpublished, oldest; END IF;
  IF (SELECT value FROM ops.health_snapshot() WHERE tenant_code = 'TEST-SACCO-B' AND metric = 'outbox_unpublished')
     >= unpublished + 1000 THEN RAISE EXCEPTION 'TEST FAILED OH2 tenants mixed'; END IF;
  IF current_setting('app.tenant_id') <> '00000000-0000-0000-0000-0000000000a1' THEN RAISE EXCEPTION 'TEST FAILED OH2 binding'; END IF;
END $$;
ROLLBACK;
\echo PASS OH2 an event waiting 10 minutes shows in the outbox figures of its tenant only

-- ---------- OH3 audit chains ----------
BEGIN;
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM ops.audit_chain_status() WHERE broken_at IS NOT NULL) THEN
    RAISE EXCEPTION 'TEST FAILED OH3 a chain reported broken';
  END IF;
  IF (SELECT count(*) FROM ops.audit_chain_status()) <> 2 THEN RAISE EXCEPTION 'TEST FAILED OH3 tenants'; END IF;
END $$;
COMMIT;
\echo PASS OH3 audit chains reported per tenant, intact

-- ---------- OH4 no other access granted ----------
DO $$
BEGIN
  BEGIN
    CREATE FUNCTION ops.oh4() RETURNS int LANGUAGE sql AS 'SELECT 1';
    RAISE EXCEPTION 'TEST FAILED OH4 mfi_app created a function in ops';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
END $$;
\echo PASS OH4 the application role cannot add to the ops schema
