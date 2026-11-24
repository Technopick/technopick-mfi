-- =============================================================================
-- V31 (increment 34, ADR-0034): member PIN and USSD sessions.
--
-- A member uses USSD with a 4-digit PIN. Staff never see or set the PIN: a teller, branch
-- manager or operations user asks for a PIN setup (first time or reset), a second person
-- approves it, and that opens a short window in which the member, from their registered phone,
-- confirms their ID number and chooses a PIN. Only a bcrypt hash is stored. Wrong PINs lock the
-- PIN until a new approved setup.
--
-- The database enforces: setups are maker-checker and only for active members; a PIN window
-- opens only from an approval just made; a locked PIN stays locked until a new approved setup;
-- only bcrypt hashes are stored; USSD sessions keep their phone; channel events are append-only.
-- =============================================================================
CREATE SCHEMA member;

CREATE TABLE member.pin_setup (
    tenant_id        uuid         NOT NULL,
    setup_id         uuid         NOT NULL,
    idempotency_key  varchar(80)  NOT NULL,
    customer_id      uuid         NOT NULL,
    kind             varchar(8)   NOT NULL CHECK (kind IN ('FIRST', 'RESET')),
    reason           varchar(300) NOT NULL CHECK (length(btrim(reason)) >= 10),
    status           varchar(10)  NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED')),
    requested_by     varchar(100) NOT NULL,
    requested_at     timestamptz  NOT NULL DEFAULT clock_timestamp(),
    decided_by       varchar(100),
    decided_at       timestamptz,
    decision_reason  varchar(300),
    PRIMARY KEY (tenant_id, setup_id),
    UNIQUE (tenant_id, idempotency_key),
    FOREIGN KEY (tenant_id, customer_id) REFERENCES customer.customer (tenant_id, customer_id),
    CHECK (decided_by IS NULL OR decided_by <> requested_by),
    CHECK ((status = 'REQUESTED') = (decided_by IS NULL)),
    CHECK ((status = 'REQUESTED') = (decided_at IS NULL))
);
CREATE UNIQUE INDEX pin_setup_one_open ON member.pin_setup (tenant_id, customer_id) WHERE status = 'REQUESTED';

CREATE TABLE member.pin (
    tenant_id         uuid        NOT NULL,
    customer_id       uuid        NOT NULL,
    status            varchar(10) NOT NULL CHECK (status IN ('SETUP_OPEN', 'ACTIVE', 'LOCKED')),
    -- bcrypt only: a plain or weakly hashed PIN can never be stored, even by a faulty application.
    pin_hash          varchar(60) CHECK (pin_hash ~ '^\$2[aby]\$(1[0-9]|2[0-9]|3[01])\$[./A-Za-z0-9]{53}$'),
    failed_attempts   int         NOT NULL DEFAULT 0 CHECK (failed_attempts BETWEEN 0 AND 10),
    setup_id          uuid        NOT NULL,
    setup_expires_at  timestamptz NOT NULL,
    pin_set_at        timestamptz,
    locked_at         timestamptz,
    updated_at        timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, customer_id),
    FOREIGN KEY (tenant_id, customer_id) REFERENCES customer.customer (tenant_id, customer_id),
    FOREIGN KEY (tenant_id, setup_id) REFERENCES member.pin_setup (tenant_id, setup_id),
    CHECK (status <> 'ACTIVE' OR (pin_hash IS NOT NULL AND pin_set_at IS NOT NULL)),
    CHECK (status <> 'SETUP_OPEN' OR pin_hash IS NULL),
    CHECK ((status = 'LOCKED') = (locked_at IS NOT NULL))
);

