-- =============================================================================
-- Tests for V34__loan_rescheduling.sql, as mfi_app. Everything is rolled back.
-- =============================================================================
\set ON_ERROR_STOP on
\set QUIET on

BEGIN;
SET LOCAL app.tenant_id = '00000000-0000-0000-0000-0000000000a1';

-- RS1: reschedules are requested for today on active loans, decided by someone else, one open per loan, never changed.
-- RS2: a new schedule version needs an approved reschedule (or a settlement) and keeps what was due, accruing or paid.
DO $$
DECLARE
  v_loan uuid; v_version int; v_today date; r uuid := gen_random_uuid(); v_first int;
BEGIN
  SELECT current_date_ INTO v_today FROM tenancy.business_date;
  SELECT l.loan_id, max(s.schedule_version) INTO v_loan, v_version FROM lending.loan l
    JOIN lending.schedule_installment s ON s.tenant_id = l.tenant_id AND s.loan_id = l.loan_id
   WHERE l.status = 'ACTIVE' GROUP BY l.loan_id ORDER BY l.loan_id LIMIT 1;
  IF v_loan IS NULL THEN RAISE EXCEPTION 'TEST SETUP RS: tenant A needs an active loan with a schedule'; END IF;

  BEGIN
    INSERT INTO lending.loan_reschedule (tenant_id, reschedule_id, idempotency_key, loan_id, kind, business_date, from_version,
        instalments, outstanding, reason, requested_by)
    VALUES (tenancy.current_tenant(), gen_random_uuid(), 'rs-a', v_loan, 'RESTRUCTURE', v_today - 1, v_version, 6, 1000,
        'Member lost income, plan agreed', 'user:officer');
    RAISE EXCEPTION 'TEST FAILED RS1a: reschedule backdated';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'RESCHEDULE_INVALID%' THEN RAISE; END IF;
  END;
  BEGIN
    INSERT INTO lending.loan_reschedule (tenant_id, reschedule_id, idempotency_key, loan_id, kind, business_date, from_version,
        instalments, prepayment, outstanding, reason, requested_by)
    VALUES (tenancy.current_tenant(), gen_random_uuid(), 'rs-b', v_loan, 'RESTRUCTURE', v_today, v_version, 6, 100, 1000,
        'Mixed restructure and prepayment', 'user:officer');
    RAISE EXCEPTION 'TEST FAILED RS1b: restructure with a prepayment amount';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  INSERT INTO lending.loan_reschedule (tenant_id, reschedule_id, idempotency_key, loan_id, kind, business_date, from_version,
      instalments, outstanding, reason, requested_by)
  VALUES (tenancy.current_tenant(), r, 'rs-c', v_loan, 'RESTRUCTURE', v_today, v_version, 6, 1000,
      'Member lost income, plan agreed', 'user:officer');
  BEGIN
    INSERT INTO lending.loan_reschedule (tenant_id, reschedule_id, idempotency_key, loan_id, kind, business_date, from_version,
        instalments, outstanding, reason, requested_by)
    VALUES (tenancy.current_tenant(), gen_random_uuid(), 'rs-d', v_loan, 'RESTRUCTURE', v_today, v_version, 6, 1000,
        'A second open request for it', 'user:officer');
    RAISE EXCEPTION 'TEST FAILED RS1c: two open reschedules';
  EXCEPTION WHEN unique_violation THEN NULL;
  END;
  BEGIN
    UPDATE lending.loan_reschedule SET status = 'APPROVED', decided_by = 'user:officer', decided_at = clock_timestamp(),
           to_version = v_version + 1 WHERE reschedule_id = r;
    RAISE EXCEPTION 'TEST FAILED RS1d: requester approved own reschedule';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    UPDATE lending.loan_reschedule SET instalments = 60 WHERE reschedule_id = r;
    RAISE EXCEPTION 'TEST FAILED RS1e: request changed';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'RESCHEDULE_IMMUTABLE%' THEN RAISE; END IF;
  END;

  -- RS2a: a new version with no approval.
  BEGIN
    INSERT INTO lending.schedule_installment (tenant_id, loan_id, schedule_version, installment_no, due_date, principal, interest)
    SELECT tenant_id, loan_id, v_version + 1, installment_no, due_date, principal, interest FROM lending.schedule_installment
     WHERE loan_id = v_loan AND schedule_version = v_version;
    SET CONSTRAINTS ALL IMMEDIATE;
    RAISE EXCEPTION 'TEST FAILED RS2a: schedule changed without approval';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'SCHEDULE_VERSION_UNEXPLAINED%' THEN RAISE; END IF;
  END;
  SET CONSTRAINTS ALL DEFERRED;

  UPDATE lending.loan_reschedule SET status = 'APPROVED', decided_by = 'user:manager', decided_at = clock_timestamp(),
         to_version = v_version + 1 WHERE reschedule_id = r;
  -- RS2b: the earliest instalment (due already, or accruing now) cannot change its interest.
  SELECT installment_no INTO v_first FROM lending.schedule_installment WHERE loan_id = v_loan AND schedule_version = v_version
   ORDER BY due_date, installment_no LIMIT 1;
  BEGIN
    INSERT INTO lending.schedule_installment (tenant_id, loan_id, schedule_version, installment_no, due_date, principal, interest)
    SELECT tenant_id, loan_id, v_version + 1, installment_no, due_date, principal,
           interest + CASE WHEN installment_no = v_first THEN 1 ELSE 0 END
      FROM lending.schedule_installment WHERE loan_id = v_loan AND schedule_version = v_version;
    SET CONSTRAINTS ALL IMMEDIATE;
    RAISE EXCEPTION 'TEST FAILED RS2b: due or accruing instalment changed its interest';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'SCHEDULE_CHANGE_INVALID%' THEN RAISE; END IF;
  END;
  SET CONSTRAINTS ALL DEFERRED;
  -- RS2c: the approved version that keeps those rules is accepted.
  INSERT INTO lending.schedule_installment (tenant_id, loan_id, schedule_version, installment_no, due_date, principal, interest)
  SELECT tenant_id, loan_id, v_version + 1, installment_no, due_date, principal, interest FROM lending.schedule_installment
   WHERE loan_id = v_loan AND schedule_version = v_version;
  SET CONSTRAINTS ALL IMMEDIATE;
  SET CONSTRAINTS ALL DEFERRED;
  BEGIN
    UPDATE lending.loan_reschedule SET status = 'REJECTED' WHERE reschedule_id = r;
    RAISE EXCEPTION 'TEST FAILED RS2d: approved reschedule changed';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'RESCHEDULE_IMMUTABLE%' THEN RAISE; END IF;
  END;
END $$;
\echo PASS RS1 reschedules are requested for today, maker-checker, one open per loan, never changed
\echo PASS RS2 new schedule versions need approval and keep what was due, accruing or paid
ROLLBACK;
