-- =============================================================================
-- Tests for V38__fixed_deposits.sql, run as mfi_app at the end of the suite. TEST-ONLY data.
-- Uses account 2000000014 (aa..d1, member CU26-0000001, funded by shares_test.sql) and its
-- savings control 2111-TEST-SAVINGS. Deposits: A 10,000 paid out at maturity, B 20,000 rolled
-- over with its interest, C 5,000 withdrawn early, D 1,000 on a product without early withdrawal.
-- =============================================================================
\set ON_ERROR_STOP on
\set QUIET on
\set tA '''00000000-0000-0000-0000-0000000000a1'''
\set tB '''00000000-0000-0000-0000-0000000000b2'''

-- ---------- helpers (this session only) ----------
CREATE FUNCTION pg_temp.entry(p_id uuid, p_key text) RETURNS void LANGUAGE sql AS $$
    INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
    SELECT tenancy.current_tenant(), p_id, p_key, current_date_, 'TERMDEP', 'fd-test', 'user:test' FROM tenancy.business_date;
$$;
CREATE FUNCTION pg_temp.line(p_id uuid, p_no int, p_account text, p_side text, p_amount numeric, p_sub uuid) RETURNS void
    LANGUAGE sql AS $$
    INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code, subledger_account_id)
    VALUES (tenancy.current_tenant(), p_id, p_no, p_account, p_side, p_amount, 'KES', 'NBO01', p_sub);
$$;
CREATE FUNCTION pg_temp.start_eod() RETURNS void LANGUAGE sql AS $$
    INSERT INTO ledger.eod_run (tenant_id, business_date, status)
    SELECT tenancy.current_tenant(), current_date_, 'RUNNING' FROM tenancy.business_date;
$$;
-- completes the run, moves the date and opens the next month's period when the date enters it
CREATE FUNCTION pg_temp.finish_eod() RETURNS void LANGUAGE plpgsql AS $$
DECLARE v_new date;
BEGIN
    UPDATE ledger.eod_run SET status = 'COMPLETED', finished_at = clock_timestamp()
     WHERE business_date = (SELECT current_date_ FROM tenancy.business_date);
    UPDATE tenancy.business_date SET current_date_ = current_date_ + 1 RETURNING current_date_ INTO v_new;
    IF NOT EXISTS (SELECT 1 FROM ledger.accounting_period WHERE v_new BETWEEN start_date AND end_date) THEN
        INSERT INTO ledger.accounting_period (tenant_id, period_id, start_date, end_date)
        VALUES (tenancy.current_tenant(), to_char(v_new, 'YYYY-MM'), date_trunc('month', v_new)::date,
                (date_trunc('month', v_new) + interval '1 month - 1 day')::date);
    END IF;
END $$;
CREATE FUNCTION pg_temp.dep(p_letter text) RETURNS uuid LANGUAGE sql AS $$
    SELECT ('fd0000' || p_letter || p_letter || '-0000-0000-0000-000000000000')::uuid;
$$;
-- expected cumulative interest at the close of p_date (FD3)
CREATE FUNCTION pg_temp.cumulative(p_deposit uuid, p_date date) RETURNS numeric LANGUAGE sql AS $$
    SELECT round(principal * rate_pct * (LEAST(p_date, maturity_date - 1) - start_date + 1) / 36500, 2)
      FROM termdep.deposit WHERE deposit_id = p_deposit;
$$;

-- ---------- FD0 setup ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO ledger.gl_account (tenant_id, account_code, name, account_class, currency) VALUES
  (:tA::uuid, '2510-TEST-FD-PRINCIPAL', 'TEST fixed deposits', 'LIABILITY', 'KES'),
  (:tA::uuid, '2520-TEST-FD-PAYABLE', 'TEST fixed deposit interest payable', 'LIABILITY', 'KES'),
  (:tA::uuid, '5510-TEST-FD-EXPENSE', 'TEST fixed deposit interest expense', 'EXPENSE', 'KES'),
  (:tA::uuid, '2530-TEST-FD-TAX', 'TEST withholding tax on deposit interest', 'LIABILITY', 'KES');
COMMIT;
\echo PASS FD0 TEST-ONLY accounts

-- ---------- FD1 products ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  BEGIN
    INSERT INTO termdep.product (tenant_id, product_code, name, currency, term_months, rate_pct, early_rate_pct, min_amount,
        tax_rate_pct, gl_principal, gl_interest_payable, gl_interest_expense, gl_tax, updated_by)
    VALUES (tenancy.current_tenant(), 'FD-BAD', 'Bad accounts', 'KES', 1, 12, 6, 1000, 15,
            '5510-TEST-FD-EXPENSE', '2520-TEST-FD-PAYABLE', '2510-TEST-FD-PRINCIPAL', '2530-TEST-FD-TAX', 'user:fin');
    RAISE EXCEPTION 'TEST FAILED FD1a';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'TERMDEP_PRODUCT_INVALID%' THEN RAISE; END IF;
  END;
  BEGIN
    INSERT INTO termdep.product (tenant_id, product_code, name, currency, term_months, rate_pct, early_rate_pct, min_amount,
        tax_rate_pct, gl_principal, gl_interest_payable, gl_interest_expense, gl_tax, updated_by)
    VALUES (tenancy.current_tenant(), 'FD-BAD', 'Early above rate', 'KES', 1, 12, 13, 1000, 15,
            '2510-TEST-FD-PRINCIPAL', '2520-TEST-FD-PAYABLE', '5510-TEST-FD-EXPENSE', '2530-TEST-FD-TAX', 'user:fin');
    RAISE EXCEPTION 'TEST FAILED FD1b';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
