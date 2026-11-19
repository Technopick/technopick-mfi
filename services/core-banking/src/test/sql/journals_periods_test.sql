-- =============================================================================
-- Tests for V23__manual_journals_and_period_close.sql, run as mfi_app last. Tenant A: business
-- date 2026-11-21 with its end of day RUNNING; period 2026-10 CLOSED, 2026-11 OPEN.
-- =============================================================================
\set ON_ERROR_STOP on
\set QUIET on
\set tA '''00000000-0000-0000-0000-0000000000a1'''
\set tB '''00000000-0000-0000-0000-0000000000b2'''

-- Q1: unbalanced, future-dated; Q2: control accounts.
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
DECLARE j uuid := gen_random_uuid();
BEGIN
  BEGIN
    INSERT INTO ledger.manual_journal (tenant_id, journal_id, idempotency_key, business_date, narrative, requested_by)
    VALUES ('00000000-0000-0000-0000-0000000000a1', j, 'mj-u', '2026-11-21', 'Unbalanced journal for testing', 'user:fin1');
    INSERT INTO ledger.manual_journal_line (tenant_id, journal_id, line_no, account_code, side, amount, currency, branch_code) VALUES
      ('00000000-0000-0000-0000-0000000000a1', j, 1, '4200-FEE-WITHDRAWAL', 'D', 100, 'KES', 'NBO01'),
      ('00000000-0000-0000-0000-0000000000a1', j, 2, '1120-MPESA-C2B-CLEARING', 'C', 90, 'KES', 'NBO01');
    SET CONSTRAINTS ALL IMMEDIATE;
    RAISE EXCEPTION 'TEST FAILED Q1a: unbalanced journal accepted';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'MANUAL_JOURNAL_UNBALANCED%' THEN RAISE; END IF;
  END;
  SET CONSTRAINTS ALL DEFERRED;
  BEGIN
    INSERT INTO ledger.manual_journal (tenant_id, journal_id, idempotency_key, business_date, narrative, requested_by)
    VALUES ('00000000-0000-0000-0000-0000000000a1', j, 'mj-f', '2026-11-22', 'Future dated journal for testing', 'user:fin1');
    INSERT INTO ledger.manual_journal_line (tenant_id, journal_id, line_no, account_code, side, amount, currency, branch_code) VALUES
      ('00000000-0000-0000-0000-0000000000a1', j, 1, '4200-FEE-WITHDRAWAL', 'D', 100, 'KES', 'NBO01'),
      ('00000000-0000-0000-0000-0000000000a1', j, 2, '1120-MPESA-C2B-CLEARING', 'C', 100, 'KES', 'NBO01');
    SET CONSTRAINTS ALL IMMEDIATE;
    RAISE EXCEPTION 'TEST FAILED Q1b: journal dated after the business date';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'MANUAL_JOURNAL_DATE%' THEN RAISE; END IF;
  END;
  SET CONSTRAINTS ALL DEFERRED;
  BEGIN
    INSERT INTO ledger.manual_journal (tenant_id, journal_id, idempotency_key, business_date, narrative, requested_by)
    VALUES ('00000000-0000-0000-0000-0000000000a1', j, 'mj-c', '2026-11-21', 'Touches loan principal directly', 'user:fin1');
    INSERT INTO ledger.manual_journal_line (tenant_id, journal_id, line_no, account_code, side, amount, currency, branch_code) VALUES
      ('00000000-0000-0000-0000-0000000000a1', j, 1, '1200-LOANS-PRINCIPAL', 'D', 100, 'KES', 'NBO01');
    RAISE EXCEPTION 'TEST FAILED Q2: manual journal on a control account';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'MANUAL_JOURNAL_CONTROL_ACCOUNT%' THEN RAISE; END IF;
  END;
END $$;
COMMIT;
\echo PASS Q1 Q2 manual journals balance, are not future-dated and never touch control accounts

