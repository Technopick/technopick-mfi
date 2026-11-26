-- =============================================================================
-- Tests for V41__guarantor_calls_and_release.sql, run as mfi_app after loan_limits_test.sql. TEST-ONLY.
-- Borrower CU26-0000087 takes loan C (9,000, three months) guaranteed 6,000 by CU26-0000001 from
-- account 2000000014. The call itself (needing days past due from real closes) is covered by the
-- API test; here: settings, refusals, and a lock that only goes down.
-- =============================================================================
\set ON_ERROR_STOP on
\set QUIET on
\set tA '''00000000-0000-0000-0000-0000000000a1'''
\set tB '''00000000-0000-0000-0000-0000000000b2'''

-- ---------- GC0 a guaranteed, disbursed loan ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO ledger.gl_account (tenant_id, account_code, name, account_class, currency) VALUES
  (:tA::uuid, '1190-TEST-GUARANTOR-CLEARING', 'TEST guarantor recoveries clearing', 'ASSET', 'KES'),
  (:tA::uuid, '2190-TEST-NOT-ASSET', 'TEST liability', 'LIABILITY', 'KES');
INSERT INTO lending.loan_application (tenant_id, application_id, idempotency_key, customer_ref, customer_id, product_version_id,
    branch_code, currency, principal, term_months, first_due_date, purpose, submitted_by)
SELECT :tA::uuid, 'f5000000-0000-0000-0000-00000000000c', 'gc-c', 'CU26-0000087', 'c0000000-0000-0000-0000-0000000000a7',
       'cccccccc-0000-0000-0000-000000000001', 'NBO01', 'KES', 9000, 3, current_date_ + 30, 'Stock for the shop', 'user:officer'
  FROM tenancy.business_date;
INSERT INTO savings.account_hold (tenant_id, hold_id, account_id, kind, amount, currency, reference, idempotency_key)
VALUES (:tA::uuid, 'f6000000-0000-0000-0000-000000000001', 'aa000000-0000-0000-0000-0000000000d1', 'EARMARK', 6000, 'KES', 'gc', 'gc-hold-1');
INSERT INTO lending.loan_guarantee (tenant_id, guarantee_id, idempotency_key, application_id, guarantor_customer_id,
    savings_account_id, amount, consent_reference, hold_id, pledged_by)
VALUES (:tA::uuid, 'f6000000-0000-0000-0000-000000000001', 'gc-g1', 'f5000000-0000-0000-0000-00000000000c',
        'c0000000-0000-0000-0000-000000000001', 'aa000000-0000-0000-0000-0000000000d1', 6000, 'Form G-9 signed',
        'f6000000-0000-0000-0000-000000000001', 'user:officer');
INSERT INTO lending.loan (tenant_id, loan_id, loan_number, customer_ref, customer_id, product_version_id, branch_code, currency,
    principal, term_months, created_by, approved_by)
VALUES (:tA::uuid, 'f7000000-0000-0000-0000-00000000000c', 'LN26-8000003', 'CU26-0000087', 'c0000000-0000-0000-0000-0000000000a7',
        'cccccccc-0000-0000-0000-000000000001', 'NBO01', 'KES', 9000, 3, 'user:officer', 'user:manager');
UPDATE lending.loan_application SET status = 'APPROVED', decided_by = 'user:manager', loan_id = 'f7000000-0000-0000-0000-00000000000c'
 WHERE application_id = 'f5000000-0000-0000-0000-00000000000c';
INSERT INTO lending.schedule_installment (tenant_id, loan_id, schedule_version, installment_no, due_date, principal, interest)
SELECT :tA::uuid, 'f7000000-0000-0000-0000-00000000000c', 1, k, current_date_ + 30 * k, 3000, 0
  FROM tenancy.business_date, generate_series(1, 3) k;
INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
SELECT :tA::uuid, 'f8000000-0000-0000-0000-000000000001', 'loan:f7000000-0000-0000-0000-00000000000c:DISBURSE:GC', current_date_,
       'LOAN', 'c', 'u' FROM tenancy.business_date;
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
  (:tA::uuid, 'f8000000-0000-0000-0000-000000000001', 1, '1200-LOANS-PRINCIPAL', 'D', 9000, 'KES', 'NBO01'),
  (:tA::uuid, 'f8000000-0000-0000-0000-000000000001', 2, '1120-MPESA-C2B-CLEARING', 'C', 9000, 'KES', 'NBO01');
