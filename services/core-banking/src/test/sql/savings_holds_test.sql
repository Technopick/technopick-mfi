-- =============================================================================
-- Tests for V2__deposit_accounts_and_holds.sql, run as the application role (mfi_app)
-- after ledger_invariants_test.sql in the same throwaway database.
-- =============================================================================
\set ON_ERROR_STOP on
\set QUIET on
\set tA '''00000000-0000-0000-0000-0000000000a1'''
\set tB '''00000000-0000-0000-0000-0000000000b2'''
\set acc1 '''aaaaaaaa-0000-0000-0000-000000000001'''

-- ---------- Setup ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO ledger.gl_account (tenant_id, account_code, name, account_class, currency, requires_subledger) VALUES
  (:tA::uuid, '2110-SAVINGS-CONTROL', 'Savings deposits - control', 'LIABILITY', 'KES', true),
  (:tA::uuid, '1000-VAULT-CASH',      'Vault cash',                 'ASSET',     'KES', false);
INSERT INTO savings.deposit_product (tenant_id, product_code, name, currency, gl_control_account)
VALUES (:tA::uuid, 'SAV01', 'Ordinary savings', 'KES', '2110-SAVINGS-CONTROL');
INSERT INTO savings.deposit_account (tenant_id, account_id, account_number, customer_ref, product_code, currency,
                                     branch_code, gl_control_account, opened_on) VALUES
  (:tA::uuid, 'aaaaaaaa-0000-0000-0000-000000000001', '0100000001', 'CUST-1', 'SAV01', 'KES', 'NBO01', '2110-SAVINGS-CONTROL', '2026-11-01'),
  (:tA::uuid, 'aaaaaaaa-0000-0000-0000-000000000002', '0100000002', 'CUST-2', 'SAV01', 'KES', 'NBO01', '2110-SAVINGS-CONTROL', '2026-11-01');
-- Cash deposit of 10,000 to account 1 and 1,000 to account 2.
INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
VALUES (:tA::uuid, '44444444-0000-0000-0000-000000000001', 'dep-1', '2026-11-18', 'TELLER', 'c', 'user:teller-1');
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code, subledger_account_id) VALUES
  (:tA::uuid, '44444444-0000-0000-0000-000000000001', 1, '1000-VAULT-CASH',      'D', 11000.00, 'KES', 'NBO01', NULL),
  (:tA::uuid, '44444444-0000-0000-0000-000000000001', 2, '2110-SAVINGS-CONTROL', 'C', 10000.00, 'KES', 'NBO01', 'aaaaaaaa-0000-0000-0000-000000000001'),
  (:tA::uuid, '44444444-0000-0000-0000-000000000001', 3, '2110-SAVINGS-CONTROL', 'C',  1000.00, 'KES', 'NBO01', 'aaaaaaaa-0000-0000-0000-000000000002');
COMMIT;

BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
DECLARE b record;
BEGIN
  SELECT * INTO b FROM savings.account_balance WHERE account_number = '0100000001';
  IF b.ledger_balance <> 10000 OR b.available_balance <> 10000 THEN
    RAISE EXCEPTION 'TEST FAILED H1: ledger % available %', b.ledger_balance, b.available_balance;
  END IF;
END $$;
COMMIT;
\echo PASS H1 deposit credits subledger; ledger and available = 10000

-- ---------- H2: control account without subledger is rejected ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
  VALUES ('00000000-0000-0000-0000-0000000000a1', '44444444-0000-0000-0000-000000000002', 'nosub', '2026-11-18', 'MANUAL', 'c', 'u');
  INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
    ('00000000-0000-0000-0000-0000000000a1', '44444444-0000-0000-0000-000000000002', 1, '2110-SAVINGS-CONTROL', 'C', 5.00, 'KES', 'NBO01');
  RAISE EXCEPTION 'TEST FAILED H2';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'LEDGER_SUBLEDGER_REQUIRED%' THEN RAISE; END IF;
END $$;
ROLLBACK;
\echo PASS H2 control-account posting without subledger rejected

-- ---------- H3: subledger line on the wrong GL is rejected ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
  VALUES ('00000000-0000-0000-0000-0000000000a1', '44444444-0000-0000-0000-000000000003', 'wronggl', '2026-11-18', 'MANUAL', 'c', 'u');
  INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code, subledger_account_id) VALUES
    ('00000000-0000-0000-0000-0000000000a1', '44444444-0000-0000-0000-000000000003', 1, '2100-CUSTOMER-DEPOSITS', 'C', 5.00, 'KES', 'NBO01',
     'aaaaaaaa-0000-0000-0000-000000000001');
  RAISE EXCEPTION 'TEST FAILED H3';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'LEDGER_SUBLEDGER_MISMATCH%' THEN RAISE; END IF;
END $$;
ROLLBACK;
\echo PASS H3 subledger line on wrong control account rejected

-- ---------- H4: authorization hold reduces available ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO savings.account_hold (tenant_id, hold_id, account_id, kind, amount, currency, reference, idempotency_key, expires_at)
VALUES (:tA::uuid, '55555555-0000-0000-0000-000000000001', :acc1::uuid, 'AUTHORIZATION', 5050.00, 'KES',
        'RRN632207000101', 'hold-sw-000101', now() + interval '7 days');
