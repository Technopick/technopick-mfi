-- =============================================================================
-- Tests for V39__member_exit.sql, run as mfi_app at the end of the suite. TEST-ONLY data.
-- Member CU26-0000086 (new here) saves 36,500.00 on product SAVT, earns two nights of savings
-- interest during the notice period and leaves; member CU26-0000085 (shares_test.sql) still holds
-- shares and cannot leave.
-- =============================================================================
\set ON_ERROR_STOP on
\set QUIET on
\set tA '''00000000-0000-0000-0000-0000000000a1'''
\set tB '''00000000-0000-0000-0000-0000000000b2'''

CREATE FUNCTION pg_temp.eod_accrue_and_close() RETURNS void LANGUAGE plpgsql AS $$
DECLARE
  v_today date := (SELECT current_date_ FROM tenancy.business_date);
  v_rate numeric := (SELECT interest_rate FROM savings.deposit_product WHERE product_code = 'SAVT');
  v_nights int := (SELECT count(*) + 1 FROM savings.interest_accrual WHERE account_id = 'ab000000-0000-0000-0000-0000000000e1');
  v_prev numeric := (SELECT COALESCE(sum(posted), 0) FROM savings.interest_accrual WHERE account_id = 'ab000000-0000-0000-0000-0000000000e1');
  v_post numeric;
  v_entry uuid := gen_random_uuid();
  v_new date;
BEGIN
  INSERT INTO ledger.eod_run (tenant_id, business_date, status) VALUES (tenancy.current_tenant(), v_today, 'RUNNING');
  v_post := round(v_nights * round(36500 * v_rate / 36500, 10), 2) - v_prev;
  INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
  VALUES (tenancy.current_tenant(), v_entry, 'savings-accrual:SAVT:NBO01:' || v_today, v_today, 'EOD', 'exit-test', 'system:eod');
  INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
    (tenancy.current_tenant(), v_entry, 1, (SELECT gl_interest_expense FROM savings.deposit_product WHERE product_code = 'SAVT'), 'D', v_post, 'KES', 'NBO01'),
    (tenancy.current_tenant(), v_entry, 2, (SELECT gl_interest_payable FROM savings.deposit_product WHERE product_code = 'SAVT'), 'C', v_post, 'KES', 'NBO01');
  INSERT INTO savings.interest_accrual (tenant_id, account_id, business_date, product_code, branch_code, balance, rate, posted, entry_id)
  VALUES (tenancy.current_tenant(), 'ab000000-0000-0000-0000-0000000000e1', v_today, 'SAVT', 'NBO01', 36500, v_rate, v_post, v_entry);
  UPDATE ledger.eod_run SET status = 'COMPLETED', finished_at = clock_timestamp() WHERE business_date = v_today;
  UPDATE tenancy.business_date SET current_date_ = current_date_ + 1 RETURNING current_date_ INTO v_new;
  IF NOT EXISTS (SELECT 1 FROM ledger.accounting_period WHERE v_new BETWEEN start_date AND end_date) THEN
    INSERT INTO ledger.accounting_period (tenant_id, period_id, start_date, end_date)
    VALUES (tenancy.current_tenant(), to_char(v_new, 'YYYY-MM'), date_trunc('month', v_new)::date,
            (date_trunc('month', v_new) + interval '1 month - 1 day')::date);
  END IF;
END $$;

-- settles member a6's exit approved by p_by, crediting refunds payable with p_refund_cr (the right amount unless testing)
CREATE FUNCTION pg_temp.settle(p_exit uuid, p_by text, p_refund_cr numeric) RETURNS numeric LANGUAGE plpgsql AS $$
DECLARE
  v_today date := (SELECT current_date_ FROM tenancy.business_date);
  v_entry uuid := gen_random_uuid();
  v_balance numeric := (SELECT ledger_balance FROM savings.account_balance WHERE account_id = 'ab000000-0000-0000-0000-0000000000e1');
  v_interest numeric := membership.unposted_interest(tenancy.current_tenant(), 'ab000000-0000-0000-0000-0000000000e1');
  p savings.deposit_product%ROWTYPE;
  v_tax numeric;
  v_total numeric;
