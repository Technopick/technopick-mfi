-- =============================================================================
-- Tests for V35__mpesa_statement_reconciliation.sql, run as mfi_app at the end of the suite.
-- Uses its own TEST-ONLY paybill 600111 and the B2C short code 600000 from mpesa_b2c_test.sql.
-- The statement date D is tenant A's business date when the test starts; the day is closed before sign-off.
-- =============================================================================
\set ON_ERROR_STOP on
\set QUIET on
\set tA '''00000000-0000-0000-0000-0000000000a1'''
\set tB '''00000000-0000-0000-0000-0000000000b2'''

-- ---------- Setup: paybill, entries on D, payments ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO ledger.gl_account (tenant_id, account_code, name, account_class, currency) VALUES
  (:tA::uuid, '1121-RECON-CLEARING', 'TEST recon paybill clearing', 'ASSET', 'KES'),
  (:tA::uuid, '2901-RECON-SUSPENSE', 'TEST recon suspense', 'LIABILITY', 'KES'),
  (:tA::uuid, '5310-RECON-CHARGES', 'TEST M-Pesa charges', 'EXPENSE', 'KES');
INSERT INTO payments.mpesa_paybill (tenant_id, short_code, branch_code, gl_clearing, gl_suspense)
VALUES (:tA::uuid, '600111', 'NBO01', '1121-RECON-CLEARING', '2901-RECON-SUSPENSE');
CREATE TEMP TABLE d ON COMMIT DROP AS SELECT current_date_ AS d FROM tenancy.business_date;
INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, switch_reference, actor)
SELECT :tA::uuid, ('a7ec0000-0000-0000-0000-00000000000' || n)::uuid, 'recon-test-' || n, d.d, 'MPESA', 'c', 'RCN000000' || n, 'provider:mpesa'
  FROM generate_series(1, 4) n, d;
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
  (:tA::uuid, 'a7ec0000-0000-0000-0000-000000000001', 1, '1121-RECON-CLEARING', 'D', 1000, 'KES', 'NBO01'),
  (:tA::uuid, 'a7ec0000-0000-0000-0000-000000000001', 2, '2901-RECON-SUSPENSE', 'C', 1000, 'KES', 'NBO01'),
  (:tA::uuid, 'a7ec0000-0000-0000-0000-000000000002', 1, '1121-RECON-CLEARING', 'D', 500, 'KES', 'NBO01'),
  (:tA::uuid, 'a7ec0000-0000-0000-0000-000000000002', 2, '2901-RECON-SUSPENSE', 'C', 500, 'KES', 'NBO01'),
  (:tA::uuid, 'a7ec0000-0000-0000-0000-000000000003', 1, '1121-RECON-CLEARING', 'D', 200, 'KES', 'NBO01'),
  (:tA::uuid, 'a7ec0000-0000-0000-0000-000000000003', 2, '2901-RECON-SUSPENSE', 'C', 200, 'KES', 'NBO01'),
  -- entry 4: finance books the day's M-Pesa charges (15 + 18)
  (:tA::uuid, 'a7ec0000-0000-0000-0000-000000000004', 1, '5310-RECON-CHARGES', 'D', 33, 'KES', 'NBO01'),
  (:tA::uuid, 'a7ec0000-0000-0000-0000-000000000004', 2, '1121-RECON-CLEARING', 'C', 33, 'KES', 'NBO01');
INSERT INTO payments.provider_payment (tenant_id, payment_id, provider, provider_ref, short_code, amount, currency,
                                       provider_time, status, journal_entry_id)
SELECT :tA::uuid, ('a8ec0000-0000-0000-0000-00000000000' || n)::uuid, 'MPESA_C2B', 'RCN000000' || n, '600111',
       (ARRAY[1000, 500, 200])[n], 'KES', now(), 'UNMATCHED', ('a7ec0000-0000-0000-0000-00000000000' || n)::uuid
  FROM generate_series(1, 3) n;
COMMIT;
\echo PASS RC0 paybill, three receipts and a charges entry on D

