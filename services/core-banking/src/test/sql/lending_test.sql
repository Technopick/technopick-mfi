-- =============================================================================
-- Tests for V3__loan_products_loans_repayments.sql, run as mfi_app after the ledger and
-- savings suites in the same throwaway database. Uses the Phase 0 worked example:
-- KES 100,000, 24% p.a. reducing balance, 12 months, 2% upfront fee.
-- =============================================================================
\set ON_ERROR_STOP on
\set QUIET on
\set tA '''00000000-0000-0000-0000-0000000000a1'''
\set tB '''00000000-0000-0000-0000-0000000000b2'''
\set v1 '''cccccccc-0000-0000-0000-000000000001'''
\set v2 '''cccccccc-0000-0000-0000-000000000002'''
\set loan '''bbbbbbbb-0000-0000-0000-000000000001'''

-- ---------- Setup: GL accounts and product ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO ledger.gl_account (tenant_id, account_code, name, account_class, currency) VALUES
  (:tA::uuid, '1210-INTEREST-RECEIVABLE', 'Interest receivable', 'ASSET',     'KES'),
  (:tA::uuid, '4000-INTEREST-INCOME',     'Interest income',     'INCOME',    'KES'),
  (:tA::uuid, '1220-FEE-RECEIVABLE',      'Fee receivable',      'ASSET',     'KES'),
  (:tA::uuid, '1230-PENALTY-RECEIVABLE',  'Penalty receivable',  'ASSET',     'KES'),
  (:tA::uuid, '4300-PENALTY-INCOME',      'Penalty income',      'INCOME',    'KES'),
  (:tA::uuid, '2400-LOAN-OVERPAYMENTS',   'Loan overpayments',   'LIABILITY', 'KES'),
  (:tA::uuid, '5100-LOAN-WRITE-OFFS',     'Loan write-offs',     'EXPENSE',   'KES'),
  (:tA::uuid, '1120-MPESA-C2B-CLEARING',  'M-Pesa C2B clearing', 'ASSET',     'KES');
INSERT INTO lending.loan_product (tenant_id, product_code, name, currency) VALUES (:tA::uuid, 'BIASHARA', 'Biashara loan', 'KES');
COMMIT;

-- Reusable version insert (status defaults to DRAFT)
\set version_cols 'tenant_id, version_id, product_code, version, interest_method, annual_rate, min_principal, max_principal, min_term_months, max_term_months, upfront_fee_rate, allocation_strategy, allocation_order, gl_principal, gl_interest_receivable, gl_interest_income, gl_fee_receivable, gl_fee_income, gl_penalty_receivable, gl_penalty_income, gl_overpayment, gl_write_off, created_by'
\set version_vals '''BIASHARA'', 1, ''REDUCING_BALANCE'', 0.24, 5000, 500000, 3, 24, 0.02, ''COMPONENT_FIRST'', ARRAY[''PENALTY'',''FEE'',''INTEREST'',''PRINCIPAL''], ''1200-LOANS-PRINCIPAL'', ''1210-INTEREST-RECEIVABLE'', ''4000-INTEREST-INCOME'', ''1220-FEE-RECEIVABLE'', ''4100-FEE-PROCESSING'', ''1230-PENALTY-RECEIVABLE'', ''4300-PENALTY-INCOME'', ''2400-LOAN-OVERPAYMENTS'', ''5100-LOAN-WRITE-OFFS'', ''user:maker'''
\set version_vals2 '''BIASHARA'', 2, ''REDUCING_BALANCE'', 0.24, 5000, 500000, 3, 24, 0.02, ''COMPONENT_FIRST'', ARRAY[''PENALTY'',''FEE'',''INTEREST'',''PRINCIPAL''], ''1200-LOANS-PRINCIPAL'', ''1210-INTEREST-RECEIVABLE'', ''4000-INTEREST-INCOME'', ''1220-FEE-RECEIVABLE'', ''4100-FEE-PROCESSING'', ''1230-PENALTY-RECEIVABLE'', ''4300-PENALTY-INCOME'', ''2400-LOAN-OVERPAYMENTS'', ''5100-LOAN-WRITE-OFFS'', ''user:maker'''

