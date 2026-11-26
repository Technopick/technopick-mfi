-- =============================================================================
-- V42: operational health figures for monitoring (ADR-0045).
--
-- The application role reads every table through row-level security bound to one tenant. Monitoring
-- needs a few counts for every tenant at once, so these functions bind each active tenant in turn
-- (transaction-local, restored afterwards) and return numbers only: no customer, account or amount
-- data leaves them. Both are SECURITY DEFINER with a fixed search_path and executable by mfi_app.
-- =============================================================================
CREATE SCHEMA ops;
GRANT USAGE ON SCHEMA ops TO mfi_app;

CREATE FUNCTION ops.health_snapshot()
    RETURNS TABLE (tenant_code varchar, metric text, value numeric)
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER
    SET search_path = pg_catalog
AS $$
DECLARE
    v_prev text := current_setting('app.tenant_id', true);
    t record;
BEGIN
    FOR t IN SELECT x.tenant_id, x.code, x.time_zone FROM tenancy.tenant x WHERE x.status = 'ACTIVE' ORDER BY x.code LOOP
        PERFORM set_config('app.tenant_id', t.tenant_id::text, true);
        RETURN QUERY
        -- the open business date against today in the tenant's time zone (0 or -1 when up to date)
        -- (NULL when the tenant has no business date yet)
        SELECT t.code, 'eod_days_behind'::text,
               (SELECT ((now() AT TIME ZONE t.time_zone)::date - b.current_date_)::numeric
                  FROM tenancy.business_date b WHERE b.tenant_id = t.tenant_id)
        UNION ALL
        SELECT t.code, 'eod_running_seconds',
               coalesce(max(extract(epoch FROM now() - r.started_at)), 0)::numeric
          FROM ledger.eod_run r WHERE r.tenant_id = t.tenant_id AND r.status = 'RUNNING'
        UNION ALL
        SELECT t.code, 'eod_last_attempt_failed',
               coalesce((SELECT CASE WHEN a.outcome IN ('FAILED', 'BLOCKED', 'INTERRUPTED') THEN 1 ELSE 0 END
                           FROM ledger.eod_attempt a WHERE a.tenant_id = t.tenant_id
                          ORDER BY a.attempt_id DESC LIMIT 1), 0)::numeric
        UNION ALL
        -- reconciliation differences recorded at the latest close
        SELECT t.code, 'reconciliation_differences', count(*)::numeric
          FROM ledger.reconciliation_result r
         WHERE r.tenant_id = t.tenant_id AND r.difference <> 0
           AND r.business_date = (SELECT max(x.business_date) FROM ledger.reconciliation_result x WHERE x.tenant_id = t.tenant_id)
        UNION ALL
        SELECT t.code, 'outbox_unpublished', count(*)::numeric
          FROM messaging.outbox_event o WHERE o.tenant_id = t.tenant_id AND o.published_at IS NULL
        UNION ALL
        SELECT t.code, 'outbox_oldest_unpublished_seconds',
               coalesce(extract(epoch FROM now() - min(o.occurred_at)), 0)::numeric
          FROM messaging.outbox_event o WHERE o.tenant_id = t.tenant_id AND o.published_at IS NULL
        UNION ALL
        -- SMS that should have gone more than 15 minutes ago, and SMS that failed in the last day
        SELECT t.code, 'sms_overdue', count(*)::numeric
          FROM notify.sms_message m
         WHERE m.tenant_id = t.tenant_id AND m.status IN ('QUEUED', 'DISPATCHED') AND m.not_before < now() - interval '15 minutes'
        UNION ALL
        SELECT t.code, 'sms_failed_24h', count(*)::numeric
          FROM notify.sms_message m WHERE m.tenant_id = t.tenant_id AND m.status = 'FAILED' AND m.failed_at > now() - interval '24 hours'
        UNION ALL
        -- loan payouts sent to M-Pesa whose outcome is still unknown after 15 minutes
        SELECT t.code, 'b2c_outcome_unknown', count(*)::numeric
          FROM payments.b2c_disbursement d
         WHERE d.tenant_id = t.tenant_id AND d.status IN ('DISPATCHED', 'SUBMITTED') AND d.updated_at < now() - interval '15 minutes';
    END LOOP;
    PERFORM set_config('app.tenant_id', coalesce(v_prev, ''), true);
END $$;

-- Walks every tenant's audit chain (ADR-0027). Slower than the snapshot: run it hourly, not per scrape.
CREATE FUNCTION ops.audit_chain_status()
    RETURNS TABLE (tenant_code varchar, broken_at bigint, links bigint)
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER
    SET search_path = pg_catalog
AS $$
DECLARE
    v_prev text := current_setting('app.tenant_id', true);
    t record;
BEGIN
    FOR t IN SELECT x.tenant_id, x.code FROM tenancy.tenant x WHERE x.status = 'ACTIVE' ORDER BY x.code LOOP
        PERFORM set_config('app.tenant_id', t.tenant_id::text, true);
        tenant_code := t.code;
        broken_at := audit.verify_chain(t.tenant_id);
        SELECT count(*) INTO links FROM audit.staff_action a WHERE a.tenant_id = t.tenant_id;
        RETURN NEXT;
    END LOOP;
    PERFORM set_config('app.tenant_id', coalesce(v_prev, ''), true);
END $$;

REVOKE ALL ON FUNCTION ops.health_snapshot(), ops.audit_chain_status() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ops.health_snapshot(), ops.audit_chain_status() TO mfi_app;
