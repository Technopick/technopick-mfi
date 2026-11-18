-- =============================================================================
-- V13: Operations close-out of M-Pesa items whose outcome the system could not learn by itself.
--   M1  one person proposes an outcome with evidence, a different person approves or rejects
--   M2  at most one pending-or-approved resolution per item
--   M3  a paid outcome carries the M-Pesa receipt / transaction id and amount; not-paid carries none
--   M4  decisions are final; nothing is changed after the decision except linking the result;
--       nothing is deleted
-- =============================================================================
CREATE TABLE payments.mpesa_resolution (
    tenant_id       uuid          NOT NULL,
    resolution_id   uuid          NOT NULL,
    item_type       varchar(16)   NOT NULL CHECK (item_type IN ('STK_REQUEST', 'B2C_DISBURSEMENT')),
    item_id         uuid          NOT NULL,
    outcome         varchar(16)   NOT NULL CHECK (outcome IN ('PAID', 'NOT_PAID')),
    provider_ref    varchar(20)   CHECK (provider_ref ~ '^[A-Z0-9]{8,20}$'),
    amount          numeric(19,4) CHECK (amount > 0),
    evidence        varchar(500)  NOT NULL CHECK (length(trim(evidence)) >= 10),
    status          varchar(16)   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    requested_by    varchar(100)  NOT NULL,
    requested_at    timestamptz   NOT NULL DEFAULT clock_timestamp(),
    decided_by      varchar(100),
    decided_at      timestamptz,
    result_status   varchar(16),
    PRIMARY KEY (tenant_id, resolution_id),
    CHECK ((outcome = 'PAID') = (provider_ref IS NOT NULL AND amount IS NOT NULL)),
    CHECK (outcome = 'PAID' OR (provider_ref IS NULL AND amount IS NULL)),
    CHECK (decided_by IS NULL OR decided_by <> requested_by),
    CHECK ((status = 'PENDING') = (decided_by IS NULL)),
    CHECK ((status = 'APPROVED') = (result_status IS NOT NULL))
);
CREATE UNIQUE INDEX mpesa_resolution_one_live ON payments.mpesa_resolution (tenant_id, item_type, item_id)
    WHERE status IN ('PENDING', 'APPROVED');

CREATE FUNCTION payments.guard_mpesa_resolution() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'RESOLUTION_IMMUTABLE: resolutions are never deleted' USING ERRCODE = 'P0001';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PENDING' THEN
            RAISE EXCEPTION 'RESOLUTION_STATUS: resolutions start PENDING' USING ERRCODE = 'P0001';
        END IF;
        RETURN NEW;
    END IF;
    IF (to_jsonb(NEW) - 'status' - 'decided_by' - 'decided_at' - 'result_status')
       IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'decided_by' - 'decided_at' - 'result_status') THEN
        RAISE EXCEPTION 'RESOLUTION_IMMUTABLE: the proposal never changes' USING ERRCODE = 'P0001';
    END IF;
    IF OLD.status <> 'PENDING' THEN
        RAISE EXCEPTION 'RESOLUTION_FINAL: resolution % is %', OLD.resolution_id, OLD.status USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status NOT IN ('APPROVED', 'REJECTED') THEN
        RAISE EXCEPTION 'RESOLUTION_STATUS: PENDING -> % not allowed', NEW.status USING ERRCODE = 'P0001';
    END IF;
    NEW.decided_at := clock_timestamp();
    RETURN NEW;
END $$;
CREATE TRIGGER mpesa_resolution_guard BEFORE INSERT OR UPDATE OR DELETE ON payments.mpesa_resolution
    FOR EACH ROW EXECUTE FUNCTION payments.guard_mpesa_resolution();

ALTER TABLE payments.mpesa_resolution ENABLE ROW LEVEL SECURITY;
ALTER TABLE payments.mpesa_resolution FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payments.mpesa_resolution
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
GRANT SELECT, INSERT, UPDATE ON payments.mpesa_resolution TO mfi_app;
