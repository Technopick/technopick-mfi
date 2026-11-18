-- =============================================================================
-- Tests for V15__customers_and_kyc.sql (customers), run as mfi_app.
-- =============================================================================
\set ON_ERROR_STOP on
\set QUIET on
\set tA '''00000000-0000-0000-0000-0000000000a1'''
\set tB '''00000000-0000-0000-0000-0000000000b2'''

BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO customer.customer (tenant_id, customer_id, customer_number, idempotency_key, first_name, last_name, date_of_birth,
    id_type, id_number, msisdn, branch_code, consent_version, consent_at, created_by)
VALUES (:tA::uuid, 'c0000000-0000-0000-0000-000000000001', customer.next_customer_number(2026), 'cust-1', 'Wanjiku', 'Kamau',
        '1990-05-01', 'NATIONAL_ID', '12345678', '254712345678', 'NBO01', 'DPA-2026-01', now(), 'user:officer');
COMMIT;
\echo PASS U0 customer registered pending verification

BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
BEGIN
  IF (SELECT customer_number FROM customer.customer WHERE customer_id = 'c0000000-0000-0000-0000-000000000001') <> 'CU26-0000001' THEN
    RAISE EXCEPTION 'TEST FAILED U7: unexpected customer number';
  END IF;
  BEGIN
    INSERT INTO customer.customer (tenant_id, customer_id, customer_number, idempotency_key, first_name, last_name, date_of_birth,
        id_type, id_number, msisdn, branch_code, consent_version, consent_at, created_by)
    VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'CU26-0000099', 'cust-2', 'Young', 'Person',
            current_date - interval '17 years', 'NATIONAL_ID', '87654321', '254712345679', 'NBO01', 'DPA-2026-01', now(), 'user:officer');
    RAISE EXCEPTION 'TEST FAILED U2a: minor registered';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'CUSTOMER_UNDERAGE%' THEN RAISE; END IF;
  END;
  BEGIN
    INSERT INTO customer.customer (tenant_id, customer_id, customer_number, idempotency_key, first_name, last_name, date_of_birth,
        id_type, id_number, msisdn, branch_code, consent_version, consent_at, created_by)
    VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'CU26-0000098', 'cust-3', 'Same', 'Document',
            '1985-01-01', 'NATIONAL_ID', '12345678', '254700000001', 'NBO01', 'DPA-2026-01', now(), 'user:officer');
    RAISE EXCEPTION 'TEST FAILED U2b: duplicate document';
  EXCEPTION WHEN unique_violation THEN NULL;
  END;
  BEGIN
    INSERT INTO customer.customer (tenant_id, customer_id, customer_number, idempotency_key, first_name, last_name, date_of_birth,
        id_type, id_number, msisdn, branch_code, consent_version, consent_at, created_by)
    VALUES ('00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), 'CU26-0000097', 'cust-4', 'Bad', 'Id',
            '1985-01-01', 'NATIONAL_ID', 'AB12', '254700000002', 'NBO01', 'DPA-2026-01', now(), 'user:officer');
    RAISE EXCEPTION 'TEST FAILED U2c: malformed national ID';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    UPDATE customer.customer SET status = 'ACTIVE', verified_by = 'user:officer', verification_note = 'ID sighted at branch'
     WHERE customer_id = 'c0000000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED U1: registrar verified own customer';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    UPDATE customer.customer SET status = 'ACTIVE', verified_by = 'user:manager', verification_note = 'ok'
     WHERE customer_id = 'c0000000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED U1b: verification without a meaningful note';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
END $$;
UPDATE customer.customer SET status = 'ACTIVE', verified_by = 'user:manager',
       verification_note = 'National ID sighted; IPRS check ref T-123'
 WHERE customer_id = 'c0000000-0000-0000-0000-000000000001';
DO $$
BEGIN
  BEGIN
    UPDATE customer.customer SET last_name = 'Other' WHERE customer_id = 'c0000000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED U4a';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'CUSTOMER_IDENTITY_FIXED%' THEN RAISE; END IF;
  END;
  BEGIN
    UPDATE customer.customer SET consent_version = 'X' WHERE customer_id = 'c0000000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED U3';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'CUSTOMER_IMMUTABLE%' THEN RAISE; END IF;
  END;
  BEGIN
    UPDATE customer.customer SET status = 'PENDING_VERIFICATION', verified_by = NULL
     WHERE customer_id = 'c0000000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED U5a';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'CUSTOMER_%' THEN RAISE; END IF;
  END;
  BEGIN
    DELETE FROM customer.customer WHERE customer_id = 'c0000000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'TEST FAILED U5b';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
END $$;
-- Contact details may change after verification; blocking and unblocking are allowed.
UPDATE customer.customer SET msisdn = '254722000000' WHERE customer_id = 'c0000000-0000-0000-0000-000000000001';
UPDATE customer.customer SET status = 'BLOCKED', status_reason = 'Suspected fraud' WHERE customer_id = 'c0000000-0000-0000-0000-000000000001';
UPDATE customer.customer SET status = 'ACTIVE', status_reason = 'Cleared', msisdn = '254712345678'
 WHERE customer_id = 'c0000000-0000-0000-0000-000000000001';
COMMIT;
\echo PASS U1-U5 U7 four-eyes KYC, adults only, one per document, consent and identity fixed, statuses, no deletes, numbering

BEGIN;
SET LOCAL app.tenant_id = :tB;
DO $$
BEGIN
  IF (SELECT count(*) FROM customer.customer) <> 0 THEN
    RAISE EXCEPTION 'TEST FAILED U8: tenant B sees tenant A customers';
  END IF;
END $$;
COMMIT;
\echo PASS U8 tenant B cannot see tenant A customers