-- ---------- R1: uploads are for configured accounts, not in the future, one per day ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
DECLARE v_d date;
BEGIN
  SELECT current_date_ INTO v_d FROM tenancy.business_date;
  BEGIN
    INSERT INTO payments.statement_upload (tenant_id, upload_id, account_kind, short_code, gl_clearing, statement_date, file_name,
        file_sha256, line_count, opening_balance, closing_balance, total_paid_in, total_withdrawn, balance_chain_ok, uploaded_by)
    VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'PAYBILL', '600111', '2901-RECON-SUSPENSE', v_d, 'x.csv',
            repeat('a', 64), 0, 0, 0, 0, 0, true, 'user:fin-1');
    RAISE EXCEPTION 'TEST FAILED R1a';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'STATEMENT_INVALID%' THEN RAISE; END IF;
  END;
  BEGIN
    INSERT INTO payments.statement_upload (tenant_id, upload_id, account_kind, short_code, gl_clearing, statement_date, file_name,
        file_sha256, line_count, opening_balance, closing_balance, total_paid_in, total_withdrawn, balance_chain_ok, uploaded_by)
    VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'PAYBILL', '600111', '1121-RECON-CLEARING', v_d + 2,
            'x.csv', repeat('a', 64), 0, 0, 0, 0, 0, true, 'user:fin-1');
    RAISE EXCEPTION 'TEST FAILED R1b';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'STATEMENT_INVALID%' THEN RAISE; END IF;
  END;
  BEGIN
    INSERT INTO payments.statement_upload (tenant_id, upload_id, account_kind, short_code, gl_clearing, statement_date, file_name,
        file_sha256, line_count, opening_balance, closing_balance, total_paid_in, total_withdrawn, balance_chain_ok, uploaded_by)
    VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'PAYBILL', '600111', '1121-RECON-CLEARING', v_d,
            'x.csv', repeat('a', 64), 0, 0, 10, 0, 0, true, 'user:fin-1');
    RAISE EXCEPTION 'TEST FAILED R1c';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
END $$;
INSERT INTO payments.statement_upload (tenant_id, upload_id, account_kind, short_code, gl_clearing, statement_date, file_name,
    file_sha256, line_count, opening_balance, closing_balance, total_paid_in, total_withdrawn, balance_chain_ok, uploaded_by)
SELECT :tA::uuid, 'a9ec0000-0000-0000-0000-000000000001', 'PAYBILL', '600111', '1121-RECON-CLEARING', current_date_,
       'paybill.csv', repeat('b', 64), 5, 0, 1517, 1550, 33, true, 'user:fin-1'
  FROM tenancy.business_date;
INSERT INTO payments.statement_line (tenant_id, upload_id, line_no, receipt_no, completed_at, details, txn_status, paid_in,
                                     withdrawn, reason_type, category, status, exception_code, payment_id) VALUES
  (:tA::uuid, 'a9ec0000-0000-0000-0000-000000000001', 1, 'RCN0000001', now(), 'Pay Bill from 2547****001', 'Completed', 1000, 0,
   'Pay Bill', 'RECEIPT', 'MATCHED', NULL, 'a8ec0000-0000-0000-0000-000000000001'),
  (:tA::uuid, 'a9ec0000-0000-0000-0000-000000000001', 2, 'RCN0000002', now(), 'Pay Bill from 2547****002', 'Completed', 550, 0,
   'Pay Bill', 'RECEIPT', 'EXCEPTION', 'AMOUNT_MISMATCH', NULL),
  (:tA::uuid, 'a9ec0000-0000-0000-0000-000000000001', 3, 'RCN0000001', now(), 'Pay Bill Charge', 'Completed', 0, 15,
   'Pay Bill Charge', 'CHARGE', 'EXCEPTION', 'NOT_IN_LEDGER', NULL),
  (:tA::uuid, 'a9ec0000-0000-0000-0000-000000000001', 4, 'RCN0000002', now(), 'Pay Bill Charge', 'Completed', 0, 18,
   'Pay Bill Charge', 'CHARGE', 'EXCEPTION', 'NOT_IN_LEDGER', NULL),
  (:tA::uuid, 'a9ec0000-0000-0000-0000-000000000001', 5, 'RCN0000009', now(), 'Pay Bill from 2547****009', 'Failed', 100, 0,
   'Pay Bill', 'RECEIPT', 'IGNORED', NULL, NULL);
DO $$
DECLARE v_d date;
BEGIN
  SELECT current_date_ INTO v_d FROM tenancy.business_date;
  INSERT INTO payments.statement_upload (tenant_id, upload_id, account_kind, short_code, gl_clearing, statement_date, file_name,
      file_sha256, line_count, opening_balance, closing_balance, total_paid_in, total_withdrawn, balance_chain_ok, uploaded_by)
  VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'PAYBILL', '600111', '1121-RECON-CLEARING', v_d,
          'again.csv', repeat('c', 64), 0, 0, 0, 0, 0, true, 'user:fin-1');
  RAISE EXCEPTION 'TEST FAILED R1d';
