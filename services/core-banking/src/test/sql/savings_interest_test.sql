-- =============================================================================
-- Tests for V18__savings_interest.sql, run as mfi_app after till_test.sql. Tenant A's business
-- date is 2026-11-20 (eod_test.sql); account 2000000014 (product SAVT, 7.3%, 15% tax, minimum
-- 1,000) holds 40,000, so one day's interest is exactly 40,000 x 7.3 / 36,500 = 8.00.
-- =============================================================================
\set ON_ERROR_STOP on
\set QUIET on
\set tA '''00000000-0000-0000-0000-0000000000a1'''
\set tB '''00000000-0000-0000-0000-0000000000b2'''
\set acct '''aa000000-0000-0000-0000-0000000000d1'''

BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  BEGIN
    INSERT INTO savings.interest_accrual (tenant_id, account_id, business_date, product_code, branch_code, balance, rate, posted)
    VALUES ('00000000-0000-0000-0000-0000000000a1', 'aa000000-0000-0000-0000-0000000000d1', '2026-11-20', 'SAVT', 'NBO01',
            40000, 7.3, 0);
    RAISE EXCEPTION 'TEST FAILED I2a: accrual without a running EOD';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'EOD_WRONG_DATE%' THEN RAISE; END IF;
  END;
END $$;
INSERT INTO ledger.eod_run (tenant_id, business_date) VALUES (:tA::uuid, '2026-11-20');
COMMIT;

BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  BEGIN
    INSERT INTO savings.interest_accrual (tenant_id, account_id, business_date, product_code, branch_code, balance, rate, posted)
    VALUES ('00000000-0000-0000-0000-0000000000a1', 'aa000000-0000-0000-0000-0000000000d1', '2026-11-20', 'SAVT', 'NBO01',
            50000, 7.3, 0);
    RAISE EXCEPTION 'TEST FAILED I2b: balance not taken from the ledger';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'INTEREST_ACCRUAL_BALANCE%' THEN RAISE; END IF;
  END;
  BEGIN
    INSERT INTO savings.interest_accrual (tenant_id, account_id, business_date, product_code, branch_code, balance, rate, posted)
    VALUES ('00000000-0000-0000-0000-0000000000a1', 'aa000000-0000-0000-0000-0000000000d1', '2026-11-20', 'SAVT', 'NBO01',
            40000, 9, 0);
    RAISE EXCEPTION 'TEST FAILED I2c: rate other than the product rate';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'INTEREST_ACCRUAL_MISMATCH%' THEN RAISE; END IF;
  END;
  BEGIN
    INSERT INTO savings.interest_accrual (tenant_id, account_id, business_date, product_code, branch_code, balance, rate, posted)
    VALUES ('00000000-0000-0000-0000-0000000000a1', 'aa000000-0000-0000-0000-0000000000d1', '2026-11-20', 'SAVT', 'NBO01',
            40000, 7.3, 0);
    RAISE EXCEPTION 'TEST FAILED I3: posted amount differs from the rounded cumulative accrual';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'INTEREST_ACCRUAL_ROUNDING%' THEN RAISE; END IF;
  END;
END $$;
COMMIT;

-- I4: an entry that does not match its rows fails at commit.
DO $$
BEGIN
  BEGIN
    PERFORM set_config('app.tenant_id', '00000000-0000-0000-0000-0000000000a1', true);
    INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
    VALUES ('00000000-0000-0000-0000-0000000000a1', 'de000000-0000-0000-0000-000000000020', 'savings-accrual:SAVT:NBO01:2026-11-20',
            '2026-11-20', 'EOD', 'c', 'system:eod');
    INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
      ('00000000-0000-0000-0000-0000000000a1', 'de000000-0000-0000-0000-000000000020', 1, '5300-TEST-INT-EXP', 'D', 9, 'KES', 'NBO01'),
      ('00000000-0000-0000-0000-0000000000a1', 'de000000-0000-0000-0000-000000000020', 2, '2120-TEST-INT-PAY', 'C', 9, 'KES', 'NBO01');
    INSERT INTO savings.interest_accrual (tenant_id, account_id, business_date, product_code, branch_code, balance, rate, posted, entry_id)
    VALUES ('00000000-0000-0000-0000-0000000000a1', 'aa000000-0000-0000-0000-0000000000d1', '2026-11-20', 'SAVT', 'NBO01',
            40000, 7.3, 8, 'de000000-0000-0000-0000-000000000020');
    SET CONSTRAINTS ALL IMMEDIATE;
    RAISE EXCEPTION 'TEST FAILED I4: accrual entry amount differs from its rows';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'EOD_AMOUNT_MISMATCH%' THEN RAISE; END IF;
  END;
END $$;

BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
VALUES (:tA::uuid, 'de000000-0000-0000-0000-000000000021', 'savings-accrual:SAVT:NBO01:2026-11-20', '2026-11-20', 'EOD', 'c', 'system:eod');
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
  (:tA::uuid, 'de000000-0000-0000-0000-000000000021', 1, '5300-TEST-INT-EXP', 'D', 8, 'KES', 'NBO01'),
  (:tA::uuid, 'de000000-0000-0000-0000-000000000021', 2, '2120-TEST-INT-PAY', 'C', 8, 'KES', 'NBO01');
