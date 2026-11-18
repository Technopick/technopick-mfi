-- =============================================================================
-- V12: Loan disbursement by M-Pesa B2C.
--   B1  a disbursement is requested by one person and approved (or rejected) by another
--   B2  at most one live disbursement per loan; another may be requested only after a
--       definitive failure or a rejection
--   B3  lifecycle only moves forward:
--         REQUESTED -> APPROVED | REJECTED
--         APPROVED -> DISPATCHED -> SUBMITTED -> COMPLETED | FAILED
--         DISPATCHED -> COMPLETED | FAILED   (result before the submit report)
--       COMPLETED and REJECTED are final; FAILED is final except for raising the
--       SUCCESS_AFTER_FAILURE alarm (money left after a failure was recorded)
--   B4  COMPLETED carries the M-Pesa transaction id and the loan's own disbursement entry
--   B5  details never change; provider ids never change once recorded; the full phone number
--       is erased when final; nothing is deleted
--   B6  only APPROVED loans can have a disbursement requested; whole-shilling amounts
-- =============================================================================
CREATE TABLE payments.mpesa_b2c_account (
    tenant_id   uuid          NOT NULL REFERENCES tenancy.tenant (tenant_id),
    short_code  varchar(12)   NOT NULL CHECK (short_code ~ '^[0-9]{5,10}$'),
    currency    char(3)       NOT NULL DEFAULT 'KES',
    branch_code varchar(16)   NOT NULL,
    gl_clearing varchar(40)   NOT NULL,
    max_amount  numeric(19,4) NOT NULL DEFAULT 150000 CHECK (max_amount > 0),
    active      boolean       NOT NULL DEFAULT true,
    PRIMARY KEY (tenant_id, short_code),
    FOREIGN KEY (tenant_id, branch_code) REFERENCES tenancy.branch (tenant_id, branch_code),
    FOREIGN KEY (tenant_id, gl_clearing) REFERENCES ledger.gl_account (tenant_id, account_code)
);

CREATE TABLE payments.b2c_disbursement (
    tenant_id        uuid          NOT NULL,
    disbursement_id  uuid          NOT NULL,
    idempotency_key  varchar(80)   NOT NULL,
    loan_id          uuid          NOT NULL,
    short_code       varchar(12)   NOT NULL,
    amount           numeric(19,4) NOT NULL CHECK (amount >= 10 AND amount = trunc(amount)),
    currency         char(3)       NOT NULL,
    msisdn           varchar(12)   CHECK (msisdn ~ '^254[17][0-9]{8}$'),
    msisdn_masked    varchar(20)   NOT NULL,
    status           varchar(16)   NOT NULL DEFAULT 'REQUESTED'
        CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED', 'DISPATCHED', 'SUBMITTED', 'COMPLETED', 'FAILED')),
    requested_by     varchar(100)  NOT NULL,
    requested_at     timestamptz   NOT NULL DEFAULT clock_timestamp(),
    decided_by       varchar(100),
    decided_at       timestamptz,
    conversation_id  varchar(60),
    transaction_id   varchar(20),
    result_code      varchar(20),
    result_desc      varchar(200),
    journal_entry_id uuid,
    attention        varchar(32)
        CHECK (attention IN ('SEND_OUTCOME_UNKNOWN', 'TIMEOUT_UNKNOWN', 'AMOUNT_MISMATCH', 'SUCCESS_AFTER_FAILURE')),
    updated_at       timestamptz   NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, disbursement_id),
    CONSTRAINT b2c_idempotent UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT b2c_conversation_once UNIQUE (tenant_id, conversation_id),
    CONSTRAINT b2c_transaction_once UNIQUE (tenant_id, transaction_id),
    FOREIGN KEY (tenant_id, loan_id) REFERENCES lending.loan (tenant_id, loan_id),
    FOREIGN KEY (tenant_id, short_code) REFERENCES payments.mpesa_b2c_account (tenant_id, short_code),
    FOREIGN KEY (tenant_id, journal_entry_id) REFERENCES ledger.journal_entry (tenant_id, entry_id),
    CHECK (decided_by IS NULL OR decided_by <> requested_by),
    CHECK ((status = 'REQUESTED') = (decided_by IS NULL)),
    CHECK ((status = 'COMPLETED') = (journal_entry_id IS NOT NULL)),
    CHECK (status <> 'COMPLETED' OR transaction_id IS NOT NULL),
    CHECK (status <> 'FAILED' OR result_code IS NOT NULL),
    CHECK ((status IN ('COMPLETED', 'FAILED', 'REJECTED')) = (msisdn IS NULL)),
    CHECK (status NOT IN ('COMPLETED', 'REJECTED') OR attention IS NULL),
    CHECK (status <> 'FAILED' OR attention IS NULL OR attention = 'SUCCESS_AFTER_FAILURE')
);
CREATE UNIQUE INDEX b2c_one_live_per_loan ON payments.b2c_disbursement (tenant_id, loan_id)
    WHERE status NOT IN ('FAILED', 'REJECTED');
CREATE INDEX b2c_attention_idx ON payments.b2c_disbursement (tenant_id, requested_at) WHERE attention IS NOT NULL;

