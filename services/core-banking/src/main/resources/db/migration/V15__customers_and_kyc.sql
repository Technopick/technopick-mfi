-- =============================================================================
-- V15: Customers and KYC.
--   U1  a customer is registered by one person and verified by another (KYC four eyes)
--   U2  one customer per identity document per tenant; adults only (18+ on registration day)
--   U3  data-processing consent is recorded at registration (version, time, who recorded it)
--   U4  identity details (names, date of birth, document) never change once verified
--   U5  PENDING_VERIFICATION -> ACTIVE <-> BLOCKED; nothing is deleted
--   U6  loan applications name an ACTIVE customer; an approved application's loan carries the
--       same customer
--   U7  customer numbers are allocated per tenant and year without gaps (CU26-0000001)
-- =============================================================================
CREATE SCHEMA IF NOT EXISTS customer;

CREATE TABLE customer.customer (
    tenant_id           uuid         NOT NULL REFERENCES tenancy.tenant (tenant_id),
    customer_id         uuid         NOT NULL,
    customer_number     varchar(12)  NOT NULL CHECK (customer_number ~ '^CU[0-9]{2}-[0-9]{7}$'),
    idempotency_key     varchar(80)  NOT NULL,
    first_name          varchar(60)  NOT NULL CHECK (length(trim(first_name)) >= 1),
    middle_name         varchar(60),
    last_name           varchar(60)  NOT NULL CHECK (length(trim(last_name)) >= 1),
    date_of_birth       date         NOT NULL,
    id_type             varchar(16)  NOT NULL CHECK (id_type IN ('NATIONAL_ID', 'PASSPORT', 'ALIEN_ID')),
    id_number           varchar(20)  NOT NULL,
    kra_pin             varchar(11)  CHECK (kra_pin ~ '^[AP][0-9]{9}[A-Z]$'),
    msisdn              varchar(12)  NOT NULL CHECK (msisdn ~ '^254[17][0-9]{8}$'),
    email               varchar(120) CHECK (email ~ '^[^@\s]+@[^@\s]+\.[^@\s]+$'),
    branch_code         varchar(16)  NOT NULL,
    status              varchar(24)  NOT NULL DEFAULT 'PENDING_VERIFICATION'
        CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'BLOCKED')),
    consent_version     varchar(20)  NOT NULL,
    consent_at          timestamptz  NOT NULL,
    created_by          varchar(100) NOT NULL,
    created_at          timestamptz  NOT NULL DEFAULT clock_timestamp(),
    verified_by         varchar(100),
    verified_at         timestamptz,
    verification_note   varchar(300),
    status_reason       varchar(300),
    updated_at          timestamptz  NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, customer_id),
    CONSTRAINT customer_number_unique UNIQUE (tenant_id, customer_number),
    CONSTRAINT customer_idempotent UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT customer_one_per_document UNIQUE (tenant_id, id_type, id_number),
    FOREIGN KEY (tenant_id, branch_code) REFERENCES tenancy.branch (tenant_id, branch_code),
    CHECK (id_type <> 'NATIONAL_ID' OR id_number ~ '^[0-9]{6,9}$'),
    CHECK (id_type <> 'PASSPORT' OR id_number ~ '^[A-Z0-9]{6,12}$'),
    CHECK (id_type <> 'ALIEN_ID' OR id_number ~ '^[0-9]{6,10}$'),
    CHECK (verified_by IS NULL OR verified_by <> created_by),
    CHECK ((status = 'PENDING_VERIFICATION') = (verified_by IS NULL)),
    CHECK (verified_by IS NULL OR length(trim(coalesce(verification_note, ''))) >= 10)
);
CREATE INDEX customer_msisdn_idx ON customer.customer (tenant_id, msisdn);
CREATE INDEX customer_name_idx ON customer.customer (tenant_id, lower(last_name), lower(first_name));

