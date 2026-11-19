-- =============================================================================
-- Tests for V21__loan_write_off.sql, run as mfi_app after loan_closure_test.sql. Tenant A's day
-- 2026-11-20 is closed; this test moves to 2026-11-21 and keeps that day's EOD RUNNING. The
-- tenant's write-off policy (owner setup) needs 90 days past due.
-- =============================================================================
\set ON_ERROR_STOP on
\set QUIET on
\set tA '''00000000-0000-0000-0000-0000000000a1'''
\set tB '''00000000-0000-0000-0000-0000000000b2'''
\set loan '''bbbbbbbb-0000-0000-0000-0000000000e1'''
\set ver '''cccccccc-0000-0000-0000-0000000000e1'''

-- A product version with a recovery account, and loan X: 6,000 over three months.
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO lending.loan_product_version (tenant_id, version_id, product_code, version, interest_method, annual_rate,
    min_principal, max_principal, min_term_months, max_term_months, upfront_fee_rate, allocation_strategy, allocation_order,
    gl_principal, gl_interest_receivable, gl_interest_income, gl_fee_receivable, gl_fee_income, gl_penalty_receivable,
    gl_penalty_income, gl_overpayment, gl_write_off, created_by, gl_write_off_recovery)
VALUES (:tA::uuid, :ver::uuid, 'BIASHARA', 7, 'REDUCING_BALANCE', 0.24, 5000, 500000, 3, 24, 0, 'COMPONENT_FIRST',
    ARRAY['PENALTY','FEE','INTEREST','PRINCIPAL'], '1200-LOANS-PRINCIPAL', '1210-INTEREST-RECEIVABLE', '4000-INTEREST-INCOME',
    '1220-FEE-RECEIVABLE', '4100-FEE-PROCESSING', '1230-PENALTY-RECEIVABLE', '4300-PENALTY-INCOME', '2400-LOAN-OVERPAYMENTS',
    '5100-LOAN-WRITE-OFFS', 'user:maker', '4900-TEST-RECOVERIES');
UPDATE lending.loan_product_version SET status = 'ACTIVE', approved_by = 'user:checker' WHERE version_id = :ver::uuid;
INSERT INTO lending.loan (tenant_id, loan_id, loan_number, customer_ref, product_version_id, branch_code, currency,
                          principal, term_months, created_by, approved_by)
VALUES (:tA::uuid, :loan::uuid, 'LN-X00001', 'CUST-X', :ver::uuid, 'NBO01', 'KES', 6000, 3, 'user:officer', 'user:manager');
INSERT INTO lending.schedule_installment (tenant_id, loan_id, schedule_version, installment_no, due_date, principal, interest) VALUES
  (:tA::uuid, :loan::uuid, 1, 1, '2026-05-20', 2000, 120),
  (:tA::uuid, :loan::uuid, 1, 2, '2026-06-20', 2000, 80),
  (:tA::uuid, :loan::uuid, 1, 3, '2026-07-20', 2000, 40);
INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
VALUES (:tA::uuid, 'de000000-0000-0000-0000-0000000000e1', 'loan:bbbbbbbb-0000-0000-0000-0000000000e1:DISBURSE:X', '2026-11-20',
        'LOAN', 'c', 'u');
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
  (:tA::uuid, 'de000000-0000-0000-0000-0000000000e1', 1, '1200-LOANS-PRINCIPAL', 'D', 6000, 'KES', 'NBO01'),
  (:tA::uuid, 'de000000-0000-0000-0000-0000000000e1', 2, '1120-MPESA-C2B-CLEARING', 'C', 6000, 'KES', 'NBO01');
UPDATE lending.loan SET status = 'ACTIVE', disbursement_entry_id = 'de000000-0000-0000-0000-0000000000e1' WHERE loan_id = :loan::uuid;
COMMIT;

-- Not eligible before an end of day shows it 90+ days late.
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  INSERT INTO lending.write_off (tenant_id, write_off_id, idempotency_key, loan_id, reason, days_past_due, requested_by)
  VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'wo-0', 'bbbbbbbb-0000-0000-0000-0000000000e1',
          'Customer unreachable since May', 0, 'user:manager');
  RAISE EXCEPTION 'TEST FAILED X1a: written off without an arrears snapshot';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'WRITE_OFF_NOT_ELIGIBLE%' THEN RAISE; END IF;
END $$;
ROLLBACK;

