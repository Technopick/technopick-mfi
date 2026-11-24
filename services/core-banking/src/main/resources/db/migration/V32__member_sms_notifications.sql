-- =============================================================================
-- V32 (increment 35, ADR-0035): SMS notifications to members.
--
-- notify.sms_message is its own outbox: a message is written in the same transaction as the event
-- it reports (a payment applied, an end of day, a PIN change), so it exists if and only if the
-- event happened. The integration runtime claims due messages, sends them through the SMS
-- provider and reports the outcome; retries and crash recovery are decided here.
--
-- The database enforces: one message per event (dedupe key), message content and recipient never
-- change, statuses only move forward (a failed send may be queued again), nothing is deleted.
-- =============================================================================
CREATE SCHEMA notify;

-- Per tenant; off until operations switch it on with a signature (and a sender ID from the provider).
CREATE TABLE notify.sms_settings (
    tenant_id      uuid        PRIMARY KEY REFERENCES tenancy.tenant (tenant_id),
    enabled        boolean     NOT NULL DEFAULT false,
    signature      varchar(20) NOT NULL CHECK (signature ~ '^[A-Za-z0-9][A-Za-z0-9 .&-]{1,19}$'),
    sender_id      varchar(11) CHECK (sender_id ~ '^[A-Za-z0-9][A-Za-z0-9 -]{0,10}$'),
    ussd_code      varchar(20) CHECK (ussd_code ~ '^\*[0-9*]{1,17}#$'),
    reminder_hour  int         NOT NULL DEFAULT 8 CHECK (reminder_hour BETWEEN 7 AND 19),
    updated_by     varchar(100) NOT NULL,
    updated_at     timestamptz NOT NULL DEFAULT clock_timestamp()
);

-- A member's choices, recorded by staff at the member's request. Security alerts ignore them.
CREATE TABLE notify.member_preference (
    tenant_id       uuid        NOT NULL,
    customer_id     uuid        NOT NULL,
    language        char(2)     NOT NULL DEFAULT 'en' CHECK (language IN ('en', 'sw')),
    payment_alerts  boolean     NOT NULL DEFAULT true,
    reminders       boolean     NOT NULL DEFAULT true,
    updated_by      varchar(100) NOT NULL,
    updated_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, customer_id),
    FOREIGN KEY (tenant_id, customer_id) REFERENCES customer.customer (tenant_id, customer_id)
);

CREATE TABLE notify.sms_message (
    tenant_id            uuid         NOT NULL,
    message_id           uuid         NOT NULL,
    dedupe_key           varchar(120) NOT NULL,
    customer_id          uuid         NOT NULL,
    msisdn               varchar(12)  NOT NULL CHECK (msisdn ~ '^254[17][0-9]{8}$'),
    template             varchar(30)  NOT NULL CHECK (template IN ('PAYMENT_LOAN', 'PAYMENT_SAVINGS', 'LOAN_DUE_SOON',
                             'LOAN_MISSED', 'PIN_SETUP_READY', 'PIN_SET', 'PIN_CHANGED', 'PIN_LOCKED')),
    category             varchar(13)  NOT NULL CHECK (category IN ('TRANSACTIONAL', 'REMINDER', 'SECURITY')),
    language             char(2)      NOT NULL CHECK (language IN ('en', 'sw')),
    body                 varchar(320) NOT NULL CHECK (length(body) BETWEEN 1 AND 320 AND body !~ '[^ -~]'),
    status               varchar(10)  NOT NULL CHECK (status IN ('QUEUED', 'DISPATCHED', 'SENT', 'DELIVERED', 'FAILED', 'SUPPRESSED')),
    suppressed_reason    varchar(40),
    attempts             int          NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 10),
    not_before           timestamptz  NOT NULL,
    claimed_at           timestamptz,
    sent_at              timestamptz,
    delivered_at         timestamptz,
    failed_at            timestamptz,
    provider_message_id  varchar(100),
    last_error           varchar(200),
    created_by           varchar(100) NOT NULL,
    created_at           timestamptz  NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, message_id),
    UNIQUE (tenant_id, dedupe_key),
    FOREIGN KEY (tenant_id, customer_id) REFERENCES customer.customer (tenant_id, customer_id),
    CHECK ((status = 'SUPPRESSED') = (suppressed_reason IS NOT NULL)),
    CHECK (status NOT IN ('SENT', 'DELIVERED') OR provider_message_id IS NOT NULL)
);
CREATE INDEX sms_message_due ON notify.sms_message (tenant_id, not_before) WHERE status = 'QUEUED';
CREATE INDEX sms_message_claimed ON notify.sms_message (tenant_id, claimed_at) WHERE status = 'DISPATCHED';
CREATE INDEX sms_message_customer ON notify.sms_message (tenant_id, customer_id, created_at);
CREATE INDEX sms_message_provider_ref ON notify.sms_message (tenant_id, provider_message_id);
-- Reminders are found by due date at the end-of-day close.
CREATE INDEX schedule_installment_due ON lending.schedule_installment (tenant_id, due_date);

