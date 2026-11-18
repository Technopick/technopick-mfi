-- =============================================================================
-- V3: loan products (versioned), loans, schedules, repayments and allocations.
--
-- Rules enforced HERE:
--   P1  product versions are created DRAFT; activation needs a checker different from the
--       maker; ACTIVE/RETIRED versions are frozen (only ACTIVE -> RETIRED allowed)
--   P2  a loan can only be created on an ACTIVE version, inside its amount/term limits, in
--       its currency, approved by someone other than its creator; its terms never change
--   P3  loan status: APPROVED -> ACTIVE (needs the disbursement entry) -> CLOSED | WRITTEN_OFF
--   P4  schedules are insert-only; every schedule version's principal sums to the loan principal
--   P5  a provider payment reference is applied once; allocations + unallocated = payment,
--       allocations and repayments are insert-only and tied to the posted journal entry
--   P6  RLS + least-privilege grants
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS lending;

CREATE TABLE lending.loan_product (
    tenant_id    uuid         NOT NULL REFERENCES tenancy.tenant (tenant_id),
    product_code varchar(32)  NOT NULL,
    name         varchar(120) NOT NULL,
    currency     char(3)      NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    PRIMARY KEY (tenant_id, product_code)
);

CREATE TABLE lending.loan_product_version (
    tenant_id            uuid          NOT NULL,
    version_id           uuid          NOT NULL,
    product_code         varchar(32)   NOT NULL,
    version              int           NOT NULL CHECK (version >= 1),
    status               varchar(16)   NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    interest_method      varchar(24)   NOT NULL CHECK (interest_method IN ('REDUCING_BALANCE', 'FLAT')),
    annual_rate          numeric(12,8) NOT NULL CHECK (annual_rate BETWEEN 0 AND 1),
    rounding_mode        varchar(16)   NOT NULL DEFAULT 'HALF_EVEN' CHECK (rounding_mode IN ('HALF_EVEN', 'HALF_UP')),
    min_principal        numeric(19,4) NOT NULL CHECK (min_principal > 0),
    max_principal        numeric(19,4) NOT NULL,
    min_term_months      int           NOT NULL CHECK (min_term_months >= 1),
    max_term_months      int           NOT NULL CHECK (max_term_months <= 600),
    upfront_fee_rate     numeric(12,8) NOT NULL DEFAULT 0 CHECK (upfront_fee_rate BETWEEN 0 AND 0.20),
    allocation_strategy  varchar(24)   NOT NULL CHECK (allocation_strategy IN ('COMPONENT_FIRST', 'INSTALLMENT_FIRST')),
    allocation_order     varchar(16)[] NOT NULL,
    schedule_method      varchar(40)   NOT NULL DEFAULT 'equal-installment/v1',
    accrual_method       varchar(40)   NOT NULL DEFAULT 'accrual/v1',
    gl_principal         varchar(40)   NOT NULL,
    gl_interest_receivable varchar(40) NOT NULL,
    gl_interest_income   varchar(40)   NOT NULL,
    gl_fee_receivable    varchar(40)   NOT NULL,
    gl_fee_income        varchar(40)   NOT NULL,
    gl_penalty_receivable varchar(40)  NOT NULL,
    gl_penalty_income    varchar(40)   NOT NULL,
    gl_overpayment       varchar(40)   NOT NULL,
    gl_write_off         varchar(40)   NOT NULL,
    created_by           varchar(100)  NOT NULL,
    approved_by          varchar(100),
    activated_at         timestamptz,
    PRIMARY KEY (tenant_id, version_id),
    UNIQUE (tenant_id, product_code, version),
    FOREIGN KEY (tenant_id, product_code) REFERENCES lending.loan_product (tenant_id, product_code),
    CHECK (min_principal <= max_principal),
    CHECK (min_term_months <= max_term_months),
    CHECK (allocation_order @> ARRAY['PENALTY','FEE','INTEREST','PRINCIPAL']::varchar[]
           AND cardinality(allocation_order) = 4),
    CHECK ((status = 'DRAFT') = (approved_by IS NULL AND activated_at IS NULL)),
    CHECK (approved_by IS NULL OR approved_by <> created_by),
    FOREIGN KEY (tenant_id, gl_principal)           REFERENCES ledger.gl_account (tenant_id, account_code),
    FOREIGN KEY (tenant_id, gl_interest_receivable) REFERENCES ledger.gl_account (tenant_id, account_code),
    FOREIGN KEY (tenant_id, gl_interest_income)     REFERENCES ledger.gl_account (tenant_id, account_code),
    FOREIGN KEY (tenant_id, gl_fee_receivable)      REFERENCES ledger.gl_account (tenant_id, account_code),
    FOREIGN KEY (tenant_id, gl_fee_income)          REFERENCES ledger.gl_account (tenant_id, account_code),
    FOREIGN KEY (tenant_id, gl_penalty_receivable)  REFERENCES ledger.gl_account (tenant_id, account_code),
    FOREIGN KEY (tenant_id, gl_penalty_income)      REFERENCES ledger.gl_account (tenant_id, account_code),
    FOREIGN KEY (tenant_id, gl_overpayment)         REFERENCES ledger.gl_account (tenant_id, account_code),
    FOREIGN KEY (tenant_id, gl_write_off)           REFERENCES ledger.gl_account (tenant_id, account_code)
);