CREATE FUNCTION payments.guard_b2c_disbursement() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    loan_status varchar(16);
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'B2C_IMMUTABLE: disbursements are never deleted' USING ERRCODE = 'P0001';
    END IF;
    IF TG_OP = 'INSERT' THEN
        SELECT status INTO loan_status FROM lending.loan WHERE tenant_id = NEW.tenant_id AND loan_id = NEW.loan_id;
        IF loan_status IS DISTINCT FROM 'APPROVED' THEN
            RAISE EXCEPTION 'B2C_LOAN_NOT_APPROVED: loan is %', loan_status USING ERRCODE = 'P0001';
        END IF;
        IF NEW.status <> 'REQUESTED' THEN
            RAISE EXCEPTION 'B2C_STATUS: disbursements start REQUESTED' USING ERRCODE = 'P0001';
        END IF;
        RETURN NEW;
    END IF;
    IF (to_jsonb(NEW) - 'status' - 'msisdn' - 'decided_by' - 'decided_at' - 'conversation_id' - 'transaction_id'
            - 'result_code' - 'result_desc' - 'journal_entry_id' - 'attention' - 'updated_at')
       IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'msisdn' - 'decided_by' - 'decided_at' - 'conversation_id'
            - 'transaction_id' - 'result_code' - 'result_desc' - 'journal_entry_id' - 'attention' - 'updated_at') THEN
        RAISE EXCEPTION 'B2C_IMMUTABLE: disbursement details never change' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.msisdn IS NOT NULL AND NEW.msisdn IS DISTINCT FROM OLD.msisdn THEN
        RAISE EXCEPTION 'B2C_IMMUTABLE: the phone number can only be erased' USING ERRCODE = 'P0001';
    END IF;
    IF (OLD.decided_by IS NOT NULL AND NEW.decided_by IS DISTINCT FROM OLD.decided_by)
       OR (OLD.conversation_id IS NOT NULL AND NEW.conversation_id IS DISTINCT FROM OLD.conversation_id)
       OR (OLD.transaction_id IS NOT NULL AND NEW.transaction_id IS DISTINCT FROM OLD.transaction_id)
       OR (OLD.journal_entry_id IS NOT NULL AND NEW.journal_entry_id IS DISTINCT FROM OLD.journal_entry_id) THEN
        RAISE EXCEPTION 'B2C_IMMUTABLE: decision and provider ids are final once recorded' USING ERRCODE = 'P0001';
    END IF;
    IF OLD.status IN ('COMPLETED', 'REJECTED') THEN
        RAISE EXCEPTION 'B2C_FINAL: disbursement % is %', OLD.disbursement_id, OLD.status USING ERRCODE = 'P0001';
    END IF;
    IF OLD.status = 'FAILED' AND NOT (NEW.status = 'FAILED' AND NEW.attention = 'SUCCESS_AFTER_FAILURE'
            AND OLD.attention IS NULL
            AND (to_jsonb(NEW) - 'attention' - 'updated_at' - 'transaction_id' - 'conversation_id')
                = (to_jsonb(OLD) - 'attention' - 'updated_at' - 'transaction_id' - 'conversation_id')) THEN
        RAISE EXCEPTION 'B2C_FINAL: disbursement % failed; only the success-after-failure alarm can be raised',
            OLD.disbursement_id USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
           (OLD.status = 'REQUESTED'  AND NEW.status IN ('APPROVED', 'REJECTED'))
        OR (OLD.status = 'APPROVED'   AND NEW.status = 'DISPATCHED')
        OR (OLD.status = 'DISPATCHED' AND NEW.status IN ('SUBMITTED', 'COMPLETED', 'FAILED'))
        OR (OLD.status = 'SUBMITTED'  AND NEW.status IN ('COMPLETED', 'FAILED'))) THEN
        RAISE EXCEPTION 'B2C_STATUS: % -> % not allowed', OLD.status, NEW.status USING ERRCODE = 'P0001';
    END IF;
    IF OLD.status = 'REQUESTED' AND NEW.status IN ('APPROVED', 'REJECTED') THEN
        NEW.decided_at := clock_timestamp();
    END IF;
    IF NEW.status = 'COMPLETED' AND NOT EXISTS (
            SELECT 1 FROM lending.loan l
             WHERE l.tenant_id = NEW.tenant_id AND l.loan_id = NEW.loan_id
               AND l.disbursement_entry_id = NEW.journal_entry_id AND l.status <> 'APPROVED') THEN
        RAISE EXCEPTION 'B2C_LOAN_NOT_ACTIVATED: completion needs the loan activated by this entry'
            USING ERRCODE = 'P0001';
    END IF;
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END $$;
CREATE TRIGGER b2c_disbursement_guard BEFORE INSERT OR UPDATE OR DELETE ON payments.b2c_disbursement
    FOR EACH ROW EXECUTE FUNCTION payments.guard_b2c_disbursement();

ALTER TABLE payments.mpesa_b2c_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE payments.mpesa_b2c_account FORCE ROW LEVEL SECURITY;
ALTER TABLE payments.b2c_disbursement ENABLE ROW LEVEL SECURITY;
ALTER TABLE payments.b2c_disbursement FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payments.mpesa_b2c_account
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
CREATE POLICY tenant_isolation ON payments.b2c_disbursement
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
GRANT SELECT, INSERT, UPDATE ON payments.mpesa_b2c_account TO mfi_app;
GRANT SELECT, INSERT, UPDATE ON payments.b2c_disbursement TO mfi_app;