END $$;
INSERT INTO termdep.product (tenant_id, product_code, name, currency, term_months, rate_pct, early_rate_pct,
    early_withdrawal_allowed, min_amount, max_amount, tax_rate_pct, gl_principal, gl_interest_payable, gl_interest_expense,
    gl_tax, updated_by) VALUES
  (:tA::uuid, 'FD-TEST-1M', 'TEST one month', 'KES', 1, 12, 6, true, 1000, 1000000, 15,
   '2510-TEST-FD-PRINCIPAL', '2520-TEST-FD-PAYABLE', '5510-TEST-FD-EXPENSE', '2530-TEST-FD-TAX', 'user:fin'),
  (:tA::uuid, 'FD-TEST-LOCK', 'TEST locked', 'KES', 1, 10, 0, false, 1000, NULL, 15,
   '2510-TEST-FD-PRINCIPAL', '2520-TEST-FD-PAYABLE', '5510-TEST-FD-EXPENSE', '2530-TEST-FD-TAX', 'user:fin');
DO $$
BEGIN
  BEGIN
    UPDATE termdep.product SET gl_tax = '2530-TEST-FD-PAYABLE' WHERE product_code = 'FD-TEST-1M';
    RAISE EXCEPTION 'TEST FAILED FD1c';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'TERMDEP_PRODUCT_FIXED%' THEN RAISE; END IF;
  END;
  IF NOT ledger.is_control_account(tenancy.current_tenant(), '2510-TEST-FD-PRINCIPAL')
     OR NOT ledger.is_control_account(tenancy.current_tenant(), '2520-TEST-FD-PAYABLE')
     OR ledger.is_control_account(tenancy.current_tenant(), '5510-TEST-FD-EXPENSE') THEN
    RAISE EXCEPTION 'TEST FAILED FD1d: control accounts';
  END IF;
END $$;
COMMIT;
\echo PASS FD1 products need the right account classes, early rate at most the rate, accounts fixed, control accounts

-- ---------- FD2 placements ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
DECLARE
  v_today date := (SELECT current_date_ FROM tenancy.business_date);
  v_customer uuid := (SELECT customer_id FROM savings.deposit_account WHERE account_id = 'aa000000-0000-0000-0000-0000000000d1');
  r record;
  n int := 0;
