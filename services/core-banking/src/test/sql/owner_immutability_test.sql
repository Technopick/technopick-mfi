-- Runs as the schema OWNER (the migration role). Even the owner, who has all
-- privileges, must be unable to rewrite posted history: the triggers stop it.
\set ON_ERROR_STOP on
\set QUIET on

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
  TRUNCATE ledger.journal_line, ledger.journal_entry;
  RAISE EXCEPTION 'TEST FAILED O3 truncate';
EXCEPTION WHEN raise_exception THEN
  IF SQLERRM NOT LIKE 'LEDGER_IMMUTABLE%' THEN RAISE; END IF;
END $$;
\echo PASS O3 owner cannot truncate journals

DO $$
DECLARE n int;
BEGIN
  SELECT count(*) INTO n FROM ledger.journal_line;
  IF n <> 9 THEN RAISE EXCEPTION 'TEST FAILED O4: expected 9 lines intact, found %', n; END IF;
END $$;
\echo PASS O4 all 9 posted lines intact after attacks
