-- =============================================================================
-- V14: Loan origination - application, decision, loan numbering.
--   A1  an application is submitted by one person and decided (approved / declined) by another
--   A2  the requested terms and the pinned product version never change after submission
--   A3  SUBMITTED -> APPROVED | DECLINED | WITHDRAWN, decisions are final; APPROVED carries the loan
--       it created, and that loan was created by the submitter and approved by the decider
--   A4  loan numbers are allocated per tenant and year without gaps under concurrency
--       (row lock on the counter) and fit M-Pesa account references (12 characters)
-- =============================================================================
CREATE TABLE lending.loan_application (
    tenant_id          uuid          NOT NULL REFERENCES tenancy.tenant (tenant_id),
    application_id     uuid          NOT NULL,
    idempotency_key    varchar(80)   NOT NULL,
    customer_ref       varchar(64)   NOT NULL,
    product_version_id uuid          NOT NULL,
    branch_code        varchar(16)   NOT NULL,
    currency           char(3)       NOT NULL,
    principal          numeric(19,4) NOT NULL CHECK (principal > 0),
    term_months        int           NOT NULL CHECK (term_months >= 1),
    first_due_date     date          NOT NULL,
    purpose            varchar(200)  NOT NULL CHECK (length(trim(purpose)) >= 3),
    status             varchar(16)   NOT NULL DEFAULT 'SUBMITTED'
        CHECK (status IN ('SUBMITTED', 'APPROVED', 'DECLINED', 'WITHDRAWN')),
    submitted_by       varchar(100)  NOT NULL,
    submitted_at       timestamptz   NOT NULL DEFAULT clock_timestamp(),
    decided_by         varchar(100),
    decided_at         timestamptz,
    decision_reason    varchar(300),
    loan_id            uuid,
    PRIMARY KEY (tenant_id, application_id),
    CONSTRAINT loan_application_idempotent UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT loan_application_one_loan UNIQUE (tenant_id, loan_id),
    FOREIGN KEY (tenant_id, product_version_id) REFERENCES lending.loan_product_version (tenant_id, version_id),
    FOREIGN KEY (tenant_id, branch_code) REFERENCES tenancy.branch (tenant_id, branch_code),
    FOREIGN KEY (tenant_id, loan_id) REFERENCES lending.loan (tenant_id, loan_id),
    CHECK ((status = 'SUBMITTED') = (decided_by IS NULL)),
    CHECK (status = 'WITHDRAWN' OR decided_by IS NULL OR decided_by <> submitted_by),
    CHECK (status <> 'WITHDRAWN' OR decided_by = submitted_by),
    CHECK ((status = 'APPROVED') = (loan_id IS NOT NULL)),
    CHECK (status <> 'DECLINED' OR length(trim(coalesce(decision_reason, ''))) >= 3)
);
CREATE INDEX loan_application_open_idx ON lending.loan_application (tenant_id, submitted_at) WHERE status = 'SUBMITTED';

CREATE FUNCTION lending.guard_loan_application() RETURNS trigger
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
           OR l.currency <> NEW.currency OR l.created_by <> NEW.submitted_by OR l.approved_by <> NEW.decided_by THEN
            RAISE EXCEPTION 'LENDING_APPLICATION_LOAN_MISMATCH: the loan does not match the approved application'
                USING ERRCODE = 'P0001';
        END IF;
    END IF;
    NEW.decided_at := clock_timestamp();
    RETURN NEW;
END $$;
CREATE TRIGGER loan_application_guard BEFORE INSERT OR UPDATE OR DELETE ON lending.loan_application
    FOR EACH ROW EXECUTE FUNCTION lending.guard_loan_application();

-- Gapless per-tenant, per-year loan numbers: LN<yy>-<7 digits>, e.g. LN26-0000001 (12 characters).
CREATE TABLE lending.loan_number_counter (
    tenant_id  uuid NOT NULL REFERENCES tenancy.tenant (tenant_id),
    year       int  NOT NULL CHECK (year BETWEEN 2000 AND 2099),
    last_value int  NOT NULL DEFAULT 0 CHECK (last_value BETWEEN 0 AND 9999999),
    PRIMARY KEY (tenant_id, year)
);

CREATE FUNCTION lending.next_loan_number(p_year int) RETURNS varchar
    LANGUAGE plpgsql
AS $$
DECLARE
    v int;
BEGIN
    INSERT INTO lending.loan_number_counter (tenant_id, year, last_value)
    VALUES (tenancy.current_tenant(), p_year, 1)
    ON CONFLICT (tenant_id, year) DO UPDATE SET last_value = lending.loan_number_counter.last_value + 1
    RETURNING last_value INTO v;
    RETURN 'LN' || lpad((p_year % 100)::text, 2, '0') || '-' || lpad(v::text, 7, '0');
END $$;

ALTER TABLE lending.loan_application ENABLE ROW LEVEL SECURITY;
ALTER TABLE lending.loan_application FORCE ROW LEVEL SECURITY;
ALTER TABLE lending.loan_number_counter ENABLE ROW LEVEL SECURITY;
ALTER TABLE lending.loan_number_counter FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON lending.loan_application
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
CREATE POLICY tenant_isolation ON lending.loan_number_counter
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
GRANT SELECT, INSERT, UPDATE ON lending.loan_application TO mfi_app;
GRANT SELECT, INSERT, UPDATE ON lending.loan_number_counter TO mfi_app;
GRANT EXECUTE ON FUNCTION lending.next_loan_number(int) TO mfi_app;