-- Day 2026-11-21: accrue 240 of interest and snapshot 185 days past due.
BEGIN;
SET LOCAL app.tenant_id = :tA;
UPDATE tenancy.business_date SET current_date_ = '2026-11-21';
INSERT INTO ledger.eod_run (tenant_id, business_date) VALUES (:tA::uuid, '2026-11-21');
INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
VALUES (:tA::uuid, 'de000000-0000-0000-0000-0000000000e2', 'loan:bbbbbbbb-0000-0000-0000-0000000000e1:ACCRUE:2026-11-21',
        '2026-11-21', 'LOAN_EOD', 'c', 'system:eod');
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
  (:tA::uuid, 'de000000-0000-0000-0000-0000000000e2', 1, '1210-INTEREST-RECEIVABLE', 'D', 240, 'KES', 'NBO01'),
  (:tA::uuid, 'de000000-0000-0000-0000-0000000000e2', 2, '4000-INTEREST-INCOME', 'C', 240, 'KES', 'NBO01');
INSERT INTO lending.loan_accrual (tenant_id, loan_id, installment_no, business_date, amount, entry_id) VALUES
  (:tA::uuid, :loan::uuid, 1, '2026-11-21', 120, 'de000000-0000-0000-0000-0000000000e2'),
  (:tA::uuid, :loan::uuid, 2, '2026-11-21', 80, 'de000000-0000-0000-0000-0000000000e2'),
  (:tA::uuid, :loan::uuid, 3, '2026-11-21', 40, 'de000000-0000-0000-0000-0000000000e2');
INSERT INTO lending.arrears_snapshot (tenant_id, loan_id, as_of, days_past_due, bucket, classification, arrears_amount, outstanding_principal)
VALUES (:tA::uuid, :loan::uuid, '2026-11-21', 185, 'DPD_OVER_180', 'LOSS', 6240, 6000);
COMMIT;

BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO lending.write_off (tenant_id, write_off_id, idempotency_key, loan_id, reason, days_past_due, requested_by)
VALUES (:tA::uuid, 'f0000000-0000-0000-0000-000000000001', 'wo-1', :loan::uuid, 'Customer unreachable since May', 185, 'user:manager');
DO $$
BEGIN
  BEGIN
    INSERT INTO lending.write_off (tenant_id, write_off_id, idempotency_key, loan_id, reason, days_past_due, requested_by)
    VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'wo-2', 'bbbbbbbb-0000-0000-0000-0000000000e1',
            'Second request for the loan', 185, 'user:manager');
    RAISE EXCEPTION 'TEST FAILED X1b: two live write-offs';
  EXCEPTION WHEN unique_violation THEN NULL;
  END;
  BEGIN
    INSERT INTO lending.write_off (tenant_id, write_off_id, idempotency_key, loan_id, reason, days_past_due, requested_by)
    VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'wo-3', 'bbbbbbbb-0000-0000-0000-000000000001',
            'Loan with overpaid money', 0, 'user:manager');
    RAISE EXCEPTION 'TEST FAILED X1c';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'WRITE_OFF_%' THEN RAISE; END IF;
  END;
  BEGIN
    UPDATE lending.write_off SET status = 'REJECTED', decided_by = 'user:manager', decision_reason = 'mine'
     WHERE write_off_id = 'f0000000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED X2: requester decided';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
END $$;
COMMIT;

-- Approval: 6,000 principal + 240 interest receivable + 0 penalty.
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
VALUES (:tA::uuid, 'de000000-0000-0000-0000-0000000000e3', 'loan:bbbbbbbb-0000-0000-0000-0000000000e1:WRITEOFF:wo-1',
        '2026-11-21', 'LOAN', 'c', 'user:supervisor');
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
  (:tA::uuid, 'de000000-0000-0000-0000-0000000000e3', 1, '5100-LOAN-WRITE-OFFS', 'D', 6240, 'KES', 'NBO01'),
  (:tA::uuid, 'de000000-0000-0000-0000-0000000000e3', 2, '1200-LOANS-PRINCIPAL', 'C', 6000, 'KES', 'NBO01'),
  (:tA::uuid, 'de000000-0000-0000-0000-0000000000e3', 3, '1210-INTEREST-RECEIVABLE', 'C', 240, 'KES', 'NBO01');