CREATE FUNCTION lending.guard_product_version() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'LENDING_VERSION_NOT_DRAFT: versions are created as DRAFT' USING ERRCODE = 'P0001';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        IF OLD.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'LENDING_VERSION_FROZEN: % v% is %', OLD.product_code, OLD.version, OLD.status
                USING ERRCODE = 'P0001';
        END IF;
        RETURN OLD;
    END IF;
    -- UPDATE
    IF OLD.status = 'DRAFT' AND NEW.status = 'ACTIVE' THEN
        IF NEW.approved_by IS NULL OR NEW.approved_by = NEW.created_by THEN
            RAISE EXCEPTION 'LENDING_MAKER_CHECKER: activation needs a checker other than the maker'
                USING ERRCODE = 'P0001';
        END IF;
        NEW.activated_at := clock_timestamp();
        RETURN NEW;
    END IF;
    IF OLD.status = 'DRAFT' AND NEW.status = 'DRAFT' THEN
        RETURN NEW; -- drafts are editable
    END IF;
    IF OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED'
       AND (to_jsonb(NEW) - 'status') = (to_jsonb(OLD) - 'status') THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'LENDING_VERSION_FROZEN: % v% is %; create a new version instead',
        OLD.product_code, OLD.version, OLD.status USING ERRCODE = 'P0001';
END $$;

CREATE TRIGGER loan_product_version_guard
    BEFORE INSERT OR UPDATE OR DELETE ON lending.loan_product_version
    FOR EACH ROW EXECUTE FUNCTION lending.guard_product_version();

-- ---------------------------------------------------------------------------
-- Loans
-- ---------------------------------------------------------------------------
CREATE TABLE lending.loan (
    tenant_id             uuid          NOT NULL REFERENCES tenancy.tenant (tenant_id),
    loan_id               uuid          NOT NULL,
    loan_number           varchar(34)   NOT NULL,
    customer_ref          varchar(64)   NOT NULL,
    product_version_id    uuid          NOT NULL,
    branch_code           varchar(16)   NOT NULL,
    currency              char(3)       NOT NULL,
    principal             numeric(19,4) NOT NULL CHECK (principal > 0),
    term_months           int           NOT NULL CHECK (term_months >= 1),
    status                varchar(16)   NOT NULL DEFAULT 'APPROVED'
        CHECK (status IN ('APPROVED', 'ACTIVE', 'CLOSED', 'WRITTEN_OFF')),
    created_by            varchar(100)  NOT NULL,
    approved_by           varchar(100)  NOT NULL,
    disbursement_entry_id uuid,
    created_at            timestamptz   NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, loan_id),
    UNIQUE (tenant_id, loan_number),
    FOREIGN KEY (tenant_id, product_version_id) REFERENCES lending.loan_product_version (tenant_id, version_id),
    FOREIGN KEY (tenant_id, branch_code) REFERENCES tenancy.branch (tenant_id, branch_code),
    FOREIGN KEY (tenant_id, disbursement_entry_id) REFERENCES ledger.journal_entry (tenant_id, entry_id),
    CONSTRAINT loan_one_disbursement UNIQUE (tenant_id, disbursement_entry_id),
    CHECK (approved_by <> created_by),
    CHECK ((status = 'APPROVED') = (disbursement_entry_id IS NULL))
);

