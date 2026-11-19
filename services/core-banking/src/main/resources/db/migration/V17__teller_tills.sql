-- =============================================================================
-- V17: Teller tills, vault transfers and end-of-day cash balancing.
--   W1  each till has its own cash GL account; the branch vault is the branch cash account
--   W2  a till has at most one open session, and a teller at most one open session at a time
--   W3  teller cash transactions belong to the capturing teller's OPEN session
--   W4  cash moves between vault and till only when a different person approves the transfer
--   W5  closing: counted cash vs the till's ledger balance; a non-zero variance is posted only
--       after a different person approves it; a closed session never changes
--   W6  a session cannot close while its cash transactions or transfers await approval
-- =============================================================================
ALTER TABLE savings.branch_cash_account ADD COLUMN gl_shortage varchar(40);
ALTER TABLE savings.branch_cash_account ADD COLUMN gl_overage varchar(40);
ALTER TABLE savings.branch_cash_account
    ADD FOREIGN KEY (tenant_id, gl_shortage) REFERENCES ledger.gl_account (tenant_id, account_code),
    ADD FOREIGN KEY (tenant_id, gl_overage) REFERENCES ledger.gl_account (tenant_id, account_code);

CREATE TABLE savings.till (
    tenant_id   uuid        NOT NULL,
    till_id     uuid        NOT NULL,
    till_code   varchar(24) NOT NULL,
    branch_code varchar(16) NOT NULL,
    currency    char(3)     NOT NULL,
    gl_cash     varchar(40) NOT NULL,
    active      boolean     NOT NULL DEFAULT true,
    PRIMARY KEY (tenant_id, till_id),
    CONSTRAINT till_code_unique UNIQUE (tenant_id, till_code),
    CONSTRAINT till_own_gl UNIQUE (tenant_id, gl_cash),
    FOREIGN KEY (tenant_id, branch_code, currency) REFERENCES savings.branch_cash_account (tenant_id, branch_code, currency),
    FOREIGN KEY (tenant_id, gl_cash) REFERENCES ledger.gl_account (tenant_id, account_code)
);

CREATE TABLE savings.till_session (
    tenant_id          uuid          NOT NULL,
    session_id         uuid          NOT NULL,
    till_id            uuid          NOT NULL,
    teller             varchar(100)  NOT NULL,
    business_date      date          NOT NULL,
    status             varchar(16)   NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSING', 'CLOSED')),
    opened_at          timestamptz   NOT NULL DEFAULT clock_timestamp(),
    counted_amount     numeric(19,4) CHECK (counted_amount >= 0),
    expected_amount    numeric(19,4),
    variance           numeric(19,4) GENERATED ALWAYS AS (counted_amount - expected_amount) STORED,
    close_requested_at timestamptz,
    variance_approved_by varchar(100),
    variance_reason    varchar(300),
    variance_entry_id  uuid,
    closed_at          timestamptz,
    PRIMARY KEY (tenant_id, session_id),
    FOREIGN KEY (tenant_id, till_id) REFERENCES savings.till (tenant_id, till_id),
    FOREIGN KEY (tenant_id, variance_entry_id) REFERENCES ledger.journal_entry (tenant_id, entry_id),
    CHECK ((status = 'OPEN') = (counted_amount IS NULL)),
    CHECK ((counted_amount IS NULL) = (expected_amount IS NULL)),
    CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL)),
    CHECK (variance_approved_by IS NULL OR variance_approved_by <> teller),
    CHECK (status <> 'CLOSED' OR variance = 0 OR (variance_approved_by IS NOT NULL AND variance_entry_id IS NOT NULL
                                                    AND length(trim(coalesce(variance_reason, ''))) >= 3)),
    CHECK (status <> 'CLOSING' OR variance <> 0)
);
CREATE UNIQUE INDEX till_session_one_per_till ON savings.till_session (tenant_id, till_id) WHERE status <> 'CLOSED';
CREATE UNIQUE INDEX till_session_one_per_teller ON savings.till_session (tenant_id, teller) WHERE status <> 'CLOSED';