-- Q3: a pending journal; the requester cannot decide; the entry must equal the lines.
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO ledger.manual_journal (tenant_id, journal_id, idempotency_key, business_date, narrative, requested_by)
VALUES (:tA::uuid, '3a000000-0000-0000-0000-000000000001', 'mj-1', '2026-11-21', 'Reclassify fee income booked to clearing', 'user:fin1');
INSERT INTO ledger.manual_journal_line (tenant_id, journal_id, line_no, account_code, side, amount, currency, branch_code) VALUES
  (:tA::uuid, '3a000000-0000-0000-0000-000000000001', 1, '1120-MPESA-C2B-CLEARING', 'D', 250, 'KES', 'NBO01'),
  (:tA::uuid, '3a000000-0000-0000-0000-000000000001', 2, '4200-FEE-WITHDRAWAL', 'C', 250, 'KES', 'NBO01');
INSERT INTO ledger.manual_journal (tenant_id, journal_id, idempotency_key, business_date, narrative, requested_by)
VALUES (:tA::uuid, '3a000000-0000-0000-0000-000000000002', 'mj-2', '2026-11-21', 'Second journal left pending to test close', 'user:fin1');
INSERT INTO ledger.manual_journal_line (tenant_id, journal_id, line_no, account_code, side, amount, currency, branch_code) VALUES
  (:tA::uuid, '3a000000-0000-0000-0000-000000000002', 1, '1120-MPESA-C2B-CLEARING', 'D', 10, 'KES', 'NBO01'),
  (:tA::uuid, '3a000000-0000-0000-0000-000000000002', 2, '4200-FEE-WITHDRAWAL', 'C', 10, 'KES', 'NBO01');
COMMIT;

BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
VALUES (:tA::uuid, 'df000000-0000-0000-0000-000000000001', 'manual:3a000000-0000-0000-0000-000000000001', '2026-11-21',
        'MANUAL', 'c', 'user:fin2');
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
  (:tA::uuid, 'df000000-0000-0000-0000-000000000001', 1, '1120-MPESA-C2B-CLEARING', 'D', 250, 'KES', 'NBO01'),
  (:tA::uuid, 'df000000-0000-0000-0000-000000000001', 2, '4200-FEE-WITHDRAWAL', 'C', 250, 'KES', 'NBO01');
INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
VALUES (:tA::uuid, 'df000000-0000-0000-0000-000000000002', 'manual:other', '2026-11-21', 'MANUAL', 'c', 'user:fin2');
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
  (:tA::uuid, 'df000000-0000-0000-0000-000000000002', 1, '1120-MPESA-C2B-CLEARING', 'D', 300, 'KES', 'NBO01'),
  (:tA::uuid, 'df000000-0000-0000-0000-000000000002', 2, '4200-FEE-WITHDRAWAL', 'C', 300, 'KES', 'NBO01');
DO $$
BEGIN
  BEGIN
    UPDATE ledger.manual_journal SET status = 'POSTED', decided_by = 'user:fin1', entry_id = 'df000000-0000-0000-0000-000000000001'
     WHERE journal_id = '3a000000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED Q3a: requester approved';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    UPDATE ledger.manual_journal SET status = 'POSTED', decided_by = 'user:fin2', entry_id = 'df000000-0000-0000-0000-000000000002'
     WHERE journal_id = '3a000000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED Q3b: posted with a different entry';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'MANUAL_JOURNAL_ENTRY%' THEN RAISE; END IF;
  END;
END $$;
UPDATE ledger.manual_journal SET status = 'POSTED', decided_by = 'user:fin2', entry_id = 'df000000-0000-0000-0000-000000000001'
 WHERE journal_id = '3a000000-0000-0000-0000-000000000001';
COMMIT;
DO $$
BEGIN
  PERFORM set_config('app.tenant_id', '00000000-0000-0000-0000-0000000000a1', false);
  BEGIN
    UPDATE ledger.manual_journal SET decision_reason = 'x' WHERE journal_id = '3a000000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED Q3c';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'MANUAL_JOURNAL_FINAL%' THEN RAISE; END IF;
  END;
  BEGIN
    INSERT INTO ledger.manual_journal_line (tenant_id, journal_id, line_no, account_code, side, amount, currency, branch_code)
    VALUES ('00000000-0000-0000-0000-0000000000a1', '3a000000-0000-0000-0000-000000000001', 3, '4200-FEE-WITHDRAWAL', 'D', 1, 'KES', 'NBO01');
    RAISE EXCEPTION 'TEST FAILED Q3d';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'MANUAL_JOURNAL_FINAL%' THEN RAISE; END IF;
  END;
END $$;
\echo PASS Q3 a different person posts exactly the approved lines; decided journals are final

