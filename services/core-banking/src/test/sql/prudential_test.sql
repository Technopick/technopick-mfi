-- =============================================================================
-- Tests for V27__prudential_settings_and_mapping.sql, as mfi_app.
-- =============================================================================
\set ON_ERROR_STOP on
\set QUIET on
\set tA '''00000000-0000-0000-0000-0000000000a1'''
\set tB '''00000000-0000-0000-0000-0000000000b2'''

BEGIN;
SET LOCAL app.tenant_id = :tA;
INSERT INTO ledger.gl_account (tenant_id, account_code, name, account_class, currency) VALUES
  (:tA::uuid, 'PRU-SHARES', 'Member shares (test)', 'EQUITY', 'KES'),
  (:tA::uuid, 'PRU-DEPOSITS', 'Member deposits (test)', 'LIABILITY', 'KES'),
  (:tA::uuid, 'PRU-BANK', 'Bank (test)', 'ASSET', 'KES'),
  (:tA::uuid, 'PRU-INCOME', 'Income (test)', 'INCOME', 'KES');
COMMIT;

-- F1: a mapping only takes accounts of the line's class, and only known lines.
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
DECLARE bad text[] := ARRAY[
  'INSERT INTO ledger.regulatory_mapping (tenant_id, account_code, line, updated_by) VALUES (tenancy.current_tenant(), ''PRU-DEPOSITS'', ''SHARE_CAPITAL'', ''x'')',
  'INSERT INTO ledger.regulatory_mapping (tenant_id, account_code, line, updated_by) VALUES (tenancy.current_tenant(), ''PRU-BANK'', ''MEMBER_DEPOSITS'', ''x'')',
  'INSERT INTO ledger.regulatory_mapping (tenant_id, account_code, line, updated_by) VALUES (tenancy.current_tenant(), ''PRU-INCOME'', ''RETAINED_EARNINGS'', ''x'')'];
  q text;
BEGIN
  FOREACH q IN ARRAY bad LOOP
    BEGIN
      EXECUTE q;
      RAISE EXCEPTION 'TEST FAILED F1: accepted %', q;
    EXCEPTION WHEN raise_exception THEN
      IF SQLERRM NOT LIKE 'MAPPING_CLASS_MISMATCH%' THEN RAISE; END IF;
    END;
  END LOOP;
  BEGIN
    INSERT INTO ledger.regulatory_mapping (tenant_id, account_code, line, updated_by)
    VALUES (tenancy.current_tenant(), 'PRU-SHARES', 'MADE_UP_LINE', 'x');
    RAISE EXCEPTION 'TEST FAILED F1: unknown line accepted';
  EXCEPTION WHEN foreign_key_violation THEN NULL;
  END;
  INSERT INTO ledger.regulatory_mapping (tenant_id, account_code, line, updated_by) VALUES
    (tenancy.current_tenant(), 'PRU-SHARES', 'SHARE_CAPITAL', 'user:finance'),
    (tenancy.current_tenant(), 'PRU-DEPOSITS', 'MEMBER_DEPOSITS', 'user:finance'),
    (tenancy.current_tenant(), 'PRU-BANK', 'BANK_BALANCES', 'user:finance');
  BEGIN
    UPDATE ledger.regulatory_mapping SET line = 'CASH' WHERE account_code = 'PRU-DEPOSITS';
    RAISE EXCEPTION 'TEST FAILED F1: remapped a liability as cash';
  EXCEPTION WHEN raise_exception THEN
    IF SQLERRM NOT LIKE 'MAPPING_CLASS_MISMATCH%' THEN RAISE; END IF;
  END;
END $$;
COMMIT;
\echo PASS F1 regulatory mapping accepts only accounts of the class each line takes

-- F2: settings must be coherent: bands increase, allowances never fall as risk rises, ratios are percentages.
BEGIN;
SET LOCAL app.tenant_id = :tA;
DO $$
DECLARE bad text[] := ARRAY[
  'INSERT INTO ledger.prudential_settings (tenant_id, updated_by, watch_max_dpd, substandard_max_dpd) VALUES (tenancy.current_tenant(), ''x'', 200, 180)',
  'INSERT INTO ledger.prudential_settings (tenant_id, updated_by, provision_doubtful_pct, provision_loss_pct) VALUES (tenancy.current_tenant(), ''x'', 60, 50)',
  'INSERT INTO ledger.prudential_settings (tenant_id, updated_by, provision_performing_pct) VALUES (tenancy.current_tenant(), ''x'', -1)',
  'INSERT INTO ledger.prudential_settings (tenant_id, updated_by, min_liquidity_pct) VALUES (tenancy.current_tenant(), ''x'', 150)',
  'INSERT INTO ledger.prudential_settings (tenant_id, updated_by, watch_max_dpd) VALUES (tenancy.current_tenant(), ''x'', 0)'];
  q text;
BEGIN
  FOREACH q IN ARRAY bad LOOP
    BEGIN
      EXECUTE q;
      RAISE EXCEPTION 'TEST FAILED F2: accepted %', q;
    EXCEPTION WHEN check_violation THEN NULL;
    END;
  END LOOP;
END $$;
INSERT INTO ledger.prudential_settings (tenant_id, updated_by) VALUES (:tA::uuid, 'user:finance');
DO $$
BEGIN
  IF (SELECT (min_core_capital, min_core_to_assets_pct, min_institutional_to_assets_pct, min_core_to_deposits_pct, min_liquidity_pct,
              watch_max_dpd, substandard_max_dpd, doubtful_max_dpd, provision_performing_pct, provision_watch_pct,
              provision_substandard_pct, provision_doubtful_pct, provision_loss_pct)::text
        FROM ledger.prudential_settings) <> '(10000000.00,10.00,8.00,8.00,15.00,30,180,360,1.00,5.00,25.00,50.00,100.00)' THEN
    RAISE EXCEPTION 'TEST FAILED F2: defaults differ from LN 95/2010 Regs 9, 13, 41 and 44';
  END IF;
END $$;
COMMIT;
\echo PASS F2 prudential settings are coherent and default to the regulations

-- F3: another tenant sees neither.
BEGIN;
SET LOCAL app.tenant_id = :tB;
DO $$
BEGIN
  IF (SELECT count(*) FROM ledger.regulatory_mapping) <> 0 OR (SELECT count(*) FROM ledger.prudential_settings) <> 0 THEN
    RAISE EXCEPTION 'TEST FAILED F3: tenant B sees tenant A''s prudential settings or mapping';
  END IF;
  BEGIN
    INSERT INTO ledger.regulatory_mapping (tenant_id, account_code, line, updated_by)
    VALUES ('00000000-0000-0000-0000-0000000000a1', 'PRU-BANK', 'CASH', 'x');
    RAISE EXCEPTION 'TEST FAILED F3: wrote another tenant''s mapping';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
END $$;
COMMIT;
\echo PASS F3 prudential settings and mapping are isolated per tenant