DO $$
BEGIN
  BEGIN
    UPDATE lending.write_off SET status = 'APPROVED', decided_by = 'user:supervisor', principal = 6000, interest = 200,
           penalty = 0, entry_id = 'de000000-0000-0000-0000-0000000000e3'
     WHERE write_off_id = 'f0000000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED X3a: approved with wrong amounts';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'WRITE_OFF_AMOUNTS%' THEN RAISE; END IF;
  END;
  BEGIN
    UPDATE lending.write_off SET status = 'APPROVED', decided_by = 'user:supervisor', principal = 6000, interest = 240,
           penalty = 0, entry_id = 'de000000-0000-0000-0000-0000000000e3'
     WHERE write_off_id = 'f0000000-0000-0000-0000-000000000001';
    SET CONSTRAINTS ALL IMMEDIATE;
    RAISE EXCEPTION 'TEST FAILED X3b: approved without writing the loan off';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'WRITE_OFF_LOAN_STATUS%' THEN RAISE; END IF;
  END;
END $$;
SET CONSTRAINTS ALL DEFERRED;
UPDATE lending.write_off SET status = 'APPROVED', decided_by = 'user:supervisor', principal = 6000, interest = 240,
       penalty = 0, entry_id = 'de000000-0000-0000-0000-0000000000e3'
 WHERE write_off_id = 'f0000000-0000-0000-0000-000000000001';
UPDATE lending.loan SET status = 'WRITTEN_OFF' WHERE loan_id = :loan::uuid;
COMMIT;
\echo PASS X1 X2 X3 eligibility, one live request, second person, exact amounts, loan written off with its approval

-- Recoveries.
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
VALUES (:tA::uuid, 'de000000-0000-0000-0000-0000000000e4', 'recovery:X-RCPT-1', '2026-11-21', 'MPESA', 'c', 'provider:mpesa');
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
  (:tA::uuid, 'de000000-0000-0000-0000-0000000000e4', 1, '1120-MPESA-C2B-CLEARING', 'D', 1000, 'KES', 'NBO01'),
  (:tA::uuid, 'de000000-0000-0000-0000-0000000000e4', 2, '4900-TEST-RECOVERIES', 'C', 1000, 'KES', 'NBO01');
INSERT INTO lending.write_off_recovery (tenant_id, recovery_id, loan_id, amount, channel, provider_ref, entry_id, recorded_by)
VALUES (:tA::uuid, gen_random_uuid(), :loan::uuid, 1000, 'MPESA_C2B', 'X-RCPT-1', 'de000000-0000-0000-0000-0000000000e4', 'provider:mpesa');
DO $$
BEGIN
  BEGIN
    INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
    VALUES ('00000000-0000-0000-0000-0000000000a1', 'de000000-0000-0000-0000-0000000000e5', 'recovery:X-RCPT-2', '2026-11-21',
            'MPESA', 'c', 'provider:mpesa');
    INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
      ('00000000-0000-0000-0000-0000000000a1', 'de000000-0000-0000-0000-0000000000e5', 1, '1120-MPESA-C2B-CLEARING', 'D', 5241, 'KES', 'NBO01'),
      ('00000000-0000-0000-0000-0000000000a1', 'de000000-0000-0000-0000-0000000000e5', 2, '4900-TEST-RECOVERIES', 'C', 5241, 'KES', 'NBO01');
    INSERT INTO lending.write_off_recovery (tenant_id, recovery_id, loan_id, amount, channel, provider_ref, entry_id, recorded_by)
    VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'bbbbbbbb-0000-0000-0000-0000000000e1', 5241, 'MPESA_C2B',
            'X-RCPT-2', 'de000000-0000-0000-0000-0000000000e5', 'provider:mpesa');
    RAISE EXCEPTION 'TEST FAILED X4a: recovered more than written off';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'WRITE_OFF_RECOVERY_EXCEEDED%' THEN RAISE; END IF;
  END;
  BEGIN
    UPDATE lending.write_off SET decision_reason = 'changed';
    RAISE EXCEPTION 'TEST FAILED X2b';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'WRITE_OFF_FINAL%' THEN RAISE; END IF;
  END;
END $$;
COMMIT;
BEGIN;
SET LOCAL app.tenant_id = :tB;
DO $$
BEGIN
  IF (SELECT count(*) FROM lending.write_off) + (SELECT count(*) FROM lending.write_off_recovery) <> 0 THEN
    RAISE EXCEPTION 'TEST FAILED X5';
  END IF;
END $$;
COMMIT;
\echo PASS X4 X5 recoveries only up to what was written off; decisions final; tenant B sees none
