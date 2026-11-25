-- =============================================================================
-- Tests for V37__share_capital.sql, run as mfi_app at the end of the suite. Uses customer
-- CU26-0000001 and its account 2000000014 (savings_teller_test.sql) and a second member created here.
-- =============================================================================
\set ON_ERROR_STOP on
\set QUIET on
\set tA '''00000000-0000-0000-0000-0000000000a1'''
\set tB '''00000000-0000-0000-0000-0000000000b2'''

-- ---------- setup: accounts, a second active member, money on account 2000000014 ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO ledger.gl_account (tenant_id, account_code, name, account_class, currency) VALUES
  (:tA::uuid, '3110-TEST-SHARE-CAPITAL', 'TEST share capital', 'EQUITY', 'KES'),
  (:tA::uuid, '3310-TEST-RETAINED', 'TEST retained earnings', 'EQUITY', 'KES'),
  (:tA::uuid, '2410-TEST-DIV-PAYABLE', 'TEST dividends payable', 'LIABILITY', 'KES'),
  (:tA::uuid, '2420-TEST-DIV-TAX', 'TEST dividend withholding tax', 'LIABILITY', 'KES'),
  (:tA::uuid, '1199-TEST-FUNDING', 'TEST funding', 'ASSET', 'KES');
INSERT INTO customer.customer (tenant_id, customer_id, customer_number, idempotency_key, first_name, last_name, date_of_birth,
    id_type, id_number, msisdn, branch_code, consent_version, consent_at, created_by)
VALUES (:tA::uuid, 'c0000000-0000-0000-0000-0000000000a5', 'CU26-0000085', 'cust-sh5', 'Share', 'Buyer', '1984-03-03',
        'NATIONAL_ID', '99887766', '254711000085', 'NBO01', 'DPA-2026-01', now(), 'user:officer');
UPDATE customer.customer SET status = 'ACTIVE', verified_by = 'user:manager', verification_note = 'ID sighted at branch'
 WHERE customer_id = 'c0000000-0000-0000-0000-0000000000a5';
INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
SELECT :tA::uuid, '5a000000-0000-0000-0000-000000000001', 'shares-test-funding', current_date_, 'TELLER', 'c', 'user:teller'
  FROM tenancy.business_date;
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code, subledger_account_id) VALUES
  (:tA::uuid, '5a000000-0000-0000-0000-000000000001', 1, '1199-TEST-FUNDING', 'D', 50000, 'KES', 'NBO01', NULL),
  (:tA::uuid, '5a000000-0000-0000-0000-000000000001', 2, '2111-TEST-SAVINGS', 'C', 50000, 'KES', 'NBO01', 'aa000000-0000-0000-0000-0000000000d1');
COMMIT;
\echo PASS SH0 TEST-ONLY accounts, second member and funding

-- ---------- SH1 settings ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  BEGIN
    INSERT INTO shares.share_settings (tenant_id, par_value, minimum_holding, gl_share_capital, gl_dividend_source,
        gl_dividends_payable, gl_dividend_tax, updated_by)
    VALUES ('00000000-0000-0000-0000-0000000000a1', 100, 1000, '2410-TEST-DIV-PAYABLE', '3310-TEST-RETAINED',
            '3110-TEST-SHARE-CAPITAL', '2420-TEST-DIV-TAX', 'user:fin');
    RAISE EXCEPTION 'TEST FAILED SH1a';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'SHARE_SETTINGS_INVALID%' THEN RAISE; END IF;
  END;
  BEGIN
    INSERT INTO shares.share_settings (tenant_id, par_value, minimum_holding, gl_share_capital, gl_dividend_source,
        gl_dividends_payable, gl_dividend_tax, updated_by)
    VALUES ('00000000-0000-0000-0000-0000000000a1', 100, 1050, '3110-TEST-SHARE-CAPITAL', '3310-TEST-RETAINED',
            '2410-TEST-DIV-PAYABLE', '2420-TEST-DIV-TAX', 'user:fin');
    RAISE EXCEPTION 'TEST FAILED SH1b';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
END $$;
INSERT INTO shares.share_settings (tenant_id, par_value, minimum_holding, gl_share_capital, gl_dividend_source,
    gl_dividends_payable, gl_dividend_tax, updated_by)