COMMIT;
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
DECLARE b record;
BEGIN
  SELECT * INTO b FROM savings.account_balance WHERE account_number = '0100000001';
  IF b.ledger_balance <> 10000 OR b.active_holds <> 5050 OR b.available_balance <> 4950 THEN
    RAISE EXCEPTION 'TEST FAILED H4: % % %', b.ledger_balance, b.active_holds, b.available_balance;
  END IF;
END $$;
COMMIT;
\echo PASS H4 hold of 5050 leaves ledger 10000, available 4950

-- ---------- H5: hold larger than available is rejected immediately ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  INSERT INTO savings.account_hold (tenant_id, hold_id, account_id, kind, amount, currency, idempotency_key, expires_at)
  VALUES ('00000000-0000-0000-0000-0000000000a1', '55555555-0000-0000-0000-000000000002',
          'aaaaaaaa-0000-0000-0000-000000000001', 'AUTHORIZATION', 4950.01, 'KES', 'hold-too-big', now() + interval '1 day');
  RAISE EXCEPTION 'TEST FAILED H5';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'SAVINGS_INSUFFICIENT_AVAILABLE%' THEN RAISE; END IF;
END $$;
ROLLBACK;
\echo PASS H5 hold exceeding available by 0.01 rejected

-- ---------- H6: duplicate hold idempotency key rejected ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  INSERT INTO savings.account_hold (tenant_id, hold_id, account_id, kind, amount, currency, idempotency_key, expires_at)
  VALUES ('00000000-0000-0000-0000-0000000000a1', '55555555-0000-0000-0000-000000000003',
          'aaaaaaaa-0000-0000-0000-000000000001', 'AUTHORIZATION', 1.00, 'KES', 'hold-sw-000101', now() + interval '1 day');
  RAISE EXCEPTION 'TEST FAILED H6';
EXCEPTION WHEN unique_violation THEN NULL;
END $$;
ROLLBACK;
\echo PASS H6 duplicate hold idempotency key rejected

-- ---------- H7: capture with an entry that does not debit the account is rejected ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  UPDATE savings.account_hold SET status = 'CAPTURED', captured_by_entry_id = '44444444-0000-0000-0000-000000000001'
   WHERE hold_id = '55555555-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'TEST FAILED H7';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'SAVINGS_CAPTURE_MISMATCH%' THEN RAISE; END IF;
END $$;
ROLLBACK;
\echo PASS H7 capture linked to an unrelated entry rejected

-- ---------- H8: completion = debit posting + hold CAPTURED in one transaction ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, switch_reference, actor)
VALUES (:tA::uuid, '44444444-0000-0000-0000-000000000010', 'sw-000101', '2026-11-18', 'SWITCH', 'c', 'RRN632207000101', 'channel:agent-AGT00001');
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code, subledger_account_id) VALUES
  (:tA::uuid, '44444444-0000-0000-0000-000000000010', 1, '2110-SAVINGS-CONTROL',     'D', 5050.00, 'KES', 'NBO01', :acc1::uuid),
  (:tA::uuid, '44444444-0000-0000-0000-000000000010', 2, '2300-AGENT-FLOAT-PAYABLE', 'C', 5000.00, 'KES', 'NBO01', NULL),
  (:tA::uuid, '44444444-0000-0000-0000-000000000010', 3, '4200-FEE-WITHDRAWAL',      'C',   50.00, 'KES', 'NBO01', NULL);
UPDATE savings.account_hold SET status = 'CAPTURED', captured_by_entry_id = '44444444-0000-0000-0000-000000000010'
 WHERE hold_id = '55555555-0000-0000-0000-000000000001';
COMMIT;
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
DECLARE b record;
BEGIN
  SELECT * INTO b FROM savings.account_balance WHERE account_number = '0100000001';
  IF b.ledger_balance <> 4950 OR b.active_holds <> 0 OR b.available_balance <> 4950 THEN
    RAISE EXCEPTION 'TEST FAILED H8: % % %', b.ledger_balance, b.active_holds, b.available_balance;
  END IF;
END $$;
COMMIT;
\echo PASS H8 capture: ledger 4950, holds 0, available 4950

-- ---------- H9: a captured hold can never change again ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  UPDATE savings.account_hold SET status = 'RELEASED', captured_by_entry_id = NULL
   WHERE hold_id = '55555555-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'TEST FAILED H9';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'SAVINGS_HOLD_FINAL%' THEN RAISE; END IF;
END $$;
ROLLBACK;
\echo PASS H9 captured hold is final

