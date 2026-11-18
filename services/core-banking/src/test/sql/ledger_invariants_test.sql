-- =============================================================================
-- Ledger invariant tests for V1__tenancy_and_ledger.sql.
-- Run by run-ledger-db-tests.(sh|ps1) as the APPLICATION role (mfi_app), so RLS and
-- grants are exercised exactly as in production. Every check prints PASS or aborts.
-- Deferred constraint triggers are forced inside each DO block with
-- SET CONSTRAINTS ALL IMMEDIATE so the failure is caught by the block's handler.
-- =============================================================================
\set ON_ERROR_STOP on
\set QUIET on
SET client_min_messages = notice;

\set tA '''00000000-0000-0000-0000-0000000000a1'''
\set tB '''00000000-0000-0000-0000-0000000000b2'''

-- ---------- Setup for tenant A (as the app, under RLS) ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO tenancy.branch (tenant_id, branch_code, name) VALUES (:tA::uuid, 'NBO01', 'Nairobi CBD'), (:tA::uuid, 'MSA01', 'Mombasa');
INSERT INTO ledger.gl_account (tenant_id, account_code, name, account_class, currency) VALUES
  (:tA::uuid, '1200-LOANS-PRINCIPAL',     'Loans receivable - principal', 'ASSET',     'KES'),
  (:tA::uuid, '1110-MPESA-B2C-CLEARING',  'M-Pesa B2C clearing',          'ASSET',     'KES'),
  (:tA::uuid, '4100-FEE-PROCESSING',      'Fee income - processing',      'INCOME',    'KES'),
  (:tA::uuid, '2100-CUSTOMER-DEPOSITS',   'Customer deposits',            'LIABILITY', 'KES'),
  (:tA::uuid, '2300-AGENT-FLOAT-PAYABLE', 'Agent float payable',          'LIABILITY', 'KES'),
  (:tA::uuid, '4200-FEE-WITHDRAWAL',      'Fee income - withdrawal',      'INCOME',    'KES'),
  (:tA::uuid, '1300-USD-NOSTRO',          'USD nostro',                   'ASSET',     'USD');
INSERT INTO ledger.accounting_period (tenant_id, period_id, start_date, end_date, status) VALUES
  (:tA::uuid, '2026-10', '2026-10-01', '2026-10-31', 'CLOSED'),
  (:tA::uuid, '2026-11', '2026-11-01', '2026-11-30', 'OPEN');
COMMIT;

-- ---------- T1: balanced fee-deducted disbursement commits ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
VALUES (:tA::uuid, '11111111-0000-0000-0000-000000000001', 'disb-1', '2026-11-18', 'LOAN', 'corr-1', 'user:loan-officer-1');
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
  (:tA::uuid, '11111111-0000-0000-0000-000000000001', 1, '1200-LOANS-PRINCIPAL',    'D', 100000.00, 'KES', 'NBO01'),
  (:tA::uuid, '11111111-0000-0000-0000-000000000001', 2, '1110-MPESA-B2C-CLEARING', 'C',  98000.00, 'KES', 'NBO01'),
  (:tA::uuid, '11111111-0000-0000-0000-000000000001', 3, '4100-FEE-PROCESSING',     'C',   2000.00, 'KES', 'NBO01');
COMMIT;
\echo PASS T1 balanced entry commits

-- Helper pattern: each negative test runs in its own transaction and asserts the error text.
-- ---------- T2: mis-posted net disbursement is rejected ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
  VALUES ('00000000-0000-0000-0000-0000000000a1', '11111111-0000-0000-0000-000000000002', 'disb-bad', '2026-11-18', 'LOAN', 'c', 'u');
  INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
    ('00000000-0000-0000-0000-0000000000a1', '11111111-0000-0000-0000-000000000002', 1, '1200-LOANS-PRINCIPAL',    'D', 98000.00, 'KES', 'NBO01'),
    ('00000000-0000-0000-0000-0000000000a1', '11111111-0000-0000-0000-000000000002', 2, '1110-MPESA-B2C-CLEARING', 'C', 98000.00, 'KES', 'NBO01'),
    ('00000000-0000-0000-0000-0000000000a1', '11111111-0000-0000-0000-000000000002', 3, '4100-FEE-PROCESSING',     'C',  2000.00, 'KES', 'NBO01');
  SET CONSTRAINTS ALL IMMEDIATE;
  RAISE EXCEPTION 'TEST FAILED T2: unbalanced entry was accepted';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'LEDGER_UNBALANCED%' THEN RAISE; END IF;