CREATE FUNCTION lending.guard_loan() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v lending.loan_product_version%ROWTYPE;
    p lending.loan_product%ROWTYPE;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'LENDING_LOAN_IMMUTABLE: loans are never deleted' USING ERRCODE = 'P0001';
    END IF;
    IF TG_OP = 'INSERT' THEN
        SELECT * INTO v FROM lending.loan_product_version
         WHERE tenant_id = NEW.tenant_id AND version_id = NEW.product_version_id;
        SELECT * INTO p FROM lending.loan_product
         WHERE tenant_id = NEW.tenant_id AND product_code = v.product_code;
        IF v.status <> 'ACTIVE' THEN
            RAISE EXCEPTION 'LENDING_VERSION_NOT_ACTIVE: % v% is %', v.product_code, v.version, v.status
                USING ERRCODE = 'P0001';
        END IF;
        IF NEW.currency <> p.currency THEN
            RAISE EXCEPTION 'LENDING_CURRENCY_MISMATCH: product is %', p.currency USING ERRCODE = 'P0001';
        END IF;
        IF NEW.principal NOT BETWEEN v.min_principal AND v.max_principal
           OR NEW.term_months NOT BETWEEN v.min_term_months AND v.max_term_months THEN
            RAISE EXCEPTION 'LENDING_OUTSIDE_PRODUCT_LIMITS: % for % months', NEW.principal, NEW.term_months
                USING ERRCODE = 'P0001';
        END IF;
        IF NEW.status <> 'APPROVED' THEN
            RAISE EXCEPTION 'LENDING_LOAN_STATUS: loans start APPROVED' USING ERRCODE = 'P0001';
        END IF;
        RETURN NEW;
    END IF;
    -- UPDATE: only status and the disbursement link may change, along the allowed path.
    IF (to_jsonb(NEW) - 'status' - 'disbursement_entry_id')
       IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'disbursement_entry_id') THEN
        RAISE EXCEPTION 'LENDING_LOAN_IMMUTABLE: loan terms never change; reschedule creates a new schedule version'
            USING ERRCODE = 'P0001';
    END IF;
    IF OLD.disbursement_entry_id IS NOT NULL
       AND NEW.disbursement_entry_id IS DISTINCT FROM OLD.disbursement_entry_id THEN
        RAISE EXCEPTION 'LENDING_LOAN_IMMUTABLE: disbursement link is final' USING ERRCODE = 'P0001';
    END IF;
    IF NOT ((OLD.status = 'APPROVED' AND NEW.status = 'ACTIVE')
         OR (OLD.status = 'ACTIVE' AND NEW.status IN ('CLOSED', 'WRITTEN_OFF'))
         OR (OLD.status = NEW.status)) THEN
        RAISE EXCEPTION 'LENDING_LOAN_STATUS: % -> % not allowed', OLD.status, NEW.status USING ERRCODE = 'P0001';
    END IF;
    IF OLD.status = 'APPROVED' AND NEW.status = 'ACTIVE' THEN
        SELECT * INTO v FROM lending.loan_product_version
         WHERE tenant_id = NEW.tenant_id AND version_id = NEW.product_version_id;
        -- The entry must be this loan's disbursement (idempotency key format from
        -- LoanPostingRules: loan:<loan_id>:DISBURSE:<ref>) and debit principal in full.
        IF NOT EXISTS (SELECT 1 FROM ledger.journal_line jl
                         JOIN ledger.journal_entry je ON je.tenant_id = jl.tenant_id AND je.entry_id = jl.entry_id
                        WHERE jl.tenant_id = NEW.tenant_id AND jl.entry_id = NEW.disbursement_entry_id
                          AND je.idempotency_key LIKE 'loan:' || NEW.loan_id || ':DISBURSE:%'
                          AND jl.account_code = v.gl_principal AND jl.side = 'D' AND jl.amount = NEW.principal
                          AND jl.currency = NEW.currency) THEN
            RAISE EXCEPTION 'LENDING_DISBURSEMENT_MISMATCH: entry % does not debit % with %',
                NEW.disbursement_entry_id, v.gl_principal, NEW.principal USING ERRCODE = 'P0001';
        END IF;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER loan_guard
    BEFORE INSERT OR UPDATE OR DELETE ON lending.loan
    FOR EACH ROW EXECUTE FUNCTION lending.guard_loan();

