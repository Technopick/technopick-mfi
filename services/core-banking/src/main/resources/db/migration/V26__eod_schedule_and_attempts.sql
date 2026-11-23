-- =============================================================================
-- V26 (increment 29, ADR-0029): scheduled end of day and its attempt history.
--
-- ledger.eod_schedule: one row per tenant, set by a supervisor. Off until switched on. The
-- cut-off is a local time in the tenant's time zone (tenancy.tenant.time_zone): business date D
-- is due to close once D at run_at has passed there.
--
-- ledger.eod_attempt: every start of end of day, scheduled or by a person, and how it ended.
-- Written by the application; an attempt can only move once from RUNNING to a final outcome, and
-- nothing is deleted, so the history is a reliable record of what happened overnight.
-- =============================================================================
CREATE TABLE ledger.eod_schedule (
    tenant_id          uuid        PRIMARY KEY REFERENCES tenancy.tenant (tenant_id),
    enabled            boolean     NOT NULL DEFAULT false,
    run_at             time        NOT NULL DEFAULT '21:00',
    overrun_minutes    int         NOT NULL DEFAULT 60 CHECK (overrun_minutes BETWEEN 5 AND 720),
    max_attempts       int         NOT NULL DEFAULT 3 CHECK (max_attempts BETWEEN 1 AND 10),
    retry_minutes      int         NOT NULL DEFAULT 15 CHECK (retry_minutes BETWEEN 5 AND 240),
    max_catch_up_days  int         NOT NULL DEFAULT 3 CHECK (max_catch_up_days BETWEEN 0 AND 7),
    updated_by         varchar(200) NOT NULL,
    updated_at         timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (run_at = date_trunc('minute', run_at))
);

CREATE TABLE ledger.eod_attempt (
    tenant_id      uuid        NOT NULL REFERENCES tenancy.tenant (tenant_id),
    attempt_id     bigint      GENERATED ALWAYS AS IDENTITY,
    business_date  date        NOT NULL,
    trigger        varchar(16) NOT NULL CHECK (trigger IN ('SCHEDULED', 'MANUAL')),
    actor          varchar(200) NOT NULL,
    started_at     timestamptz NOT NULL,
    finished_at    timestamptz,
    outcome        varchar(16) NOT NULL DEFAULT 'RUNNING'
        CHECK (outcome IN ('RUNNING', 'COMPLETED', 'BLOCKED', 'FAILED', 'SKIPPED', 'INTERRUPTED')),
    reason         varchar(500),
    PRIMARY KEY (tenant_id, attempt_id),
    CHECK ((outcome = 'RUNNING') = (finished_at IS NULL)),
    CHECK (finished_at IS NULL OR finished_at >= started_at),
    CHECK (outcome IN ('RUNNING', 'COMPLETED') OR reason IS NOT NULL)
);
CREATE INDEX eod_attempt_recent ON ledger.eod_attempt (tenant_id, business_date, attempt_id DESC);

CREATE FUNCTION ledger.guard_eod_attempt() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'EOD_ATTEMPT_IMMUTABLE: attempts are never deleted' USING ERRCODE = 'P0001';
    END IF;
    IF OLD.outcome <> 'RUNNING' THEN
        RAISE EXCEPTION 'EOD_ATTEMPT_IMMUTABLE: attempt % already ended %', OLD.attempt_id, OLD.outcome USING ERRCODE = 'P0001';
    END IF;
    IF (NEW.tenant_id, NEW.attempt_id, NEW.business_date, NEW.trigger, NEW.actor, NEW.started_at)
       IS DISTINCT FROM (OLD.tenant_id, OLD.attempt_id, OLD.business_date, OLD.trigger, OLD.actor, OLD.started_at) THEN
        RAISE EXCEPTION 'EOD_ATTEMPT_IMMUTABLE: only the outcome of an attempt is recorded later' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER eod_attempt_guard BEFORE UPDATE OR DELETE ON ledger.eod_attempt
    FOR EACH ROW EXECUTE FUNCTION ledger.guard_eod_attempt();

ALTER TABLE ledger.eod_schedule ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.eod_schedule FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ledger.eod_schedule
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
ALTER TABLE ledger.eod_attempt ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.eod_attempt FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ledger.eod_attempt
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());

GRANT SELECT, INSERT, UPDATE ON ledger.eod_schedule TO mfi_app;
GRANT SELECT, INSERT, UPDATE ON ledger.eod_attempt TO mfi_app;