-- ---------- H10: debit that would overdraw is rejected at commit ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
  VALUES ('00000000-0000-0000-0000-0000000000a1', '44444444-0000-0000-0000-000000000011', 'overdraw', '2026-11-18', 'TELLER', 'c', 'u');
  INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code, subledger_account_id) VALUES
    ('00000000-0000-0000-0000-0000000000a1', '44444444-0000-0000-0000-000000000011', 1, '2110-SAVINGS-CONTROL', 'D', 4950.01, 'KES', 'NBO01', 'aaaaaaaa-0000-0000-0000-000000000001'),
    ('00000000-0000-0000-0000-0000000000a1', '44444444-0000-0000-0000-000000000011', 2, '1000-VAULT-CASH',      'C', 4950.01, 'KES', 'NBO01', NULL);
  SET CONSTRAINTS ALL IMMEDIATE;
  RAISE EXCEPTION 'TEST FAILED H10';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'SAVINGS_INSUFFICIENT_AVAILABLE%' THEN RAISE; END IF;
END $$;
ROLLBACK;
\echo PASS H10 overdrawing debit rejected

-- ---------- H11: release, and early expiry refused ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO savings.account_hold (tenant_id, hold_id, account_id, kind, amount, currency, idempotency_key, expires_at)
VALUES (:tA::uuid, '55555555-0000-0000-0000-000000000004', :acc1::uuid, 'AUTHORIZATION', 100.00, 'KES', 'hold-rel', now() + interval '1 day');
DO $$
BEGIN
  UPDATE savings.account_hold SET status = 'EXPIRED' WHERE hold_id = '55555555-0000-0000-0000-000000000004';
  RAISE EXCEPTION 'TEST FAILED H11 early expiry';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'SAVINGS_HOLD_NOT_EXPIRED%' THEN RAISE; END IF;
END $$;
UPDATE savings.account_hold SET status = 'RELEASED' WHERE hold_id = '55555555-0000-0000-0000-000000000004';
DO $$
DECLARE r record;
BEGIN
  SELECT * INTO r FROM savings.account_hold WHERE hold_id = '55555555-0000-0000-0000-000000000004';
  IF r.status <> 'RELEASED' OR r.closed_at IS NULL THEN RAISE EXCEPTION 'TEST FAILED H11 release'; END IF;
END $$;
COMMIT;
\echo PASS H11 early expiry refused; release stamps closed_at

-- ---------- H12: holds cannot be deleted; amount cannot be edited ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  DELETE FROM savings.account_hold WHERE hold_id = '55555555-0000-0000-0000-000000000004';
  RAISE EXCEPTION 'TEST FAILED H12 delete';
EXCEPTION WHEN insufficient_privilege THEN NULL;
END $$;
INSERT INTO savings.account_hold (tenant_id, hold_id, account_id, kind, amount, currency, idempotency_key, expires_at)
VALUES (:tA::uuid, '55555555-0000-0000-0000-000000000005', :acc1::uuid, 'AUTHORIZATION', 10.00, 'KES', 'hold-edit', now() + interval '1 day');
DO $$
BEGIN
  UPDATE savings.account_hold SET status = 'RELEASED', amount = 1 WHERE hold_id = '55555555-0000-0000-0000-000000000005';
  RAISE EXCEPTION 'TEST FAILED H12 edit';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'SAVINGS_HOLD_IMMUTABLE%' THEN RAISE; END IF;
END $$;
ROLLBACK;
\echo PASS H12 holds cannot be deleted or have their amount edited

-- ---------- H13: overdraft not allowed by product ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  UPDATE savings.deposit_account SET overdraft_limit = 1000 WHERE account_number = '0100000001';
  RAISE EXCEPTION 'TEST FAILED H13';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'SAVINGS_OVERDRAFT_NOT_ALLOWED%' THEN RAISE; END IF;
END $$;
ROLLBACK;
\echo PASS H13 overdraft on non-overdraft product rejected

-- ---------- H14: tenant B sees no accounts, holds or balances of tenant A ----------
BEGIN;
SET LOCAL app.tenant_id = :tB;
DO $$
DECLARE n int;
BEGIN
  SELECT count(*) INTO n FROM savings.deposit_account;  IF n <> 0 THEN RAISE EXCEPTION 'TEST FAILED H14 accounts %', n; END IF;
  SELECT count(*) INTO n FROM savings.account_hold;     IF n <> 0 THEN RAISE EXCEPTION 'TEST FAILED H14 holds %', n; END IF;
  SELECT count(*) INTO n FROM savings.account_balance;  IF n <> 0 THEN RAISE EXCEPTION 'TEST FAILED H14 balances %', n; END IF;
END $$;
ROLLBACK;
\echo PASS H14 cross-tenant savings data invisible

-- ---------- H15: subledger reconciles to GL control ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
DECLARE v_gl numeric; v_sub numeric;
BEGIN
  SELECT sum(CASE side WHEN 'C' THEN amount ELSE -amount END) INTO v_gl
    FROM ledger.journal_line WHERE account_code = '2110-SAVINGS-CONTROL';
  SELECT sum(ledger_balance) INTO v_sub FROM savings.account_balance WHERE currency = 'KES';
  IF v_gl IS DISTINCT FROM v_sub THEN RAISE EXCEPTION 'TEST FAILED H15: GL % vs subledger %', v_gl, v_sub; END IF;
END $$;
ROLLBACK;
\echo PASS H15 savings subledger total equals GL control balance

\echo ALL SAVINGS AND HOLD TESTS PASSED