END $$;
ROLLBACK;
\echo PASS T2 unbalanced entry rejected

-- ---------- T3: single-line entry rejected ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
  VALUES ('00000000-0000-0000-0000-0000000000a1', '11111111-0000-0000-0000-000000000003', 'one-line', '2026-11-18', 'MANUAL', 'c', 'u');
  INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
    ('00000000-0000-0000-0000-0000000000a1', '11111111-0000-0000-0000-000000000003', 1, '1200-LOANS-PRINCIPAL', 'D', 1.00, 'KES', 'NBO01');
  SET CONSTRAINTS ALL IMMEDIATE;
  RAISE EXCEPTION 'TEST FAILED T3';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'LEDGER_UNBALANCED%' THEN RAISE; END IF;
END $$;
ROLLBACK;
\echo PASS T3 single-line entry rejected

-- ---------- T4: balanced in total but not per branch ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
  VALUES ('00000000-0000-0000-0000-0000000000a1', '11111111-0000-0000-0000-000000000004', 'branch-x', '2026-11-18', 'MANUAL', 'c', 'u');
  INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
    ('00000000-0000-0000-0000-0000000000a1', '11111111-0000-0000-0000-000000000004', 1, '2100-CUSTOMER-DEPOSITS', 'D', 10.00, 'KES', 'NBO01'),
    ('00000000-0000-0000-0000-0000000000a1', '11111111-0000-0000-0000-000000000004', 2, '4200-FEE-WITHDRAWAL',    'C', 10.00, 'KES', 'MSA01');
  SET CONSTRAINTS ALL IMMEDIATE;
  RAISE EXCEPTION 'TEST FAILED T4';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'LEDGER_UNBALANCED%' THEN RAISE; END IF;
END $$;
ROLLBACK;
\echo PASS T4 per-branch imbalance rejected

-- ---------- T5: closed period rejected ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
  VALUES ('00000000-0000-0000-0000-0000000000a1', '11111111-0000-0000-0000-000000000005', 'late', '2026-10-31', 'MANUAL', 'c', 'u');
  RAISE EXCEPTION 'TEST FAILED T5';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'LEDGER_PERIOD_CLOSED%' THEN RAISE; END IF;
END $$;
ROLLBACK;
\echo PASS T5 posting into closed period rejected

-- ---------- T6: duplicate idempotency key rejected ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
  VALUES ('00000000-0000-0000-0000-0000000000a1', '11111111-0000-0000-0000-000000000006', 'disb-1', '2026-11-18', 'LOAN', 'c', 'u');
  RAISE EXCEPTION 'TEST FAILED T6';
EXCEPTION WHEN unique_violation THEN NULL;
END $$;
ROLLBACK;
\echo PASS T6 duplicate idempotency key rejected

-- ---------- T7: line currency must match account currency ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
  VALUES ('00000000-0000-0000-0000-0000000000a1', '11111111-0000-0000-0000-000000000007', 'ccy', '2026-11-18', 'MANUAL', 'c', 'u');
  INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
    ('00000000-0000-0000-0000-0000000000a1', '11111111-0000-0000-0000-000000000007', 1, '1300-USD-NOSTRO', 'D', 10.00, 'KES', 'NBO01');
  RAISE EXCEPTION 'TEST FAILED T7';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'LEDGER_CURRENCY_MISMATCH%' THEN RAISE; END IF;
END $$;
ROLLBACK;
\echo PASS T7 currency mismatch rejected

-- ---------- T8: app role cannot UPDATE / DELETE / TRUNCATE journals (no grant) ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  UPDATE ledger.journal_line SET amount = 1 WHERE entry_id = '11111111-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'TEST FAILED T8 update';
EXCEPTION WHEN insufficient_privilege THEN NULL;
END $$;
DO $$
BEGIN
  DELETE FROM ledger.journal_entry WHERE entry_id = '11111111-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'TEST FAILED T8 delete';