-- Y1: periods.
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  BEGIN
    INSERT INTO ledger.accounting_period (tenant_id, period_id, start_date, end_date)
    VALUES ('00000000-0000-0000-0000-0000000000a1', '2026-12', '2026-12-02', '2026-12-31');
    RAISE EXCEPTION 'TEST FAILED Y1a';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'PERIOD_SHAPE%' THEN RAISE; END IF;
  END;
  BEGIN
    INSERT INTO ledger.accounting_period (tenant_id, period_id, start_date, end_date)
    VALUES ('00000000-0000-0000-0000-0000000000a1', '2027-01', '2027-01-01', '2027-01-31');
    RAISE EXCEPTION 'TEST FAILED Y1b';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'PERIOD_NOT_CONTIGUOUS%' THEN RAISE; END IF;
  END;
  BEGIN
    UPDATE ledger.accounting_period SET status = 'CLOSED', closed_by = 'user:fin2' WHERE period_id = '2026-11';
    RAISE EXCEPTION 'TEST FAILED Y2a: closed before its last day';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'PERIOD_CLOSE_RULES%' THEN RAISE; END IF;
  END;
END $$;
INSERT INTO ledger.accounting_period (tenant_id, period_id, start_date, end_date) VALUES (:tA::uuid, '2026-12', '2026-12-01', '2026-12-31');
COMMIT;

-- Close days 21-30 November (the tills are closed) and move to 1 December.
DO $$
DECLARE d date;
BEGIN
  PERFORM set_config('app.tenant_id', '00000000-0000-0000-0000-0000000000a1', false);
  UPDATE ledger.eod_run SET status = 'COMPLETED' WHERE business_date = '2026-11-21';
  UPDATE tenancy.business_date SET current_date_ = '2026-11-22';
  FOR d IN SELECT generate_series('2026-11-22'::date, '2026-11-30'::date, interval '1 day')::date LOOP
    INSERT INTO ledger.eod_run (tenant_id, business_date, status) VALUES ('00000000-0000-0000-0000-0000000000a1', d, 'RUNNING');
    UPDATE ledger.eod_run SET status = 'COMPLETED' WHERE business_date = d;
    UPDATE tenancy.business_date SET current_date_ = d + 1;
  END LOOP;
END $$;

BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  BEGIN
    UPDATE ledger.accounting_period SET status = 'CLOSED', closed_by = 'user:fin2' WHERE period_id = '2026-11';
    RAISE EXCEPTION 'TEST FAILED Y2b: closed with a pending manual journal';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'PERIOD_CLOSE_RULES%' THEN RAISE; END IF;
  END;
END $$;
UPDATE ledger.manual_journal SET status = 'REJECTED', decided_by = 'user:fin2', decision_reason = 'Not needed'
 WHERE journal_id = '3a000000-0000-0000-0000-000000000002';
UPDATE ledger.accounting_period SET status = 'CLOSED', closed_by = 'user:fin2' WHERE period_id = '2026-11';
COMMIT;
DO $$
BEGIN
  PERFORM set_config('app.tenant_id', '00000000-0000-0000-0000-0000000000a1', false);
  IF (SELECT closed_at FROM ledger.accounting_period WHERE period_id = '2026-11') IS NULL THEN
    RAISE EXCEPTION 'TEST FAILED Y2c: close time not stamped';
  END IF;
  BEGIN
    UPDATE ledger.accounting_period SET status = 'OPEN' WHERE period_id = '2026-11';
    RAISE EXCEPTION 'TEST FAILED Y2d: reopened';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'PERIOD_FINAL%' THEN RAISE; END IF;
  END;
  BEGIN
    INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
    VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'late', '2026-11-25', 'MANUAL', 'c', 'u');
    RAISE EXCEPTION 'TEST FAILED Y2e: posted into a closed month';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'LEDGER_PERIOD_CLOSED%' THEN RAISE; END IF;
  END;
END $$;
BEGIN;
SET LOCAL app.tenant_id = :tB;
DO $$
BEGIN
  IF (SELECT count(*) FROM ledger.manual_journal) <> 0 THEN RAISE EXCEPTION 'TEST FAILED Q4'; END IF;
END $$;
COMMIT;
\echo PASS Y1 Y2 Q4 monthly contiguous periods; close only after the last day, nothing pending; final; tenant B sees no journals