EXCEPTION WHEN unique_violation THEN NULL;
END $$;
COMMIT;
\echo PASS R1 statements need the configured clearing account, a past or current date, adding balances and one per day

-- ---------- R2/R3: lines are kept as uploaded; matches are checked and unique ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  BEGIN
    UPDATE payments.statement_line SET status = 'MATCHED', exception_code = NULL, payment_id = 'a8ec0000-0000-0000-0000-000000000002'
     WHERE upload_id = 'a9ec0000-0000-0000-0000-000000000001' AND line_no = 2;
    RAISE EXCEPTION 'TEST FAILED R3a';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'STATEMENT_MATCH_INVALID%' THEN RAISE; END IF;
  END;
  BEGIN
    INSERT INTO payments.statement_line (tenant_id, upload_id, line_no, receipt_no, completed_at, details, txn_status, paid_in,
                                         withdrawn, category, status, payment_id)
    VALUES ('00000000-0000-0000-0000-0000000000a1', 'a9ec0000-0000-0000-0000-000000000001', 6, 'RCN0000001', now(), 'dup',
            'Completed', 1000, 0, 'RECEIPT', 'MATCHED', 'a8ec0000-0000-0000-0000-000000000001');
    RAISE EXCEPTION 'TEST FAILED R3b';
  EXCEPTION WHEN unique_violation THEN NULL;
  END;
  BEGIN
    UPDATE payments.statement_line SET paid_in = 999 WHERE upload_id = 'a9ec0000-0000-0000-0000-000000000001' AND line_no = 2;
    RAISE EXCEPTION 'TEST FAILED R2a';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'STATEMENT_IMMUTABLE%' THEN RAISE; END IF;
  END;
  BEGIN
    UPDATE payments.statement_line SET status = 'EXCEPTION', exception_code = 'NOT_IN_LEDGER', payment_id = NULL
     WHERE upload_id = 'a9ec0000-0000-0000-0000-000000000001' AND line_no = 1;
    RAISE EXCEPTION 'TEST FAILED R2b';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'STATEMENT_IMMUTABLE%' THEN RAISE; END IF;
  END;
  BEGIN
    DELETE FROM payments.statement_line WHERE upload_id = 'a9ec0000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED R2c';
  EXCEPTION WHEN insufficient_privilege OR raise_exception THEN
    IF SQLSTATE = 'P0001' AND SQLERRM NOT LIKE 'STATEMENT_IMMUTABLE%' THEN RAISE; END IF;
  END;
END $$;
COMMIT;
\echo PASS R2 R3 statement lines immutable; a match needs the same receipt and amount, once

-- ---------- R4: resolutions ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  BEGIN
    UPDATE payments.statement_line SET status = 'RESOLVED', resolution = 'EXPLAINED', resolved_by = 'user:fin-2',
           resolved_at = now(), resolution_note = 'short'
     WHERE upload_id = 'a9ec0000-0000-0000-0000-000000000001' AND line_no = 2;
    RAISE EXCEPTION 'TEST FAILED R4a';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    -- one charge alone does not account for the 33.00 entry
    UPDATE payments.statement_line SET status = 'RESOLVED', resolution = 'LINKED_JOURNAL', resolved_by = 'user:fin-2',
           resolved_at = now(), resolution_note = 'Charges booked by journal',
           journal_entry_id = 'a7ec0000-0000-0000-0000-000000000004'
     WHERE upload_id = 'a9ec0000-0000-0000-0000-000000000001' AND line_no = 3;
    SET CONSTRAINTS ALL IMMEDIATE;
    RAISE EXCEPTION 'TEST FAILED R4b';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'STATEMENT_LINK_INVALID%' THEN RAISE; END IF;
  END;
  SET CONSTRAINTS ALL DEFERRED;
  BEGIN
    -- a receipt's own entry is matched, never linked
    UPDATE payments.statement_line SET status = 'RESOLVED', resolution = 'LINKED_JOURNAL', resolved_by = 'user:fin-2',
           resolved_at = now(), resolution_note = 'Same receipt, amount differs',
           journal_entry_id = 'a7ec0000-0000-0000-0000-000000000002'
     WHERE upload_id = 'a9ec0000-0000-0000-0000-000000000001' AND line_no = 2;
    SET CONSTRAINTS ALL IMMEDIATE;
    RAISE EXCEPTION 'TEST FAILED R4c';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'STATEMENT_LINK_INVALID%' THEN RAISE; END IF;
  END;
  SET CONSTRAINTS ALL DEFERRED;
