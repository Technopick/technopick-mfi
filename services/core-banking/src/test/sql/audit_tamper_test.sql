-- =============================================================================
-- Tests for V24 (part 2, as owner): someone with database rights edits a row behind the triggers;
-- verify_chain points at it.
-- =============================================================================
\set ON_ERROR_STOP on
\set QUIET on
BEGIN;
SET LOCAL app.tenant_id = '00000000-0000-0000-0000-0000000000a1';
DO $$
BEGIN
  BEGIN
    UPDATE audit.staff_action SET status = 200 WHERE chain_no = 3;
    RAISE EXCEPTION 'TEST FAILED S2c: owner changed an audit row through the trigger';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM LIKE 'TEST FAILED%' THEN RAISE; END IF;
  END;
END $$;
ALTER TABLE audit.staff_action DISABLE TRIGGER staff_action_immutable;
UPDATE audit.staff_action SET status = 204 WHERE chain_no = 2;  -- was 200
ALTER TABLE audit.staff_action ENABLE TRIGGER staff_action_immutable;
DO $$
BEGIN
  IF audit.verify_chain('00000000-0000-0000-0000-0000000000a1') IS DISTINCT FROM 2 THEN
    RAISE EXCEPTION 'TEST FAILED S5: tampering not detected at chain number 2';
  END IF;
END $$;
ROLLBACK;
\echo PASS S5 a row edited with triggers disabled is found by verify_chain