CREATE FUNCTION notify.check_sms_message() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'SMS_IMMUTABLE: messages are never deleted' USING ERRCODE = 'P0001';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status NOT IN ('QUEUED', 'SUPPRESSED') OR NEW.attempts <> 0 OR NEW.claimed_at IS NOT NULL
           OR NEW.sent_at IS NOT NULL OR NEW.provider_message_id IS NOT NULL THEN
            RAISE EXCEPTION 'SMS_INVALID: a message starts queued (or suppressed) and unsent' USING ERRCODE = 'P0001';
        END IF;
        RETURN NEW;
    END IF;
    IF (NEW.message_id, NEW.dedupe_key, NEW.customer_id, NEW.msisdn, NEW.template, NEW.category, NEW.language, NEW.body,
        NEW.created_by, NEW.created_at, NEW.suppressed_reason)
       IS DISTINCT FROM (OLD.message_id, OLD.dedupe_key, OLD.customer_id, OLD.msisdn, OLD.template, OLD.category, OLD.language,
        OLD.body, OLD.created_by, OLD.created_at, OLD.suppressed_reason) THEN
        RAISE EXCEPTION 'SMS_IMMUTABLE: a message''s recipient and content never change' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status = OLD.status THEN
        IF NEW.status IN ('DELIVERED', 'FAILED', 'SUPPRESSED')
           OR (NEW.attempts, NEW.provider_message_id, NEW.sent_at) IS DISTINCT FROM (OLD.attempts, OLD.provider_message_id, OLD.sent_at) THEN
            RAISE EXCEPTION 'SMS_STATUS: % message cannot change', OLD.status USING ERRCODE = 'P0001';
        END IF;
        RETURN NEW;
    END IF;
    IF NOT ((OLD.status = 'QUEUED' AND NEW.status IN ('DISPATCHED', 'FAILED'))
         OR (OLD.status = 'DISPATCHED' AND NEW.status IN ('SENT', 'QUEUED', 'FAILED'))
         OR (OLD.status = 'SENT' AND NEW.status IN ('DELIVERED', 'FAILED'))) THEN
        RAISE EXCEPTION 'SMS_STATUS: % -> % not allowed', OLD.status, NEW.status USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status = 'DISPATCHED' AND (NEW.attempts <> OLD.attempts + 1 OR NEW.claimed_at IS NULL) THEN
        RAISE EXCEPTION 'SMS_STATUS: each claim counts one attempt' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status <> 'DISPATCHED' AND NEW.attempts <> OLD.attempts THEN
        RAISE EXCEPTION 'SMS_STATUS: attempts change only when a message is claimed' USING ERRCODE = 'P0001';
    END IF;
    IF OLD.provider_message_id IS NOT NULL AND NEW.provider_message_id IS DISTINCT FROM OLD.provider_message_id THEN
        RAISE EXCEPTION 'SMS_IMMUTABLE: the provider reference never changes' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER sms_message_rules BEFORE INSERT OR UPDATE OR DELETE ON notify.sms_message
    FOR EACH ROW EXECUTE FUNCTION notify.check_sms_message();

ALTER TABLE notify.sms_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE notify.sms_settings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notify.sms_settings
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
ALTER TABLE notify.member_preference ENABLE ROW LEVEL SECURITY;
ALTER TABLE notify.member_preference FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notify.member_preference
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
ALTER TABLE notify.sms_message ENABLE ROW LEVEL SECURITY;
ALTER TABLE notify.sms_message FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notify.sms_message
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());

GRANT USAGE ON SCHEMA notify TO mfi_app;
GRANT SELECT, INSERT, UPDATE ON notify.sms_settings TO mfi_app;
GRANT SELECT, INSERT, UPDATE ON notify.member_preference TO mfi_app;
GRANT SELECT, INSERT, UPDATE ON notify.sms_message TO mfi_app;
