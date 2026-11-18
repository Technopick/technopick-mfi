-- Runs as the schema OWNER (the migration role). Even the owner, who has all
-- privileges, must be unable to rewrite posted history: the triggers stop it.
\set ON_ERROR_STOP on
\set QUIET on
SELECT count(*) AS lines_before FROM ledger.journal_line \gset

DO $$
BEGIN
  UPDATE ledger.journal_line SET amount = amount + 1;
  RAISE EXCEPTION 'TEST FAILED O1 update';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'LEDGER_IMMUTABLE%' THEN RAISE; END IF;
END $$;
\echo PASS O1 owner cannot update posted lines

DO $$
BEGIN
  DELETE FROM ledger.journal_entry;
  RAISE EXCEPTION 'TEST FAILED O2 delete';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'LEDGER_IMMUTABLE%' THEN RAISE; END IF;
END $$;
\echo PASS O2 owner cannot delete posted entries

DO $$
BEGIN
  -- CASCADE reaches every referencing table (holds, ...); some trigger must refuse.
  TRUNCATE ledger.journal_line, ledger.journal_entry CASCADE;
  RAISE EXCEPTION 'TEST FAILED O3 truncate';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'LEDGER_IMMUTABLE%' AND SQLERRM NOT LIKE 'SAVINGS_HOLD_IMMUTABLE%' THEN RAISE; END IF;
END $$;
\echo PASS O3 owner cannot truncate journals

SELECT set_config('mfi.lines_before', :'lines_before', false) \gset
DO $$
DECLARE n int;
BEGIN
  SELECT count(*) INTO n FROM ledger.journal_line;
  IF n <> current_setting('mfi.lines_before')::int THEN
    RAISE EXCEPTION 'TEST FAILED O4: expected % lines intact, found %', current_setting('mfi.lines_before'), n;
  END IF;
END $$;
\echo PASS O4 every posted line intact after attacks

DO $$
BEGIN
  DELETE FROM savings.account_hold;
  RAISE EXCEPTION 'TEST FAILED O5';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'SAVINGS_HOLD_IMMUTABLE%' THEN RAISE; END IF;
END $$;
\echo PASS O5 owner cannot delete holds