BEGIN
  FOR r IN SELECT * FROM (VALUES ('a', 10000, 'FD-TEST-1M', 12, 6, true, 'PAYOUT'),
                                 ('b', 20000, 'FD-TEST-1M', 12, 6, true, 'ROLLOVER_ALL'),
                                 ('c', 5000, 'FD-TEST-1M', 12, 6, true, 'PAYOUT'),
                                 ('d', 1000, 'FD-TEST-LOCK', 10, 0, false, 'PAYOUT')) x(letter, amount, product, rate, early, allow, instr)
  LOOP
    n := n + 1;
    PERFORM pg_temp.entry(pg_temp.dep(r.letter), 'fd-test-place-' || r.letter);
    PERFORM pg_temp.line(pg_temp.dep(r.letter), 1, '2111-TEST-SAVINGS', 'D', r.amount, 'aa000000-0000-0000-0000-0000000000d1');
    PERFORM pg_temp.line(pg_temp.dep(r.letter), 2, '2510-TEST-FD-PRINCIPAL', 'C', r.amount, NULL);
    INSERT INTO termdep.deposit (tenant_id, deposit_id, deposit_number, idempotency_key, customer_id, product_code,
        savings_account_id, branch_code, currency, principal, rate_pct, early_rate_pct, early_withdrawal_allowed, tax_rate_pct,
        term_months, start_date, maturity_date, maturity_instruction, placement_entry_id, created_by)
    VALUES (tenancy.current_tenant(), pg_temp.dep(r.letter), termdep.next_number(tenancy.current_tenant()), 'fd-test-' || r.letter,
            v_customer, r.product, 'aa000000-0000-0000-0000-0000000000d1', 'NBO01', 'KES', r.amount, r.rate, r.early, r.allow, 15,
            1, v_today, (v_today + interval '1 month')::date, r.instr, pg_temp.dep(r.letter), 'user:teller');
  END LOOP;
  IF (SELECT string_agg(deposit_number, ',' ORDER BY deposit_number) FROM termdep.deposit) <> 'FD00000001,FD00000002,FD00000003,FD00000004' THEN
    RAISE EXCEPTION 'TEST FAILED FD2a: numbers %', (SELECT string_agg(deposit_number, ',') FROM termdep.deposit);
  END IF;
  -- wrong maturity, amount below the minimum, a date other than today
  BEGIN
    INSERT INTO termdep.deposit (tenant_id, deposit_id, deposit_number, idempotency_key, customer_id, product_code,
        savings_account_id, branch_code, currency, principal, rate_pct, early_rate_pct, early_withdrawal_allowed, tax_rate_pct,
        term_months, start_date, maturity_date, maturity_instruction, placement_entry_id, created_by)
    VALUES (tenancy.current_tenant(), gen_random_uuid(), 'FD99999999', 'fd-test-x1', v_customer, 'FD-TEST-1M',
            'aa000000-0000-0000-0000-0000000000d1', 'NBO01', 'KES', 1000, 12, 6, true, 15, 1, v_today, v_today + 40, 'PAYOUT',
            pg_temp.dep('a'), 'user:teller');
    RAISE EXCEPTION 'TEST FAILED FD2b';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'TERMDEP_DEPOSIT_INVALID%' THEN RAISE; END IF;
  END;
  BEGIN
    INSERT INTO termdep.deposit (tenant_id, deposit_id, deposit_number, idempotency_key, customer_id, product_code,
        savings_account_id, branch_code, currency, principal, rate_pct, early_rate_pct, early_withdrawal_allowed, tax_rate_pct,
        term_months, start_date, maturity_date, maturity_instruction, placement_entry_id, created_by)
    VALUES (tenancy.current_tenant(), gen_random_uuid(), 'FD99999999', 'fd-test-x2', v_customer, 'FD-TEST-1M',
            'aa000000-0000-0000-0000-0000000000d1', 'NBO01', 'KES', 500, 12, 6, true, 15, 1, v_today,
            (v_today + interval '1 month')::date, 'PAYOUT', pg_temp.dep('a'), 'user:teller');
    RAISE EXCEPTION 'TEST FAILED FD2c';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'TERMDEP_AMOUNT%' THEN RAISE; END IF;
  END;
  BEGIN
    INSERT INTO termdep.deposit (tenant_id, deposit_id, deposit_number, idempotency_key, customer_id, product_code,
        savings_account_id, branch_code, currency, principal, rate_pct, early_rate_pct, early_withdrawal_allowed, tax_rate_pct,
        term_months, start_date, maturity_date, maturity_instruction, placement_entry_id, created_by)
    VALUES (tenancy.current_tenant(), gen_random_uuid(), 'FD99999999', 'fd-test-x3', v_customer, 'FD-TEST-1M',
            'aa000000-0000-0000-0000-0000000000d1', 'NBO01', 'KES', 1000, 12, 6, true, 15, 1, v_today - 1,
            (v_today - 1 + interval '1 month')::date, 'PAYOUT', pg_temp.dep('a'), 'user:teller');
    RAISE EXCEPTION 'TEST FAILED FD2d';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'TERMDEP_DATE%' THEN RAISE; END IF;
  END;
END $$;
COMMIT;

-- an entry that does not match the deposit is refused at commit
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
DECLARE
  v_today date := (SELECT current_date_ FROM tenancy.business_date);
BEGIN
  PERFORM pg_temp.entry('fd0000ee-0000-0000-0000-000000000000', 'fd-test-place-e');
  PERFORM pg_temp.line('fd0000ee-0000-0000-0000-000000000000', 1, '2111-TEST-SAVINGS', 'D', 9000, 'aa000000-0000-0000-0000-0000000000d1');
  PERFORM pg_temp.line('fd0000ee-0000-0000-0000-000000000000', 2, '2510-TEST-FD-PRINCIPAL', 'C', 9000, NULL);
  INSERT INTO termdep.deposit (tenant_id, deposit_id, deposit_number, idempotency_key, customer_id, product_code,
      savings_account_id, branch_code, currency, principal, rate_pct, early_rate_pct, early_withdrawal_allowed, tax_rate_pct,
      term_months, start_date, maturity_date, maturity_instruction, placement_entry_id, created_by)
  SELECT tenancy.current_tenant(), 'fd0000ee-0000-0000-0000-000000000000', 'FD99999998', 'fd-test-e', customer_id, 'FD-TEST-1M',
         account_id, 'NBO01', 'KES', 10000, 12, 6, true, 15, 1, v_today, (v_today + interval '1 month')::date, 'PAYOUT',
         'fd0000ee-0000-0000-0000-000000000000', 'user:teller'
    FROM savings.deposit_account WHERE account_id = 'aa000000-0000-0000-0000-0000000000d1';
  SET CONSTRAINTS termdep.deposit_posting IMMEDIATE;
  RAISE EXCEPTION 'TEST FAILED FD2e';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'TERMDEP_POSTING_MISMATCH%' THEN RAISE; END IF;
END $$;
ROLLBACK;