-- ---------- L1: versions cannot be born ACTIVE ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  INSERT INTO lending.loan_product_version (tenant_id, version_id, product_code, version, status, interest_method, annual_rate,
    min_principal, max_principal, min_term_months, max_term_months, allocation_strategy, allocation_order,
    gl_principal, gl_interest_receivable, gl_interest_income, gl_fee_receivable, gl_fee_income, gl_penalty_receivable,
    gl_penalty_income, gl_overpayment, gl_write_off, created_by)
  VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'BIASHARA', 9, 'ACTIVE', 'FLAT', 0.1, 1, 2, 1, 2,
    'COMPONENT_FIRST', ARRAY['PENALTY','FEE','INTEREST','PRINCIPAL'], '1200-LOANS-PRINCIPAL', '1210-INTEREST-RECEIVABLE',
    '4000-INTEREST-INCOME', '1220-FEE-RECEIVABLE', '4100-FEE-PROCESSING', '1230-PENALTY-RECEIVABLE', '4300-PENALTY-INCOME',
    '2400-LOAN-OVERPAYMENTS', '5100-LOAN-WRITE-OFFS', 'user:maker');
  RAISE EXCEPTION 'TEST FAILED L1';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'LENDING_VERSION_NOT_DRAFT%' THEN RAISE; END IF;
END $$;
ROLLBACK;
\echo PASS L1 product version cannot be created ACTIVE

-- ---------- L2/L3: maker-checker activation ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO lending.loan_product_version (:version_cols) VALUES (:tA::uuid, :v1::uuid, :version_vals);
COMMIT;
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  UPDATE lending.loan_product_version SET status = 'ACTIVE', approved_by = 'user:maker'
   WHERE version_id = 'cccccccc-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'TEST FAILED L2';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'LENDING_MAKER_CHECKER%' THEN RAISE; END IF;
END $$;
ROLLBACK;
\echo PASS L2 maker cannot approve own product version
BEGIN;
SET LOCAL app.tenant_id = :tA;
UPDATE lending.loan_product_version SET status = 'ACTIVE', approved_by = 'user:checker' WHERE version_id = :v1::uuid;
DO $$
BEGIN
  IF (SELECT activated_at FROM lending.loan_product_version WHERE version_id = 'cccccccc-0000-0000-0000-000000000001') IS NULL THEN
    RAISE EXCEPTION 'TEST FAILED L3';
  END IF;
END $$;
COMMIT;
\echo PASS L3 checker activates version; activation time stamped

-- ---------- L4: active version is frozen ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  UPDATE lending.loan_product_version SET annual_rate = 0.30 WHERE version_id = 'cccccccc-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'TEST FAILED L4 update';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'LENDING_VERSION_FROZEN%' THEN RAISE; END IF;
END $$;
DO $$
BEGIN
  DELETE FROM lending.loan_product_version WHERE version_id = 'cccccccc-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'TEST FAILED L4 delete';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'LENDING_VERSION_FROZEN%' THEN RAISE; END IF;
END $$;
ROLLBACK;
\echo PASS L4 active version terms cannot be edited or deleted

-- ---------- L5: loans need an ACTIVE version ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO lending.loan_product_version (:version_cols) VALUES (:tA::uuid, :v2::uuid, :version_vals2);
DO $$
BEGIN
  INSERT INTO lending.loan (tenant_id, loan_id, loan_number, customer_ref, product_version_id, branch_code, currency,
                            principal, term_months, created_by, approved_by)
  VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'LN-X', 'CUST-1', 'cccccccc-0000-0000-0000-000000000002',
          'NBO01', 'KES', 10000, 12, 'user:officer', 'user:manager');
  RAISE EXCEPTION 'TEST FAILED L5';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'LENDING_VERSION_NOT_ACTIVE%' THEN RAISE; END IF;
END $$;
COMMIT;
\echo PASS L5 loan on a DRAFT product version rejected