-- ---------------------------------------------------------------------------
-- Schedules (insert-only, versioned for reschedules)
-- ---------------------------------------------------------------------------
CREATE TABLE lending.schedule_installment (
    tenant_id        uuid          NOT NULL,
    loan_id          uuid          NOT NULL,
    schedule_version int           NOT NULL CHECK (schedule_version >= 1),
    installment_no   int           NOT NULL CHECK (installment_no >= 1),
    due_date         date          NOT NULL,
    principal        numeric(19,4) NOT NULL CHECK (principal >= 0),
    interest         numeric(19,4) NOT NULL CHECK (interest >= 0),
    PRIMARY KEY (tenant_id, loan_id, schedule_version, installment_no),
    FOREIGN KEY (tenant_id, loan_id) REFERENCES lending.loan (tenant_id, loan_id)
);

CREATE FUNCTION lending.check_schedule_sum() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_principal numeric;
    v_sum numeric;
    v_count int;
    v_max int;
BEGIN
    SELECT principal INTO v_principal FROM lending.loan WHERE tenant_id = NEW.tenant_id AND loan_id = NEW.loan_id;
    SELECT sum(principal), count(*), max(installment_no) INTO v_sum, v_count, v_max
      FROM lending.schedule_installment
     WHERE tenant_id = NEW.tenant_id AND loan_id = NEW.loan_id AND schedule_version = NEW.schedule_version;
    IF v_sum <> v_principal THEN
        RAISE EXCEPTION 'LENDING_SCHEDULE_MISMATCH: schedule v% principal % <> loan principal %',
            NEW.schedule_version, v_sum, v_principal USING ERRCODE = 'P0001';
    END IF;
    IF v_count <> v_max THEN
        RAISE EXCEPTION 'LENDING_SCHEDULE_GAP: installments are not numbered 1..%', v_max USING ERRCODE = 'P0001';
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER schedule_installment_sum
    AFTER INSERT ON lending.schedule_installment
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION lending.check_schedule_sum();

-- ---------------------------------------------------------------------------
-- Repayments and allocations (insert-only)
-- ---------------------------------------------------------------------------
CREATE TABLE lending.repayment (
    tenant_id        uuid          NOT NULL,
    repayment_id     uuid          NOT NULL,
    loan_id          uuid          NOT NULL,
    amount           numeric(19,4) NOT NULL CHECK (amount > 0),
    unallocated      numeric(19,4) NOT NULL DEFAULT 0 CHECK (unallocated >= 0),
    currency         char(3)       NOT NULL,
    channel          varchar(24)   NOT NULL CHECK (channel IN ('MPESA_C2B', 'MPESA_STK', 'BANK', 'TELLER', 'SWITCH', 'INTERNAL')),
    provider_ref     varchar(100)  NOT NULL,
    journal_entry_id uuid          NOT NULL,
    received_at      timestamptz   NOT NULL,
    PRIMARY KEY (tenant_id, repayment_id),
    CONSTRAINT repayment_applied_once UNIQUE (tenant_id, channel, provider_ref),
    CONSTRAINT repayment_one_entry UNIQUE (tenant_id, journal_entry_id),
    CHECK (unallocated <= amount),
    FOREIGN KEY (tenant_id, loan_id) REFERENCES lending.loan (tenant_id, loan_id),
    FOREIGN KEY (tenant_id, journal_entry_id) REFERENCES ledger.journal_entry (tenant_id, entry_id)
);

