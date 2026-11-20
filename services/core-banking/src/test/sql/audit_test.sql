-- =============================================================================
-- Tests for V24__staff_audit_trail.sql (part 1, as mfi_app).
-- =============================================================================
\set ON_ERROR_STOP on
\set QUIET on
\set tA '''00000000-0000-0000-0000-0000000000a1'''
\set tB '''00000000-0000-0000-0000-0000000000b2'''

BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO audit.staff_action (tenant_id, occurred_at, actor, roles, method, route, path, status, duration_ms, request_id,
                                prev_hash, hash)
VALUES (:tA::uuid, '2026-11-20 09:00:00+03', 'user:teller', 'TELLER', 'POST', '/api/v1/savings/teller/transactions',
        '/api/v1/savings/teller/transactions', 200, 12, 'r-1', repeat('a', 64), repeat('b', 64)),
       (:tA::uuid, '2026-11-20 09:01:00+03', 'user:manager', 'BRANCH_MANAGER', 'GET', '/api/v1/customers/{number}',
        '/api/v1/customers/CU26-0000001', 200, 5, 'r-2', repeat('a', 64), repeat('b', 64)),
       (:tA::uuid, '2026-11-20 09:02:00+03', 'user:teller', 'TELLER', 'POST', '/api/v1/lending/write-offs/{id}/approve',
        '/api/v1/lending/write-offs/x/approve', 403, 3, 'r-3', repeat('a', 64), repeat('b', 64));
DO $$
BEGIN
  IF (SELECT prev_hash FROM audit.staff_action WHERE chain_no = 1) <> repeat('0', 64)
     OR (SELECT prev_hash FROM audit.staff_action WHERE chain_no = 2) <> (SELECT hash FROM audit.staff_action WHERE chain_no = 1)
     OR EXISTS (SELECT 1 FROM audit.staff_action WHERE hash = repeat('b', 64)) THEN
    RAISE EXCEPTION 'TEST FAILED S3a: the database must compute the chain';
  END IF;
  IF audit.verify_chain('00000000-0000-0000-0000-0000000000a1') IS NOT NULL THEN
    RAISE EXCEPTION 'TEST FAILED S3b: fresh chain does not verify';
  END IF;
  BEGIN
    UPDATE audit.staff_action SET status = 200 WHERE chain_no = 3;
    RAISE EXCEPTION 'TEST FAILED S2a';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
  BEGIN
    DELETE FROM audit.staff_action WHERE chain_no = 3;
    RAISE EXCEPTION 'TEST FAILED S2b';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
END $$;
COMMIT;
BEGIN;
SET LOCAL app.tenant_id = :tB;
DO $$
BEGIN
  IF (SELECT count(*) FROM audit.staff_action) <> 0 THEN RAISE EXCEPTION 'TEST FAILED S4: tenant B sees tenant A audit'; END IF;
END $$;
COMMIT;
\echo PASS S1 S2 S3 S4 rows chained by the database, verify intact, application cannot change or delete, tenants isolated
