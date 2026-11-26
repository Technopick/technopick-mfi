-- =============================================================================
-- Backup fingerprint (ADR-0045). Printed once inside the snapshot the backup was taken from,
-- and again from the restored database; the two must be identical line for line.
-- Run with row security off by a role that may bypass it (mfi_backup, or an administrator).
-- One "kind|key|value" line per fact, sorted, nothing that changes on restore (no OIDs,
-- no timestamps of the restore itself, no statistics).
-- =============================================================================
SET row_security = off;
-- row text must not depend on the session (restores may run on another server)
SET TimeZone = 'UTC';
SET DateStyle = 'ISO, YMD';
SET IntervalStyle = 'postgres';
SET extra_float_digits = 1;

SELECT line FROM (
    -- schema version
    SELECT 'flyway|version|' || coalesce((SELECT version FROM public.flyway_schema_history
                                          WHERE success ORDER BY installed_rank DESC LIMIT 1), 'none') AS line
    UNION ALL
    SELECT 'flyway|failed|' || count(*) FROM public.flyway_schema_history WHERE NOT success
    UNION ALL
    -- exact row count of every table holding data (partitions counted one by one)
    SELECT 'rows|' || n.nspname || '.' || c.relname || '|' ||
           (xpath('/row/c/text()', query_to_xml(format('SELECT count(*) AS c FROM %I.%I', n.nspname, c.relname),
                                                false, true, '')))[1]::text
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE c.relkind = 'r' AND n.nspname NOT IN ('pg_catalog', 'information_schema') AND n.nspname NOT LIKE 'pg_toast%'
    UNION ALL
    -- the ledger, per tenant and currency: debits, credits, entries
    SELECT 'ledger|' || tenant_id || '/' || currency || '|D=' || sum(amount) FILTER (WHERE side = 'D')
           || ' C=' || sum(amount) FILTER (WHERE side = 'C') || ' entries=' || count(DISTINCT entry_id)
      FROM ledger.journal_line GROUP BY tenant_id, currency
    UNION ALL
    -- balance of every account (catches an amount moved between accounts with totals unchanged)
    SELECT 'account|' || tenant_id || '/' || account_code || '/' || branch_code || '|' ||
           sum(CASE side WHEN 'D' THEN amount ELSE -amount END)
      FROM ledger.journal_line GROUP BY tenant_id, account_code, branch_code
    UNION ALL
    -- each tenant's business date and the head of its audit chain
    SELECT 'business_date|' || tenant_id || '|' || current_date_ FROM tenancy.business_date
    UNION ALL
    SELECT 'audit_head|' || tenant_id || '|' || max(chain_no) || '/' ||
           (array_agg(hash ORDER BY chain_no DESC))[1]
      FROM audit.staff_action GROUP BY tenant_id
    UNION ALL
    -- data the counts above cannot see changing: per table, the sum of a 60-bit hash of every
    -- row's text (order-free, so no table is sorted or held in memory)
    SELECT 'digest|' || n.nspname || '.' || c.relname || '|' ||
           (xpath('/row/d/text()', query_to_xml(
               format('SELECT coalesce(sum((''x'' || substr(md5(t::text), 1, 15))::bit(60)::bigint::numeric), 0) AS d FROM %I.%I t',
                      n.nspname, c.relname), false, true, '')))[1]::text
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE c.relkind = 'r' AND n.nspname NOT IN ('pg_catalog', 'information_schema', 'public') AND n.nspname NOT LIKE 'pg_toast%'
) f
ORDER BY line;