-- ---------- L6/L7: limits and four-eyes on loans ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  INSERT INTO lending.loan (tenant_id, loan_id, loan_number, customer_ref, product_version_id, branch_code, currency,
                            principal, term_months, created_by, approved_by)
  VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'LN-Y', 'CUST-1', 'cccccccc-0000-0000-0000-000000000001',
          'NBO01', 'KES', 500000.01, 12, 'user:officer', 'user:manager');
  RAISE EXCEPTION 'TEST FAILED L6';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'LENDING_OUTSIDE_PRODUCT_LIMITS%' THEN RAISE; END IF;
END $$;
DO $$
BEGIN
  INSERT INTO lending.loan (tenant_id, loan_id, loan_number, customer_ref, product_version_id, branch_code, currency,
                            principal, term_months, created_by, approved_by)
  VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'LN-Z', 'CUST-1', 'cccccccc-0000-0000-0000-000000000001',
          'NBO01', 'KES', 10000, 12, 'user:officer', 'user:officer');
  RAISE EXCEPTION 'TEST FAILED L7';
EXCEPTION WHEN check_violation THEN NULL;
END $$;
ROLLBACK;
\echo PASS L6 loan above product maximum rejected
\echo PASS L7 loan approved by its own creator rejected

-- ---------- L8: approved loan with its schedule ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO lending.loan (tenant_id, loan_id, loan_number, customer_ref, product_version_id, branch_code, currency,
                          principal, term_months, created_by, approved_by)
VALUES (:tA::uuid, :loan::uuid, 'LN-000001', 'CUST-1', :v1::uuid, 'NBO01', 'KES', 100000, 12, 'user:officer', 'user:manager');
INSERT INTO lending.schedule_installment (tenant_id, loan_id, schedule_version, installment_no, due_date, principal, interest) VALUES
  (:tA::uuid, :loan::uuid, 1, 1, '2026-12-18', 7455.96, 2000.00),
  (:tA::uuid, :loan::uuid, 1, 2, '2027-01-18', 7605.08, 1850.88),
  (:tA::uuid, :loan::uuid, 1, 3, '2027-02-18', 7757.18, 1698.78),
  (:tA::uuid, :loan::uuid, 1, 4, '2027-03-18', 7912.32, 1543.64),
  (:tA::uuid, :loan::uuid, 1, 5, '2027-04-18', 8070.57, 1385.39),
  (:tA::uuid, :loan::uuid, 1, 6, '2027-05-18', 8231.98, 1223.98),
  (:tA::uuid, :loan::uuid, 1, 7, '2027-06-18', 8396.62, 1059.34),
  (:tA::uuid, :loan::uuid, 1, 8, '2027-07-18', 8564.55, 891.41),
  (:tA::uuid, :loan::uuid, 1, 9, '2027-08-18', 8735.85, 720.11),
  (:tA::uuid, :loan::uuid, 1, 10, '2027-09-18', 8910.56, 545.40),
  (:tA::uuid, :loan::uuid, 1, 11, '2027-10-18', 9088.77, 367.19),
  (:tA::uuid, :loan::uuid, 1, 12, '2027-11-18', 9270.56, 185.41);
COMMIT;
\echo PASS L8 loan and 12-installment schedule (principal sums to 100000.00) accepted

-- ---------- L9: schedule that does not sum to principal rejected ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  INSERT INTO lending.schedule_installment (tenant_id, loan_id, schedule_version, installment_no, due_date, principal, interest) VALUES
    ('00000000-0000-0000-0000-0000000000a1', 'bbbbbbbb-0000-0000-0000-000000000001', 2, 1, '2026-12-18', 50000, 0),
    ('00000000-0000-0000-0000-0000000000a1', 'bbbbbbbb-0000-0000-0000-000000000001', 2, 2, '2027-01-18', 49999.99, 0);
  SET CONSTRAINTS ALL IMMEDIATE;
  RAISE EXCEPTION 'TEST FAILED L9';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'LENDING_SCHEDULE_MISMATCH%' THEN RAISE; END IF;
END $$;
ROLLBACK;
\echo PASS L9 schedule short by 0.01 rejected