-- nothing else posts to deposit principal
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  PERFORM pg_temp.entry('fd0000ff-0000-0000-0000-000000000000', 'fd-test-direct');
  PERFORM pg_temp.line('fd0000ff-0000-0000-0000-000000000000', 1, '1199-TEST-FUNDING', 'D', 100, NULL);
  PERFORM pg_temp.line('fd0000ff-0000-0000-0000-000000000000', 2, '2510-TEST-FD-PRINCIPAL', 'C', 100, NULL);
  SET CONSTRAINTS ledger.journal_line_term_deposit IMMEDIATE;
  RAISE EXCEPTION 'TEST FAILED FD2f';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'TERMDEP_DIRECT_POSTING%' THEN RAISE; END IF;
END $$;
ROLLBACK;
\echo PASS FD2 placements copy the product terms, today only, within limits, numbered, entry exact; no direct postings

-- ---------- FD3 accrual at the close of the start day ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  INSERT INTO termdep.accrual_batch (tenant_id, entry_id, business_date, product_code, branch_code, deposits, total, created_by)
  SELECT tenancy.current_tenant(), pg_temp.dep('a'), current_date_, 'FD-TEST-1M', 'NBO01', 1, 1, 'system:eod' FROM tenancy.business_date;
  RAISE EXCEPTION 'TEST FAILED FD3a';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'TERMDEP_NOT_IN_EOD%' THEN RAISE; END IF;
END $$;
ROLLBACK;

BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$ BEGIN PERFORM pg_temp.start_eod(); END $$;
DO $$
DECLARE
  v_today date := (SELECT current_date_ FROM tenancy.business_date);
BEGIN
  IF pg_temp.cumulative(pg_temp.dep('a'), v_today) <> 3.29 OR pg_temp.cumulative(pg_temp.dep('b'), v_today) <> 6.58
     OR pg_temp.cumulative(pg_temp.dep('c'), v_today) <> 1.64 OR pg_temp.cumulative(pg_temp.dep('d'), v_today) <> 0.27 THEN
    RAISE EXCEPTION 'TEST FAILED FD3b: one night of interest';
  END IF;
  PERFORM pg_temp.entry('fd000001-0000-0000-0000-000000000001', 'fd-test-accrual-1m-1');
  PERFORM pg_temp.line('fd000001-0000-0000-0000-000000000001', 1, '5510-TEST-FD-EXPENSE', 'D', 11.51, NULL);
  PERFORM pg_temp.line('fd000001-0000-0000-0000-000000000001', 2, '2520-TEST-FD-PAYABLE', 'C', 11.51, NULL);
  INSERT INTO termdep.accrual_batch (tenant_id, entry_id, business_date, product_code, branch_code, deposits, total, created_by)
  VALUES (tenancy.current_tenant(), 'fd000001-0000-0000-0000-000000000001', v_today, 'FD-TEST-1M', 'NBO01', 3, 11.51, 'system:eod');
  BEGIN
    INSERT INTO termdep.accrual (tenant_id, deposit_id, business_date, nights, cumulative, posted, entry_id)
    VALUES (tenancy.current_tenant(), pg_temp.dep('a'), v_today, 1, 3.30, 3.30, 'fd000001-0000-0000-0000-000000000001');
    RAISE EXCEPTION 'TEST FAILED FD3c';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'TERMDEP_ACCRUAL_INVALID%' THEN RAISE; END IF;
  END;
  INSERT INTO termdep.accrual (tenant_id, deposit_id, business_date, nights, cumulative, posted, entry_id) VALUES
    (tenancy.current_tenant(), pg_temp.dep('a'), v_today, 1, 3.29, 3.29, 'fd000001-0000-0000-0000-000000000001'),
    (tenancy.current_tenant(), pg_temp.dep('b'), v_today, 1, 6.58, 6.58, 'fd000001-0000-0000-0000-000000000001'),
    (tenancy.current_tenant(), pg_temp.dep('c'), v_today, 1, 1.64, 1.64, 'fd000001-0000-0000-0000-000000000001');
  PERFORM pg_temp.entry('fd000001-0000-0000-0000-000000000002', 'fd-test-accrual-lock-1');
  PERFORM pg_temp.line('fd000001-0000-0000-0000-000000000002', 1, '5510-TEST-FD-EXPENSE', 'D', 0.27, NULL);
  PERFORM pg_temp.line('fd000001-0000-0000-0000-000000000002', 2, '2520-TEST-FD-PAYABLE', 'C', 0.27, NULL);
  INSERT INTO termdep.accrual_batch (tenant_id, entry_id, business_date, product_code, branch_code, deposits, total, created_by)
  VALUES (tenancy.current_tenant(), 'fd000001-0000-0000-0000-000000000002', v_today, 'FD-TEST-LOCK', 'NBO01', 1, 0.27, 'system:eod');
  INSERT INTO termdep.accrual (tenant_id, deposit_id, business_date, nights, cumulative, posted, entry_id) VALUES
    (tenancy.current_tenant(), pg_temp.dep('d'), v_today, 1, 0.27, 0.27, 'fd000001-0000-0000-0000-000000000002');
  -- a deposit cannot be matured before the day before its maturity
  BEGIN
    INSERT INTO termdep.closure (tenant_id, deposit_id, kind, business_date, nights, rate_applied, interest_earned, accrued_before,
        tax, net_interest, to_savings, created_by)
    VALUES (tenancy.current_tenant(), pg_temp.dep('a'), 'MATURITY', v_today, 1, 12, 3.29, 3.29, 0.49, 2.80, 10002.80, 'system:eod');
    RAISE EXCEPTION 'TEST FAILED FD3d';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'TERMDEP_CLOSURE_INVALID%' THEN RAISE; END IF;
  END;
