-- =============================================================================
-- V24: Audit trail of staff actions.
--   S1  one row per audited API request: who (actor, roles), what (method, route, the actual
--       path with its identifiers), result (HTTP status), when, from where; no request bodies
--   S2  append-only: the application can insert and read, never change or delete
--   S3  tamper-evident: each row carries sha256(previous row's hash || this row's content), chained
--       per tenant in the order rows are written; audit.verify_chain() reports the first row that breaks it
-- =============================================================================

CREATE SCHEMA audit;
GRANT USAGE ON SCHEMA audit TO mfi_app;

CREATE TABLE audit.staff_action (
    tenant_id     uuid          NOT NULL REFERENCES tenancy.tenant (tenant_id),
    seq           bigint        GENERATED ALWAYS AS IDENTITY,
    chain_no      bigint        NOT NULL,
    occurred_at   timestamptz   NOT NULL,
    actor         varchar(100)  NOT NULL,
    roles         varchar(300)  NOT NULL,
    method        varchar(8)    NOT NULL CHECK (method IN ('GET', 'POST', 'PUT', 'PATCH', 'DELETE')),
    route         varchar(200)  NOT NULL,
    path          varchar(500)  NOT NULL,
    query         varchar(500),
    status        int           NOT NULL CHECK (status BETWEEN 100 AND 599),
    duration_ms   int           NOT NULL CHECK (duration_ms >= 0),
    client_ip     varchar(64),
    request_id    varchar(64)   NOT NULL,
    prev_hash     char(64)      NOT NULL,
    hash          char(64)      NOT NULL,
    PRIMARY KEY (tenant_id, seq),
    CONSTRAINT staff_action_chain_no UNIQUE (tenant_id, chain_no)
);
CREATE INDEX staff_action_actor_idx ON audit.staff_action (tenant_id, actor, occurred_at DESC);
CREATE INDEX staff_action_time_idx ON audit.staff_action (tenant_id, occurred_at DESC);

CREATE FUNCTION audit.row_digest(p_prev char(64), r audit.staff_action) RETURNS char(64)
    LANGUAGE sql IMMUTABLE
AS $$
    SELECT encode(sha256(convert_to(concat_ws('|', p_prev, r.tenant_id::text, r.chain_no::text,
        to_char(r.occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US'), r.actor, r.roles, r.method, r.route, r.path,
        COALESCE(r.query, ''), r.status::text, r.duration_ms::text, COALESCE(r.client_ip, ''), r.request_id), 'UTF8')), 'hex')
$$;

-- S3: the database computes the chain; whatever the application sends for the hashes is replaced.
CREATE FUNCTION audit.chain_staff_action() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended('audit.staff_action:' || NEW.tenant_id::text, 0));
    -- Chain in the order rows take the lock (identity values can be handed out in another order).
    SELECT hash, chain_no + 1 INTO NEW.prev_hash, NEW.chain_no FROM audit.staff_action
     WHERE tenant_id = NEW.tenant_id ORDER BY chain_no DESC LIMIT 1;
    NEW.prev_hash := COALESCE(NEW.prev_hash, repeat('0', 64));
    NEW.chain_no := COALESCE(NEW.chain_no, 1);
    NEW.hash := audit.row_digest(NEW.prev_hash, NEW);
    RETURN NEW;
END $$;
CREATE TRIGGER staff_action_chain BEFORE INSERT ON audit.staff_action
    FOR EACH ROW EXECUTE FUNCTION audit.chain_staff_action();
CREATE TRIGGER staff_action_immutable BEFORE UPDATE OR DELETE ON audit.staff_action
    FOR EACH ROW EXECUTE FUNCTION ledger.reject_mutation();

-- Returns NULL when the chain is intact, otherwise the first chain number that does not verify
-- (a changed, removed or reordered row breaks every link after it).
CREATE FUNCTION audit.verify_chain(p_tenant uuid) RETURNS bigint
    LANGUAGE plpgsql STABLE
AS $$
DECLARE
    r audit.staff_action;
    v_prev char(64) := repeat('0', 64);
BEGIN
    FOR r IN SELECT * FROM audit.staff_action WHERE tenant_id = p_tenant ORDER BY chain_no LOOP
        IF r.prev_hash <> v_prev OR r.hash <> audit.row_digest(v_prev, r) THEN
            RETURN r.chain_no;
        END IF;
        v_prev := r.hash;
    END LOOP;
    RETURN NULL;
END $$;

ALTER TABLE audit.staff_action ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.staff_action FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON audit.staff_action
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
GRANT SELECT, INSERT ON audit.staff_action TO mfi_app;
GRANT EXECUTE ON FUNCTION audit.verify_chain(uuid), audit.row_digest(char, audit.staff_action) TO mfi_app;