BEGIN
  SELECT * INTO p FROM savings.deposit_product WHERE product_code = 'SAVT';
  v_tax := round(v_interest * p.withholding_tax_rate / 100, 2);
  v_total := v_balance + v_interest - v_tax;
  INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
  VALUES (tenancy.current_tenant(), v_entry, 'exit-test-' || v_entry, v_today, 'MEMBERSHIP', 'exit-test', p_by);
  UPDATE membership.exit_request SET status = 'APPROVED', decided_by = p_by, decided_at = now(), business_date = v_today,
         gl_refunds_payable = '2450-TEST-REFUNDS', refund_total = v_total, entry_id = v_entry WHERE exit_id = p_exit;
  INSERT INTO membership.exit_account (tenant_id, exit_id, account_id, balance, interest_gross, interest_tax, refund)
  VALUES (tenancy.current_tenant(), p_exit, 'ab000000-0000-0000-0000-0000000000e1', v_balance, v_interest, v_tax, v_total);
  INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code, subledger_account_id) VALUES
    (tenancy.current_tenant(), v_entry, 1, '2111-TEST-SAVINGS', 'D', v_balance, 'KES', 'NBO01', 'ab000000-0000-0000-0000-0000000000e1'),
    (tenancy.current_tenant(), v_entry, 2, p.gl_interest_payable, 'D', v_interest, 'KES', 'NBO01', NULL),
    (tenancy.current_tenant(), v_entry, 3, '2450-TEST-REFUNDS', 'C', COALESCE(p_refund_cr, v_total), 'KES', 'NBO01', NULL);
  IF v_tax > 0 THEN
    INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code)
    VALUES (tenancy.current_tenant(), v_entry, 4, p.gl_withholding_tax, 'C', v_tax, 'KES', 'NBO01');
  END IF;
  UPDATE savings.deposit_account SET status = 'CLOSED' WHERE account_id = 'ab000000-0000-0000-0000-0000000000e1';
  UPDATE customer.customer SET status = 'EXITED' WHERE customer_id = 'c0000000-0000-0000-0000-0000000000a6';
  RETURN v_total;
END $$;

-- ---------- MX0 setup ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO ledger.gl_account (tenant_id, account_code, name, account_class, currency) VALUES
  (:tA::uuid, '2450-TEST-REFUNDS', 'TEST member refunds payable', 'LIABILITY', 'KES'),
  (:tA::uuid, '1010-TEST-BANK', 'TEST bank account', 'ASSET', 'KES');
INSERT INTO customer.customer (tenant_id, customer_id, customer_number, idempotency_key, first_name, last_name, date_of_birth,
    id_type, id_number, msisdn, branch_code, consent_version, consent_at, created_by)
VALUES (:tA::uuid, 'c0000000-0000-0000-0000-0000000000a6', 'CU26-0000086', 'cust-mx6', 'Leaving', 'Member', '1980-05-05',
        'NATIONAL_ID', '99887767', '254711000086', 'NBO01', 'DPA-2026-01', now(), 'user:officer'),
       (:tA::uuid, 'c0000000-0000-0000-0000-0000000000a7', 'CU26-0000087', 'cust-mx7', 'Staying', 'Member', '1981-06-06',
        'NATIONAL_ID', '99887768', '254711000087', 'NBO01', 'DPA-2026-01', now(), 'user:officer');
UPDATE customer.customer SET status = 'ACTIVE', verified_by = 'user:manager', verification_note = 'ID sighted at branch'
 WHERE customer_id IN ('c0000000-0000-0000-0000-0000000000a6', 'c0000000-0000-0000-0000-0000000000a7');
INSERT INTO savings.deposit_account (tenant_id, account_id, account_number, customer_ref, customer_id, product_code, currency,
    branch_code, gl_control_account, opened_on)
SELECT :tA::uuid, 'ab000000-0000-0000-0000-0000000000e1', savings.next_account_number(), 'CU26-0000086',
       'c0000000-0000-0000-0000-0000000000a6', 'SAVT', 'KES', 'NBO01', '2111-TEST-SAVINGS', current_date_ FROM tenancy.business_date;
INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
SELECT :tA::uuid, 'e0000000-0000-0000-0000-000000000001', 'exit-test-funding', current_date_, 'TELLER', 'c', 'user:teller'
  FROM tenancy.business_date;
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code, subledger_account_id) VALUES
  (:tA::uuid, 'e0000000-0000-0000-0000-000000000001', 1, '1199-TEST-FUNDING', 'D', 36500, 'KES', 'NBO01', NULL),
  (:tA::uuid, 'e0000000-0000-0000-0000-000000000001', 2, '2111-TEST-SAVINGS', 'C', 36500, 'KES', 'NBO01', 'ab000000-0000-0000-0000-0000000000e1');
COMMIT;
\echo PASS MX0 TEST-ONLY accounts, a leaving member with 36,500 saved, a member who stays

-- ---------- MX1 settings ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  BEGIN
    INSERT INTO membership.exit_request (tenant_id, exit_id, idempotency_key, customer_id, reason, requested_on, notice_days,
        earliest_exit_date, requested_by)
    SELECT tenancy.current_tenant(), gen_random_uuid(), 'x0', 'c0000000-0000-0000-0000-0000000000a6', 'Relocating abroad for work',
           current_date_, 2, current_date_ + 2, 'user:officer' FROM tenancy.business_date;
    RAISE EXCEPTION 'TEST FAILED MX1a';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'EXIT_SETTINGS_MISSING%' THEN RAISE; END IF;
  END;
  BEGIN
    INSERT INTO membership.exit_settings (tenant_id, notice_days, gl_refunds_payable, updated_by)
    VALUES (tenancy.current_tenant(), 2, '1010-TEST-BANK', 'user:fin');
    RAISE EXCEPTION 'TEST FAILED MX1b';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'EXIT_SETTINGS_INVALID%' THEN RAISE; END IF;
  END;
END $$;
INSERT INTO membership.exit_settings (tenant_id, notice_days, gl_refunds_payable, updated_by)
VALUES (:tA::uuid, 2, '2450-TEST-REFUNDS', 'user:fin');
COMMIT;
\echo PASS MX1 exits need settings with a LIABILITY refunds account

-- ---------- MX2 requests ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  BEGIN
    INSERT INTO membership.exit_request (tenant_id, exit_id, idempotency_key, customer_id, reason, requested_on, notice_days,
        earliest_exit_date, requested_by)
    SELECT tenancy.current_tenant(), gen_random_uuid(), 'x1', 'c0000000-0000-0000-0000-0000000000a6', 'Relocating abroad for work',
           current_date_, 0, current_date_, 'user:officer' FROM tenancy.business_date;
    RAISE EXCEPTION 'TEST FAILED MX2a';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'EXIT_INVALID%' THEN RAISE; END IF;
  END;
END $$;
INSERT INTO membership.exit_request (tenant_id, exit_id, idempotency_key, customer_id, reason, requested_on, notice_days,
    earliest_exit_date, requested_by)
SELECT :tA::uuid, x.id, x.k, x.c, 'Relocating abroad for work', current_date_, 2, current_date_ + 2, 'user:officer'
  FROM tenancy.business_date,
       (VALUES ('e1000000-0000-0000-0000-000000000006'::uuid, 'x6', 'c0000000-0000-0000-0000-0000000000a6'::uuid),
               ('e1000000-0000-0000-0000-000000000005'::uuid, 'x5', 'c0000000-0000-0000-0000-0000000000a5'::uuid)) x(id, k, c);
DO $$
BEGIN
  BEGIN
    INSERT INTO membership.exit_request (tenant_id, exit_id, idempotency_key, customer_id, reason, requested_on, notice_days,
        earliest_exit_date, requested_by)
    SELECT tenancy.current_tenant(), gen_random_uuid(), 'x6b', 'c0000000-0000-0000-0000-0000000000a6', 'Asked a second time',
           current_date_, 2, current_date_ + 2, 'user:teller' FROM tenancy.business_date;
    RAISE EXCEPTION 'TEST FAILED MX2b';
  EXCEPTION WHEN unique_violation THEN NULL;
  END;
  BEGIN
    UPDATE membership.exit_request SET status = 'APPROVED', decided_by = 'user:officer', decided_at = now(),
           business_date = (SELECT current_date_ FROM tenancy.business_date), gl_refunds_payable = '2450-TEST-REFUNDS', refund_total = 0
     WHERE exit_id = 'e1000000-0000-0000-0000-000000000006';
    RAISE EXCEPTION 'TEST FAILED MX2c';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'EXIT_SAME_PERSON%' THEN RAISE; END IF;
  END;
  BEGIN
    UPDATE membership.exit_request SET status = 'APPROVED', decided_by = 'user:manager', decided_at = now(),
           business_date = (SELECT current_date_ FROM tenancy.business_date), gl_refunds_payable = '2450-TEST-REFUNDS', refund_total = 0
     WHERE exit_id = 'e1000000-0000-0000-0000-000000000006';
    RAISE EXCEPTION 'TEST FAILED MX2d';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'EXIT_NOTICE%' THEN RAISE; END IF;
  END;
