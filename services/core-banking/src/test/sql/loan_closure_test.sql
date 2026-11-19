-- =============================================================================
-- Tests for V20__loan_closure_and_credit.sql, run as mfi_app after loan_origination_test.sql.
-- Tenant A's business date is 2026-11-20. Loan P (6,000 over three installments of 2,000, no
-- interest, all due) is repaid with 7,000: 1,000 is held as credit.
-- =============================================================================
\set ON_ERROR_STOP on
\set QUIET on
\set tA '''00000000-0000-0000-0000-0000000000a1'''
\set tB '''00000000-0000-0000-0000-0000000000b2'''
\set loan '''bbbbbbbb-0000-0000-0000-0000000000c1'''
\set v1 '''cccccccc-0000-0000-0000-000000000001'''

BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO lending.loan (tenant_id, loan_id, loan_number, customer_ref, product_version_id, branch_code, currency,
                          principal, term_months, created_by, approved_by)
VALUES (:tA::uuid, :loan::uuid, 'LN-P00001', 'CUST-P', :v1::uuid, 'NBO01', 'KES', 6000, 3, 'user:officer', 'user:manager');
INSERT INTO lending.schedule_installment (tenant_id, loan_id, schedule_version, installment_no, due_date, principal, interest) VALUES
  (:tA::uuid, :loan::uuid, 1, 1, '2026-11-18', 2000, 0),
  (:tA::uuid, :loan::uuid, 1, 2, '2026-11-19', 2000, 0),
  (:tA::uuid, :loan::uuid, 1, 3, '2026-11-20', 2000, 0);
INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
VALUES (:tA::uuid, 'dc000000-0000-0000-0000-000000000001', 'loan:bbbbbbbb-0000-0000-0000-0000000000c1:DISBURSE:P', '2026-11-20',
        'LOAN', 'c', 'u');
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
  (:tA::uuid, 'dc000000-0000-0000-0000-000000000001', 1, '1200-LOANS-PRINCIPAL', 'D', 6000, 'KES', 'NBO01'),
  (:tA::uuid, 'dc000000-0000-0000-0000-000000000001', 2, '1120-MPESA-C2B-CLEARING', 'C', 6000, 'KES', 'NBO01');
UPDATE lending.loan SET status = 'ACTIVE', disbursement_entry_id = 'dc000000-0000-0000-0000-000000000001' WHERE loan_id = :loan::uuid;
COMMIT;

BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  BEGIN
    INSERT INTO lending.loan_closure (tenant_id, loan_id, closed_on, reason, credit_balance, closed_by)
    VALUES ('00000000-0000-0000-0000-0000000000a1', 'bbbbbbbb-0000-0000-0000-0000000000c1', '2026-11-20', 'REPAID', 0, 'u');
    RAISE EXCEPTION 'TEST FAILED P1a: closed while unpaid';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'LENDING_NOT_REPAID%' THEN RAISE; END IF;
  END;
  BEGIN
    UPDATE lending.loan SET status = 'CLOSED' WHERE loan_id = 'bbbbbbbb-0000-0000-0000-0000000000c1';
    RAISE EXCEPTION 'TEST FAILED P1b: status closed without closure';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'LENDING_NOT_REPAID%' THEN RAISE; END IF;
  END;
END $$;
COMMIT;

-- Repayment of 7,000 and closure in one transaction (P3: the commit check accepts the closed loan).
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
VALUES (:tA::uuid, 'dc000000-0000-0000-0000-000000000002', 'loan:bbbbbbbb-0000-0000-0000-0000000000c1:REPAY:P1', '2026-11-20',
        'LOAN', 'c', 'u');
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
  (:tA::uuid, 'dc000000-0000-0000-0000-000000000002', 1, '1120-MPESA-C2B-CLEARING', 'D', 7000, 'KES', 'NBO01'),
  (:tA::uuid, 'dc000000-0000-0000-0000-000000000002', 2, '1200-LOANS-PRINCIPAL', 'C', 6000, 'KES', 'NBO01'),
  (:tA::uuid, 'dc000000-0000-0000-0000-000000000002', 3, '2400-LOAN-OVERPAYMENTS', 'C', 1000, 'KES', 'NBO01');
