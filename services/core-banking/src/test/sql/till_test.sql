-- =============================================================================
-- Tests for V17__teller_tills.sql, run as mfi_app after savings_teller_test.sql (session S1 on
-- till T1 is OPEN for user:teller; account 2000000014 has 40,000).
-- =============================================================================
\set ON_ERROR_STOP on
\set QUIET on
\set tA '''00000000-0000-0000-0000-0000000000a1'''
\set tB '''00000000-0000-0000-0000-0000000000b2'''

BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  BEGIN
    INSERT INTO savings.till_session (tenant_id, session_id, till_id, teller, business_date)
    VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), '7e000000-0000-0000-0000-000000000001', 'user:teller2', '2026-11-18');
    RAISE EXCEPTION 'TEST FAILED W2a: two open sessions on one till';
  EXCEPTION WHEN unique_violation THEN NULL;
  END;
  BEGIN
    INSERT INTO savings.till_session (tenant_id, session_id, till_id, teller, business_date)
    VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), '7e000000-0000-0000-0000-000000000002', 'user:teller', '2026-11-18');
    RAISE EXCEPTION 'TEST FAILED W2b: one teller on two tills';
  EXCEPTION WHEN unique_violation THEN NULL;
  END;
  BEGIN
    INSERT INTO savings.teller_transaction (tenant_id, txn_id, idempotency_key, account_id, kind, amount, currency, branch_code,
        narrative, status, requested_by, session_id)
    VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'w-1', 'aa000000-0000-0000-0000-0000000000d1', 'DEPOSIT',
            10, 'KES', 'NBO01', 'x', 'PENDING_APPROVAL', 'user:teller2', '5e000000-0000-0000-0000-000000000001');
    RAISE EXCEPTION 'TEST FAILED W3a: transaction on another teller''s session';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'TELLER_NO_OPEN_TILL%' THEN RAISE; END IF;
  END;
  BEGIN
    INSERT INTO savings.teller_transaction (tenant_id, txn_id, idempotency_key, account_id, kind, amount, currency, branch_code,
        narrative, status, requested_by)
    VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'w-2', 'aa000000-0000-0000-0000-0000000000d1', 'DEPOSIT',
            10, 'KES', 'NBO01', 'x', 'PENDING_APPROVAL', 'user:teller');
    RAISE EXCEPTION 'TEST FAILED W3b: transaction without a session';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'TELLER_NO_OPEN_TILL%' THEN RAISE; END IF;
  END;
  BEGIN
    INSERT INTO savings.cash_transfer (tenant_id, transfer_id, idempotency_key, session_id, direction, amount, currency, requested_by)
    VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'x-0', '5e000000-0000-0000-0000-000000000001',
            'VAULT_TO_TILL', 1000, 'KES', 'user:teller2');
    RAISE EXCEPTION 'TEST FAILED W4a: transfer on another teller''s session';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'CASH_TRANSFER_SESSION%' THEN RAISE; END IF;
  END;
END $$;
INSERT INTO savings.cash_transfer (tenant_id, transfer_id, idempotency_key, session_id, direction, amount, currency, requested_by)
VALUES (:tA::uuid, 'c7000000-0000-0000-0000-000000000001', 'x-1', '5e000000-0000-0000-0000-000000000001', 'VAULT_TO_TILL',
        50000, 'KES', 'user:teller');
DO $$
BEGIN
  BEGIN
    UPDATE savings.cash_transfer SET status = 'REJECTED', decided_by = 'user:teller', decision_reason = 'mine'
     WHERE transfer_id = 'c7000000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED W4b: requester decided own transfer';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    UPDATE savings.cash_transfer SET status = 'POSTED', decided_by = 'user:manager'
     WHERE transfer_id = 'c7000000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED W4c: posted without an entry';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    UPDATE savings.till_session SET status = 'CLOSING', counted_amount = 1, expected_amount = 0
     WHERE session_id = '5e000000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED W6: closed with a pending transfer';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'TILL_SESSION_PENDING%' THEN RAISE; END IF;
  END;
END $$;
INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
VALUES (:tA::uuid, 'de000000-0000-0000-0000-000000000010', 'transfer:x-1', '2026-11-18', 'TELLER', 'c', 'user:manager');
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
  (:tA::uuid, 'de000000-0000-0000-0000-000000000010', 1, '1002-TEST-TILL-1', 'D', 50000, 'KES', 'NBO01'),
  (:tA::uuid, 'de000000-0000-0000-0000-000000000010', 2, '1001-TEST-CASH', 'C', 50000, 'KES', 'NBO01');
UPDATE savings.cash_transfer SET status = 'POSTED', decided_by = 'user:manager', journal_entry_id = 'de000000-0000-0000-0000-000000000010'
 WHERE transfer_id = 'c7000000-0000-0000-0000-000000000001';
COMMIT;
\echo PASS W2 W3 W4 W6 one session per till and teller, own open session only, transfers need a second person, no close while pending

BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  BEGIN
    UPDATE savings.till_session SET status = 'CLOSED', counted_amount = 49900, expected_amount = 50000
     WHERE session_id = '5e000000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED W5a: closed with an unapproved variance';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
END $$;
UPDATE savings.till_session SET status = 'CLOSING', counted_amount = 49900, expected_amount = 50000
 WHERE session_id = '5e000000-0000-0000-0000-000000000001';
DO $$
BEGIN
  BEGIN
    UPDATE savings.till_session SET status = 'CLOSED', variance_approved_by = 'user:teller', variance_reason = 'own'
     WHERE session_id = '5e000000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED W5b: teller approved own variance';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
END $$;
INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
VALUES (:tA::uuid, 'de000000-0000-0000-0000-000000000011', 'till-variance:s1', '2026-11-18', 'TELLER', 'c', 'user:manager');
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
  (:tA::uuid, 'de000000-0000-0000-0000-000000000011', 1, '5200-TEST-SHORTAGE', 'D', 100, 'KES', 'NBO01'),
  (:tA::uuid, 'de000000-0000-0000-0000-000000000011', 2, '1002-TEST-TILL-1', 'C', 100, 'KES', 'NBO01');
UPDATE savings.till_session SET status = 'CLOSED', variance_approved_by = 'user:manager', variance_reason = 'Short count, teller notified',
       variance_entry_id = 'de000000-0000-0000-0000-000000000011'
 WHERE session_id = '5e000000-0000-0000-0000-000000000001';
DO $$
BEGIN
  IF (SELECT variance FROM savings.till_session WHERE session_id = '5e000000-0000-0000-0000-000000000001') <> -100 THEN
    RAISE EXCEPTION 'TEST FAILED W5c: variance not counted minus expected';
  END IF;
  BEGIN
    UPDATE savings.till_session SET variance_reason = 'changed' WHERE session_id = '5e000000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED W5d';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'TILL_SESSION_FINAL%' THEN RAISE; END IF;
  END;
  BEGIN
    DELETE FROM savings.till_session WHERE session_id = '5e000000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED W5e';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
END $$;
COMMIT;
\echo PASS W5 variance closes only with a different approver, reason and entry; closed sessions are final

BEGIN;
SET LOCAL app.tenant_id = :tB;
DO $$
BEGIN
  IF (SELECT count(*) FROM savings.till_session) + (SELECT count(*) FROM savings.till) <> 0 THEN
    RAISE EXCEPTION 'TEST FAILED W7: tenant B sees tenant A tills';
  END IF;
END $$;
COMMIT;
\echo PASS W7 tenant B cannot see tenant A tills