CREATE TABLE savings.cash_transfer (
    tenant_id        uuid          NOT NULL,
    transfer_id      uuid          NOT NULL,
    idempotency_key  varchar(80)   NOT NULL,
    session_id       uuid          NOT NULL,
    direction        varchar(16)   NOT NULL CHECK (direction IN ('VAULT_TO_TILL', 'TILL_TO_VAULT')),
    amount           numeric(19,4) NOT NULL CHECK (amount > 0),
    currency         char(3)       NOT NULL,
    status           varchar(16)   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'POSTED', 'REJECTED')),
    requested_by     varchar(100)  NOT NULL,
    requested_at     timestamptz   NOT NULL DEFAULT clock_timestamp(),
    decided_by       varchar(100),
    decided_at       timestamptz,
    decision_reason  varchar(300),
    journal_entry_id uuid,
    PRIMARY KEY (tenant_id, transfer_id),
    CONSTRAINT cash_transfer_idempotent UNIQUE (tenant_id, idempotency_key),
    FOREIGN KEY (tenant_id, session_id) REFERENCES savings.till_session (tenant_id, session_id),
    FOREIGN KEY (tenant_id, journal_entry_id) REFERENCES ledger.journal_entry (tenant_id, entry_id),
    CHECK ((status = 'PENDING') = (decided_by IS NULL)),
    CHECK (decided_by IS NULL OR decided_by <> requested_by),
    CHECK ((status = 'POSTED') = (journal_entry_id IS NOT NULL)),
    CHECK (status <> 'REJECTED' OR length(trim(coalesce(decision_reason, ''))) >= 3)
);
CREATE INDEX cash_transfer_pending_idx ON savings.cash_transfer (tenant_id, requested_at) WHERE status = 'PENDING';

ALTER TABLE savings.teller_transaction ADD COLUMN session_id uuid;
ALTER TABLE savings.teller_transaction
    ADD FOREIGN KEY (tenant_id, session_id) REFERENCES savings.till_session (tenant_id, session_id);

-- W3: new teller transactions need the capturing teller's open session at the account's branch.
CREATE FUNCTION savings.require_open_session() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    s savings.till_session%ROWTYPE;
    t savings.till%ROWTYPE;
BEGIN
    SELECT * INTO s FROM savings.till_session WHERE tenant_id = NEW.tenant_id AND session_id = NEW.session_id;
    IF s.session_id IS NULL OR s.status <> 'OPEN' OR s.teller <> NEW.requested_by THEN
        RAISE EXCEPTION 'TELLER_NO_OPEN_TILL: cash transactions need the teller''s own open till session'
            USING ERRCODE = 'P0001';
    END IF;
    SELECT * INTO t FROM savings.till WHERE tenant_id = s.tenant_id AND till_id = s.till_id;
    IF t.branch_code <> NEW.branch_code OR t.currency <> NEW.currency THEN
        RAISE EXCEPTION 'TELLER_TILL_MISMATCH: the till is at % (%), the account at % (%)',
            t.branch_code, t.currency, NEW.branch_code, NEW.currency USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER teller_transaction_session BEFORE INSERT ON savings.teller_transaction
    FOR EACH ROW EXECUTE FUNCTION savings.require_open_session();

CREATE FUNCTION savings.guard_cash_transfer() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    s savings.till_session%ROWTYPE;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'CASH_TRANSFER_IMMUTABLE: transfers are never deleted' USING ERRCODE = 'P0001';
    END IF;
    IF TG_OP = 'INSERT' THEN
        SELECT * INTO s FROM savings.till_session WHERE tenant_id = NEW.tenant_id AND session_id = NEW.session_id;
        IF s.status IS DISTINCT FROM 'OPEN' OR s.teller <> NEW.requested_by OR NEW.status <> 'PENDING' THEN
            RAISE EXCEPTION 'CASH_TRANSFER_SESSION: transfers start PENDING on the teller''s own open session'
                USING ERRCODE = 'P0001';
        END IF;
        RETURN NEW;
    END IF;
    IF (to_jsonb(NEW) - 'status' - 'decided_by' - 'decided_at' - 'decision_reason' - 'journal_entry_id')
       IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'decided_by' - 'decided_at' - 'decision_reason' - 'journal_entry_id') THEN
        RAISE EXCEPTION 'CASH_TRANSFER_IMMUTABLE: transfer details never change' USING ERRCODE = 'P0001';
    END IF;
    IF OLD.status <> 'PENDING' THEN
        RAISE EXCEPTION 'CASH_TRANSFER_FINAL: transfer % is %', OLD.transfer_id, OLD.status USING ERRCODE = 'P0001';
    END IF;
    NEW.decided_at := clock_timestamp();
    RETURN NEW;
