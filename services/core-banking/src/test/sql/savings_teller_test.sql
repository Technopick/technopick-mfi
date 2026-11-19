-- =============================================================================
-- Tests for V16__savings_accounts_and_teller.sql, run as mfi_app after customer_test.sql
-- (customer CU26-0000001 ACTIVE in tenant A). Setup rows (GL, product, policy) are inserted by the
-- owner in the runner because the app role cannot configure them.
-- =============================================================================
\set ON_ERROR_STOP on
\set QUIET on
\set tA '''00000000-0000-0000-0000-0000000000a1'''
\set tB '''00000000-0000-0000-0000-0000000000b2'''

BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
DECLARE a varchar; b varchar;
BEGIN
  a := savings.next_account_number();
  b := savings.next_account_number();
  IF a <> '2000000014' OR b <> '2000000022' THEN
    RAISE EXCEPTION 'TEST FAILED D2: % %', a, b;
  END IF;
END $$;
-- Pending customer cannot own an account; the verified one can.
INSERT INTO customer.customer (tenant_id, customer_id, customer_number, idempotency_key, first_name, last_name, date_of_birth,
    id_type, id_number, msisdn, branch_code, consent_version, consent_at, created_by)
VALUES (:tA::uuid, 'c0000000-0000-0000-0000-000000000002', 'CU26-0000050', 'cust-t1', 'Pending', 'Person', '1980-01-01',
        'NATIONAL_ID', '55667788', '254733000000', 'NBO01', 'DPA-2026-01', now(), 'user:officer');
DO $$
BEGIN
  INSERT INTO savings.deposit_account (tenant_id, account_id, account_number, customer_ref, customer_id, product_code, currency,
      branch_code, gl_control_account, opened_on)
  VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), '2000000030', 'CU26-0000050',
          'c0000000-0000-0000-0000-000000000002', 'SAVT', 'KES', 'NBO01', '2111-TEST-SAVINGS', '2026-11-18');
  RAISE EXCEPTION 'TEST FAILED D1a: account for an unverified customer';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'SAVINGS_CUSTOMER_NOT_ACTIVE%' THEN RAISE; END IF;
END $$;
DO $$
BEGIN
  INSERT INTO savings.deposit_account (tenant_id, account_id, account_number, customer_ref, customer_id, product_code, currency,
      branch_code, gl_control_account, opened_on)
  VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), '2000000030', 'SOMEONE-ELSE',
          'c0000000-0000-0000-0000-000000000001', 'SAVT', 'KES', 'NBO01', '2111-TEST-SAVINGS', '2026-11-18');
  RAISE EXCEPTION 'TEST FAILED D1b: customer reference not the customer number';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'SAVINGS_CUSTOMER_MISMATCH%' THEN RAISE; END IF;
END $$;
INSERT INTO savings.deposit_account (tenant_id, account_id, account_number, customer_ref, customer_id, product_code, currency,
    branch_code, gl_control_account, opened_on)
VALUES (:tA::uuid, 'aa000000-0000-0000-0000-0000000000d1', '2000000014', 'CU26-0000001', 'c0000000-0000-0000-0000-000000000001',
        'SAVT', 'KES', 'NBO01', '2111-TEST-SAVINGS', '2026-11-18');
DO $$
BEGIN
  UPDATE savings.deposit_account SET customer_ref = 'CU26-0000050', customer_id = 'c0000000-0000-0000-0000-000000000002'
   WHERE account_id = 'aa000000-0000-0000-0000-0000000000d1';
  RAISE EXCEPTION 'TEST FAILED D1c: owner changed';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'SAVINGS_ACCOUNT_IMMUTABLE%' THEN RAISE; END IF;
END $$;
COMMIT;
\echo PASS D1 D2 accounts only for verified customers, owner fixed, numbers 2+8 digits+Luhn

-- The teller works in an open till session (V17).
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO savings.till_session (tenant_id, session_id, till_id, teller, business_date)
VALUES (:tA::uuid, '5e000000-0000-0000-0000-000000000001', '7e000000-0000-0000-0000-000000000001', 'user:teller', '2026-11-18');
COMMIT;

-- Deposit of 100,000 (below the 1,000,000 deposit threshold) posts directly.
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
VALUES (:tA::uuid, 'de000000-0000-0000-0000-000000000001', 'teller:t-1', '2026-11-18', 'TELLER', 'c', 'user:teller');
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code, subledger_account_id) VALUES
  (:tA::uuid, 'de000000-0000-0000-0000-000000000001', 1, '1001-TEST-CASH', 'D', 100000, 'KES', 'NBO01', NULL),
  (:tA::uuid, 'de000000-0000-0000-0000-000000000001', 2, '2111-TEST-SAVINGS', 'C', 100000, 'KES', 'NBO01', 'aa000000-0000-0000-0000-0000000000d1');
INSERT INTO savings.teller_transaction (tenant_id, txn_id, idempotency_key, account_id, kind, amount, currency, branch_code,
    narrative, status, requested_by, journal_entry_id, session_id)
VALUES (:tA::uuid, 'ee000000-0000-0000-0000-000000000001', 't-1', 'aa000000-0000-0000-0000-0000000000d1', 'DEPOSIT', 100000, 'KES',
        'NBO01', 'Cash deposit', 'POSTED', 'user:teller', 'de000000-0000-0000-0000-000000000001',
        '5e000000-0000-0000-0000-000000000001');