VALUES (:tA::uuid, 100, 1000, '3110-TEST-SHARE-CAPITAL', '3310-TEST-RETAINED', '2410-TEST-DIV-PAYABLE', '2420-TEST-DIV-TAX', 'user:fin');
DO $$
BEGIN
  IF NOT ledger.is_control_account('00000000-0000-0000-0000-0000000000a1', '3110-TEST-SHARE-CAPITAL') THEN
    RAISE EXCEPTION 'TEST FAILED SH4a: share capital is not a control account';
  END IF;
END $$;
COMMIT;
\echo PASS SH1 settings need the right account classes and a whole-share minimum

-- ---------- SH2 SH3 SH4 purchases ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
SELECT :tA::uuid, '5a000000-0000-0000-0000-000000000002', 'shares-test-buy-1', current_date_, 'SHARES', 'c', 'user:teller'
  FROM tenancy.business_date;
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code, subledger_account_id) VALUES
  (:tA::uuid, '5a000000-0000-0000-0000-000000000002', 1, '2111-TEST-SAVINGS', 'D', 5000, 'KES', 'NBO01', 'aa000000-0000-0000-0000-0000000000d1'),
  (:tA::uuid, '5a000000-0000-0000-0000-000000000002', 2, '3110-TEST-SHARE-CAPITAL', 'C', 5000, 'KES', 'NBO01', NULL);
DO $$
DECLARE v_today date;
BEGIN
  SELECT current_date_ INTO v_today FROM tenancy.business_date;
  BEGIN
    INSERT INTO shares.share_transaction (tenant_id, txn_id, idempotency_key, customer_id, kind, amount, business_date, entry_id,
        source_account_id, created_by)
    VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'x1', 'c0000000-0000-0000-0000-000000000001', 'PURCHASE',
            5050, v_today, '5a000000-0000-0000-0000-000000000002', 'aa000000-0000-0000-0000-0000000000d1', 'user:teller');
    RAISE EXCEPTION 'TEST FAILED SH2a';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'SHARE_NOT_WHOLE%' THEN RAISE; END IF;
  END;
  BEGIN
    INSERT INTO shares.share_transaction (tenant_id, txn_id, idempotency_key, customer_id, kind, amount, business_date, entry_id,
        source_account_id, created_by)
    VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'x2', 'c0000000-0000-0000-0000-000000000001', 'PURCHASE',
            5000, v_today - 1, '5a000000-0000-0000-0000-000000000002', 'aa000000-0000-0000-0000-0000000000d1', 'user:teller');
    RAISE EXCEPTION 'TEST FAILED SH2b';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'SHARE_DATE%' THEN RAISE; END IF;
  END;
  BEGIN
    INSERT INTO shares.share_transaction (tenant_id, txn_id, idempotency_key, customer_id, kind, amount, business_date, entry_id,
        source_account_id, created_by)
    VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'x3', 'c0000000-0000-0000-0000-0000000000a5', 'PURCHASE',
            5000, v_today, '5a000000-0000-0000-0000-000000000002', 'aa000000-0000-0000-0000-0000000000d1', 'user:teller');
    RAISE EXCEPTION 'TEST FAILED SH3a';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'SHARE_ACCOUNT_NOT_OWN%' THEN RAISE; END IF;
  END;
  BEGIN
    INSERT INTO shares.share_transaction (tenant_id, txn_id, idempotency_key, customer_id, kind, amount, business_date, entry_id,
        source_account_id, created_by)
    VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'x4', 'c0000000-0000-0000-0000-000000000001', 'PURCHASE',
            4000, v_today, '5a000000-0000-0000-0000-000000000002', 'aa000000-0000-0000-0000-0000000000d1', 'user:teller');
    SET CONSTRAINTS shares.share_transaction_posting IMMEDIATE;
    RAISE EXCEPTION 'TEST FAILED SH3b';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'SHARE_POSTING_MISMATCH%' THEN RAISE; END IF;
  END;
  SET CONSTRAINTS ALL DEFERRED;
END $$;
INSERT INTO shares.share_transaction (tenant_id, txn_id, idempotency_key, customer_id, kind, amount, business_date, entry_id,
    source_account_id, created_by)