-- ---------- L10: activation with an unrelated entry rejected ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
-- Entry 1111...01 debits 1200 with exactly 100,000 but is another event's entry.
DO $$
BEGIN
  UPDATE lending.loan SET status = 'ACTIVE', disbursement_entry_id = '11111111-0000-0000-0000-000000000001'
   WHERE loan_id = 'bbbbbbbb-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'TEST FAILED L10 foreign entry';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'LENDING_DISBURSEMENT_MISMATCH%' THEN RAISE; END IF;
END $$;
DO $$
BEGIN
  UPDATE lending.loan SET status = 'ACTIVE', disbursement_entry_id = '44444444-0000-0000-0000-000000000001'
   WHERE loan_id = 'bbbbbbbb-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'TEST FAILED L10';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'LENDING_DISBURSEMENT_MISMATCH%' THEN RAISE; END IF;
END $$;
ROLLBACK;
\echo PASS L10 activation linked to a different event entry, even with the right amount, rejected

-- ---------- L11: disbursement posting + activation in one transaction ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
VALUES (:tA::uuid, '77777777-0000-0000-0000-000000000001', 'loan:bbbbbbbb-0000-0000-0000-000000000001:DISBURSE:B2C-1',
        '2026-11-18', 'LOAN', 'c', 'user:manager');
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
  (:tA::uuid, '77777777-0000-0000-0000-000000000001', 1, '1200-LOANS-PRINCIPAL',    'D', 100000.00, 'KES', 'NBO01'),
  (:tA::uuid, '77777777-0000-0000-0000-000000000001', 2, '1110-MPESA-B2C-CLEARING', 'C',  98000.00, 'KES', 'NBO01'),
  (:tA::uuid, '77777777-0000-0000-0000-000000000001', 3, '4100-FEE-PROCESSING',     'C',   2000.00, 'KES', 'NBO01');
UPDATE lending.loan SET status = 'ACTIVE', disbursement_entry_id = '77777777-0000-0000-0000-000000000001' WHERE loan_id = :loan::uuid;
COMMIT;
\echo PASS L11 disbursement posted and loan activated atomically

-- ---------- L12: loan terms immutable; illegal status move rejected ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  UPDATE lending.loan SET principal = 90000 WHERE loan_id = 'bbbbbbbb-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'TEST FAILED L12 principal';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'LENDING_LOAN_IMMUTABLE%' THEN RAISE; END IF;
END $$;
DO $$
BEGIN
  UPDATE lending.loan SET status = 'APPROVED' WHERE loan_id = 'bbbbbbbb-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'TEST FAILED L12 status';
EXCEPTION WHEN raise_exception OR check_violation THEN
  IF SQLSTATE = 'P0001' AND SQLERRM NOT LIKE 'LENDING_LOAN_%' THEN RAISE; END IF;
END $$;
ROLLBACK;
\echo PASS L12 loan principal and status cannot be rewound

-- ---------- L13: repayment of 10,000 = interest 2,000 + principal 7,455.96 + overpayment 544.04 ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
VALUES (:tA::uuid, '77777777-0000-0000-0000-000000000002', 'loan:bbbbbbbb-0000-0000-0000-000000000001:REPAY:QK7X1',
        '2026-11-18', 'LOAN', 'c', 'channel:mpesa-c2b');
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
  (:tA::uuid, '77777777-0000-0000-0000-000000000002', 1, '1120-MPESA-C2B-CLEARING',  'D', 10000.00, 'KES', 'NBO01'),
  (:tA::uuid, '77777777-0000-0000-0000-000000000002', 2, '1210-INTEREST-RECEIVABLE', 'C',  2000.00, 'KES', 'NBO01'),
  (:tA::uuid, '77777777-0000-0000-0000-000000000002', 3, '1200-LOANS-PRINCIPAL',     'C',  7455.96, 'KES', 'NBO01'),
  (:tA::uuid, '77777777-0000-0000-0000-000000000002', 4, '2400-LOAN-OVERPAYMENTS',   'C',   544.04, 'KES', 'NBO01');
INSERT INTO lending.repayment (tenant_id, repayment_id, loan_id, amount, unallocated, currency, channel, provider_ref,
                               journal_entry_id, received_at)