END $$;
UPDATE payments.statement_line SET status = 'RESOLVED', resolution = 'LINKED_JOURNAL', resolved_by = 'user:fin-2',
       resolved_at = now(), resolution_note = 'Charges booked by journal', journal_entry_id = 'a7ec0000-0000-0000-0000-000000000004'
 WHERE upload_id = 'a9ec0000-0000-0000-0000-000000000001' AND line_no IN (3, 4);
COMMIT;
\echo PASS R4 resolutions need a note; linked lines must add up to the entry; receipts are not linked

-- ---------- R5: sign-off ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  UPDATE payments.statement_upload SET status = 'SIGNED_OFF', signed_off_by = 'user:sup-1', signed_off_at = now(),
         ledger_balance = 1667, difference = -150, ledger_only_count = 2, ledger_only_amount = 700, note = 'Reviewed today'
   WHERE upload_id = 'a9ec0000-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'TEST FAILED R5 day open';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'RECON_DAY_OPEN%' THEN RAISE; END IF;
END $$;
-- close D (end of day) and move to D + 1
INSERT INTO ledger.eod_run (tenant_id, business_date, status) SELECT :tA::uuid, current_date_, 'RUNNING' FROM tenancy.business_date;
UPDATE ledger.eod_run SET status = 'COMPLETED', finished_at = clock_timestamp()
 WHERE business_date = (SELECT current_date_ FROM tenancy.business_date);
UPDATE tenancy.business_date SET current_date_ = current_date_ + 1;
COMMIT;

-- ---------- R5: sign-off ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
DECLARE
  v_ledger numeric; v_count int; v_amount numeric;
BEGIN
  v_ledger := payments.clearing_balance('00000000-0000-0000-0000-0000000000a1', '1121-RECON-CLEARING',
                                        (SELECT current_date_ - 1 FROM tenancy.business_date));
  IF v_ledger <> 1667 THEN RAISE EXCEPTION 'TEST FAILED R5 ledger balance %', v_ledger; END IF;
  BEGIN
    UPDATE payments.statement_upload SET status = 'SIGNED_OFF', signed_off_by = 'user:sup-1', signed_off_at = now(),
           ledger_balance = 1667, difference = -150, ledger_only_count = 2, ledger_only_amount = 700, note = 'Reviewed today'
     WHERE upload_id = 'a9ec0000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED R5a';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'RECON_EXCEPTIONS_OPEN%' THEN RAISE; END IF;
  END;
  UPDATE payments.statement_line SET status = 'RESOLVED', resolution = 'EXPLAINED', resolved_by = 'user:fin-2',
         resolved_at = now(), resolution_note = 'Member paid 550; ledger shows 500; raised with Safaricom'
   WHERE upload_id = 'a9ec0000-0000-0000-0000-000000000001' AND line_no = 2;
  -- receipts 2 (explained, not matched) and 3 (not on the statement) remain on the ledger only
  SELECT count(*), sum(net) INTO v_count, v_amount
    FROM payments.statement_ledger_only('00000000-0000-0000-0000-0000000000a1', 'a9ec0000-0000-0000-0000-000000000001');
  IF v_count <> 2 OR v_amount <> 700 THEN RAISE EXCEPTION 'TEST FAILED R5 ledger-only % %', v_count, v_amount; END IF;
  BEGIN
    UPDATE payments.statement_upload SET status = 'SIGNED_OFF', signed_off_by = 'user:fin-1', signed_off_at = now(),
           ledger_balance = 1667, difference = -150, ledger_only_count = 2, ledger_only_amount = 700, note = 'Reviewed today'
     WHERE upload_id = 'a9ec0000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED R5b';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    UPDATE payments.statement_upload SET status = 'SIGNED_OFF', signed_off_by = 'user:fin-2', signed_off_at = now(),
           ledger_balance = 1667, difference = -150, ledger_only_count = 2, ledger_only_amount = 700, note = 'Reviewed today'
     WHERE upload_id = 'a9ec0000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED R5c';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'RECON_SAME_PERSON%' THEN RAISE; END IF;
  END;
  BEGIN
    UPDATE payments.statement_upload SET status = 'SIGNED_OFF', signed_off_by = 'user:sup-1', signed_off_at = now(),
           ledger_balance = 1517, difference = 0, ledger_only_count = 0, ledger_only_amount = 0
     WHERE upload_id = 'a9ec0000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED R5d';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'RECON_STALE%' THEN RAISE; END IF;
  END;
  BEGIN
    UPDATE payments.statement_upload SET status = 'SIGNED_OFF', signed_off_by = 'user:sup-1', signed_off_at = now(),
           ledger_balance = 1667, difference = -150, ledger_only_count = 2, ledger_only_amount = 700
     WHERE upload_id = 'a9ec0000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED R5e';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'RECON_NOTE_REQUIRED%' THEN RAISE; END IF;
  END;