COMMIT;

BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  INSERT INTO savings.teller_transaction (tenant_id, txn_id, idempotency_key, account_id, kind, amount, currency, branch_code,
      narrative, status, requested_by, journal_entry_id, session_id)
  VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 't-2', 'aa000000-0000-0000-0000-0000000000d1', 'WITHDRAWAL',
          60000, 'KES', 'NBO01', 'Cash withdrawal', 'POSTED', 'user:teller', 'de000000-0000-0000-0000-000000000001',
          '5e000000-0000-0000-0000-000000000001');
  RAISE EXCEPTION 'TEST FAILED D4a: large withdrawal posted without approval';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'TELLER_APPROVAL_REQUIRED%' THEN RAISE; END IF;
END $$;
INSERT INTO savings.teller_transaction (tenant_id, txn_id, idempotency_key, account_id, kind, amount, currency, branch_code,
    narrative, status, requested_by, session_id)
VALUES (:tA::uuid, 'ee000000-0000-0000-0000-000000000002', 't-2', 'aa000000-0000-0000-0000-0000000000d1', 'WITHDRAWAL', 60000, 'KES',
        'NBO01', 'Cash withdrawal', 'PENDING_APPROVAL', 'user:teller', '5e000000-0000-0000-0000-000000000001');
DO $$
BEGIN
  IF NOT (SELECT requires_approval FROM savings.teller_transaction WHERE txn_id = 'ee000000-0000-0000-0000-000000000002') THEN
    RAISE EXCEPTION 'TEST FAILED D4b: database did not mark approval as required';
  END IF;
  BEGIN
    UPDATE savings.teller_transaction SET status = 'REJECTED', decided_by = 'user:teller', decision_reason = 'no'
     WHERE txn_id = 'ee000000-0000-0000-0000-000000000002';
    RAISE EXCEPTION 'TEST FAILED D4c: requester decided own transaction';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    UPDATE savings.teller_transaction SET amount = 1 WHERE txn_id = 'ee000000-0000-0000-0000-000000000002';
    RAISE EXCEPTION 'TEST FAILED D5a';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'TELLER_TXN_IMMUTABLE%' THEN RAISE; END IF;
  END;
END $$;
INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
VALUES (:tA::uuid, 'de000000-0000-0000-0000-000000000002', 'teller:t-2', '2026-11-18', 'TELLER', 'c', 'user:manager');
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code, subledger_account_id) VALUES
  (:tA::uuid, 'de000000-0000-0000-0000-000000000002', 1, '2111-TEST-SAVINGS', 'D', 60000, 'KES', 'NBO01', 'aa000000-0000-0000-0000-0000000000d1'),
  (:tA::uuid, 'de000000-0000-0000-0000-000000000002', 2, '1001-TEST-CASH', 'C', 60000, 'KES', 'NBO01', NULL);
UPDATE savings.teller_transaction SET status = 'POSTED', decided_by = 'user:manager',
       journal_entry_id = 'de000000-0000-0000-0000-000000000002'
 WHERE txn_id = 'ee000000-0000-0000-0000-000000000002';
DO $$
BEGIN
  BEGIN
    UPDATE savings.teller_transaction SET status = 'REJECTED', decision_reason = 'changed mind'
     WHERE txn_id = 'ee000000-0000-0000-0000-000000000002';
    RAISE EXCEPTION 'TEST FAILED D5b';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'TELLER_TXN_FINAL%' THEN RAISE; END IF;
  END;
  BEGIN
    DELETE FROM savings.teller_transaction WHERE txn_id = 'ee000000-0000-0000-0000-000000000002';
    RAISE EXCEPTION 'TEST FAILED D5c';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
END $$;
COMMIT;
\echo PASS D4 D5 large withdrawal needs a second person (database decides), decisions final, no deletes

-- Withdrawing more than the balance is refused at commit by the existing available-balance rule.
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
VALUES (:tA::uuid, 'de000000-0000-0000-0000-000000000003', 'teller:t-3', '2026-11-18', 'TELLER', 'c', 'user:teller');
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code, subledger_account_id) VALUES
  (:tA::uuid, 'de000000-0000-0000-0000-000000000003', 1, '2111-TEST-SAVINGS', 'D', 40000.01, 'KES', 'NBO01', 'aa000000-0000-0000-0000-0000000000d1'),
  (:tA::uuid, 'de000000-0000-0000-0000-000000000003', 2, '1001-TEST-CASH', 'C', 40000.01, 'KES', 'NBO01', NULL);
DO $$
BEGIN
  SET CONSTRAINTS ALL IMMEDIATE;
  RAISE EXCEPTION 'TEST FAILED D6: overdrawn';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'SAVINGS_INSUFFICIENT_AVAILABLE%' THEN RAISE; END IF;
END $$;
ROLLBACK;
\echo PASS D6 withdrawal above the balance refused

BEGIN;
SET LOCAL app.tenant_id = :tB;
DO $$
BEGIN
  IF (SELECT count(*) FROM savings.teller_transaction) <> 0 THEN
    RAISE EXCEPTION 'TEST FAILED D7: tenant B sees tenant A teller transactions';
  END IF;
END $$;
COMMIT;
\echo PASS D7 tenant B cannot see tenant A teller transactions
