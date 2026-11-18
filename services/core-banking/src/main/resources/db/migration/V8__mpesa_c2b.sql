-- =============================================================================
-- V8: M-Pesa C2B (paybill) payments in core banking, and the integration runtime's durable
-- callback store.
--   M1  each provider receipt (M-Pesa TransID) is applied at most once per tenant
--   M2  a receipt is APPLIED to a deposit account or held UNMATCHED in suspense, always with
--       its posted journal entry; records are insert-only (suspense clearance is a later,
--       maker-checker flow that posts its own entries)
--   M3  inbound callbacks are stored before acknowledgement, unique per provider reference,
--       and only their processing fields can change
-- =============================================================================
CREATE TABLE payments.mpesa_paybill (
    tenant_id      uuid        NOT NULL REFERENCES tenancy.tenant (tenant_id),
    short_code     varchar(12) NOT NULL CHECK (short_code ~ '^[0-9]{5,10}$'),
    currency       char(3)     NOT NULL DEFAULT 'KES',
    branch_code    varchar(16) NOT NULL,
    gl_clearing    varchar(40) NOT NULL,
    gl_suspense    varchar(40) NOT NULL,
    max_amount     numeric(19,4) NOT NULL DEFAULT 250000 CHECK (max_amount > 0),
    active         boolean     NOT NULL DEFAULT true,
    PRIMARY KEY (tenant_id, short_code),
    FOREIGN KEY (tenant_id, branch_code) REFERENCES tenancy.branch (tenant_id, branch_code),
    FOREIGN KEY (tenant_id, gl_clearing) REFERENCES ledger.gl_account (tenant_id, account_code),
    FOREIGN KEY (tenant_id, gl_suspense) REFERENCES ledger.gl_account (tenant_id, account_code)
);

CREATE TABLE payments.provider_payment (
    tenant_id          uuid          NOT NULL,
    payment_id         uuid          NOT NULL,
    provider           varchar(16)   NOT NULL CHECK (provider IN ('MPESA_C2B')),
    provider_ref       varchar(40)   NOT NULL,
    short_code         varchar(12)   NOT NULL,
    amount             numeric(19,4) NOT NULL CHECK (amount > 0),
    currency           char(3)       NOT NULL,
    bill_ref           varchar(40),
    msisdn_masked      varchar(20),
    provider_time      timestamptz   NOT NULL,
    status             varchar(16)   NOT NULL CHECK (status IN ('APPLIED', 'UNMATCHED')),
    deposit_account_id uuid,
    journal_entry_id   uuid          NOT NULL,
    received_at        timestamptz   NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, payment_id),
    CONSTRAINT provider_payment_once UNIQUE (tenant_id, provider, provider_ref),
    CHECK ((status = 'APPLIED') = (deposit_account_id IS NOT NULL)),
    FOREIGN KEY (tenant_id, short_code) REFERENCES payments.mpesa_paybill (tenant_id, short_code),
    FOREIGN KEY (tenant_id, deposit_account_id) REFERENCES savings.deposit_account (tenant_id, account_id),
    FOREIGN KEY (tenant_id, journal_entry_id) REFERENCES ledger.journal_entry (tenant_id, entry_id)
);
CREATE TRIGGER provider_payment_immutable BEFORE UPDATE OR DELETE ON payments.provider_payment
    FOR EACH ROW EXECUTE FUNCTION ledger.reject_mutation();

ALTER TABLE payments.mpesa_paybill ENABLE ROW LEVEL SECURITY;
ALTER TABLE payments.mpesa_paybill FORCE ROW LEVEL SECURITY;
ALTER TABLE payments.provider_payment ENABLE ROW LEVEL SECURITY;
ALTER TABLE payments.provider_payment FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payments.mpesa_paybill
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
CREATE POLICY tenant_isolation ON payments.provider_payment
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
GRANT SELECT, INSERT, UPDATE ON payments.mpesa_paybill TO mfi_app;
GRANT SELECT, INSERT ON payments.provider_payment TO mfi_app;

-- ---------------------------------------------------------------------------
-- Integration runtime (role mfi_relay): durable inbound callback store
-- ---------------------------------------------------------------------------
CREATE SCHEMA IF NOT EXISTS integration;

CREATE TABLE integration.provider_callback (
    callback_id   uuid          PRIMARY KEY,
    provider      varchar(16)   NOT NULL,
    callback_type varchar(32)   NOT NULL,
    provider_ref  varchar(60)   NOT NULL,
    tenant_id     uuid          NOT NULL REFERENCES tenancy.tenant (tenant_id),
    payload       jsonb         NOT NULL,
    received_at   timestamptz   NOT NULL DEFAULT clock_timestamp(),
    status        varchar(16)   NOT NULL DEFAULT 'RECEIVED' CHECK (status IN ('RECEIVED', 'APPLIED', 'FAILED')),
    attempts      int           NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_attempt_at timestamptz,
    last_error    varchar(500),
    outcome       varchar(40),
    CONSTRAINT provider_callback_once UNIQUE (provider, callback_type, provider_ref)
);
CREATE INDEX provider_callback_pending_idx ON integration.provider_callback (received_at)
    WHERE status <> 'APPLIED';

CREATE FUNCTION integration.guard_callback() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'CALLBACK_IMMUTABLE: callbacks are never deleted' USING ERRCODE = 'P0001';
    END IF;
    IF (to_jsonb(NEW) - 'status' - 'attempts' - 'last_attempt_at' - 'last_error' - 'outcome')
       IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'attempts' - 'last_attempt_at' - 'last_error' - 'outcome') THEN
        RAISE EXCEPTION 'CALLBACK_IMMUTABLE: the received payload never changes' USING ERRCODE = 'P0001';
    END IF;
    IF OLD.status = 'APPLIED' THEN
        RAISE EXCEPTION 'CALLBACK_IMMUTABLE: callback % already applied', OLD.callback_id USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER provider_callback_guard BEFORE UPDATE OR DELETE ON integration.provider_callback
    FOR EACH ROW EXECUTE FUNCTION integration.guard_callback();
CREATE TRIGGER provider_callback_no_truncate BEFORE TRUNCATE ON integration.provider_callback
    FOR EACH STATEMENT EXECUTE FUNCTION integration.guard_callback();

REVOKE ALL ON SCHEMA integration FROM PUBLIC;
GRANT USAGE ON SCHEMA integration TO mfi_relay;
GRANT SELECT, INSERT ON integration.provider_callback TO mfi_relay;
GRANT UPDATE (status, attempts, last_attempt_at, last_error, outcome) ON integration.provider_callback TO mfi_relay;