SELECT :tA::uuid, '5b000000-0000-0000-0000-000000000001', 'shares-test-buy-1', 'c0000000-0000-0000-0000-000000000001', 'PURCHASE',
       5000, current_date_, '5a000000-0000-0000-0000-000000000002', 'aa000000-0000-0000-0000-0000000000d1', 'user:teller'
  FROM tenancy.business_date;
COMMIT;
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  BEGIN
    INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
    SELECT '00000000-0000-0000-0000-0000000000a1', '5a000000-0000-0000-0000-000000000003', 'shares-test-direct', current_date_,
           'MANUAL', 'c', 'user:fin' FROM tenancy.business_date;
    INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
      ('00000000-0000-0000-0000-0000000000a1', '5a000000-0000-0000-0000-000000000003', 1, '1199-TEST-FUNDING', 'D', 700, 'KES', 'NBO01'),
      ('00000000-0000-0000-0000-0000000000a1', '5a000000-0000-0000-0000-000000000003', 2, '3110-TEST-SHARE-CAPITAL', 'C', 700, 'KES', 'NBO01');
    SET CONSTRAINTS ledger.journal_line_share_capital IMMEDIATE;
    RAISE EXCEPTION 'TEST FAILED SH4b';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'SHARE_DIRECT_POSTING%' THEN RAISE; END IF;
  END;
  SET CONSTRAINTS ALL DEFERRED;
  BEGIN
    UPDATE shares.share_settings SET par_value = 50;
    RAISE EXCEPTION 'TEST FAILED SH1c';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'SHARE_SETTINGS_LOCKED%' THEN RAISE; END IF;
  END;
  BEGIN
    UPDATE shares.share_transaction SET amount = 6000;
    RAISE EXCEPTION 'TEST FAILED SH2c';
  EXCEPTION WHEN raise_exception OR insufficient_privilege THEN
    IF SQLSTATE = 'P0001' AND SQLERRM NOT LIKE 'SHARE_IMMUTABLE%' THEN RAISE; END IF;
  END;
END $$;
UPDATE shares.share_settings SET minimum_holding = 1000, dividend_tax_rate = 5;
COMMIT;
\echo PASS SH2 SH3 SH4 whole shares on the business date from the members own account; only purchases post to share capital

-- ---------- SH5 transfers ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  BEGIN
    INSERT INTO shares.share_transfer (tenant_id, transfer_id, idempotency_key, from_customer_id, to_customer_id, amount, reason,
        requested_by)
    VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 't-self', 'c0000000-0000-0000-0000-000000000001',
            'c0000000-0000-0000-0000-000000000001', 1000, 'Moving shares to myself', 'user:officer');
    RAISE EXCEPTION 'TEST FAILED SH5a';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    INSERT INTO shares.share_transfer (tenant_id, transfer_id, idempotency_key, from_customer_id, to_customer_id, amount, reason,
        requested_by)
    VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 't-pending', 'c0000000-0000-0000-0000-000000000001',
            'c0000000-0000-0000-0000-000000000002', 1000, 'To a member still pending', 'user:officer');
    RAISE EXCEPTION 'TEST FAILED SH5b';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'SHARE_MEMBER_INACTIVE%' THEN RAISE; END IF;
  END;
END $$;
INSERT INTO shares.share_transfer (tenant_id, transfer_id, idempotency_key, from_customer_id, to_customer_id, amount, reason, requested_by)
VALUES (:tA::uuid, '5c000000-0000-0000-0000-000000000001', 't-1', 'c0000000-0000-0000-0000-000000000001',
        'c0000000-0000-0000-0000-0000000000a5', 2000, 'Sale of shares to a family member', 'user:officer');
DO $$
BEGIN
  BEGIN
    UPDATE shares.share_transfer SET status = 'APPROVED', decided_by = 'user:officer', decided_at = now(),
           business_date = (SELECT current_date_ FROM tenancy.business_date)
     WHERE transfer_id = '5c000000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED SH5c';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    UPDATE shares.share_transfer SET status = 'APPROVED', decided_by = 'user:manager', decided_at = now(),
           business_date = (SELECT current_date_ FROM tenancy.business_date)
     WHERE transfer_id = '5c000000-0000-0000-0000-000000000001';
    INSERT INTO shares.share_transaction (tenant_id, txn_id, idempotency_key, customer_id, kind, amount, business_date, transfer_id,
        created_by)
    SELECT '00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 't-1-out', 'c0000000-0000-0000-0000-000000000001',
           'TRANSFER_OUT', 2000, current_date_, '5c000000-0000-0000-0000-000000000001', 'user:manager' FROM tenancy.business_date;
    SET CONSTRAINTS shares.share_transfer_pair IMMEDIATE;
    RAISE EXCEPTION 'TEST FAILED SH5d';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'SHARE_TRANSFER_MISMATCH%' THEN RAISE; END IF;
  END;
  SET CONSTRAINTS ALL DEFERRED;