END $$;
DO $$ BEGIN PERFORM pg_temp.finish_eod(); END $$;
COMMIT;

-- a batch whose total differs from its rows is refused at commit
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$ BEGIN PERFORM pg_temp.start_eod(); END $$;
DO $$
DECLARE
  v_today date := (SELECT current_date_ FROM tenancy.business_date);
BEGIN
  PERFORM pg_temp.entry('fd000002-0000-0000-0000-000000000001', 'fd-test-accrual-bad');
  PERFORM pg_temp.line('fd000002-0000-0000-0000-000000000001', 1, '5510-TEST-FD-EXPENSE', 'D', 5.00, NULL);
  PERFORM pg_temp.line('fd000002-0000-0000-0000-000000000001', 2, '2520-TEST-FD-PAYABLE', 'C', 5.00, NULL);
  INSERT INTO termdep.accrual_batch (tenant_id, entry_id, business_date, product_code, branch_code, deposits, total, created_by)
  VALUES (tenancy.current_tenant(), 'fd000002-0000-0000-0000-000000000001', v_today, 'FD-TEST-1M', 'NBO01', 1, 5.00, 'system:eod');
  INSERT INTO termdep.accrual (tenant_id, deposit_id, business_date, nights, cumulative, posted, entry_id)
  VALUES (tenancy.current_tenant(), pg_temp.dep('a'), v_today, 2, 6.58, 3.29, 'fd000002-0000-0000-0000-000000000001');
  SET CONSTRAINTS termdep.accrual_batch_totals IMMEDIATE;
  RAISE EXCEPTION 'TEST FAILED FD3e';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'TERMDEP_ACCRUAL_MISMATCH%' THEN RAISE; END IF;
END $$;
ROLLBACK;
\echo PASS FD3 interest accrues per night in a running close, cumulative and rounded, batch totals exact

-- ---------- FD5 early withdrawal (day 2) ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  BEGIN
    INSERT INTO termdep.early_request (tenant_id, request_id, idempotency_key, deposit_id, reason, requested_by)
    VALUES (tenancy.current_tenant(), 'fd0000e1-0000-0000-0000-000000000000', 'fd-test-early-d', pg_temp.dep('d'),
            'Member needs school fees', 'user:teller');
    RAISE EXCEPTION 'TEST FAILED FD5a';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'TERMDEP_NO_EARLY%' THEN RAISE; END IF;
  END;
  INSERT INTO termdep.early_request (tenant_id, request_id, idempotency_key, deposit_id, reason, requested_by)
  VALUES (tenancy.current_tenant(), 'fd0000e2-0000-0000-0000-000000000000', 'fd-test-early-c', pg_temp.dep('c'),
          'Member needs school fees', 'user:teller');
  BEGIN
    UPDATE termdep.early_request SET status = 'APPROVED', decided_by = 'user:teller', decided_at = now()
     WHERE request_id = 'fd0000e2-0000-0000-0000-000000000000';
    RAISE EXCEPTION 'TEST FAILED FD5b';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'TERMDEP_SAME_PERSON%' THEN RAISE; END IF;
  END;
END $$;
UPDATE termdep.early_request SET status = 'APPROVED', decided_by = 'user:manager', decided_at = now()
 WHERE request_id = 'fd0000e2-0000-0000-0000-000000000000';
-- one night at the early rate of 6%: 0.82, tax 0.12, net 0.70; 1.64 accrued, so 0.82 back to expense
DO $$ BEGIN PERFORM pg_temp.entry('fd000003-0000-0000-0000-000000000001', 'fd-test-early-c'); END $$;
DO $$ BEGIN PERFORM pg_temp.line('fd000003-0000-0000-0000-000000000001', 1, '2510-TEST-FD-PRINCIPAL', 'D', 5000, NULL); END $$;
DO $$ BEGIN PERFORM pg_temp.line('fd000003-0000-0000-0000-000000000001', 2, '2520-TEST-FD-PAYABLE', 'D', 1.64, NULL); END $$;
DO $$ BEGIN PERFORM pg_temp.line('fd000003-0000-0000-0000-000000000001', 3, '5510-TEST-FD-EXPENSE', 'C', 0.82, NULL); END $$;
DO $$ BEGIN PERFORM pg_temp.line('fd000003-0000-0000-0000-000000000001', 4, '2530-TEST-FD-TAX', 'C', 0.12, NULL); END $$;
DO $$ BEGIN PERFORM pg_temp.line('fd000003-0000-0000-0000-000000000001', 5, '2111-TEST-SAVINGS', 'C', 5000.70, 'aa000000-0000-0000-0000-0000000000d1'); END $$;
DO $$
BEGIN
  INSERT INTO termdep.closure (tenant_id, deposit_id, kind, business_date, nights, rate_applied, interest_earned, accrued_before,
      tax, net_interest, to_savings, early_request_id, entry_id, created_by)
  SELECT tenancy.current_tenant(), pg_temp.dep('c'), 'EARLY', current_date_, 1, 6, 0.82, 1.64, 0.12, 0.70, 5001.64,
         'fd0000e2-0000-0000-0000-000000000000', 'fd000003-0000-0000-0000-000000000001', 'user:manager' FROM tenancy.business_date;
  RAISE EXCEPTION 'TEST FAILED FD5c';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'TERMDEP_CLOSURE_INVALID%' THEN RAISE; END IF;
