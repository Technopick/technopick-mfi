-- =============================================================================
-- V36: M-Pesa B2C transaction status queries and float checks (ADR-0039).
--   Q1  a status query is asked only about a disbursement whose outcome is unknown (DISPATCHED or
--       SUBMITTED); at most one open query (PENDING or SENT) per disbursement
--   Q2  queries move PENDING -> SENT | SEND_FAILED | NO_ANSWER, SENT -> ANSWERED | NO_ANSWER;
--       the other states are final; what was asked and who asked never change; nothing is deleted
--   Q3  an answer records the result code and, when M-Pesa reports the payment, its receipt and
--       amount; an answer completes the disbursement only through the normal result path
--   F1  float settings per B2C account: a threshold that is not negative and a daily check hour
--   F2  balance checks follow the same states as queries; one open check per account
--   F3  an answered check with result 0 carries the float account's balances and the ledger
--       clearing balance at the time of the answer, re-derived here
-- =============================================================================
CREATE TABLE payments.b2c_status_query (
    tenant_id         uuid          NOT NULL,
    query_id          uuid          NOT NULL,
    disbursement_id   uuid          NOT NULL,
    reason            varchar(24)   NOT NULL
        CHECK (reason IN ('SEND_OUTCOME_UNKNOWN', 'TIMEOUT_UNKNOWN', 'RESULT_OVERDUE', 'MANUAL')),
    status            varchar(12)   NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'SENT', 'SEND_FAILED', 'ANSWERED', 'NO_ANSWER')),
    requested_by      varchar(100)  NOT NULL,
    requested_at      timestamptz   NOT NULL DEFAULT clock_timestamp(),
    sent_at           timestamptz,
    answered_at       timestamptz,
    result_code       varchar(20),
    result_desc       varchar(200),
    reported_status   varchar(40),
    reported_receipt  varchar(20)   CHECK (reported_receipt ~ '^[A-Z0-9]{8,20}$'),
    reported_amount   numeric(19,4) CHECK (reported_amount > 0),
    outcome           varchar(24)
        CHECK (outcome IN ('COMPLETED_DISBURSEMENT', 'AMOUNT_MISMATCH', 'ALREADY_FINAL', 'NOT_CONFIRMED')),
    PRIMARY KEY (tenant_id, query_id),
    FOREIGN KEY (tenant_id, disbursement_id) REFERENCES payments.b2c_disbursement (tenant_id, disbursement_id),
    CHECK ((status IN ('SENT', 'ANSWERED')) <= (sent_at IS NOT NULL)),
    CHECK ((status = 'ANSWERED') = (answered_at IS NOT NULL AND result_code IS NOT NULL AND outcome IS NOT NULL)),
    CHECK (status = 'ANSWERED' OR (reported_status IS NULL AND reported_receipt IS NULL AND reported_amount IS NULL)),
    CHECK (status IN ('SEND_FAILED', 'NO_ANSWER', 'ANSWERED') OR result_desc IS NULL)
);
CREATE UNIQUE INDEX b2c_status_query_one_open ON payments.b2c_status_query (tenant_id, disbursement_id)
    WHERE status IN ('PENDING', 'SENT');
CREATE INDEX b2c_status_query_by_disbursement ON payments.b2c_status_query (tenant_id, disbursement_id, requested_at);

CREATE FUNCTION payments.guard_b2c_status_query() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_status varchar;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'B2C_QUERY_IMMUTABLE: status queries are never deleted' USING ERRCODE = 'P0001';
    END IF;
    IF TG_OP = 'INSERT' THEN
        SELECT status INTO v_status FROM payments.b2c_disbursement
         WHERE tenant_id = NEW.tenant_id AND disbursement_id = NEW.disbursement_id;
        IF v_status NOT IN ('DISPATCHED', 'SUBMITTED') THEN
            RAISE EXCEPTION 'B2C_QUERY_INVALID: disbursement is %, its outcome is known', v_status USING ERRCODE = 'P0001';
        END IF;
        IF NEW.status <> 'PENDING' THEN
            RAISE EXCEPTION 'B2C_QUERY_INVALID: queries start PENDING' USING ERRCODE = 'P0001';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status NOT IN ('PENDING', 'SENT') THEN
        RAISE EXCEPTION 'B2C_QUERY_IMMUTABLE: query is %', OLD.status USING ERRCODE = 'P0001';
    END IF;
    IF NEW.query_id <> OLD.query_id OR NEW.disbursement_id <> OLD.disbursement_id OR NEW.reason <> OLD.reason
       OR NEW.requested_by <> OLD.requested_by OR NEW.requested_at <> OLD.requested_at THEN
        RAISE EXCEPTION 'B2C_QUERY_IMMUTABLE: what was asked never changes' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
           (OLD.status = 'PENDING' AND NEW.status IN ('SENT', 'SEND_FAILED', 'NO_ANSWER'))
        OR (OLD.status = 'SENT' AND NEW.status IN ('ANSWERED', 'NO_ANSWER'))) THEN
        RAISE EXCEPTION 'B2C_QUERY_STATUS: % -> % not allowed', OLD.status, NEW.status USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER b2c_status_query_guard BEFORE INSERT OR UPDATE OR DELETE ON payments.b2c_status_query
    FOR EACH ROW EXECUTE FUNCTION payments.guard_b2c_status_query();

