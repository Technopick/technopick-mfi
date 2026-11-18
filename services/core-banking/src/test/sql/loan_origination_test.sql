-- =============================================================================
-- Tests for V14__loan_origination.sql, run as mfi_app after lending_test.sql (product version
-- cccccccc-...-01 ACTIVE in tenant A).
-- =============================================================================
\set ON_ERROR_STOP on
\set QUIET on
\set tA '''00000000-0000-0000-0000-0000000000a1'''
\set tB '''00000000-0000-0000-0000-0000000000b2'''

BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO lending.loan_application (tenant_id, application_id, idempotency_key, customer_ref, product_version_id,
    branch_code, currency, principal, term_months, first_due_date, purpose, submitted_by)
VALUES (:tA::uuid, 'aaaa0000-0000-0000-0000-000000000001', 'app-1', 'CUST-77', 'cccccccc-0000-0000-0000-000000000001',
        'NBO01', 'KES', 10000, 12, '2026-12-18', 'Stock for the shop', 'user:officer'),
       (:tA::uuid, 'aaaa0000-0000-0000-0000-000000000002', 'app-2', 'CUST-78', 'cccccccc-0000-0000-0000-000000000001',
        'NBO01', 'KES', 10000, 12, '2026-12-18', 'School fees', 'user:officer');
COMMIT;
\echo PASS A0 applications submitted

BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  UPDATE lending.loan_application SET principal = 20000 WHERE application_id = 'aaaa0000-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'TEST FAILED A2';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'LENDING_APPLICATION_IMMUTABLE%' THEN RAISE; END IF;
END $$;
DO $$
BEGIN
  UPDATE lending.loan_application SET status = 'DECLINED', decided_by = 'user:officer', decision_reason = 'Too risky'
   WHERE application_id = 'aaaa0000-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'TEST FAILED A1';
EXCEPTION WHEN check_violation THEN NULL;
END $$;
DO $$
BEGIN
  UPDATE lending.loan_application SET status = 'DECLINED', decided_by = 'user:manager'
   WHERE application_id = 'aaaa0000-0000-0000-0000-000000000002';
  RAISE EXCEPTION 'TEST FAILED A3a: decline without reason';
EXCEPTION WHEN check_violation THEN NULL;
END $$;
-- A loan that does not match the application cannot be linked to its approval.
INSERT INTO lending.loan (tenant_id, loan_id, loan_number, customer_ref, product_version_id, branch_code, currency,
                          principal, term_months, created_by, approved_by)
VALUES (:tA::uuid, 'aaaa1111-0000-0000-0000-000000000001', 'LN26-9000001', 'CUST-77', 'cccccccc-0000-0000-0000-000000000001',
        'NBO01', 'KES', 12000, 12, 'user:officer', 'user:manager');
DO $$
BEGIN
  UPDATE lending.loan_application SET status = 'APPROVED', decided_by = 'user:manager',
         loan_id = 'aaaa1111-0000-0000-0000-000000000001'
   WHERE application_id = 'aaaa0000-0000-0000-0000-000000000001';
  RAISE EXCEPTION 'TEST FAILED A3b';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'LENDING_APPLICATION_LOAN_MISMATCH%' THEN RAISE; END IF;
END $$;
INSERT INTO lending.loan (tenant_id, loan_id, loan_number, customer_ref, product_version_id, branch_code, currency,
                          principal, term_months, created_by, approved_by)
VALUES (:tA::uuid, 'aaaa1111-0000-0000-0000-000000000002', 'LN26-9000002', 'CUST-77', 'cccccccc-0000-0000-0000-000000000001',
        'NBO01', 'KES', 10000, 12, 'user:officer', 'user:manager');
INSERT INTO lending.schedule_installment (tenant_id, loan_id, schedule_version, installment_no, due_date, principal, interest) VALUES
  (:tA::uuid, 'aaaa1111-0000-0000-0000-000000000001', 1, 1, '2026-12-18', 12000, 0),
  (:tA::uuid, 'aaaa1111-0000-0000-0000-000000000002', 1, 1, '2026-12-18', 10000, 0);
UPDATE lending.loan_application SET status = 'APPROVED', decided_by = 'user:manager',
       loan_id = 'aaaa1111-0000-0000-0000-000000000002'
 WHERE application_id = 'aaaa0000-0000-0000-0000-000000000001';
UPDATE lending.loan_application SET status = 'DECLINED', decided_by = 'user:manager', decision_reason = 'Insufficient income'
 WHERE application_id = 'aaaa0000-0000-0000-0000-000000000002';
DO $$
BEGIN
  UPDATE lending.loan_application SET decision_reason = 'changed' WHERE application_id = 'aaaa0000-0000-0000-0000-000000000002';
  RAISE EXCEPTION 'TEST FAILED A3c';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'LENDING_APPLICATION_FINAL%' THEN RAISE; END IF;
END $$;
COMMIT;
\echo PASS A1 A2 A3 decider differs from submitter, terms fixed, approval only with the matching loan, decisions final

BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
DECLARE a varchar; b varchar; c varchar;
BEGIN
  a := lending.next_loan_number(2026);
  b := lending.next_loan_number(2026);
  c := lending.next_loan_number(2027);
  IF a <> 'LN26-0000001' OR b <> 'LN26-0000002' OR c <> 'LN27-0000001' OR length(a) <> 12 THEN
    RAISE EXCEPTION 'TEST FAILED A4a: % % %', a, b, c;
  END IF;
END $$;
COMMIT;
BEGIN;
SET LOCAL app.tenant_id = :tB;
DO $$
BEGIN
  IF lending.next_loan_number(2026) <> 'LN26-0000001' THEN
    RAISE EXCEPTION 'TEST FAILED A4b: tenants share a counter';
  END IF;
  IF (SELECT count(*) FROM lending.loan_application) <> 0 THEN
    RAISE EXCEPTION 'TEST FAILED A5: tenant B sees tenant A applications';
  END IF;
END $$;
ROLLBACK;
\echo PASS A4 A5 loan numbers per tenant and year, 12 characters; tenant isolation