END $$;
INSERT INTO termdep.closure (tenant_id, deposit_id, kind, business_date, nights, rate_applied, interest_earned, accrued_before,
    tax, net_interest, to_savings, early_request_id, entry_id, created_by)
SELECT :tA::uuid, pg_temp.dep('c'), 'EARLY', current_date_, 1, 6, 0.82, 1.64, 0.12, 0.70, 5000.70,
       'fd0000e2-0000-0000-0000-000000000000', 'fd000003-0000-0000-0000-000000000001', 'user:manager' FROM tenancy.business_date;
UPDATE termdep.deposit SET status = 'WITHDRAWN_EARLY' WHERE deposit_id = pg_temp.dep('c');
COMMIT;
\echo PASS FD5 early withdrawal asked and approved by different people, early rate for nights held, excess accrual reversed

-- ---------- FD4 maturity: close days until the day before A and B mature ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  WHILE (SELECT current_date_ FROM tenancy.business_date) < (SELECT maturity_date - 1 FROM termdep.deposit WHERE deposit_id = pg_temp.dep('a')) LOOP
    PERFORM pg_temp.start_eod();
    PERFORM pg_temp.finish_eod();
  END LOOP;
END $$;
COMMIT;

BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  INSERT INTO termdep.closure (tenant_id, deposit_id, kind, business_date, nights, rate_applied, interest_earned, accrued_before,
      tax, net_interest, to_savings, created_by)
  SELECT tenancy.current_tenant(), pg_temp.dep('a'), 'MATURITY', current_date_, 30, 12, 98.63, 3.29, 14.79, 83.84, 10083.84,
         'user:teller' FROM tenancy.business_date;
  RAISE EXCEPTION 'TEST FAILED FD4a';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'TERMDEP_NOT_IN_EOD%' THEN RAISE; END IF;
END $$;
ROLLBACK;

BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$ BEGIN PERFORM pg_temp.start_eod(); END $$;
DO $$
DECLARE
  v_today date := (SELECT current_date_ FROM tenancy.business_date);
  a termdep.deposit%ROWTYPE;
  b termdep.deposit%ROWTYPE;
  v_full_a numeric;
  v_full_b numeric;
  v_post_a numeric;
  v_post_b numeric;
  v_tax_a numeric;
  v_tax_b numeric;
  v_nights int;