END $$;
CREATE TRIGGER cash_transfer_guard BEFORE INSERT OR UPDATE OR DELETE ON savings.cash_transfer
    FOR EACH ROW EXECUTE FUNCTION savings.guard_cash_transfer();

CREATE FUNCTION savings.guard_till_session() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'TILL_SESSION_IMMUTABLE: sessions are never deleted' USING ERRCODE = 'P0001';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'OPEN' THEN
            RAISE EXCEPTION 'TILL_SESSION_STATUS: sessions start OPEN' USING ERRCODE = 'P0001';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.session_id <> OLD.session_id OR NEW.till_id <> OLD.till_id OR NEW.teller <> OLD.teller
       OR NEW.business_date <> OLD.business_date OR NEW.opened_at <> OLD.opened_at THEN
        RAISE EXCEPTION 'TILL_SESSION_IMMUTABLE: session identity never changes' USING ERRCODE = 'P0001';
    END IF;
    IF OLD.status = 'CLOSED' THEN
        RAISE EXCEPTION 'TILL_SESSION_FINAL: session % is closed', OLD.session_id USING ERRCODE = 'P0001';
    END IF;
    IF NOT ((OLD.status = 'OPEN' AND NEW.status IN ('CLOSING', 'CLOSED'))
         OR (OLD.status = 'CLOSING' AND NEW.status IN ('OPEN', 'CLOSED'))
         OR (OLD.status = NEW.status AND OLD.status = 'OPEN')) THEN
        RAISE EXCEPTION 'TILL_SESSION_STATUS: % -> % not allowed', OLD.status, NEW.status USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status IN ('CLOSING', 'CLOSED') AND OLD.status = 'OPEN' AND (
           EXISTS (SELECT 1 FROM savings.teller_transaction
                    WHERE tenant_id = NEW.tenant_id AND session_id = NEW.session_id AND status = 'PENDING_APPROVAL')
        OR EXISTS (SELECT 1 FROM savings.cash_transfer
                    WHERE tenant_id = NEW.tenant_id AND session_id = NEW.session_id AND status = 'PENDING')) THEN
        RAISE EXCEPTION 'TILL_SESSION_PENDING: decide pending cash transactions and transfers before closing'
            USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status = 'CLOSED' THEN
        NEW.closed_at := clock_timestamp();
    END IF;
    IF NEW.status = 'CLOSING' AND OLD.status = 'OPEN' THEN
        NEW.close_requested_at := clock_timestamp();
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER till_session_guard BEFORE INSERT OR UPDATE OR DELETE ON savings.till_session
    FOR EACH ROW EXECUTE FUNCTION savings.guard_till_session();

ALTER TABLE savings.till ENABLE ROW LEVEL SECURITY;
ALTER TABLE savings.till FORCE ROW LEVEL SECURITY;
ALTER TABLE savings.till_session ENABLE ROW LEVEL SECURITY;
ALTER TABLE savings.till_session FORCE ROW LEVEL SECURITY;
ALTER TABLE savings.cash_transfer ENABLE ROW LEVEL SECURITY;
ALTER TABLE savings.cash_transfer FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON savings.till
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
CREATE POLICY tenant_isolation ON savings.till_session
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
CREATE POLICY tenant_isolation ON savings.cash_transfer
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
GRANT SELECT ON savings.till TO mfi_app;
GRANT SELECT, INSERT, UPDATE ON savings.till_session, savings.cash_transfer TO mfi_app;
