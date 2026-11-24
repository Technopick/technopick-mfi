-- =============================================================================
-- Tests for V31__member_pin_and_ussd.sql, as mfi_app.
-- =============================================================================
\set ON_ERROR_STOP on
\set QUIET on

-- PN1: PIN setups are maker-checker, one open request per member, only for active members.
-- PN2: a PIN window opens only from a fresh approval, stores only bcrypt hashes, and a locked PIN
--      stays locked until a new approved setup.
-- PN3: USSD sessions keep their phone and verification; channel events are append-only.
DO $$
DECLARE
  c uuid; other uuid; s1 uuid := gen_random_uuid(); s2 uuid := gen_random_uuid(); s3 uuid := gen_random_uuid();
  h1 text := '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy';
  h2 text := '$2a$10$abcdefghijklmnopqrstuuJ8ZQ9eF8i7k0QFq0nDGx.9xK8mJ5D6i';
  st text;
BEGIN
  PERFORM set_config('app.tenant_id', '00000000-0000-0000-0000-0000000000a1', true);
  SELECT customer_id INTO c FROM customer.customer WHERE status = 'ACTIVE' ORDER BY customer_number LIMIT 1;
  IF c IS NULL THEN
    RAISE EXCEPTION 'TEST SETUP PN: tenant A needs an active customer';
  END IF;
  other := gen_random_uuid();
  INSERT INTO customer.customer (tenant_id, customer_id, customer_number, idempotency_key, first_name, last_name, date_of_birth,
      id_type, id_number, msisdn, branch_code, consent_version, consent_at, created_by)
  SELECT tenancy.current_tenant(), other, customer.next_customer_number(2026), 'pn-member', 'Pin', 'Test', date '1990-01-01',
      'NATIONAL_ID', '39999001', '254799999001', branch_code, 'DPA-2026-01', now(), 'pn:officer'
    FROM customer.customer WHERE customer_id = c;
  UPDATE customer.customer SET status = 'ACTIVE', verified_by = 'pn:manager', verification_note = 'PN test member, TEST ONLY'
   WHERE customer_id = other;

  -- PN1
  INSERT INTO member.pin_setup (tenant_id, setup_id, idempotency_key, customer_id, kind, reason, requested_by)
  VALUES (tenancy.current_tenant(), s1, 'pn-1', c, 'FIRST', 'Member enrolled for USSD', 'user:teller');
  BEGIN
    INSERT INTO member.pin_setup (tenant_id, setup_id, idempotency_key, customer_id, kind, reason, requested_by)
    VALUES (tenancy.current_tenant(), gen_random_uuid(), 'pn-2', c, 'FIRST', 'A second open request', 'user:teller');
    RAISE EXCEPTION 'TEST FAILED PN1a: two open setups';
  EXCEPTION WHEN unique_violation THEN NULL;
  END;
  BEGIN
    UPDATE member.pin_setup SET status = 'APPROVED', decided_by = 'user:teller', decided_at = clock_timestamp() WHERE setup_id = s1;
    RAISE EXCEPTION 'TEST FAILED PN1b: requester approved own setup';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    INSERT INTO member.pin_setup (tenant_id, setup_id, idempotency_key, customer_id, kind, reason, requested_by, status,
        decided_by, decided_at)
    VALUES (tenancy.current_tenant(), gen_random_uuid(), 'pn-3', other, 'FIRST', 'Born approved setup', 'user:a', 'APPROVED',
        'user:b', clock_timestamp());
    RAISE EXCEPTION 'TEST FAILED PN1c: setup inserted already approved';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'PIN_SETUP_INVALID%' THEN RAISE; END IF;
  END;
  UPDATE customer.customer SET status = 'BLOCKED', status_reason = 'PN test' WHERE customer_id = other;
  BEGIN
    INSERT INTO member.pin_setup (tenant_id, setup_id, idempotency_key, customer_id, kind, reason, requested_by)
    VALUES (tenancy.current_tenant(), gen_random_uuid(), 'pn-4', other, 'FIRST', 'Blocked member request', 'user:teller');
    RAISE EXCEPTION 'TEST FAILED PN1d: setup for a blocked member';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'MEMBER_NOT_ACTIVE%' THEN RAISE; END IF;
  END;
  UPDATE customer.customer SET status = 'ACTIVE' WHERE customer_id = other;
  UPDATE member.pin_setup SET status = 'APPROVED', decided_by = 'user:supervisor', decided_at = clock_timestamp() WHERE setup_id = s1;
  BEGIN
    UPDATE member.pin_setup SET status = 'REJECTED' WHERE setup_id = s1;
    RAISE EXCEPTION 'TEST FAILED PN1e: decision changed';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'PIN_SETUP_IMMUTABLE%' THEN RAISE; END IF;
  END;

  -- PN2
  INSERT INTO member.pin_setup (tenant_id, setup_id, idempotency_key, customer_id, kind, reason, requested_by)
  VALUES (tenancy.current_tenant(), s2, 'pn-5', other, 'FIRST', 'Other member, not yet approved', 'user:teller');
  BEGIN
    INSERT INTO member.pin (tenant_id, customer_id, status, setup_id, setup_expires_at)
    VALUES (tenancy.current_tenant(), other, 'SETUP_OPEN', s2, clock_timestamp() + interval '1 day');
    RAISE EXCEPTION 'TEST FAILED PN2a: window opened without approval';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'PIN_SETUP_REQUIRED%' THEN RAISE; END IF;
  END;
  BEGIN
    INSERT INTO member.pin (tenant_id, customer_id, status, setup_id, setup_expires_at)
    VALUES (tenancy.current_tenant(), other, 'SETUP_OPEN', s1, clock_timestamp() + interval '1 day');
    RAISE EXCEPTION 'TEST FAILED PN2b: window opened from another member''s approval';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'PIN_SETUP_REQUIRED%' THEN RAISE; END IF;
  END;
  BEGIN
    INSERT INTO member.pin (tenant_id, customer_id, status, setup_id, setup_expires_at)
    VALUES (tenancy.current_tenant(), c, 'SETUP_OPEN', s1, clock_timestamp() + interval '30 days');
    RAISE EXCEPTION 'TEST FAILED PN2c: window longer than 7 days';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'PIN_SETUP_INVALID%' THEN RAISE; END IF;
  END;
  BEGIN
    INSERT INTO member.pin (tenant_id, customer_id, status, setup_id, setup_expires_at, pin_hash, pin_set_at)
    VALUES (tenancy.current_tenant(), c, 'ACTIVE', s1, clock_timestamp() + interval '1 day', h1, clock_timestamp());
    RAISE EXCEPTION 'TEST FAILED PN2d: PIN created active without a window';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'PIN_SETUP_REQUIRED%' THEN RAISE; END IF;
  END;
  INSERT INTO member.pin (tenant_id, customer_id, status, setup_id, setup_expires_at)
  VALUES (tenancy.current_tenant(), c, 'SETUP_OPEN', s1, clock_timestamp() + interval '1 day');
  BEGIN
    UPDATE member.pin SET status = 'ACTIVE', pin_hash = '1234', pin_set_at = clock_timestamp() WHERE customer_id = c;
    RAISE EXCEPTION 'TEST FAILED PN2e: plain PIN stored';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  UPDATE member.pin SET status = 'ACTIVE', pin_hash = h1, pin_set_at = clock_timestamp() WHERE customer_id = c;
  UPDATE member.pin SET pin_hash = h2, pin_set_at = clock_timestamp() + interval '1 second' WHERE customer_id = c;
  UPDATE member.pin SET failed_attempts = 3, status = 'LOCKED', locked_at = clock_timestamp() WHERE customer_id = c;
  BEGIN
    UPDATE member.pin SET status = 'ACTIVE', failed_attempts = 0, locked_at = NULL WHERE customer_id = c;
    RAISE EXCEPTION 'TEST FAILED PN2f: locked PIN unlocked without a setup';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'PIN_LOCKED%' THEN RAISE; END IF;
  END;
  BEGIN
    UPDATE member.pin SET status = 'SETUP_OPEN', pin_hash = NULL, failed_attempts = 0, locked_at = NULL WHERE customer_id = c;
    RAISE EXCEPTION 'TEST FAILED PN2g: window reopened with the old approval';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'PIN_SETUP_REQUIRED%' THEN RAISE; END IF;
  END;
  INSERT INTO member.pin_setup (tenant_id, setup_id, idempotency_key, customer_id, kind, reason, requested_by)
  VALUES (tenancy.current_tenant(), s3, 'pn-6', c, 'RESET', 'Member forgot PIN, ID seen', 'user:teller');
  UPDATE member.pin_setup SET status = 'APPROVED', decided_by = 'user:manager', decided_at = clock_timestamp() WHERE setup_id = s3;
  UPDATE member.pin SET status = 'SETUP_OPEN', setup_id = s3, pin_hash = NULL, failed_attempts = 0, locked_at = NULL,
      setup_expires_at = clock_timestamp() + interval '1 day' WHERE customer_id = c;
  SELECT status INTO st FROM member.pin WHERE customer_id = c;
  IF st <> 'SETUP_OPEN' THEN RAISE EXCEPTION 'TEST FAILED PN2h: reset did not reopen (%)', st; END IF;
  BEGIN
    DELETE FROM member.pin WHERE customer_id = c;
    RAISE EXCEPTION 'TEST FAILED PN2i: PIN record deleted';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;

  -- PN3
  INSERT INTO member.ussd_session (tenant_id, session_id, service_code, msisdn, customer_id)
  VALUES (tenancy.current_tenant(), 'ATUid_pn3', '*483*1#', '254700000001', c);
  INSERT INTO member.ussd_event (tenant_id, session_id, customer_id, event) VALUES (tenancy.current_tenant(), 'ATUid_pn3', c, 'PIN_OK');
  UPDATE member.ussd_session SET pin_verified = true, requests = 2, last_seen_at = clock_timestamp() WHERE session_id = 'ATUid_pn3';
  BEGIN
    UPDATE member.ussd_session SET msisdn = '254700000002' WHERE session_id = 'ATUid_pn3';
    RAISE EXCEPTION 'TEST FAILED PN3a: session phone changed';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'USSD_SESSION_IMMUTABLE%' THEN RAISE; END IF;
  END;
  BEGIN
    UPDATE member.ussd_session SET customer_id = other WHERE session_id = 'ATUid_pn3';
    RAISE EXCEPTION 'TEST FAILED PN3b: session member changed';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'USSD_SESSION_IMMUTABLE%' THEN RAISE; END IF;
  END;
  BEGIN
    UPDATE member.ussd_event SET event = 'PIN_WRONG' WHERE session_id = 'ATUid_pn3';
    RAISE EXCEPTION 'TEST FAILED PN3c: event changed';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
  RAISE EXCEPTION 'PN rollback';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM <> 'PN rollback' THEN RAISE; END IF;
END $$;
\echo PASS PN1 PIN setups are maker-checker, one open per member, active members only
\echo PASS PN2 PIN windows open only from a fresh approval, hashes only, locked stays locked
\echo PASS PN3 USSD sessions keep their phone and member, events are append-only
