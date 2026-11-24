-- =============================================================================
-- Tests for V30__member_self_service.sql, as mfi_app.
-- =============================================================================
\set ON_ERROR_STOP on
\set QUIET on

-- MS1: a member's payment prompt goes only to the member's own phone, for the member's own account
-- or loan, from an active member; who asked is never changed.
DO $$
DECLARE c customer.customer%ROWTYPE; acct text; other_acct text; code text; ok uuid := gen_random_uuid();
BEGIN
  PERFORM set_config('app.tenant_id', '00000000-0000-0000-0000-0000000000a1', true);
  SELECT cu.* INTO c FROM customer.customer cu
   WHERE cu.status = 'ACTIVE' AND EXISTS (SELECT 1 FROM savings.deposit_account a WHERE a.customer_id = cu.customer_id)
   ORDER BY cu.customer_number LIMIT 1;
  IF c.customer_id IS NULL THEN
    RAISE EXCEPTION 'TEST SETUP MS1: tenant A needs an active customer with a savings account';
  END IF;
  SELECT account_number INTO acct FROM savings.deposit_account WHERE customer_id = c.customer_id ORDER BY account_number LIMIT 1;
  SELECT account_number INTO other_acct FROM savings.deposit_account
   WHERE customer_id IS DISTINCT FROM c.customer_id ORDER BY account_number LIMIT 1;
  SELECT short_code INTO code FROM payments.mpesa_paybill ORDER BY short_code LIMIT 1;
  IF other_acct IS NULL OR code IS NULL THEN
    RAISE EXCEPTION 'TEST SETUP MS1: tenant A needs another member''s account and a paybill';
  END IF;
  BEGIN
    INSERT INTO payments.stk_request (tenant_id, request_id, idempotency_key, short_code, target_reference, amount, currency,
        msisdn, msisdn_masked, description, requested_by, member_customer_id)
    VALUES (tenancy.current_tenant(), gen_random_uuid(), 'ms1a', code, acct, 100, 'KES',
        CASE WHEN c.msisdn = '254700000001' THEN '254700000002' ELSE '254700000001' END, '2547*****001', 'Deposit', 'member:x',
        c.customer_id);
    RAISE EXCEPTION 'TEST FAILED MS1a: prompt to another phone accepted';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'MEMBER_PHONE_MISMATCH%' THEN RAISE; END IF;
  END;
  BEGIN
    INSERT INTO payments.stk_request (tenant_id, request_id, idempotency_key, short_code, target_reference, amount, currency,
        msisdn, msisdn_masked, description, requested_by, member_customer_id)
    VALUES (tenancy.current_tenant(), gen_random_uuid(), 'ms1b', code, other_acct, 100, 'KES', c.msisdn, '2547*****001',
        'Deposit', 'member:x', c.customer_id);
    RAISE EXCEPTION 'TEST FAILED MS1b: prompt paying into another member''s account accepted';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'MEMBER_TARGET_NOT_OWN%' THEN RAISE; END IF;
  END;
  INSERT INTO payments.stk_request (tenant_id, request_id, idempotency_key, short_code, target_reference, amount, currency,
      msisdn, msisdn_masked, description, requested_by, member_customer_id)
  VALUES (tenancy.current_tenant(), ok, 'ms1c', code, acct, 100, 'KES', c.msisdn, '2547*****001', 'Deposit', 'member:x',
      c.customer_id);
  BEGIN
    UPDATE payments.stk_request SET member_customer_id = NULL WHERE request_id = ok;
    RAISE EXCEPTION 'TEST FAILED MS1c: member link changed';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'MEMBER_LINK_IMMUTABLE%' AND SQLERRM NOT LIKE 'STK_REQUEST_IMMUTABLE%' THEN RAISE; END IF;
  END;
  RAISE EXCEPTION 'MS1 rollback';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM <> 'MS1 rollback' THEN RAISE; END IF;
END $$;
\echo PASS MS1 member payment prompts go only to the member phone and own account, and the link never changes