EXCEPTION WHEN insufficient_privilege THEN NULL;
END $$;
ROLLBACK;
\echo PASS T8 app role has no update/delete privilege on journals

-- ---------- T9: switched withdrawal, then its exact reversal ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, switch_reference, actor)
VALUES (:tA::uuid, '22222222-0000-0000-0000-000000000001', 'sw-000001', '2026-11-18', 'SWITCH', 'corr-sw', 'RRN632207000001', 'channel:agent-AGT00001');
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
  (:tA::uuid, '22222222-0000-0000-0000-000000000001', 1, '2100-CUSTOMER-DEPOSITS',   'D', 5050.00, 'KES', 'NBO01'),
  (:tA::uuid, '22222222-0000-0000-0000-000000000001', 2, '2300-AGENT-FLOAT-PAYABLE', 'C', 5000.00, 'KES', 'NBO01'),
  (:tA::uuid, '22222222-0000-0000-0000-000000000001', 3, '4200-FEE-WITHDRAWAL',      'C',   50.00, 'KES', 'NBO01');
COMMIT;
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, switch_reference, reverses_entry_id, actor)
VALUES (:tA::uuid, '22222222-0000-0000-0000-000000000002', 'sw-000001-rev', '2026-11-18', 'REVERSAL', 'corr-sw-rev', 'RRN632207000001',
        '22222222-0000-0000-0000-000000000001', 'system:saf');
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
  (:tA::uuid, '22222222-0000-0000-0000-000000000002', 1, '2300-AGENT-FLOAT-PAYABLE', 'D', 5000.00, 'KES', 'NBO01'),
  (:tA::uuid, '22222222-0000-0000-0000-000000000002', 2, '4200-FEE-WITHDRAWAL',      'D',   50.00, 'KES', 'NBO01'),
  (:tA::uuid, '22222222-0000-0000-0000-000000000002', 3, '2100-CUSTOMER-DEPOSITS',   'C', 5050.00, 'KES', 'NBO01');
COMMIT;
\echo PASS T9 mirrored reversal accepted

-- ---------- T10: second reversal of the same entry rejected ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, reverses_entry_id, actor)
  VALUES ('00000000-0000-0000-0000-0000000000a1', '22222222-0000-0000-0000-000000000003', 'sw-000001-rev2', '2026-11-18', 'REVERSAL', 'c',
          '22222222-0000-0000-0000-000000000001', 'u');
  RAISE EXCEPTION 'TEST FAILED T10';
EXCEPTION WHEN unique_violation THEN NULL;
END $$;
ROLLBACK;
\echo PASS T10 double reversal rejected

-- ---------- T11: reversal that does not mirror the original rejected ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, reverses_entry_id, actor)
  VALUES ('00000000-0000-0000-0000-0000000000a1', '33333333-0000-0000-0000-000000000001', 'disb-1-rev', '2026-11-18', 'REVERSAL', 'c',
          '11111111-0000-0000-0000-000000000001', 'u');
  -- Balanced, but reverses only part of the original.
  INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
    ('00000000-0000-0000-0000-0000000000a1', '33333333-0000-0000-0000-000000000001', 1, '1110-MPESA-B2C-CLEARING', 'D', 98000.00, 'KES', 'NBO01'),
    ('00000000-0000-0000-0000-0000000000a1', '33333333-0000-0000-0000-000000000001', 2, '1200-LOANS-PRINCIPAL',    'C', 98000.00, 'KES', 'NBO01');
  SET CONSTRAINTS ALL IMMEDIATE;
  RAISE EXCEPTION 'TEST FAILED T11';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'LEDGER_REVERSAL_MISMATCH%' THEN RAISE; END IF;
END $$;
ROLLBACK;
\echo PASS T11 non-mirroring reversal rejected