END $$;
UPDATE shares.share_transfer SET status = 'APPROVED', decided_by = 'user:manager', decided_at = now(),
       business_date = (SELECT current_date_ FROM tenancy.business_date)
 WHERE transfer_id = '5c000000-0000-0000-0000-000000000001';
INSERT INTO shares.share_transaction (tenant_id, txn_id, idempotency_key, customer_id, kind, amount, business_date, transfer_id, created_by)
SELECT :tA::uuid, gen_random_uuid(), 't-1-' || k, c::uuid, k, 2000, current_date_, '5c000000-0000-0000-0000-000000000001', 'user:manager'
  FROM tenancy.business_date,
       (VALUES ('TRANSFER_OUT', 'c0000000-0000-0000-0000-000000000001'), ('TRANSFER_IN', 'c0000000-0000-0000-0000-0000000000a5')) v(k, c);
COMMIT;
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO shares.share_transfer (tenant_id, transfer_id, idempotency_key, from_customer_id, to_customer_id, amount, reason, requested_by)
VALUES (:tA::uuid, '5c000000-0000-0000-0000-000000000002', 't-2', 'c0000000-0000-0000-0000-000000000001',
        'c0000000-0000-0000-0000-0000000000a5', 2500, 'Would leave the seller below minimum', 'user:officer');
DO $$
BEGIN
  UPDATE shares.share_transfer SET status = 'APPROVED', decided_by = 'user:manager', decided_at = now(),
         business_date = (SELECT current_date_ FROM tenancy.business_date)
   WHERE transfer_id = '5c000000-0000-0000-0000-000000000002';
  INSERT INTO shares.share_transaction (tenant_id, txn_id, idempotency_key, customer_id, kind, amount, business_date, transfer_id, created_by)
  SELECT '00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 't-2-' || k, c::uuid, k, 2500, current_date_,
         '5c000000-0000-0000-0000-000000000002', 'user:manager'
    FROM tenancy.business_date,
         (VALUES ('TRANSFER_OUT', 'c0000000-0000-0000-0000-000000000001'), ('TRANSFER_IN', 'c0000000-0000-0000-0000-0000000000a5')) v(k, c);
  SET CONSTRAINTS shares.share_transaction_posting IMMEDIATE;
  RAISE EXCEPTION 'TEST FAILED SH5e';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'SHARE_BELOW_MINIMUM%' THEN RAISE; END IF;
END $$;
SET CONSTRAINTS ALL DEFERRED;
UPDATE shares.share_transfer SET status = 'REJECTED', decided_by = 'user:manager', decided_at = now(),
       decision_reason = 'Seller would fall below the minimum'
 WHERE transfer_id = '5c000000-0000-0000-0000-000000000002';
DO $$
BEGIN
  IF shares.holding('00000000-0000-0000-0000-0000000000a1', 'c0000000-0000-0000-0000-000000000001', NULL) <> 3000
     OR shares.holding('00000000-0000-0000-0000-0000000000a1', 'c0000000-0000-0000-0000-0000000000a5', NULL) <> 2000 THEN
    RAISE EXCEPTION 'TEST FAILED SH5f: holdings';
  END IF;
END $$;
COMMIT;
\echo PASS SH5 transfers between two active members, approved by another person, both sides written, seller keeps the minimum

