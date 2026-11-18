-- =============================================================================
-- V7: transactional outbox. Core banking writes an event in the SAME transaction as the
-- business change, so an event exists if and only if the change committed. The integration
-- runtime's relay (role mfi_relay) publishes events to the broker and marks them published.
--
-- Rules: events are never edited or deleted (retention/archival is a later, audited job);
-- the only allowed update is marking publication and counting attempts. mfi_app sees only its
-- tenant's events; mfi_relay sees all tenants' events but can change nothing except those two
-- columns.
-- Prerequisite (infrastructure bootstrap): CREATE ROLE mfi_relay LOGIN NOSUPERUSER NOBYPASSRLS;
-- =============================================================================
CREATE SCHEMA IF NOT EXISTS messaging;

CREATE TABLE messaging.outbox_event (
    event_id         uuid         PRIMARY KEY,
    tenant_id        uuid         NOT NULL REFERENCES tenancy.tenant (tenant_id),
    event_type       varchar(80)  NOT NULL CHECK (event_type ~ '^[A-Z][A-Za-z]+$'),
    aggregate_type   varchar(40)  NOT NULL,
    aggregate_id     varchar(100) NOT NULL,
    payload          jsonb        NOT NULL,
    correlation_id   varchar(100),
    occurred_at      timestamptz  NOT NULL DEFAULT clock_timestamp(),
    published_at     timestamptz,
    publish_attempts int          NOT NULL DEFAULT 0 CHECK (publish_attempts >= 0)
);
CREATE INDEX outbox_unpublished_idx ON messaging.outbox_event (occurred_at) WHERE published_at IS NULL;

CREATE FUNCTION messaging.guard_outbox() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'OUTBOX_IMMUTABLE: events are never deleted' USING ERRCODE = 'P0001';
    END IF;
    IF (to_jsonb(NEW) - 'published_at' - 'publish_attempts') IS DISTINCT FROM
       (to_jsonb(OLD) - 'published_at' - 'publish_attempts') THEN
        RAISE EXCEPTION 'OUTBOX_IMMUTABLE: only publication fields may change' USING ERRCODE = 'P0001';
    END IF;
    IF OLD.published_at IS NOT NULL AND NEW.published_at IS DISTINCT FROM OLD.published_at THEN
        RAISE EXCEPTION 'OUTBOX_IMMUTABLE: event % already published', OLD.event_id USING ERRCODE = 'P0001';
    END IF;
    IF NEW.publish_attempts < OLD.publish_attempts THEN
        RAISE EXCEPTION 'OUTBOX_IMMUTABLE: attempts never decrease' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER outbox_guard BEFORE UPDATE OR DELETE ON messaging.outbox_event
    FOR EACH ROW EXECUTE FUNCTION messaging.guard_outbox();
CREATE TRIGGER outbox_no_truncate BEFORE TRUNCATE ON messaging.outbox_event
    FOR EACH STATEMENT EXECUTE FUNCTION messaging.guard_outbox();

ALTER TABLE messaging.outbox_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE messaging.outbox_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON messaging.outbox_event TO mfi_app
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
CREATE POLICY relay_all_tenants ON messaging.outbox_event TO mfi_relay
    USING (true);

REVOKE ALL ON SCHEMA messaging FROM PUBLIC;
GRANT USAGE ON SCHEMA messaging TO mfi_app, mfi_relay;
GRANT SELECT, INSERT ON messaging.outbox_event TO mfi_app;
GRANT SELECT ON messaging.outbox_event TO mfi_relay;
GRANT UPDATE (published_at, publish_attempts) ON messaging.outbox_event TO mfi_relay;