UPDATE lending.loan SET status = 'ACTIVE', disbursement_entry_id = 'f8000000-0000-0000-0000-000000000001'
 WHERE loan_id = 'f7000000-0000-0000-0000-00000000000c';
DO $$
BEGIN
  IF (SELECT locked_amount FROM lending.loan_guarantee WHERE guarantee_id = 'f6000000-0000-0000-0000-000000000001') <> 6000
     OR (SELECT current_hold_id FROM lending.loan_guarantee WHERE guarantee_id = 'f6000000-0000-0000-0000-000000000001')
        <> 'f6000000-0000-0000-0000-000000000001' THEN
    RAISE EXCEPTION 'TEST FAILED GC0: a new guarantee locks its whole amount on its own hold';
  END IF;
END $$;
COMMIT;
\echo PASS GC0 a guaranteed loan, disbursed; the pledge is its current lock

-- ---------- GC1 settings ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  BEGIN
    INSERT INTO lending.guarantor_call (tenant_id, call_id, idempotency_key, loan_id, days_past_due, amount, reason, requested_by)
    VALUES (tenancy.current_tenant(), gen_random_uuid(), 'c0', 'f7000000-0000-0000-0000-00000000000c', 0, 100,
            'Borrower stopped paying', 'user:officer');
    RAISE EXCEPTION 'TEST FAILED GC1a';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'GUARANTEE_SETTINGS_MISSING%' THEN RAISE; END IF;
  END;
  BEGIN
    INSERT INTO lending.guarantee_settings (tenant_id, recovery_clearing, min_days_past_due, release_as_repaid, updated_by)
    VALUES (tenancy.current_tenant(), '2190-TEST-NOT-ASSET', 1, true, 'user:fin');
    RAISE EXCEPTION 'TEST FAILED GC1b';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'GUARANTEE_SETTINGS_INVALID%' THEN RAISE; END IF;
  END;
END $$;
INSERT INTO lending.guarantee_settings (tenant_id, recovery_clearing, min_days_past_due, release_as_repaid, updated_by)
VALUES (:tA::uuid, '1190-TEST-GUARANTOR-CLEARING', 1, true, 'user:fin');
DO $$
BEGIN
  IF NOT ledger.is_control_account(tenancy.current_tenant(), '1190-TEST-GUARANTOR-CLEARING') THEN
    RAISE EXCEPTION 'TEST FAILED GC1c: the clearing account is a control account';
  END IF;
END $$;
COMMIT;

-- nothing else posts to the clearing account
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
  SELECT tenancy.current_tenant(), 'f8000000-0000-0000-0000-0000000000ff', 'gc-direct', current_date_, 'MANUAL', 'c', 'user:fin'
    FROM tenancy.business_date;
  INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
    (tenancy.current_tenant(), 'f8000000-0000-0000-0000-0000000000ff', 1, '1190-TEST-GUARANTOR-CLEARING', 'D', 10, 'KES', 'NBO01'),
    (tenancy.current_tenant(), 'f8000000-0000-0000-0000-0000000000ff', 2, '1199-TEST-FUNDING', 'C', 10, 'KES', 'NBO01');
  SET CONSTRAINTS ledger.journal_line_recovery_clearing IMMEDIATE;
  RAISE EXCEPTION 'TEST FAILED GC1d';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'GUARANTEE_DIRECT_POSTING%' THEN RAISE; END IF;
END $$;
ROLLBACK;
\echo PASS GC1 calls need settings with an ASSET clearing account that only recoveries post to

-- ---------- GC3 a call needs a loan far enough past due ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  BEGIN
    INSERT INTO lending.guarantor_call (tenant_id, call_id, idempotency_key, loan_id, days_past_due, amount, reason, requested_by)
    VALUES (tenancy.current_tenant(), gen_random_uuid(), 'c1', 'f7000000-0000-0000-0000-00000000000c', 0, 100,
            'Borrower stopped paying', 'user:officer');
    RAISE EXCEPTION 'TEST FAILED GC3a';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'GUARANTOR_CALL_INVALID%' THEN RAISE; END IF;
  END;
END $$;
COMMIT;
\echo PASS GC3 guarantors are not called on a loan that is not past due