BEGIN
  SELECT * INTO a FROM termdep.deposit WHERE deposit_id = pg_temp.dep('a');
  SELECT * INTO b FROM termdep.deposit WHERE deposit_id = pg_temp.dep('b');
  v_nights := a.maturity_date - a.start_date;
  v_full_a := round(10000 * 12 * v_nights / 36500.0, 2);
  v_full_b := round(20000 * 12 * v_nights / 36500.0, 2);
  IF pg_temp.cumulative(a.deposit_id, v_today) <> v_full_a THEN
    RAISE EXCEPTION 'TEST FAILED FD4b: the last close accrues the full term';
  END IF;
  v_post_a := v_full_a - 3.29;
  v_post_b := v_full_b - 6.58;
  PERFORM pg_temp.entry('fd000004-0000-0000-0000-000000000001', 'fd-test-accrual-last');
  PERFORM pg_temp.line('fd000004-0000-0000-0000-000000000001', 1, '5510-TEST-FD-EXPENSE', 'D', v_post_a + v_post_b, NULL);
  PERFORM pg_temp.line('fd000004-0000-0000-0000-000000000001', 2, '2520-TEST-FD-PAYABLE', 'C', v_post_a + v_post_b, NULL);
  INSERT INTO termdep.accrual_batch (tenant_id, entry_id, business_date, product_code, branch_code, deposits, total, created_by)
  VALUES (tenancy.current_tenant(), 'fd000004-0000-0000-0000-000000000001', v_today, 'FD-TEST-1M', 'NBO01', 2, v_post_a + v_post_b, 'system:eod');
  INSERT INTO termdep.accrual (tenant_id, deposit_id, business_date, nights, cumulative, posted, entry_id) VALUES
    (tenancy.current_tenant(), a.deposit_id, v_today, v_nights, v_full_a, v_post_a, 'fd000004-0000-0000-0000-000000000001'),
    (tenancy.current_tenant(), b.deposit_id, v_today, v_nights, v_full_b, v_post_b, 'fd000004-0000-0000-0000-000000000001');

  -- A pays principal and net interest to savings
  v_tax_a := round(v_full_a * 0.15, 2);
  PERFORM pg_temp.entry('fd000005-0000-0000-0000-000000000001', 'fd-test-mature-a');
  PERFORM pg_temp.line('fd000005-0000-0000-0000-000000000001', 1, '2510-TEST-FD-PRINCIPAL', 'D', 10000, NULL);
  PERFORM pg_temp.line('fd000005-0000-0000-0000-000000000001', 2, '2520-TEST-FD-PAYABLE', 'D', v_full_a, NULL);
  PERFORM pg_temp.line('fd000005-0000-0000-0000-000000000001', 3, '2530-TEST-FD-TAX', 'C', v_tax_a, NULL);
  PERFORM pg_temp.line('fd000005-0000-0000-0000-000000000001', 4, '2111-TEST-SAVINGS', 'C', 10000 + v_full_a - v_tax_a,
                       'aa000000-0000-0000-0000-0000000000d1');
  INSERT INTO termdep.closure (tenant_id, deposit_id, kind, business_date, nights, rate_applied, interest_earned, accrued_before,
      tax, net_interest, to_savings, entry_id, created_by)
  VALUES (tenancy.current_tenant(), a.deposit_id, 'MATURITY', v_today, v_nights, 12, v_full_a, v_full_a, v_tax_a, v_full_a - v_tax_a,
          10000 + v_full_a - v_tax_a, 'fd000005-0000-0000-0000-000000000001', 'system:eod');
  UPDATE termdep.deposit SET status = 'MATURED' WHERE deposit_id = a.deposit_id;

  -- B rolls over with its net interest into a new deposit from the maturity date
  v_tax_b := round(v_full_b * 0.15, 2);
  PERFORM pg_temp.entry('fd000005-0000-0000-0000-000000000002', 'fd-test-mature-b');
  PERFORM pg_temp.line('fd000005-0000-0000-0000-000000000002', 1, '2520-TEST-FD-PAYABLE', 'D', v_full_b, NULL);
  PERFORM pg_temp.line('fd000005-0000-0000-0000-000000000002', 2, '2530-TEST-FD-TAX', 'C', v_tax_b, NULL);
  PERFORM pg_temp.line('fd000005-0000-0000-0000-000000000002', 3, '2510-TEST-FD-PRINCIPAL', 'C', v_full_b - v_tax_b, NULL);
  BEGIN
    INSERT INTO termdep.deposit (tenant_id, deposit_id, deposit_number, idempotency_key, customer_id, product_code,
        savings_account_id, branch_code, currency, principal, rate_pct, early_rate_pct, early_withdrawal_allowed, tax_rate_pct,
        term_months, start_date, maturity_date, maturity_instruction, placement_entry_id, rolled_from, created_by)
    VALUES (tenancy.current_tenant(), pg_temp.dep('e'), 'FD99999997', 'fd-test-g-bad', b.customer_id, 'FD-TEST-1M',
            b.savings_account_id, 'NBO01', 'KES', 20000 + v_full_b - v_tax_b, 12, 6, true, 15, 1, v_today,
            (v_today + interval '1 month')::date, 'PAYOUT', 'fd000005-0000-0000-0000-000000000002', b.deposit_id, 'system:eod');
    RAISE EXCEPTION 'TEST FAILED FD4c';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'TERMDEP_DEPOSIT_INVALID%' THEN RAISE; END IF;
  END;
  INSERT INTO termdep.deposit (tenant_id, deposit_id, deposit_number, idempotency_key, customer_id, product_code,
      savings_account_id, branch_code, currency, principal, rate_pct, early_rate_pct, early_withdrawal_allowed, tax_rate_pct,
      term_months, start_date, maturity_date, maturity_instruction, placement_entry_id, rolled_from, created_by)
  VALUES (tenancy.current_tenant(), pg_temp.dep('e'), termdep.next_number(tenancy.current_tenant()), 'fd-test-g', b.customer_id,
          'FD-TEST-1M', b.savings_account_id, 'NBO01', 'KES', 20000 + v_full_b - v_tax_b, 12, 6, true, 15, 1, b.maturity_date,
          (b.maturity_date + interval '1 month')::date, 'ROLLOVER_ALL', 'fd000005-0000-0000-0000-000000000002', b.deposit_id,
          'system:eod');
  INSERT INTO termdep.closure (tenant_id, deposit_id, kind, business_date, nights, rate_applied, interest_earned, accrued_before,
      tax, net_interest, to_savings, new_principal, new_deposit_id, entry_id, created_by)
  VALUES (tenancy.current_tenant(), b.deposit_id, 'MATURITY', v_today, v_nights, 12, v_full_b, v_full_b, v_tax_b, v_full_b - v_tax_b,
          0, 20000 + v_full_b - v_tax_b, pg_temp.dep('e'), 'fd000005-0000-0000-0000-000000000002', 'system:eod');
  UPDATE termdep.deposit SET status = 'ROLLED_OVER' WHERE deposit_id = b.deposit_id;

  -- the new deposit is not due yet
  BEGIN
    INSERT INTO termdep.closure (tenant_id, deposit_id, kind, business_date, nights, rate_applied, interest_earned, accrued_before,
        tax, net_interest, to_savings, created_by)
    VALUES (tenancy.current_tenant(), pg_temp.dep('e'), 'MATURITY', v_today, 0, 12, 0, 0, 0, 0, 0, 'system:eod');
    RAISE EXCEPTION 'TEST FAILED FD4d';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'TERMDEP_CLOSURE_INVALID%' THEN RAISE; END IF;
  END;