-- ---------- SH6 dividends ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  INSERT INTO shares.dividend_run (tenant_id, run_id, idempotency_key, financial_year, record_date, rate_pct, tax_rate,
      gl_dividend_source, gl_dividends_payable, gl_dividend_tax, members, shares_total, gross_total, tax_total, net_total,
      to_savings_total, to_payable_total, requested_by)
  SELECT '00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'd-open', extract(year FROM current_date_)::int, current_date_,
         10, 5, '3310-TEST-RETAINED', '2410-TEST-DIV-PAYABLE', '2420-TEST-DIV-TAX', 0, 0, 0, 0, 0, 0, 0, 'user:fin'
    FROM tenancy.business_date;
  RAISE EXCEPTION 'TEST FAILED SH6a';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'DIVIDEND_DAY_NOT_CLOSED%' THEN RAISE; END IF;
END $$;
-- close the day
INSERT INTO ledger.eod_run (tenant_id, business_date, status) SELECT :tA::uuid, current_date_, 'RUNNING' FROM tenancy.business_date;
UPDATE ledger.eod_run SET status = 'COMPLETED', finished_at = clock_timestamp()
 WHERE business_date = (SELECT current_date_ FROM tenancy.business_date);
UPDATE tenancy.business_date SET current_date_ = current_date_ + 1;
COMMIT;

BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO shares.dividend_run (tenant_id, run_id, idempotency_key, financial_year, record_date, rate_pct, tax_rate,
    gl_dividend_source, gl_dividends_payable, gl_dividend_tax, members, shares_total, gross_total, tax_total, net_total,
    to_savings_total, to_payable_total, requested_by)
SELECT :tA::uuid, '5d000000-0000-0000-0000-000000000001', 'd-1', extract(year FROM current_date_ - 1)::int, current_date_ - 1,
       10, 5, '3310-TEST-RETAINED', '2410-TEST-DIV-PAYABLE', '2420-TEST-DIV-TAX', 2, 5000, 500, 25, 475, 285, 190, 'user:fin'
  FROM tenancy.business_date;
DO $$
BEGIN
  BEGIN
    INSERT INTO shares.dividend_line (tenant_id, run_id, customer_id, shares_amount, gross, tax, net, account_id, branch_code)
    VALUES ('00000000-0000-0000-0000-0000000000a1', '5d000000-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-000000000001',
            3000, 310, 15.5, 294.5, 'aa000000-0000-0000-0000-0000000000d1', 'NBO01');
    RAISE EXCEPTION 'TEST FAILED SH6b';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'DIVIDEND_LINE_INVALID%' THEN RAISE; END IF;
  END;
  BEGIN
    INSERT INTO shares.dividend_line (tenant_id, run_id, customer_id, shares_amount, gross, tax, net, account_id, branch_code)
    VALUES ('00000000-0000-0000-0000-0000000000a1', '5d000000-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-000000000001',
            3000, 300, 15, 285, 'aa000000-0000-0000-0000-0000000000d1', 'NBO01');
    SET CONSTRAINTS shares.dividend_run_totals IMMEDIATE;
    RAISE EXCEPTION 'TEST FAILED SH6c';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'DIVIDEND_TOTAL_MISMATCH%' THEN RAISE; END IF;
  END;
  SET CONSTRAINTS ALL DEFERRED;
END $$;
INSERT INTO shares.dividend_line (tenant_id, run_id, customer_id, shares_amount, gross, tax, net, account_id, branch_code) VALUES
  (:tA::uuid, '5d000000-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-000000000001', 3000, 300, 15, 285,
   'aa000000-0000-0000-0000-0000000000d1', 'NBO01'),
  (:tA::uuid, '5d000000-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-0000000000a5', 2000, 200, 10, 190, NULL, 'NBO01');
COMMIT;

BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  INSERT INTO shares.dividend_run (tenant_id, run_id, idempotency_key, financial_year, record_date, rate_pct, tax_rate,
      gl_dividend_source, gl_dividends_payable, gl_dividend_tax, members, shares_total, gross_total, tax_total, net_total,
      to_savings_total, to_payable_total, requested_by)
  SELECT '00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'd-2', extract(year FROM current_date_ - 1)::int, current_date_ - 1,
         12, 5, '3310-TEST-RETAINED', '2410-TEST-DIV-PAYABLE', '2420-TEST-DIV-TAX', 0, 0, 0, 0, 0, 0, 0, 'user:fin'
    FROM tenancy.business_date;
  RAISE EXCEPTION 'TEST FAILED SH6d';