CREATE TABLE payments.b2c_float_settings (
    tenant_id          uuid          NOT NULL,
    short_code         varchar(12)   NOT NULL,
    float_account_name varchar(60)   NOT NULL DEFAULT 'Utility Account',
    low_float_threshold numeric(19,4) NOT NULL DEFAULT 0 CHECK (low_float_threshold >= 0),
    daily_check_hour   int           NOT NULL DEFAULT 7 CHECK (daily_check_hour BETWEEN 0 AND 23),
    updated_by         varchar(100)  NOT NULL,
    updated_at         timestamptz   NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, short_code),
    FOREIGN KEY (tenant_id, short_code) REFERENCES payments.mpesa_b2c_account (tenant_id, short_code)
);

CREATE TABLE payments.b2c_balance_check (
    tenant_id         uuid          NOT NULL,
    check_id          uuid          NOT NULL,
    short_code        varchar(12)   NOT NULL,
    reason            varchar(12)   NOT NULL CHECK (reason IN ('DAILY', 'MANUAL')),
    status            varchar(12)   NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'SENT', 'SEND_FAILED', 'ANSWERED', 'NO_ANSWER')),
    requested_by      varchar(100)  NOT NULL,
    requested_at      timestamptz   NOT NULL DEFAULT clock_timestamp(),
    sent_at           timestamptz,
    answered_at       timestamptz,
    result_code       varchar(20),
    result_desc       varchar(200),
    float_account     varchar(60),
    float_current     numeric(19,4),
    float_available   numeric(19,4),
    accounts          jsonb,
    ledger_balance    numeric(19,4),
    committed         numeric(19,4) CHECK (committed >= 0),
    PRIMARY KEY (tenant_id, check_id),
    FOREIGN KEY (tenant_id, short_code) REFERENCES payments.mpesa_b2c_account (tenant_id, short_code),
    CHECK ((status IN ('SENT', 'ANSWERED')) <= (sent_at IS NOT NULL)),
    CHECK ((status = 'ANSWERED') = (answered_at IS NOT NULL AND result_code IS NOT NULL)),
    CHECK ((status = 'ANSWERED' AND result_code = '0')
           = (float_account IS NOT NULL AND float_current IS NOT NULL AND float_available IS NOT NULL
              AND accounts IS NOT NULL AND ledger_balance IS NOT NULL AND committed IS NOT NULL))
);
CREATE UNIQUE INDEX b2c_balance_check_one_open ON payments.b2c_balance_check (tenant_id, short_code)
    WHERE status IN ('PENDING', 'SENT');
CREATE INDEX b2c_balance_check_recent ON payments.b2c_balance_check (tenant_id, short_code, requested_at DESC);

CREATE FUNCTION payments.guard_b2c_balance_check() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_gl     varchar;
    v_ledger numeric;
    v_today  date;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'B2C_FLOAT_IMMUTABLE: balance checks are never deleted' USING ERRCODE = 'P0001';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PENDING' THEN
            RAISE EXCEPTION 'B2C_FLOAT_INVALID: checks start PENDING' USING ERRCODE = 'P0001';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status NOT IN ('PENDING', 'SENT') THEN
        RAISE EXCEPTION 'B2C_FLOAT_IMMUTABLE: check is %', OLD.status USING ERRCODE = 'P0001';
    END IF;
    IF NEW.check_id <> OLD.check_id OR NEW.short_code <> OLD.short_code OR NEW.reason <> OLD.reason
       OR NEW.requested_by <> OLD.requested_by OR NEW.requested_at <> OLD.requested_at THEN
        RAISE EXCEPTION 'B2C_FLOAT_IMMUTABLE: what was asked never changes' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
           (OLD.status = 'PENDING' AND NEW.status IN ('SENT', 'SEND_FAILED', 'NO_ANSWER'))
        OR (OLD.status = 'SENT' AND NEW.status IN ('ANSWERED', 'NO_ANSWER'))) THEN
        RAISE EXCEPTION 'B2C_FLOAT_STATUS: % -> % not allowed', OLD.status, NEW.status USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status = 'ANSWERED' AND NEW.result_code = '0' THEN
        SELECT gl_clearing INTO v_gl FROM payments.mpesa_b2c_account
         WHERE tenant_id = NEW.tenant_id AND short_code = NEW.short_code;
        SELECT current_date_ INTO v_today FROM tenancy.business_date WHERE tenant_id = NEW.tenant_id;
        v_ledger := payments.clearing_balance(NEW.tenant_id, v_gl, v_today);
        IF NEW.ledger_balance <> v_ledger THEN
            RAISE EXCEPTION 'B2C_FLOAT_INVALID: ledger balance % recorded, the clearing account holds %',
                NEW.ledger_balance, v_ledger USING ERRCODE = 'P0001';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER b2c_balance_check_guard BEFORE INSERT OR UPDATE OR DELETE ON payments.b2c_balance_check
    FOR EACH ROW EXECUTE FUNCTION payments.guard_b2c_balance_check();

ALTER TABLE payments.b2c_status_query ENABLE ROW LEVEL SECURITY;
ALTER TABLE payments.b2c_status_query FORCE ROW LEVEL SECURITY;
ALTER TABLE payments.b2c_float_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE payments.b2c_float_settings FORCE ROW LEVEL SECURITY;
ALTER TABLE payments.b2c_balance_check ENABLE ROW LEVEL SECURITY;
ALTER TABLE payments.b2c_balance_check FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payments.b2c_status_query
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
CREATE POLICY tenant_isolation ON payments.b2c_float_settings
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
CREATE POLICY tenant_isolation ON payments.b2c_balance_check
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());

GRANT SELECT, INSERT, UPDATE ON payments.b2c_status_query TO mfi_app;
GRANT SELECT, INSERT, UPDATE ON payments.b2c_float_settings TO mfi_app;
GRANT SELECT, INSERT, UPDATE ON payments.b2c_balance_check TO mfi_app;