END $$;
DO $$ BEGIN PERFORM pg_temp.finish_eod(); END $$;
COMMIT;

BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
DECLARE
  v_principal numeric;
  v_payable numeric;
BEGIN
  IF (SELECT string_agg(status, ',' ORDER BY deposit_number) FROM termdep.deposit)
       <> 'MATURED,ROLLED_OVER,WITHDRAWN_EARLY,ACTIVE,ACTIVE' THEN
    RAISE EXCEPTION 'TEST FAILED FD4e: %', (SELECT string_agg(status, ',' ORDER BY deposit_number) FROM termdep.deposit);
  END IF;
  -- the ledger agrees with the deposits (FD6): principal = active deposits, payable = their accrued interest
  SELECT COALESCE(sum(CASE side WHEN 'C' THEN amount ELSE -amount END), 0) INTO v_principal
    FROM ledger.journal_line WHERE account_code = '2510-TEST-FD-PRINCIPAL';
  SELECT COALESCE(sum(CASE side WHEN 'C' THEN amount ELSE -amount END), 0) INTO v_payable
    FROM ledger.journal_line WHERE account_code = '2520-TEST-FD-PAYABLE';
  IF v_principal <> (SELECT sum(principal) FROM termdep.deposit WHERE status = 'ACTIVE')
     OR v_payable <> (SELECT COALESCE(sum(x.posted), 0) FROM termdep.accrual x JOIN termdep.deposit d USING (tenant_id, deposit_id)
                       WHERE d.status = 'ACTIVE') THEN
    RAISE EXCEPTION 'TEST FAILED FD4f: ledger principal % payable %', v_principal, v_payable;
  END IF;
END $$;
COMMIT;
\echo PASS FD4 maturity in the close before the maturity date: full interest, tax, payout or rollover from the maturity date

-- ---------- FD7 immutability and the payout account ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  BEGIN
    UPDATE savings.deposit_account SET status = 'CLOSED' WHERE account_id = 'aa000000-0000-0000-0000-0000000000d1';
    RAISE EXCEPTION 'TEST FAILED FD7a';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'TERMDEP_ACCOUNT_IN_USE%' THEN RAISE; END IF;
  END;
  BEGIN
    UPDATE termdep.deposit SET principal = 1 WHERE deposit_id = pg_temp.dep('d');
    RAISE EXCEPTION 'TEST FAILED FD7b';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'TERMDEP_IMMUTABLE%' THEN RAISE; END IF;
  END;
  BEGIN
    UPDATE termdep.deposit SET maturity_instruction = 'ROLLOVER_ALL' WHERE deposit_id = pg_temp.dep('a');
    RAISE EXCEPTION 'TEST FAILED FD7c';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'TERMDEP_IMMUTABLE%' THEN RAISE; END IF;
  END;
  BEGIN
    UPDATE termdep.deposit SET maturity_instruction = 'ROLLOVER_ALL' WHERE deposit_id = pg_temp.dep('d');
    RAISE EXCEPTION 'TEST FAILED FD7d';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'TERMDEP_IMMUTABLE%' THEN RAISE; END IF;
  END;
  UPDATE termdep.deposit SET maturity_instruction = 'ROLLOVER_PRINCIPAL', instruction_changed_by = 'user:teller',
         instruction_changed_at = now() WHERE deposit_id = pg_temp.dep('d');
  BEGIN
    DELETE FROM termdep.closure WHERE deposit_id = pg_temp.dep('a');
    RAISE EXCEPTION 'TEST FAILED FD7e';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
END $$;
COMMIT;
\echo PASS FD7 a payout account stays open, deposits keep their terms, closed deposits never change

BEGIN;
SET LOCAL app.tenant_id = :tB;
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM termdep.deposit) OR EXISTS (SELECT 1 FROM termdep.product) OR EXISTS (SELECT 1 FROM termdep.accrual) THEN
    RAISE EXCEPTION 'TEST FAILED FD-RLS';
  END IF;
END $$;
COMMIT;
\echo PASS FD-RLS fixed deposits are invisible to other tenants