END $$;
COMMIT;

-- two closes pass during the notice period; the leaving member earns savings interest each night
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$ BEGIN PERFORM pg_temp.eod_accrue_and_close(); PERFORM pg_temp.eod_accrue_and_close(); END $$;
COMMIT;

BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  IF membership.unposted_interest(tenancy.current_tenant(), 'ab000000-0000-0000-0000-0000000000e1')
       <> round(2 * (SELECT interest_rate FROM savings.deposit_product WHERE product_code = 'SAVT'), 2) THEN
    RAISE EXCEPTION 'TEST FAILED MX2e: two nights of interest on 36,500';
  END IF;
  -- the member still holding shares cannot leave
  BEGIN
    UPDATE membership.exit_request SET status = 'APPROVED', decided_by = 'user:manager', decided_at = now(),
           business_date = (SELECT current_date_ FROM tenancy.business_date), gl_refunds_payable = '2450-TEST-REFUNDS', refund_total = 0
     WHERE exit_id = 'e1000000-0000-0000-0000-000000000005';
    RAISE EXCEPTION 'TEST FAILED MX2f';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'EXIT_BLOCKED%shares to transfer%' THEN RAISE; END IF;
  END;
  IF NOT ('2000.00 in shares to transfer to another member' = ANY (membership.blockers(tenancy.current_tenant(),
            'c0000000-0000-0000-0000-0000000000a5'))) THEN
    RAISE EXCEPTION 'TEST FAILED MX2g: %', membership.blockers(tenancy.current_tenant(), 'c0000000-0000-0000-0000-0000000000a5');
  END IF;
  IF cardinality(membership.blockers(tenancy.current_tenant(), 'c0000000-0000-0000-0000-0000000000a6')) <> 0 THEN
    RAISE EXCEPTION 'TEST FAILED MX2h';
  END IF;
END $$;
UPDATE membership.exit_request SET status = 'CANCELLED', decided_by = 'user:officer', decided_at = now(),
       decision_reason = 'Member will transfer shares first' WHERE exit_id = 'e1000000-0000-0000-0000-000000000005';
COMMIT;
\echo PASS MX2 one open request, notice copied and served, asker cannot approve, nothing outstanding; blockers named

-- ---------- MX3 settlement ----------
-- an entry that does not credit refunds payable exactly is refused at commit
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  PERFORM pg_temp.settle('e1000000-0000-0000-0000-000000000006', 'user:manager', 36000);
  SET CONSTRAINTS membership.exit_request_settled IMMEDIATE;
  RAISE EXCEPTION 'TEST FAILED MX3a';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'EXIT_POSTING_MISMATCH%' THEN RAISE; END IF;
END $$;
ROLLBACK;

-- an account settled at the wrong balance is refused
BEGIN;
SET LOCAL app.tenant_id = :tA;
UPDATE membership.exit_request SET status = 'APPROVED', decided_by = 'user:manager', decided_at = now(),
       business_date = (SELECT current_date_ FROM tenancy.business_date), gl_refunds_payable = '2450-TEST-REFUNDS', refund_total = 1
 WHERE exit_id = 'e1000000-0000-0000-0000-000000000006';
DO $$
BEGIN
  INSERT INTO membership.exit_account (tenant_id, exit_id, account_id, balance, interest_gross, interest_tax, refund)
  VALUES (tenancy.current_tenant(), 'e1000000-0000-0000-0000-000000000006', 'ab000000-0000-0000-0000-0000000000e1', 1, 0, 0, 1);
  RAISE EXCEPTION 'TEST FAILED MX3b';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'EXIT_ACCOUNT_INVALID%' THEN RAISE; END IF;
END $$;
ROLLBACK;

BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
DECLARE
  v_total numeric;
  v_expected numeric;
  v_interest numeric := round(2 * (SELECT interest_rate FROM savings.deposit_product WHERE product_code = 'SAVT'), 2);
BEGIN
  v_total := pg_temp.settle('e1000000-0000-0000-0000-000000000006', 'user:manager', NULL);
  v_expected := 36500 + v_interest - round(v_interest * (SELECT withholding_tax_rate FROM savings.deposit_product WHERE product_code = 'SAVT') / 100, 2);
  IF v_total <> v_expected THEN
    RAISE EXCEPTION 'TEST FAILED MX3c: refund % expected %', v_total, v_expected;
  END IF;
END $$;
COMMIT;
\echo PASS MX3 approval settles the balance and unpaid interest less tax into refunds payable, accounts closed, entry exact

-- ---------- MX4 exited ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  BEGIN
    UPDATE customer.customer SET status = 'ACTIVE' WHERE customer_id = 'c0000000-0000-0000-0000-0000000000a6';
    RAISE EXCEPTION 'TEST FAILED MX4a';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'CUSTOMER_EXITED%' THEN RAISE; END IF;
  END;
  BEGIN
    UPDATE customer.customer SET status = 'EXITED' WHERE customer_id = 'c0000000-0000-0000-0000-0000000000a7';
    RAISE EXCEPTION 'TEST FAILED MX4b';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'CUSTOMER_STATUS%' THEN RAISE; END IF;
  END;
  BEGIN
    INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
    SELECT tenancy.current_tenant(), 'e0000000-0000-0000-0000-000000000009', 'exit-test-after', current_date_, 'TELLER', 'c', 'user:teller'
      FROM tenancy.business_date;
    INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code, subledger_account_id)
    VALUES (tenancy.current_tenant(), 'e0000000-0000-0000-0000-000000000009', 1, '2111-TEST-SAVINGS', 'C', 10, 'KES', 'NBO01',
            'ab000000-0000-0000-0000-0000000000e1');
    RAISE EXCEPTION 'TEST FAILED MX4c';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'SAVINGS_ACCOUNT_CLOSED%' THEN RAISE; END IF;
  END;
  BEGIN
    UPDATE membership.exit_settings SET gl_refunds_payable = '2410-TEST-DIV-PAYABLE';
    RAISE EXCEPTION 'TEST FAILED MX4d';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'EXIT_SETTINGS_LOCKED%' THEN RAISE; END IF;
  END;
END $$;
COMMIT;
\echo PASS MX4 a member who has left stays EXITED, only an approved exit gets there, closed accounts take nothing

-- ---------- MX5 refund payment ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
DECLARE
  v_total numeric := (SELECT refund_total FROM membership.exit_request WHERE exit_id = 'e1000000-0000-0000-0000-000000000006');
BEGIN
  BEGIN
    INSERT INTO membership.refund_payment (tenant_id, payment_id, idempotency_key, exit_id, amount, method, paid_from, reference, requested_by)
    VALUES (tenancy.current_tenant(), gen_random_uuid(), 'rp0', 'e1000000-0000-0000-0000-000000000006', v_total, 'BANK_TRANSFER',
            '2111-TEST-SAVINGS', 'EFT 001', 'user:fin');
    RAISE EXCEPTION 'TEST FAILED MX5a';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'REFUND_INVALID%' THEN RAISE; END IF;
  END;
  BEGIN
    INSERT INTO membership.refund_payment (tenant_id, payment_id, idempotency_key, exit_id, amount, method, paid_from, reference, requested_by)
    VALUES (tenancy.current_tenant(), gen_random_uuid(), 'rp0', 'e1000000-0000-0000-0000-000000000006', v_total - 1, 'BANK_TRANSFER',
            '1010-TEST-BANK', 'EFT 001', 'user:fin');
    RAISE EXCEPTION 'TEST FAILED MX5b';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'REFUND_INVALID%' THEN RAISE; END IF;
  END;
  INSERT INTO membership.refund_payment (tenant_id, payment_id, idempotency_key, exit_id, amount, method, paid_from, reference, requested_by)
  VALUES (tenancy.current_tenant(), 'e2000000-0000-0000-0000-000000000001', 'rp1', 'e1000000-0000-0000-0000-000000000006', v_total,
          'BANK_TRANSFER', '1010-TEST-BANK', 'EFT 001', 'user:fin');
  BEGIN
    UPDATE membership.refund_payment SET status = 'REJECTED', decided_by = 'user:fin', decided_at = now(), decision_reason = 'wrong'
     WHERE payment_id = 'e2000000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED MX5c';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'EXIT_SAME_PERSON%' THEN RAISE; END IF;
  END;
  -- nothing else posts to refunds payable
  BEGIN
    INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
    SELECT tenancy.current_tenant(), 'e0000000-0000-0000-0000-00000000000a', 'exit-test-direct', current_date_, 'MANUAL', 'c', 'user:fin'
      FROM tenancy.business_date;
    INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
      (tenancy.current_tenant(), 'e0000000-0000-0000-0000-00000000000a', 1, '2450-TEST-REFUNDS', 'D', 10, 'KES', 'NBO01'),
      (tenancy.current_tenant(), 'e0000000-0000-0000-0000-00000000000a', 2, '1010-TEST-BANK', 'C', 10, 'KES', 'NBO01');
    SET CONSTRAINTS ledger.journal_line_member_refunds IMMEDIATE;
    RAISE EXCEPTION 'TEST FAILED MX5d';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'EXIT_DIRECT_POSTING%' THEN RAISE; END IF;
  END;