VALUES (:tA::uuid, '88888888-0000-0000-0000-000000000001', :loan::uuid, 10000.00, 544.04, 'KES', 'MPESA_C2B', 'QK7X1',
        '77777777-0000-0000-0000-000000000002', now());
INSERT INTO lending.repayment_allocation (tenant_id, repayment_id, line_no, installment_no, component, amount) VALUES
  (:tA::uuid, '88888888-0000-0000-0000-000000000001', 1, 1, 'INTEREST', 2000.00),
  (:tA::uuid, '88888888-0000-0000-0000-000000000001', 2, 1, 'PRINCIPAL', 7455.96);
COMMIT;
\echo PASS L13 repayment, allocations and journal entry recorded together

-- ---------- L14: the same M-Pesa receipt cannot be applied twice ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  INSERT INTO lending.repayment (tenant_id, repayment_id, loan_id, amount, currency, channel, provider_ref, journal_entry_id, received_at)
  VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'bbbbbbbb-0000-0000-0000-000000000001', 10000.00, 'KES',
          'MPESA_C2B', 'QK7X1', '77777777-0000-0000-0000-000000000001', now());
  RAISE EXCEPTION 'TEST FAILED L14';
EXCEPTION WHEN unique_violation THEN NULL;
END $$;
ROLLBACK;
\echo PASS L14 duplicate M-Pesa receipt rejected

-- ---------- L15: allocations that do not add up rejected ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
  VALUES ('00000000-0000-0000-0000-0000000000a1', '77777777-0000-0000-0000-000000000003', 'rep-bad', '2026-11-18', 'LOAN', 'c', 'u');
  INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
    ('00000000-0000-0000-0000-0000000000a1', '77777777-0000-0000-0000-000000000003', 1, '1120-MPESA-C2B-CLEARING', 'D', 500.00, 'KES', 'NBO01'),
    ('00000000-0000-0000-0000-0000000000a1', '77777777-0000-0000-0000-000000000003', 2, '1210-INTEREST-RECEIVABLE', 'C', 500.00, 'KES', 'NBO01');
  INSERT INTO lending.repayment (tenant_id, repayment_id, loan_id, amount, currency, channel, provider_ref, journal_entry_id, received_at)
  VALUES ('00000000-0000-0000-0000-0000000000a1', '88888888-0000-0000-0000-000000000002', 'bbbbbbbb-0000-0000-0000-000000000001',
          500.00, 'KES', 'MPESA_C2B', 'QK7X9', '77777777-0000-0000-0000-000000000003', now());
  INSERT INTO lending.repayment_allocation (tenant_id, repayment_id, line_no, installment_no, component, amount) VALUES
    ('00000000-0000-0000-0000-0000000000a1', '88888888-0000-0000-0000-000000000002', 1, 2, 'INTEREST', 499.99);
  SET CONSTRAINTS ALL IMMEDIATE;
  RAISE EXCEPTION 'TEST FAILED L15';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'LENDING_ALLOCATION_MISMATCH%' THEN RAISE; END IF;
END $$;
ROLLBACK;
\echo PASS L15 allocations short by 0.01 rejected

-- ---------- L16: repayment amount must equal its journal entry ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
  VALUES ('00000000-0000-0000-0000-0000000000a1', '77777777-0000-0000-0000-000000000004', 'rep-bad2', '2026-11-18', 'LOAN', 'c', 'u');
  INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
    ('00000000-0000-0000-0000-0000000000a1', '77777777-0000-0000-0000-000000000004', 1, '1120-MPESA-C2B-CLEARING', 'D', 400.00, 'KES', 'NBO01'),
    ('00000000-0000-0000-0000-0000000000a1', '77777777-0000-0000-0000-000000000004', 2, '1210-INTEREST-RECEIVABLE', 'C', 400.00, 'KES', 'NBO01');
  INSERT INTO lending.repayment (tenant_id, repayment_id, loan_id, amount, currency, channel, provider_ref, journal_entry_id, received_at)
  VALUES ('00000000-0000-0000-0000-0000000000a1', '88888888-0000-0000-0000-000000000003', 'bbbbbbbb-0000-0000-0000-000000000001',
          500.00, 'KES', 'MPESA_C2B', 'QK7Y1', '77777777-0000-0000-0000-000000000004', now());
  INSERT INTO lending.repayment_allocation (tenant_id, repayment_id, line_no, installment_no, component, amount) VALUES
    ('00000000-0000-0000-0000-0000000000a1', '88888888-0000-0000-0000-000000000003', 1, 2, 'INTEREST', 500.00);
  SET CONSTRAINTS ALL IMMEDIATE;
  RAISE EXCEPTION 'TEST FAILED L16';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'LENDING_REPAYMENT_ENTRY_MISMATCH%' THEN RAISE; END IF;
