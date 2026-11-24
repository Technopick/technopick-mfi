-- =============================================================================
-- Tests for V32__member_sms_notifications.sql, as mfi_app.
-- =============================================================================
\set ON_ERROR_STOP on
\set QUIET on

-- SM1: one message per event; recipient and content never change; plain printable text only.
-- SM2: statuses only move forward (a failed send may be queued again), each claim counts one attempt,
--      sent messages carry the provider reference, finished messages never change, nothing is deleted.
DO $$
DECLARE c uuid; m uuid := gen_random_uuid(); st text;
BEGIN
  PERFORM set_config('app.tenant_id', '00000000-0000-0000-0000-0000000000a1', true);
  SELECT customer_id INTO c FROM customer.customer WHERE status = 'ACTIVE' ORDER BY customer_number LIMIT 1;
  IF c IS NULL THEN RAISE EXCEPTION 'TEST SETUP SM: tenant A needs an active customer'; END IF;
  INSERT INTO notify.sms_message (tenant_id, message_id, dedupe_key, customer_id, msisdn, template, category, language, body,
      status, not_before, created_by)
  VALUES (tenancy.current_tenant(), m, 'pay:SM1', c, '254700000001', 'PAYMENT_SAVINGS', 'TRANSACTIONAL', 'en',
      'Test: KES 100.00 deposited.', 'QUEUED', clock_timestamp(), 'sm:test');
  BEGIN
    INSERT INTO notify.sms_message (tenant_id, message_id, dedupe_key, customer_id, msisdn, template, category, language, body,
        status, not_before, created_by)
    VALUES (tenancy.current_tenant(), gen_random_uuid(), 'pay:SM1', c, '254700000001', 'PAYMENT_SAVINGS', 'TRANSACTIONAL', 'en',
        'Test: KES 100.00 deposited.', 'QUEUED', clock_timestamp(), 'sm:test');
    RAISE EXCEPTION 'TEST FAILED SM1a: second message for the same event';
  EXCEPTION WHEN unique_violation THEN NULL;
  END;
  BEGIN
    UPDATE notify.sms_message SET body = 'Test: KES 900.00 deposited.' WHERE message_id = m;
    RAISE EXCEPTION 'TEST FAILED SM1b: body changed';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'SMS_IMMUTABLE%' THEN RAISE; END IF;
  END;
  BEGIN
    UPDATE notify.sms_message SET msisdn = '254700000002' WHERE message_id = m;
    RAISE EXCEPTION 'TEST FAILED SM1c: recipient changed';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'SMS_IMMUTABLE%' THEN RAISE; END IF;
  END;
  BEGIN
    INSERT INTO notify.sms_message (tenant_id, message_id, dedupe_key, customer_id, msisdn, template, category, language, body,
        status, not_before, created_by)
    VALUES (tenancy.current_tenant(), gen_random_uuid(), 'pay:SM1d', c, '254700000001', 'PAYMENT_SAVINGS', 'TRANSACTIONAL', 'sw',
        E'Line one\nline two', 'QUEUED', clock_timestamp(), 'sm:test');
    RAISE EXCEPTION 'TEST FAILED SM1d: control characters accepted';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    INSERT INTO notify.sms_message (tenant_id, message_id, dedupe_key, customer_id, msisdn, template, category, language, body,
        status, not_before, created_by, attempts, claimed_at)
    VALUES (tenancy.current_tenant(), gen_random_uuid(), 'pay:SM1e', c, '254700000001', 'PAYMENT_SAVINGS', 'TRANSACTIONAL', 'en',
        'Born dispatched', 'DISPATCHED', clock_timestamp(), 'sm:test', 1, clock_timestamp());
    RAISE EXCEPTION 'TEST FAILED SM1e: message inserted as dispatched';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'SMS_INVALID%' THEN RAISE; END IF;
  END;

  -- SM2
  BEGIN
    UPDATE notify.sms_message SET status = 'SENT', provider_message_id = 'p-1', sent_at = clock_timestamp() WHERE message_id = m;
    RAISE EXCEPTION 'TEST FAILED SM2a: sent without being claimed';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'SMS_STATUS%' THEN RAISE; END IF;
  END;
  BEGIN
    UPDATE notify.sms_message SET status = 'DISPATCHED', attempts = 3, claimed_at = clock_timestamp() WHERE message_id = m;
    RAISE EXCEPTION 'TEST FAILED SM2b: claim counted three attempts';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'SMS_STATUS%' THEN RAISE; END IF;
  END;
  UPDATE notify.sms_message SET status = 'DISPATCHED', attempts = 1, claimed_at = clock_timestamp() WHERE message_id = m;
  UPDATE notify.sms_message SET status = 'QUEUED', last_error = 'HTTP 503', not_before = clock_timestamp() + interval '1 minute'
   WHERE message_id = m;
  UPDATE notify.sms_message SET status = 'DISPATCHED', attempts = 2, claimed_at = clock_timestamp() WHERE message_id = m;
  BEGIN
    UPDATE notify.sms_message SET status = 'SENT', sent_at = clock_timestamp() WHERE message_id = m;
    RAISE EXCEPTION 'TEST FAILED SM2c: sent without a provider reference';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  UPDATE notify.sms_message SET status = 'SENT', provider_message_id = 'p-1', sent_at = clock_timestamp() WHERE message_id = m;
  UPDATE notify.sms_message SET status = 'DELIVERED', delivered_at = clock_timestamp() WHERE message_id = m;
  BEGIN
    UPDATE notify.sms_message SET status = 'FAILED', failed_at = clock_timestamp() WHERE message_id = m;
    RAISE EXCEPTION 'TEST FAILED SM2d: delivered message changed';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'SMS_STATUS%' THEN RAISE; END IF;
  END;
  SELECT status INTO st FROM notify.sms_message WHERE message_id = m;
  IF st <> 'DELIVERED' THEN RAISE EXCEPTION 'TEST FAILED SM2e: status %', st; END IF;
  BEGIN
    DELETE FROM notify.sms_message WHERE message_id = m;
    RAISE EXCEPTION 'TEST FAILED SM2f: message deleted';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
  RAISE EXCEPTION 'SM rollback';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM <> 'SM rollback' THEN RAISE; END IF;
END $$;
\echo PASS SM1 one SMS per event, recipient and content never change, printable text only
\echo PASS SM2 SMS statuses move forward only, one attempt per claim, sent needs a provider reference