CREATE FUNCTION member.check_pin() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    s member.pin_setup%ROWTYPE;
    opening boolean;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'PIN_IMMUTABLE: a member PIN record is never deleted' USING ERRCODE = 'P0001';
    END IF;
    opening := NEW.status = 'SETUP_OPEN' AND (TG_OP = 'INSERT' OR OLD.status <> 'SETUP_OPEN' OR NEW.setup_id IS DISTINCT FROM OLD.setup_id);
    IF TG_OP = 'INSERT' AND NOT opening THEN
        RAISE EXCEPTION 'PIN_SETUP_REQUIRED: a PIN record starts with an approved setup' USING ERRCODE = 'P0001';
    END IF;
    IF opening THEN
        -- Only an approval made in the last ten minutes, for this member, not used before, opens the window.
        SELECT * INTO s FROM member.pin_setup WHERE tenant_id = NEW.tenant_id AND setup_id = NEW.setup_id;
        IF s.status IS DISTINCT FROM 'APPROVED' OR s.customer_id IS DISTINCT FROM NEW.customer_id
           OR s.decided_at < clock_timestamp() - interval '10 minutes'
           OR (TG_OP = 'UPDATE' AND NEW.setup_id = OLD.setup_id) THEN
            RAISE EXCEPTION 'PIN_SETUP_REQUIRED: a PIN window opens only from a new approved setup for this member'
                USING ERRCODE = 'P0001';
        END IF;
        IF NEW.setup_expires_at <= clock_timestamp() OR NEW.setup_expires_at > s.decided_at + interval '7 days'
           OR NEW.failed_attempts <> 0 OR NEW.locked_at IS NOT NULL THEN
            RAISE EXCEPTION 'PIN_SETUP_INVALID: a new window starts clean and lasts at most 7 days' USING ERRCODE = 'P0001';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM customer.customer c
                        WHERE c.tenant_id = NEW.tenant_id AND c.customer_id = NEW.customer_id AND c.status = 'ACTIVE') THEN
            RAISE EXCEPTION 'MEMBER_NOT_ACTIVE: a PIN is set up only for an active member' USING ERRCODE = 'P0001';
        END IF;
        NEW.updated_at := clock_timestamp();
        RETURN NEW;
    END IF;
    IF NEW.setup_id IS DISTINCT FROM OLD.setup_id OR NEW.setup_expires_at IS DISTINCT FROM OLD.setup_expires_at THEN
        RAISE EXCEPTION 'PIN_SETUP_REQUIRED: the setup changes only with a new approved setup' USING ERRCODE = 'P0001';
    END IF;
    IF OLD.status = 'LOCKED' AND (NEW.status <> 'LOCKED' OR NEW.pin_hash IS DISTINCT FROM OLD.pin_hash) THEN
        RAISE EXCEPTION 'PIN_LOCKED: a locked PIN is opened again only by a new approved setup' USING ERRCODE = 'P0001';
    END IF;
    IF OLD.status = 'SETUP_OPEN' AND NEW.status = 'ACTIVE' THEN
        IF OLD.setup_expires_at <= clock_timestamp() THEN
            RAISE EXCEPTION 'PIN_SETUP_EXPIRED: the PIN window has closed' USING ERRCODE = 'P0001';
        END IF;
        IF NEW.failed_attempts <> 0 THEN
            RAISE EXCEPTION 'PIN_SETUP_INVALID: a new PIN starts with no failed attempts' USING ERRCODE = 'P0001';
        END IF;
    ELSIF NEW.status = 'ACTIVE' AND OLD.status = 'ACTIVE' THEN
        IF NEW.pin_hash IS DISTINCT FROM OLD.pin_hash AND NEW.pin_set_at IS NOT DISTINCT FROM OLD.pin_set_at THEN
            RAISE EXCEPTION 'PIN_INVALID: a changed PIN records when it was set' USING ERRCODE = 'P0001';
        END IF;
    ELSIF NEW.status = 'LOCKED' AND OLD.status IN ('ACTIVE', 'SETUP_OPEN') THEN
        IF NEW.pin_hash IS DISTINCT FROM OLD.pin_hash THEN
            RAISE EXCEPTION 'PIN_INVALID: locking never changes the PIN' USING ERRCODE = 'P0001';
        END IF;
    ELSIF NEW.status IS DISTINCT FROM OLD.status OR (NEW.status = 'SETUP_OPEN' AND NEW.pin_hash IS NOT NULL) THEN
        RAISE EXCEPTION 'PIN_STATUS: % -> % not allowed', OLD.status, NEW.status USING ERRCODE = 'P0001';
    END IF;
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END $$;

CREATE TRIGGER pin_rules BEFORE INSERT OR UPDATE OR DELETE ON member.pin
    FOR EACH ROW EXECUTE FUNCTION member.check_pin();

CREATE FUNCTION member.check_pin_setup() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'PIN_SETUP_IMMUTABLE: setups are never deleted' USING ERRCODE = 'P0001';
    END IF;
    IF TG_OP = 'INSERT' AND NEW.status <> 'REQUESTED' THEN
        RAISE EXCEPTION 'PIN_SETUP_INVALID: a setup starts as a request' USING ERRCODE = 'P0001';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF OLD.status <> 'REQUESTED' THEN
            RAISE EXCEPTION 'PIN_SETUP_IMMUTABLE: setup already %', OLD.status USING ERRCODE = 'P0001';
        END IF;
        IF (NEW.customer_id, NEW.kind, NEW.reason, NEW.requested_by, NEW.requested_at, NEW.idempotency_key)
           IS DISTINCT FROM (OLD.customer_id, OLD.kind, OLD.reason, OLD.requested_by, OLD.requested_at, OLD.idempotency_key) THEN
            RAISE EXCEPTION 'PIN_SETUP_IMMUTABLE: only the decision is recorded later' USING ERRCODE = 'P0001';
        END IF;
    END IF;
    IF NEW.status IN ('REQUESTED', 'APPROVED') AND NOT EXISTS (
           SELECT 1 FROM customer.customer c
            WHERE c.tenant_id = NEW.tenant_id AND c.customer_id = NEW.customer_id AND c.status = 'ACTIVE') THEN
        RAISE EXCEPTION 'MEMBER_NOT_ACTIVE: a PIN is set up only for an active member' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER pin_setup_rules BEFORE INSERT OR UPDATE OR DELETE ON member.pin_setup
    FOR EACH ROW EXECUTE FUNCTION member.check_pin_setup();