CREATE FUNCTION customer.guard_customer() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'CUSTOMER_IMMUTABLE: customers are never deleted' USING ERRCODE = 'P0001';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PENDING_VERIFICATION' THEN
            RAISE EXCEPTION 'CUSTOMER_STATUS: customers start PENDING_VERIFICATION' USING ERRCODE = 'P0001';
        END IF;
        IF NEW.date_of_birth > (current_date - interval '18 years')::date THEN
            RAISE EXCEPTION 'CUSTOMER_UNDERAGE: customers must be 18 or older' USING ERRCODE = 'P0001';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.customer_id <> OLD.customer_id OR NEW.customer_number <> OLD.customer_number
       OR NEW.idempotency_key <> OLD.idempotency_key OR NEW.created_by <> OLD.created_by
       OR NEW.created_at <> OLD.created_at OR NEW.consent_version <> OLD.consent_version
       OR NEW.consent_at <> OLD.consent_at THEN
        RAISE EXCEPTION 'CUSTOMER_IMMUTABLE: registration record never changes' USING ERRCODE = 'P0001';
    END IF;
    IF OLD.status <> 'PENDING_VERIFICATION' AND (
           NEW.first_name IS DISTINCT FROM OLD.first_name OR NEW.middle_name IS DISTINCT FROM OLD.middle_name
        OR NEW.last_name IS DISTINCT FROM OLD.last_name OR NEW.date_of_birth IS DISTINCT FROM OLD.date_of_birth
        OR NEW.id_type IS DISTINCT FROM OLD.id_type OR NEW.id_number IS DISTINCT FROM OLD.id_number) THEN
        RAISE EXCEPTION 'CUSTOMER_IDENTITY_FIXED: verified identity details never change' USING ERRCODE = 'P0001';
    END IF;
    IF OLD.verified_by IS NOT NULL AND (NEW.verified_by IS DISTINCT FROM OLD.verified_by
        OR NEW.verified_at IS DISTINCT FROM OLD.verified_at
        OR NEW.verification_note IS DISTINCT FROM OLD.verification_note) THEN
        RAISE EXCEPTION 'CUSTOMER_IMMUTABLE: the verification record never changes' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
           (OLD.status = 'PENDING_VERIFICATION' AND NEW.status = 'ACTIVE')
        OR (OLD.status = 'ACTIVE' AND NEW.status = 'BLOCKED')
        OR (OLD.status = 'BLOCKED' AND NEW.status = 'ACTIVE')) THEN
        RAISE EXCEPTION 'CUSTOMER_STATUS: % -> % not allowed', OLD.status, NEW.status USING ERRCODE = 'P0001';
    END IF;
    IF OLD.status = 'PENDING_VERIFICATION' AND NEW.status = 'ACTIVE' THEN
        NEW.verified_at := clock_timestamp();
    END IF;
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END $$;
CREATE TRIGGER customer_guard BEFORE INSERT OR UPDATE OR DELETE ON customer.customer
    FOR EACH ROW EXECUTE FUNCTION customer.guard_customer();

CREATE TABLE customer.customer_number_counter (
    tenant_id  uuid NOT NULL REFERENCES tenancy.tenant (tenant_id),
    year       int  NOT NULL CHECK (year BETWEEN 2000 AND 2099),
    last_value int  NOT NULL DEFAULT 0 CHECK (last_value BETWEEN 0 AND 9999999),
    PRIMARY KEY (tenant_id, year)
);

CREATE FUNCTION customer.next_customer_number(p_year int) RETURNS varchar
    LANGUAGE plpgsql
AS $$
DECLARE
    v int;
BEGIN
    INSERT INTO customer.customer_number_counter (tenant_id, year, last_value)
    VALUES (tenancy.current_tenant(), p_year, 1)
    ON CONFLICT (tenant_id, year) DO UPDATE SET last_value = customer.customer_number_counter.last_value + 1
    RETURNING last_value INTO v;
    RETURN 'CU' || lpad((p_year % 100)::text, 2, '0') || '-' || lpad(v::text, 7, '0');
END $$;

-- ---------------------------------------------------------------- links from lending (C6)
ALTER TABLE lending.loan_application ADD COLUMN customer_id uuid;
ALTER TABLE lending.loan_application
    ADD FOREIGN KEY (tenant_id, customer_id) REFERENCES customer.customer (tenant_id, customer_id);
