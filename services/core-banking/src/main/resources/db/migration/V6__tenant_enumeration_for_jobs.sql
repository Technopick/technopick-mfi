-- =============================================================================
-- V6: lets background jobs (running as mfi_app) list ACTIVE tenants without being able to
-- read tenant rows. Returns ids only; every job then binds one tenant per transaction, so
-- row-level security still applies to all data it touches.
-- =============================================================================
CREATE FUNCTION tenancy.active_tenant_ids() RETURNS SETOF uuid
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, tenancy
AS $$ SELECT tenant_id FROM tenancy.tenant WHERE status = 'ACTIVE' ORDER BY tenant_id $$;

REVOKE ALL ON FUNCTION tenancy.active_tenant_ids() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenancy.active_tenant_ids() TO mfi_app;