-- One row per USSD session, as numbered by the aggregator.
CREATE TABLE member.ussd_session (
    tenant_id      uuid        NOT NULL,
    session_id     varchar(64) NOT NULL CHECK (session_id ~ '^[A-Za-z0-9_.:-]{1,64}$'),
    service_code   varchar(20) NOT NULL,
    msisdn         varchar(12) NOT NULL CHECK (msisdn ~ '^254[17][0-9]{8}$'),
    customer_id    uuid,
    started_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
    last_seen_at   timestamptz NOT NULL DEFAULT clock_timestamp(),
    requests       int         NOT NULL DEFAULT 1 CHECK (requests BETWEEN 1 AND 200),
    pin_verified   boolean     NOT NULL DEFAULT false,
    PRIMARY KEY (tenant_id, session_id),
    FOREIGN KEY (tenant_id, customer_id) REFERENCES customer.customer (tenant_id, customer_id)
);
CREATE INDEX ussd_session_customer ON member.ussd_session (tenant_id, customer_id, started_at);

CREATE FUNCTION member.guard_ussd_session() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'USSD_SESSION_IMMUTABLE: sessions are never deleted' USING ERRCODE = 'P0001';
    END IF;
    IF (NEW.session_id, NEW.service_code, NEW.msisdn, NEW.started_at)
       IS DISTINCT FROM (OLD.session_id, OLD.service_code, OLD.msisdn, OLD.started_at)
       OR (OLD.customer_id IS NOT NULL AND NEW.customer_id IS DISTINCT FROM OLD.customer_id)
       OR (OLD.pin_verified AND NOT NEW.pin_verified)
       OR NEW.requests < OLD.requests THEN
        RAISE EXCEPTION 'USSD_SESSION_IMMUTABLE: a session keeps its phone, member and verification' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER ussd_session_guard BEFORE UPDATE OR DELETE ON member.ussd_session
    FOR EACH ROW EXECUTE FUNCTION member.guard_ussd_session();

-- What happened on the channel, for branch staff and audit. Never the PIN or the ID number.
CREATE TABLE member.ussd_event (
    tenant_id    uuid         NOT NULL,
    event_id     bigint       GENERATED ALWAYS AS IDENTITY,
    session_id   varchar(64)  NOT NULL,
    customer_id  uuid,
    occurred_at  timestamptz  NOT NULL DEFAULT clock_timestamp(),
    event        varchar(20)  NOT NULL CHECK (event IN ('NOT_REGISTERED', 'NOT_ACTIVE', 'NO_PIN', 'PIN_LOCKED', 'PIN_OK',
                     'PIN_WRONG', 'ID_WRONG', 'SETUP_EXPIRED', 'PIN_SET', 'PIN_CHANGED', 'BALANCES', 'STATEMENT', 'LOANS',
                     'PAYMENT_PROMPT', 'PAYMENT_REFUSED', 'SESSION_EXPIRED')),
    detail       varchar(200),
    PRIMARY KEY (tenant_id, event_id),
    FOREIGN KEY (tenant_id, session_id) REFERENCES member.ussd_session (tenant_id, session_id),
    FOREIGN KEY (tenant_id, customer_id) REFERENCES customer.customer (tenant_id, customer_id)
);
CREATE INDEX ussd_event_customer ON member.ussd_event (tenant_id, customer_id, occurred_at);

CREATE FUNCTION member.guard_ussd_event() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'USSD_EVENT_IMMUTABLE: channel events are never changed or deleted' USING ERRCODE = 'P0001';
END $$;

CREATE TRIGGER ussd_event_guard BEFORE UPDATE OR DELETE ON member.ussd_event
    FOR EACH ROW EXECUTE FUNCTION member.guard_ussd_event();

ALTER TABLE member.pin_setup ENABLE ROW LEVEL SECURITY;
ALTER TABLE member.pin_setup FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON member.pin_setup
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
ALTER TABLE member.pin ENABLE ROW LEVEL SECURITY;
ALTER TABLE member.pin FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON member.pin
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
ALTER TABLE member.ussd_session ENABLE ROW LEVEL SECURITY;
ALTER TABLE member.ussd_session FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON member.ussd_session
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
ALTER TABLE member.ussd_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE member.ussd_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON member.ussd_event
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());

GRANT USAGE ON SCHEMA member TO mfi_app;
GRANT SELECT, INSERT, UPDATE ON member.pin_setup TO mfi_app;
GRANT SELECT, INSERT, UPDATE ON member.pin TO mfi_app;
GRANT SELECT, INSERT, UPDATE ON member.ussd_session TO mfi_app;
GRANT SELECT, INSERT ON member.ussd_event TO mfi_app;