END $$;
COMMIT;
\echo PASS MX5a refunds paid in full from an ASSET account, no direct postings to refunds payable

BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
DECLARE
  v_total numeric := (SELECT refund_total FROM membership.exit_request WHERE exit_id = 'e1000000-0000-0000-0000-000000000006');
BEGIN
  INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
  SELECT tenancy.current_tenant(), 'e0000000-0000-0000-0000-00000000000b', 'exit-test-refund', current_date_, 'MEMBERSHIP', 'c', 'user:fin2'
    FROM tenancy.business_date;
  INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
    (tenancy.current_tenant(), 'e0000000-0000-0000-0000-00000000000b', 1, '2450-TEST-REFUNDS', 'D', v_total, 'KES', 'NBO01'),
    (tenancy.current_tenant(), 'e0000000-0000-0000-0000-00000000000b', 2, '1010-TEST-BANK', 'C', v_total, 'KES', 'NBO01');
  UPDATE membership.refund_payment SET status = 'APPROVED', decided_by = 'user:fin2', decided_at = now(),
         business_date = (SELECT current_date_ FROM tenancy.business_date), entry_id = 'e0000000-0000-0000-0000-00000000000b'
   WHERE payment_id = 'e2000000-0000-0000-0000-000000000001';
END $$;
COMMIT;

BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  IF (SELECT COALESCE(sum(CASE side WHEN 'C' THEN amount ELSE -amount END), 0) FROM ledger.journal_line
       WHERE account_code = '2450-TEST-REFUNDS') <> 0 THEN
    RAISE EXCEPTION 'TEST FAILED MX5e: refunds payable should be empty once paid';
  END IF;
  BEGIN
    INSERT INTO membership.refund_payment (tenant_id, payment_id, idempotency_key, exit_id, amount, method, paid_from, reference, requested_by)
    SELECT tenancy.current_tenant(), gen_random_uuid(), 'rp2', exit_id, refund_total, 'CASH', '1010-TEST-BANK', 'Paid twice', 'user:fin'
      FROM membership.exit_request WHERE exit_id = 'e1000000-0000-0000-0000-000000000006';
    RAISE EXCEPTION 'TEST FAILED MX5f';
  EXCEPTION WHEN unique_violation THEN NULL;
  END;
  -- with the refund paid the refunds account may change
  UPDATE membership.exit_settings SET gl_refunds_payable = '2410-TEST-DIV-PAYABLE', updated_by = 'user:fin';
  UPDATE membership.exit_settings SET gl_refunds_payable = '2450-TEST-REFUNDS', updated_by = 'user:fin';
END $$;
COMMIT;
\echo PASS MX5 refund approved by another person, paid once, refunds payable back to zero

BEGIN;
SET LOCAL app.tenant_id = :tB;
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM membership.exit_request) OR EXISTS (SELECT 1 FROM membership.exit_settings) THEN
    RAISE EXCEPTION 'TEST FAILED MX-RLS';
  END IF;
END $$;
COMMIT;
\echo PASS MX-RLS exits are invisible to other tenants