-- ---------- T12: reversal of a reversal rejected ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, reverses_entry_id, actor)
  VALUES ('00000000-0000-0000-0000-0000000000a1', '22222222-0000-0000-0000-000000000004', 'rev-of-rev', '2026-11-18', 'REVERSAL', 'c',
          '22222222-0000-0000-0000-000000000002', 'u');
  INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
    ('00000000-0000-0000-0000-0000000000a1', '22222222-0000-0000-0000-000000000004', 1, '2100-CUSTOMER-DEPOSITS',   'D', 5050.00, 'KES', 'NBO01'),
    ('00000000-0000-0000-0000-0000000000a1', '22222222-0000-0000-0000-000000000004', 2, '2300-AGENT-FLOAT-PAYABLE', 'C', 5000.00, 'KES', 'NBO01'),
    ('00000000-0000-0000-0000-0000000000a1', '22222222-0000-0000-0000-000000000004', 3, '4200-FEE-WITHDRAWAL',      'C',   50.00, 'KES', 'NBO01');
  SET CONSTRAINTS ALL IMMEDIATE;
  RAISE EXCEPTION 'TEST FAILED T12';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'LEDGER_REVERSAL_OF_REVERSAL%' THEN RAISE; END IF;
END $$;
ROLLBACK;
\echo PASS T12 reversal of a reversal rejected

-- ---------- T13: switched withdrawal + reversal net to zero on every account ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
DECLARE v_bad int;
BEGIN
  SELECT count(*) INTO v_bad FROM (
    SELECT account_code, sum(CASE side WHEN 'D' THEN amount ELSE -amount END) AS net
      FROM ledger.journal_line
     WHERE entry_id IN ('22222222-0000-0000-0000-000000000001', '22222222-0000-0000-0000-000000000002')
     GROUP BY account_code HAVING sum(CASE side WHEN 'D' THEN amount ELSE -amount END) <> 0) x;
  IF v_bad <> 0 THEN RAISE EXCEPTION 'TEST FAILED T13: % accounts not netted', v_bad; END IF;
END $$;
COMMIT;
\echo PASS T13 withdrawal and reversal net to zero

-- ---------- T14: tenant B cannot see or write tenant A data ----------
BEGIN;
SET LOCAL app.tenant_id = :tB;
DO $$
DECLARE n int;
BEGIN
  SELECT count(*) INTO n FROM ledger.journal_entry;
  IF n <> 0 THEN RAISE EXCEPTION 'TEST FAILED T14: tenant B sees % entries', n; END IF;
  SELECT count(*) INTO n FROM ledger.journal_line;
  IF n <> 0 THEN RAISE EXCEPTION 'TEST FAILED T14: tenant B sees % lines', n; END IF;
  SELECT count(*) INTO n FROM ledger.gl_account;
  IF n <> 0 THEN RAISE EXCEPTION 'TEST FAILED T14: tenant B sees % accounts', n; END IF;
  SELECT count(*) INTO n FROM tenancy.tenant;
  IF n <> 1 THEN RAISE EXCEPTION 'TEST FAILED T14: tenant B sees % tenant rows', n; END IF;
END $$;
DO $$
BEGIN
  -- Writing a row stamped with tenant A while acting as tenant B must fail.
  INSERT INTO ledger.gl_account (tenant_id, account_code, name, account_class, currency)
  VALUES ('00000000-0000-0000-0000-0000000000a1', '9999-SMUGGLED', 'x', 'ASSET', 'KES');
  RAISE EXCEPTION 'TEST FAILED T14 write';
EXCEPTION WHEN insufficient_privilege THEN NULL;  -- RLS WITH CHECK violation is 42501
END $$;
ROLLBACK;
\echo PASS T14 cross-tenant read and write blocked

-- ---------- T15: no tenant context means no rows (fail closed) ----------
BEGIN;
DO $$
DECLARE n int;
BEGIN
  SELECT count(*) INTO n FROM ledger.journal_entry;
  IF n <> 0 THEN RAISE EXCEPTION 'TEST FAILED T15: % rows visible without tenant context', n; END IF;
END $$;
ROLLBACK;
\echo PASS T15 missing tenant context returns nothing

-- ---------- T16: tenant A sees exactly its 3 posted entries ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
DECLARE n int;
BEGIN
  SELECT count(*) INTO n FROM ledger.journal_entry;
  IF n <> 3 THEN RAISE EXCEPTION 'TEST FAILED T16: expected 3 entries, found %', n; END IF;
END $$;
ROLLBACK;
\echo PASS T16 tenant A sees exactly its own 3 entries

\echo ALL LEDGER DATABASE TESTS PASSED