INSERT INTO lending.repayment (tenant_id, repayment_id, loan_id, amount, unallocated, currency, channel, provider_ref,
                               journal_entry_id, received_at)
VALUES (:tA::uuid, 'dc000000-0000-0000-0000-0000000000a1', :loan::uuid, 7000, 1000, 'KES', 'MPESA_C2B', 'P-RCPT-1',
        'dc000000-0000-0000-0000-000000000002', now());
INSERT INTO lending.repayment_allocation (tenant_id, repayment_id, line_no, installment_no, component, amount) VALUES
  (:tA::uuid, 'dc000000-0000-0000-0000-0000000000a1', 1, 1, 'PRINCIPAL', 2000),
  (:tA::uuid, 'dc000000-0000-0000-0000-0000000000a1', 2, 2, 'PRINCIPAL', 2000),
  (:tA::uuid, 'dc000000-0000-0000-0000-0000000000a1', 3, 3, 'PRINCIPAL', 2000);
DO $$
BEGIN
  BEGIN
    INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
    VALUES ('00000000-0000-0000-0000-0000000000a1', 'dc000000-0000-0000-0000-000000000003',
            'loan:bbbbbbbb-0000-0000-0000-0000000000c1:REPAY:CREDIT:x', '2026-11-20', 'LOAN', 'c', 'u');
    INSERT INTO lending.repayment (tenant_id, repayment_id, loan_id, amount, unallocated, currency, channel, provider_ref,
                                   journal_entry_id, received_at)
    VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'bbbbbbbb-0000-0000-0000-0000000000c1', 1500, 0, 'KES',
            'INTERNAL', 'CREDIT:LN-P00001:x', 'dc000000-0000-0000-0000-000000000003', now());
    RAISE EXCEPTION 'TEST FAILED P4: applied more credit than held';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'LENDING_CREDIT_EXCEEDED%' THEN RAISE; END IF;
  END;
  BEGIN
    INSERT INTO lending.loan_closure (tenant_id, loan_id, closed_on, reason, credit_balance, closed_by)
    VALUES ('00000000-0000-0000-0000-0000000000a1', 'bbbbbbbb-0000-0000-0000-0000000000c1', '2026-11-20', 'REPAID', 0, 'u');
    RAISE EXCEPTION 'TEST FAILED P2: closure without the credit held';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'LENDING_CLOSURE_MISMATCH%' THEN RAISE; END IF;
  END;
END $$;
INSERT INTO lending.loan_closure (tenant_id, loan_id, closed_on, reason, credit_balance, closed_by)
VALUES (:tA::uuid, :loan::uuid, '2026-11-20', 'REPAID', 1000, 'user:system');
UPDATE lending.loan SET status = 'CLOSED' WHERE loan_id = :loan::uuid;
COMMIT;
\echo PASS P1 P2 P4 closes only when fully repaid, with the credit held; credit cannot be over-applied

BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  BEGIN
    INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
    VALUES ('00000000-0000-0000-0000-0000000000a1', 'dc000000-0000-0000-0000-000000000004',
            'loan:bbbbbbbb-0000-0000-0000-0000000000c1:REPAY:P2', '2026-11-20', 'LOAN', 'c', 'u');
    INSERT INTO lending.repayment (tenant_id, repayment_id, loan_id, amount, unallocated, currency, channel, provider_ref,
                                   journal_entry_id, received_at)
    VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'bbbbbbbb-0000-0000-0000-0000000000c1', 100, 100, 'KES',
            'MPESA_C2B', 'P-RCPT-2', 'dc000000-0000-0000-0000-000000000004', now());
    RAISE EXCEPTION 'TEST FAILED P3: repayment on a closed loan';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'LENDING_LOAN_NOT_ACTIVE%' THEN RAISE; END IF;
  END;
  BEGIN
    DELETE FROM lending.loan_closure;
    RAISE EXCEPTION 'TEST FAILED P5';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
END $$;
COMMIT;
BEGIN;
SET LOCAL app.tenant_id = :tB;
DO $$
BEGIN
  IF (SELECT count(*) FROM lending.loan_closure) <> 0 THEN RAISE EXCEPTION 'TEST FAILED P6'; END IF;
END $$;
COMMIT;
\echo PASS P3 P5 P6 no repayment after closure; closures are final; tenant B sees none