END $$;
UPDATE payments.statement_upload SET status = 'SIGNED_OFF', signed_off_by = 'user:sup-1', signed_off_at = now(),
       ledger_balance = 1667, difference = -150, ledger_only_count = 2, ledger_only_amount = 700,
       note = 'Receipt RCN0000003 arrives on the next statement; RCN0000002 amount query open'
 WHERE upload_id = 'a9ec0000-0000-0000-0000-000000000001';
DO $$
BEGIN
  BEGIN
    UPDATE payments.statement_line SET active = false WHERE upload_id = 'a9ec0000-0000-0000-0000-000000000001' AND line_no = 1;
    RAISE EXCEPTION 'TEST FAILED R5f';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'STATEMENT_IMMUTABLE%' THEN RAISE; END IF;
  END;
  BEGIN
    UPDATE payments.statement_upload SET note = 'changed afterwards' WHERE upload_id = 'a9ec0000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED R5g';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'STATEMENT_IMMUTABLE%' THEN RAISE; END IF;
  END;
END $$;
COMMIT;
\echo PASS R5 sign-off needs no open exceptions, another person, current ledger figures and a note for differences

-- ---------- R1: withdrawing a wrong upload ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO payments.statement_upload (tenant_id, upload_id, account_kind, short_code, gl_clearing, statement_date, file_name,
    file_sha256, line_count, opening_balance, closing_balance, total_paid_in, total_withdrawn, balance_chain_ok, uploaded_by)
SELECT :tA::uuid, 'a9ec0000-0000-0000-0000-000000000002', 'B2C', '600000', '1110-MPESA-B2C-CLEARING', current_date_ - 1,
       'b2c.csv', repeat('d', 64), 1, 100, 90, 0, 10, true, 'user:fin-1'
  FROM tenancy.business_date;
INSERT INTO payments.statement_line (tenant_id, upload_id, line_no, receipt_no, completed_at, details, txn_status, paid_in,
                                     withdrawn, category, status, exception_code)
VALUES (:tA::uuid, 'a9ec0000-0000-0000-0000-000000000002', 1, 'B2CX000001', now(), 'Charge', 'Completed', 0, 10, 'CHARGE',
        'EXCEPTION', 'NOT_IN_LEDGER');
DO $$
BEGIN
  UPDATE payments.statement_upload SET status = 'WITHDRAWN', withdrawn_by = 'user:fin-1', withdrawn_at = now(), note = 'wrong file'
   WHERE upload_id = 'a9ec0000-0000-0000-0000-000000000002';
  RAISE EXCEPTION 'TEST FAILED R1e';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'STATEMENT_INVALID%' THEN RAISE; END IF;
END $$;
UPDATE payments.statement_line SET active = false WHERE upload_id = 'a9ec0000-0000-0000-0000-000000000002';
UPDATE payments.statement_upload SET status = 'WITHDRAWN', withdrawn_by = 'user:fin-1', withdrawn_at = now(), note = 'wrong file'
 WHERE upload_id = 'a9ec0000-0000-0000-0000-000000000002';
INSERT INTO payments.statement_upload (tenant_id, upload_id, account_kind, short_code, gl_clearing, statement_date, file_name,
    file_sha256, line_count, opening_balance, closing_balance, total_paid_in, total_withdrawn, balance_chain_ok, uploaded_by)
SELECT :tA::uuid, 'a9ec0000-0000-0000-0000-000000000003', 'B2C', '600000', '1110-MPESA-B2C-CLEARING', current_date_ - 1,
       'b2c-right.csv', repeat('d', 64), 0, 100, 100, 0, 0, true, 'user:fin-1'
  FROM tenancy.business_date;
COMMIT;
\echo PASS R1 a withdrawn statement releases its lines and the day can be uploaded again

-- ---------- RLS ----------
BEGIN;
SET LOCAL app.tenant_id = :tB;
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM payments.statement_upload) OR EXISTS (SELECT 1 FROM payments.statement_line) THEN
    RAISE EXCEPTION 'TEST FAILED RLS';
  END IF;
END $$;
COMMIT;
\echo PASS RC6 statements are invisible to other tenants
