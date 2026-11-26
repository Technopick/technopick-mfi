-- =============================================================================
-- Checks on a restored database that do not depend on the backup's fingerprint (ADR-0045).
-- Prints PASS or FAIL lines; the restore script fails if any line starts with FAIL.
-- =============================================================================
SET row_security = off;

-- Every migration recorded as applied succeeded.
SELECT CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END || ' migrations: ' || count(*) || ' failed'
  FROM public.flyway_schema_history WHERE NOT success;

-- Every journal entry balances in each currency.
SELECT CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END || ' journal entries balance: ' || count(*) || ' do not'
  FROM (SELECT tenant_id, entry_id, currency
          FROM ledger.journal_line GROUP BY tenant_id, entry_id, currency
        HAVING sum(CASE side WHEN 'D' THEN amount ELSE -amount END) <> 0) x;

-- Every journal entry has lines (an entry header without lines means a partial restore).
SELECT CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END || ' journal entries have lines: ' || count(*) || ' without'
  FROM ledger.journal_entry e
 WHERE NOT EXISTS (SELECT 1 FROM ledger.journal_line l WHERE l.tenant_id = e.tenant_id AND l.entry_id = e.entry_id);

-- Each tenant's audit chain verifies from the first link to the last.
SELECT CASE WHEN broken IS NULL THEN 'PASS' ELSE 'FAIL' END || ' audit chain ' || code || ': '
       || coalesce('broken at link ' || broken, rows || ' links intact')
  FROM (SELECT t.code, audit.verify_chain(t.tenant_id) AS broken,
               (SELECT count(*) FROM audit.staff_action a WHERE a.tenant_id = t.tenant_id) AS rows
          FROM tenancy.tenant t) x
 ORDER BY code;

-- Each tenant has a business date.
SELECT CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END || ' business dates: ' || count(*) || ' tenant(s) without one'
  FROM tenancy.tenant t WHERE t.status = 'ACTIVE'
   AND NOT EXISTS (SELECT 1 FROM tenancy.business_date b WHERE b.tenant_id = t.tenant_id);