ALTER TABLE lending.loan ADD COLUMN customer_id uuid;
ALTER TABLE lending.loan
    ADD FOREIGN KEY (tenant_id, customer_id) REFERENCES customer.customer (tenant_id, customer_id);

CREATE FUNCTION lending.require_active_customer() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    c customer.customer%ROWTYPE;
BEGIN
    IF TG_TABLE_NAME = 'loan' AND NEW.customer_id IS NULL THEN
        RETURN NEW; -- loans created before V15 (test data) carry no customer link
    END IF;
    SELECT * INTO c FROM customer.customer WHERE tenant_id = NEW.tenant_id AND customer_id = NEW.customer_id;
    IF c.customer_id IS NULL OR c.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'LENDING_CUSTOMER_NOT_ACTIVE: the customer must exist and be verified' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.customer_ref <> c.customer_number THEN
        RAISE EXCEPTION 'LENDING_CUSTOMER_MISMATCH: customer reference must be the customer number' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER loan_application_customer BEFORE INSERT ON lending.loan_application
    FOR EACH ROW EXECUTE FUNCTION lending.require_active_customer();
CREATE TRIGGER loan_customer BEFORE INSERT ON lending.loan
    FOR EACH ROW EXECUTE FUNCTION lending.require_active_customer();

CREATE OR REPLACE FUNCTION lending.guard_loan_application() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    l lending.loan%ROWTYPE;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'LENDING_APPLICATION_IMMUTABLE: applications are never deleted' USING ERRCODE = 'P0001';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'SUBMITTED' THEN
            RAISE EXCEPTION 'LENDING_APPLICATION_STATUS: applications start SUBMITTED' USING ERRCODE = 'P0001';
        END IF;
        RETURN NEW;
    END IF;
    IF (to_jsonb(NEW) - 'status' - 'decided_by' - 'decided_at' - 'decision_reason' - 'loan_id')
       IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'decided_by' - 'decided_at' - 'decision_reason' - 'loan_id') THEN
        RAISE EXCEPTION 'LENDING_APPLICATION_IMMUTABLE: submitted terms never change' USING ERRCODE = 'P0001';
    END IF;
    IF OLD.status <> 'SUBMITTED' THEN
        RAISE EXCEPTION 'LENDING_APPLICATION_FINAL: application % is %', OLD.application_id, OLD.status
            USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status NOT IN ('APPROVED', 'DECLINED', 'WITHDRAWN') THEN
        RAISE EXCEPTION 'LENDING_APPLICATION_STATUS: SUBMITTED -> % not allowed', NEW.status USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status = 'APPROVED' THEN
        SELECT * INTO l FROM lending.loan WHERE tenant_id = NEW.tenant_id AND loan_id = NEW.loan_id;
        IF l.loan_id IS NULL OR l.customer_ref <> NEW.customer_ref OR l.product_version_id <> NEW.product_version_id
           OR l.branch_code <> NEW.branch_code OR l.principal <> NEW.principal OR l.term_months <> NEW.term_months
           OR l.currency <> NEW.currency OR l.created_by <> NEW.submitted_by OR l.approved_by <> NEW.decided_by
           OR l.customer_id IS DISTINCT FROM NEW.customer_id THEN
            RAISE EXCEPTION 'LENDING_APPLICATION_LOAN_MISMATCH: the loan does not match the approved application'
                USING ERRCODE = 'P0001';
        END IF;
    END IF;
    NEW.decided_at := clock_timestamp();
    RETURN NEW;
END $$;

ALTER TABLE customer.customer ENABLE ROW LEVEL SECURITY;
ALTER TABLE customer.customer FORCE ROW LEVEL SECURITY;
ALTER TABLE customer.customer_number_counter ENABLE ROW LEVEL SECURITY;
ALTER TABLE customer.customer_number_counter FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON customer.customer
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
CREATE POLICY tenant_isolation ON customer.customer_number_counter
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
GRANT USAGE ON SCHEMA customer TO mfi_app;
GRANT SELECT, INSERT, UPDATE ON customer.customer TO mfi_app;
GRANT SELECT, INSERT, UPDATE ON customer.customer_number_counter TO mfi_app;
GRANT EXECUTE ON FUNCTION customer.next_customer_number(int) TO mfi_app;