CREATE TABLE lending.repayment_allocation (
    tenant_id      uuid          NOT NULL,
    repayment_id   uuid          NOT NULL,
    line_no        int           NOT NULL CHECK (line_no >= 1),
    installment_no int           NOT NULL,
    component      varchar(16)   NOT NULL CHECK (component IN ('PENALTY', 'FEE', 'INTEREST', 'PRINCIPAL')),
    amount         numeric(19,4) NOT NULL CHECK (amount > 0),
    PRIMARY KEY (tenant_id, repayment_id, line_no),
    FOREIGN KEY (tenant_id, repayment_id) REFERENCES lending.repayment (tenant_id, repayment_id)
);

CREATE FUNCTION lending.check_repayment() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    r lending.repayment%ROWTYPE;
    v_alloc numeric;
    v_loan_status varchar;
BEGIN
    SELECT * INTO r FROM lending.repayment WHERE tenant_id = NEW.tenant_id AND repayment_id = NEW.repayment_id;
    SELECT COALESCE(sum(amount), 0) INTO v_alloc FROM lending.repayment_allocation
     WHERE tenant_id = r.tenant_id AND repayment_id = r.repayment_id;
    IF v_alloc + r.unallocated <> r.amount THEN
        RAISE EXCEPTION 'LENDING_ALLOCATION_MISMATCH: allocated % + unallocated % <> payment %',
            v_alloc, r.unallocated, r.amount USING ERRCODE = 'P0001';
    END IF;
    IF (SELECT COALESCE(sum(amount), 0) FROM ledger.journal_line
         WHERE tenant_id = r.tenant_id AND entry_id = r.journal_entry_id AND side = 'D' AND currency = r.currency)
       <> r.amount THEN
        RAISE EXCEPTION 'LENDING_REPAYMENT_ENTRY_MISMATCH: journal entry % does not total %',
            r.journal_entry_id, r.amount USING ERRCODE = 'P0001';
    END IF;
    SELECT status INTO v_loan_status FROM lending.loan WHERE tenant_id = r.tenant_id AND loan_id = r.loan_id;
    IF v_loan_status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'LENDING_LOAN_NOT_ACTIVE: repayments need an ACTIVE loan (is %)', v_loan_status
            USING ERRCODE = 'P0001';
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER repayment_balanced
    AFTER INSERT ON lending.repayment
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION lending.check_repayment();
CREATE CONSTRAINT TRIGGER repayment_allocation_balanced
    AFTER INSERT ON lending.repayment_allocation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION lending.check_repayment();

-- Insert-only: no UPDATE/DELETE grants below; owner-level protection too.
CREATE TRIGGER schedule_installment_immutable
    BEFORE UPDATE OR DELETE ON lending.schedule_installment
    FOR EACH ROW EXECUTE FUNCTION ledger.reject_mutation();
CREATE TRIGGER repayment_immutable
    BEFORE UPDATE OR DELETE ON lending.repayment
    FOR EACH ROW EXECUTE FUNCTION ledger.reject_mutation();
CREATE TRIGGER repayment_allocation_immutable
    BEFORE UPDATE OR DELETE ON lending.repayment_allocation
    FOR EACH ROW EXECUTE FUNCTION ledger.reject_mutation();

-- ---------------------------------------------------------------------------
-- RLS and grants
-- ---------------------------------------------------------------------------
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['loan_product', 'loan_product_version', 'loan', 'schedule_installment',
                             'repayment', 'repayment_allocation'] LOOP
        EXECUTE format('ALTER TABLE lending.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE lending.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON lending.%I USING (tenant_id = tenancy.current_tenant()) '
                       'WITH CHECK (tenant_id = tenancy.current_tenant())', t);
    END LOOP;
END $$;

REVOKE ALL ON SCHEMA lending FROM PUBLIC;
GRANT USAGE ON SCHEMA lending TO mfi_app;
GRANT SELECT, INSERT, UPDATE ON lending.loan_product, lending.loan_product_version, lending.loan TO mfi_app;
GRANT DELETE ON lending.loan_product_version TO mfi_app;  -- drafts only (trigger)
GRANT SELECT, INSERT ON lending.schedule_installment, lending.repayment, lending.repayment_allocation TO mfi_app;