EXCEPTION WHEN unique_violation THEN NULL;
END $$;
INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
SELECT :tA::uuid, '5a000000-0000-0000-0000-000000000009', 'shares-test-dividend-wrong', current_date_, 'SHARES', 'c', 'user:manager'
  FROM tenancy.business_date;
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code, subledger_account_id) VALUES
  (:tA::uuid, '5a000000-0000-0000-0000-000000000009', 1, '3310-TEST-RETAINED', 'D', 500, 'KES', 'NBO01', NULL),
  (:tA::uuid, '5a000000-0000-0000-0000-000000000009', 2, '2420-TEST-DIV-TAX', 'C', 25, 'KES', 'NBO01', NULL),
  (:tA::uuid, '5a000000-0000-0000-0000-000000000009', 3, '2111-TEST-SAVINGS', 'C', 280, 'KES', 'NBO01', 'aa000000-0000-0000-0000-0000000000d1'),
  (:tA::uuid, '5a000000-0000-0000-0000-000000000009', 4, '2410-TEST-DIV-PAYABLE', 'C', 195, 'KES', 'NBO01', NULL);
DO $$
BEGIN
  BEGIN
    UPDATE shares.dividend_run SET status = 'POSTED', decided_by = 'user:fin', decided_at = now(),
           business_date = (SELECT current_date_ FROM tenancy.business_date), entry_id = '5a000000-0000-0000-0000-000000000009'
     WHERE run_id = '5d000000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED SH6e';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    UPDATE shares.dividend_run SET status = 'POSTED', decided_by = 'user:manager', decided_at = now(),
           business_date = (SELECT current_date_ FROM tenancy.business_date), entry_id = '5a000000-0000-0000-0000-000000000009'
     WHERE run_id = '5d000000-0000-0000-0000-000000000001';
    SET CONSTRAINTS shares.dividend_run_totals IMMEDIATE;
    RAISE EXCEPTION 'TEST FAILED SH6f';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'DIVIDEND_POSTING_MISMATCH%' THEN RAISE; END IF;
  END;
  SET CONSTRAINTS ALL DEFERRED;
END $$;
ROLLBACK;

BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
SELECT :tA::uuid, '5a000000-0000-0000-0000-000000000010', 'shares-test-dividend', current_date_, 'SHARES', 'c', 'user:manager'
  FROM tenancy.business_date;
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code, subledger_account_id) VALUES
  (:tA::uuid, '5a000000-0000-0000-0000-000000000010', 1, '3310-TEST-RETAINED', 'D', 500, 'KES', 'NBO01', NULL),
  (:tA::uuid, '5a000000-0000-0000-0000-000000000010', 2, '2420-TEST-DIV-TAX', 'C', 25, 'KES', 'NBO01', NULL),
  (:tA::uuid, '5a000000-0000-0000-0000-000000000010', 3, '2111-TEST-SAVINGS', 'C', 285, 'KES', 'NBO01', 'aa000000-0000-0000-0000-0000000000d1'),
  (:tA::uuid, '5a000000-0000-0000-0000-000000000010', 4, '2410-TEST-DIV-PAYABLE', 'C', 190, 'KES', 'NBO01', NULL);
UPDATE shares.dividend_run SET status = 'POSTED', decided_by = 'user:manager', decided_at = now(),
       business_date = (SELECT current_date_ FROM tenancy.business_date), entry_id = '5a000000-0000-0000-0000-000000000010'
 WHERE run_id = '5d000000-0000-0000-0000-000000000001';
COMMIT;
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  UPDATE shares.dividend_run SET decision_reason = 'changed' WHERE run_id = '5d000000-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'TEST FAILED SH6g';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'DIVIDEND_IMMUTABLE%' THEN RAISE; END IF;
END $$;
COMMIT;
\echo PASS SH6 dividends on a closed record date, lines from the register, totals and posting exact, one run a year, another person posts

BEGIN;
SET LOCAL app.tenant_id = :tB;
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM shares.share_transaction) OR EXISTS (SELECT 1 FROM shares.dividend_run)
     OR EXISTS (SELECT 1 FROM shares.share_settings) THEN
    RAISE EXCEPTION 'TEST FAILED SH-RLS';
  END IF;
END $$;
COMMIT;
\echo PASS SH-RLS the share register is invisible to other tenants