INSERT INTO savings.interest_accrual (tenant_id, account_id, business_date, product_code, branch_code, balance, rate, posted, entry_id)
VALUES (:tA::uuid, :acct::uuid, '2026-11-20', 'SAVT', 'NBO01', 40000, 7.3, 8, 'de000000-0000-0000-0000-000000000021');
COMMIT;
\echo PASS I2 I3 I4 accrual only during EOD, ledger balance and product rate, rounded cumulative, entry matches rows

-- I5: posting. Gross 8.00, tax 15% = 1.20, net 6.80.
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
VALUES (:tA::uuid, 'de000000-0000-0000-0000-000000000022', 'savings-interest:aa000000-0000-0000-0000-0000000000d1:2026-11-20',
        '2026-11-20', 'EOD', 'c', 'system:eod');
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code, subledger_account_id) VALUES
  (:tA::uuid, 'de000000-0000-0000-0000-000000000022', 1, '2120-TEST-INT-PAY', 'D', 8, 'KES', 'NBO01', NULL),
  (:tA::uuid, 'de000000-0000-0000-0000-000000000022', 2, '2111-TEST-SAVINGS', 'C', 6.80, 'KES', 'NBO01', :acct::uuid),
  (:tA::uuid, 'de000000-0000-0000-0000-000000000022', 3, '2960-TEST-WHT', 'C', 1.20, 'KES', 'NBO01', NULL);
DO $$
BEGIN
  BEGIN
    INSERT INTO savings.interest_posting (tenant_id, posting_id, account_id, period_start, period_end, gross, tax, net, tax_rate, entry_id)
    VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'aa000000-0000-0000-0000-0000000000d1', '2026-11-20',
            '2026-11-20', 8, 0, 8, 0, 'de000000-0000-0000-0000-000000000022');
    RAISE EXCEPTION 'TEST FAILED I5a: tax other than the product rate';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'INTEREST_POSTING_TAX%' THEN RAISE; END IF;
  END;
  BEGIN
    INSERT INTO savings.interest_posting (tenant_id, posting_id, account_id, period_start, period_end, gross, tax, net, tax_rate, entry_id)
    VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'aa000000-0000-0000-0000-0000000000d1', '2026-11-20',
            '2026-11-20', 10, 1.5, 8.5, 15, 'de000000-0000-0000-0000-000000000022');
    RAISE EXCEPTION 'TEST FAILED I5b: gross other than the accruals';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'INTEREST_POSTING_PERIOD%' THEN RAISE; END IF;
  END;
END $$;
INSERT INTO savings.interest_posting (tenant_id, posting_id, account_id, period_start, period_end, gross, tax, net, tax_rate, entry_id)
VALUES (:tA::uuid, '1e000000-0000-0000-0000-000000000001', :acct::uuid, '2026-11-20', '2026-11-20', 8, 1.20, 6.80, 15,
        'de000000-0000-0000-0000-000000000022');
COMMIT;
DO $$
BEGIN
  PERFORM set_config('app.tenant_id', '00000000-0000-0000-0000-0000000000a1', false);
  BEGIN
    UPDATE savings.interest_posting SET net = 0;
    RAISE EXCEPTION 'TEST FAILED I6a';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
  BEGIN
    UPDATE savings.interest_accrual SET posted = 0;
    RAISE EXCEPTION 'TEST FAILED I6b';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
END $$;
\echo PASS I5 posting covers exactly the accruals of its period, tax at the product rate, net to the account; records final

-- I6: the day cannot close with a till open.
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO savings.till_session (tenant_id, session_id, till_id, teller, business_date)
VALUES (:tA::uuid, '5e000000-0000-0000-0000-000000000002', '7e000000-0000-0000-0000-000000000001', 'user:teller', '2026-11-20');
COMMIT;
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  BEGIN
    UPDATE ledger.eod_run SET status = 'COMPLETED' WHERE business_date = '2026-11-20';
    RAISE EXCEPTION 'TEST FAILED I6c: EOD completed with an open till';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'EOD_TILLS_OPEN%' THEN RAISE; END IF;
  END;
END $$;
UPDATE savings.till_session SET status = 'CLOSED', counted_amount = 0, expected_amount = 0
 WHERE session_id = '5e000000-0000-0000-0000-000000000002';
UPDATE ledger.eod_run SET status = 'COMPLETED' WHERE business_date = '2026-11-20';
COMMIT;

BEGIN;
SET LOCAL app.tenant_id = :tB;
DO $$
BEGIN
  IF (SELECT count(*) FROM savings.interest_accrual) + (SELECT count(*) FROM savings.interest_posting) <> 0 THEN
    RAISE EXCEPTION 'TEST FAILED I7: tenant B sees tenant A interest';
  END IF;
END $$;
COMMIT;
\echo PASS I6 I7 end of day waits for every till to close; tenant B cannot see tenant A interest