END $$;
ROLLBACK;
\echo PASS L16 repayment that disagrees with its journal entry rejected

-- ---------- L17: repayments, allocations, schedules are insert-only ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  UPDATE lending.repayment_allocation SET amount = 1 WHERE repayment_id = '88888888-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'TEST FAILED L17 allocation';
EXCEPTION WHEN insufficient_privilege THEN NULL;
END $$;
DO $$
BEGIN
  DELETE FROM lending.schedule_installment WHERE loan_id = 'bbbbbbbb-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'TEST FAILED L17 schedule';
EXCEPTION WHEN insufficient_privilege THEN NULL;
END $$;
ROLLBACK;
\echo PASS L17 repayments, allocations and schedules cannot be edited or deleted

-- ---------- L18: retirement keeps terms; tenant B sees nothing ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
UPDATE lending.loan_product_version SET status = 'RETIRED' WHERE version_id = :v1::uuid;
DO $$
BEGIN
  IF (SELECT annual_rate FROM lending.loan_product_version WHERE version_id = 'cccccccc-0000-0000-0000-000000000001') <> 0.24 THEN
    RAISE EXCEPTION 'TEST FAILED L18 retire';
  END IF;
END $$;
ROLLBACK;
BEGIN;
SET LOCAL app.tenant_id = :tB;
DO $$
DECLARE n int;
BEGIN
  SELECT count(*) INTO n FROM lending.loan;                 IF n <> 0 THEN RAISE EXCEPTION 'TEST FAILED L18 loans %', n; END IF;
  SELECT count(*) INTO n FROM lending.loan_product_version; IF n <> 0 THEN RAISE EXCEPTION 'TEST FAILED L18 versions %', n; END IF;
  SELECT count(*) INTO n FROM lending.repayment;            IF n <> 0 THEN RAISE EXCEPTION 'TEST FAILED L18 repayments %', n; END IF;
END $$;
ROLLBACK;
\echo PASS L18 version retires without term changes; tenant B sees no lending data

-- ---------- L19: principal receivable reconciles to loan balances ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
DECLARE v_gl numeric; v_loans numeric;
BEGIN
  SELECT sum(CASE side WHEN 'D' THEN amount ELSE -amount END) INTO v_gl
    FROM ledger.journal_line jl JOIN ledger.journal_entry je USING (tenant_id, entry_id)
   WHERE jl.account_code = '1200-LOANS-PRINCIPAL' AND je.idempotency_key LIKE 'loan:bbbbbbbb-0000-0000-0000-000000000001:%';
  SELECT l.principal - COALESCE(sum(a.amount), 0) INTO v_loans
    FROM lending.loan l
    LEFT JOIN lending.repayment r USING (tenant_id, loan_id)
    LEFT JOIN lending.repayment_allocation a ON a.tenant_id = r.tenant_id AND a.repayment_id = r.repayment_id AND a.component = 'PRINCIPAL'
   WHERE l.loan_id = 'bbbbbbbb-0000-0000-0000-000000000001'
   GROUP BY l.principal;
  IF v_gl <> v_loans OR v_loans <> 92544.04 THEN
    RAISE EXCEPTION 'TEST FAILED L19: GL % vs loan % (expected 92544.04)', v_gl, v_loans;
  END IF;
END $$;
ROLLBACK;
\echo PASS L19 loan principal (92544.04) reconciles to GL principal receivable

\echo ALL LENDING TESTS PASSED