-- ---------- GC2 GC5 a lock only goes down, on a new hold; recovery only through a call ----------
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  -- the same hold cannot carry a smaller lock
  BEGIN
    UPDATE lending.loan_guarantee SET locked_amount = 4000 WHERE guarantee_id = 'f6000000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED GC2a';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'GUARANTEE_INVALID%' THEN RAISE; END IF;
  END;
  -- recovered money needs an approved call
  BEGIN
    INSERT INTO savings.account_hold (tenant_id, hold_id, account_id, kind, amount, currency, reference, idempotency_key)
    VALUES (tenancy.current_tenant(), 'f6000000-0000-0000-0000-000000000009', 'aa000000-0000-0000-0000-0000000000d1', 'EARMARK', 5000,
            'KES', 'gc', 'gc-hold-9');
    UPDATE lending.loan_guarantee SET locked_amount = 5000, recovered = 1000, current_hold_id = 'f6000000-0000-0000-0000-000000000009'
     WHERE guarantee_id = 'f6000000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED GC2b';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'GUARANTEE_IMMUTABLE%' THEN RAISE; END IF;
  END;
  -- the current hold is not released on its own
  BEGIN
    UPDATE savings.account_hold SET status = 'RELEASED' WHERE hold_id = 'f6000000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED GC2c';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'GUARANTEE_LOCKED%' THEN RAISE; END IF;
  END;
END $$;
-- a third of the principal is repaid: the lock comes down to 4,000 on a new hold; the old hold is released
INSERT INTO savings.account_hold (tenant_id, hold_id, account_id, kind, amount, currency, reference, idempotency_key)
VALUES (:tA::uuid, 'f6000000-0000-0000-0000-000000000002', 'aa000000-0000-0000-0000-0000000000d1', 'EARMARK', 4000, 'KES', 'gc',
        'gc-hold-2');
UPDATE lending.loan_guarantee SET locked_amount = 4000, current_hold_id = 'f6000000-0000-0000-0000-000000000002'
 WHERE guarantee_id = 'f6000000-0000-0000-0000-000000000001';
DO $$
BEGIN
  IF (SELECT status FROM savings.account_hold WHERE hold_id = 'f6000000-0000-0000-0000-000000000001') <> 'RELEASED'
     OR (SELECT status FROM savings.account_hold WHERE hold_id = 'f6000000-0000-0000-0000-000000000002') <> 'ACTIVE' THEN
    RAISE EXCEPTION 'TEST FAILED GC5a: the lock did not move to the new hold';
  END IF;
  -- a lock never goes back up
  BEGIN
    INSERT INTO savings.account_hold (tenant_id, hold_id, account_id, kind, amount, currency, reference, idempotency_key)
    VALUES (tenancy.current_tenant(), 'f6000000-0000-0000-0000-000000000003', 'aa000000-0000-0000-0000-0000000000d1', 'EARMARK', 5000,
            'KES', 'gc', 'gc-hold-3');
    UPDATE lending.loan_guarantee SET locked_amount = 5000, current_hold_id = 'f6000000-0000-0000-0000-000000000003'
     WHERE guarantee_id = 'f6000000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED GC5b';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'GUARANTEE_IMMUTABLE%' THEN RAISE; END IF;
  END;
  -- the guarantor's cover and exit see the lock, not the pledge
  IF (SELECT pledged_for_others FROM lending.loan_limit(tenancy.current_tenant(), 'c0000000-0000-0000-0000-000000000001',
        (SELECT product_code FROM lending.loan_product_version WHERE version_id = 'cccccccc-0000-0000-0000-000000000001'), NULL)) <> 4000 THEN
    RAISE EXCEPTION 'TEST FAILED GC5c';
  END IF;
  IF NOT ('1 guarantee(s) for other members'' loans (4000.00)' = ANY (membership.blockers(tenancy.current_tenant(),
            'c0000000-0000-0000-0000-000000000001'))) THEN
    RAISE EXCEPTION 'TEST FAILED GC5d: %', membership.blockers(tenancy.current_tenant(), 'c0000000-0000-0000-0000-000000000001');
  END IF;
END $$;
COMMIT;
\echo PASS GC2 GC5 a lock only goes down, onto a new hold that replaces the old; recovery needs a call; cover and exit see the lock

BEGIN;
SET LOCAL app.tenant_id = :tB;
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM lending.guarantor_call) OR EXISTS (SELECT 1 FROM lending.guarantee_settings) THEN
    RAISE EXCEPTION 'TEST FAILED GC-RLS';
  END IF;
END $$;
COMMIT;
\echo PASS GC-RLS guarantor calls are invisible to other tenants
