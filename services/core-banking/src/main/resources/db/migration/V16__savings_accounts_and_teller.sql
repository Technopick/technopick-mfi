-- =============================================================================
-- V16: Savings accounts for customers, teller cash transactions, account numbering.
--   D1  new deposit accounts belong to an ACTIVE (KYC-verified) customer
--   D2  account numbers: '2' + 8-digit per-tenant sequence + Luhn check digit (10 digits), so a
--       mistyped paybill reference rarely matches another customer's account
--   D3  each branch has one teller cash GL account; teller postings use it
--   D4  a teller transaction above the tenant's policy threshold needs approval by a different
--       person before it is posted; the database decides whether approval is required
--   D5  POSTED carries its journal entry; decisions are final; details never change; no deletes
-- =============================================================================
ALTER TABLE savings.deposit_account ADD COLUMN customer_id uuid;
ALTER TABLE savings.deposit_account ADD COLUMN open_idempotency_key varchar(80);
ALTER TABLE savings.deposit_account ADD CONSTRAINT deposit_account_open_idempotent UNIQUE (tenant_id, open_idempotency_key);
ALTER TABLE savings.deposit_account
    ADD FOREIGN KEY (tenant_id, customer_id) REFERENCES customer.customer (tenant_id, customer_id);
CREATE INDEX deposit_account_customer_idx ON savings.deposit_account (tenant_id, customer_id);

CREATE FUNCTION savings.require_active_customer() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    c customer.customer%ROWTYPE;
BEGIN
    IF NEW.customer_id IS NULL THEN
        RETURN NEW; -- accounts created before V16 (test data) carry no customer link
    END IF;
    SELECT * INTO c FROM customer.customer WHERE tenant_id = NEW.tenant_id AND customer_id = NEW.customer_id;
    IF c.customer_id IS NULL OR c.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'SAVINGS_CUSTOMER_NOT_ACTIVE: the customer must exist and be verified' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.customer_ref <> c.customer_number THEN
        RAISE EXCEPTION 'SAVINGS_CUSTOMER_MISMATCH: customer reference must be the customer number' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER deposit_account_customer BEFORE INSERT ON savings.deposit_account
    FOR EACH ROW EXECUTE FUNCTION savings.require_active_customer();

CREATE FUNCTION savings.guard_account_owner() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.customer_id IS DISTINCT FROM OLD.customer_id OR NEW.customer_ref IS DISTINCT FROM OLD.customer_ref
       OR NEW.account_number IS DISTINCT FROM OLD.account_number THEN
        RAISE EXCEPTION 'SAVINGS_ACCOUNT_IMMUTABLE: owner and number never change' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER deposit_account_owner_guard BEFORE UPDATE ON savings.deposit_account
    FOR EACH ROW EXECUTE FUNCTION savings.guard_account_owner();

-- T2 ------------------------------------------------------------------------
CREATE TABLE savings.account_number_counter (
    tenant_id  uuid PRIMARY KEY REFERENCES tenancy.tenant (tenant_id),
    last_value int  NOT NULL DEFAULT 0 CHECK (last_value BETWEEN 0 AND 99999999)
);

CREATE FUNCTION savings.luhn_digit(p_digits text) RETURNS int
    LANGUAGE plpgsql IMMUTABLE
AS $$
DECLARE
    total int := 0;
    d int;
    i int;
    double boolean := true;  -- the digit next to the check digit is doubled
BEGIN
    FOR i IN REVERSE length(p_digits)..1 LOOP
        d := substr(p_digits, i, 1)::int;
        IF double THEN
            d := d * 2;
            IF d > 9 THEN d := d - 9; END IF;
        END IF;
        total := total + d;
        double := NOT double;
    END LOOP;
    RETURN (10 - total % 10) % 10;
END $$;

CREATE FUNCTION savings.next_account_number() RETURNS varchar
    LANGUAGE plpgsql
AS $$
DECLARE
    v int;
    body text;
BEGIN
    INSERT INTO savings.account_number_counter (tenant_id, last_value) VALUES (tenancy.current_tenant(), 1)
    ON CONFLICT (tenant_id) DO UPDATE SET last_value = savings.account_number_counter.last_value + 1
    RETURNING last_value INTO v;
    body := '2' || lpad(v::text, 8, '0');
    RETURN body || savings.luhn_digit(body)::text;
END $$;

-- T3 ------------------------------------------------------------------------
CREATE TABLE savings.branch_cash_account (
    tenant_id   uuid        NOT NULL,
    branch_code varchar(16) NOT NULL,
    currency    char(3)     NOT NULL,
    gl_cash     varchar(40) NOT NULL,
    PRIMARY KEY (tenant_id, branch_code, currency),
    FOREIGN KEY (tenant_id, branch_code) REFERENCES tenancy.branch (tenant_id, branch_code),
    FOREIGN KEY (tenant_id, gl_cash) REFERENCES ledger.gl_account (tenant_id, account_code)
);

-- T4 ------------------------------------------------------------------------
CREATE TABLE savings.teller_policy (
    tenant_id                 uuid          NOT NULL REFERENCES tenancy.tenant (tenant_id),
    currency                  char(3)       NOT NULL,
    withdrawal_approval_above numeric(19,4) NOT NULL CHECK (withdrawal_approval_above >= 0),
    deposit_approval_above    numeric(19,4) NOT NULL CHECK (deposit_approval_above >= 0),
    PRIMARY KEY (tenant_id, currency)
);

CREATE TABLE savings.teller_transaction (
    tenant_id         uuid          NOT NULL,
    txn_id            uuid          NOT NULL,
    idempotency_key   varchar(80)   NOT NULL,
    account_id        uuid          NOT NULL,
    kind              varchar(12)   NOT NULL CHECK (kind IN ('DEPOSIT', 'WITHDRAWAL')),
    amount            numeric(19,4) NOT NULL CHECK (amount > 0),
    currency          char(3)       NOT NULL,
    branch_code       varchar(16)   NOT NULL,
    narrative         varchar(140)  NOT NULL,
    requires_approval boolean       NOT NULL DEFAULT false,
    status            varchar(20)   NOT NULL CHECK (status IN ('PENDING_APPROVAL', 'POSTED', 'REJECTED')),
    requested_by      varchar(100)  NOT NULL,
    requested_at      timestamptz   NOT NULL DEFAULT clock_timestamp(),
    decided_by        varchar(100),
    decided_at        timestamptz,
    decision_reason   varchar(300),
    journal_entry_id  uuid,
    PRIMARY KEY (tenant_id, txn_id),
    CONSTRAINT teller_txn_idempotent UNIQUE (tenant_id, idempotency_key),
    FOREIGN KEY (tenant_id, account_id) REFERENCES savings.deposit_account (tenant_id, account_id),
    FOREIGN KEY (tenant_id, branch_code) REFERENCES tenancy.branch (tenant_id, branch_code),
    FOREIGN KEY (tenant_id, journal_entry_id) REFERENCES ledger.journal_entry (tenant_id, entry_id),
    CHECK ((status = 'POSTED') = (journal_entry_id IS NOT NULL)),
    CHECK (decided_by IS NULL OR decided_by <> requested_by),
    CHECK (NOT requires_approval OR status = 'PENDING_APPROVAL' OR decided_by IS NOT NULL),
    CHECK (requires_approval OR decided_by IS NULL),
    CHECK (status <> 'REJECTED' OR length(trim(coalesce(decision_reason, ''))) >= 3)
);
CREATE INDEX teller_txn_pending_idx ON savings.teller_transaction (tenant_id, requested_at) WHERE status = 'PENDING_APPROVAL';
CREATE INDEX teller_txn_account_idx ON savings.teller_transaction (tenant_id, account_id, requested_at);

CREATE FUNCTION savings.guard_teller_transaction() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    p savings.teller_policy%ROWTYPE;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'TELLER_TXN_IMMUTABLE: teller transactions are never deleted' USING ERRCODE = 'P0001';
    END IF;
    IF TG_OP = 'INSERT' THEN
        SELECT * INTO p FROM savings.teller_policy WHERE tenant_id = NEW.tenant_id AND currency = NEW.currency;
        IF p.tenant_id IS NULL THEN
            RAISE EXCEPTION 'TELLER_POLICY_MISSING: no teller policy for %', NEW.currency USING ERRCODE = 'P0001';
        END IF;
        NEW.requires_approval := CASE NEW.kind WHEN 'WITHDRAWAL' THEN NEW.amount > p.withdrawal_approval_above
                                               ELSE NEW.amount > p.deposit_approval_above END;
        IF NEW.requires_approval AND NEW.status <> 'PENDING_APPROVAL' THEN
            RAISE EXCEPTION 'TELLER_APPROVAL_REQUIRED: % of % needs approval by a second person', NEW.kind, NEW.amount
                USING ERRCODE = 'P0001';
        END IF;
        IF NOT NEW.requires_approval AND NEW.status = 'PENDING_APPROVAL' THEN
            NEW.requires_approval := true; -- a teller may ask for a second person voluntarily
        END IF;
        IF NEW.status NOT IN ('PENDING_APPROVAL', 'POSTED') THEN
            RAISE EXCEPTION 'TELLER_TXN_STATUS: new transactions are PENDING_APPROVAL or POSTED' USING ERRCODE = 'P0001';
        END IF;
        RETURN NEW;
    END IF;
    IF (to_jsonb(NEW) - 'status' - 'decided_by' - 'decided_at' - 'decision_reason' - 'journal_entry_id')
       IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'decided_by' - 'decided_at' - 'decision_reason' - 'journal_entry_id') THEN
        RAISE EXCEPTION 'TELLER_TXN_IMMUTABLE: transaction details never change' USING ERRCODE = 'P0001';
    END IF;
    IF OLD.status <> 'PENDING_APPROVAL' THEN
        RAISE EXCEPTION 'TELLER_TXN_FINAL: transaction % is %', OLD.txn_id, OLD.status USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status NOT IN ('POSTED', 'REJECTED') THEN
        RAISE EXCEPTION 'TELLER_TXN_STATUS: PENDING_APPROVAL -> % not allowed', NEW.status USING ERRCODE = 'P0001';
    END IF;
    NEW.decided_at := clock_timestamp();
    RETURN NEW;
END $$;
CREATE TRIGGER teller_transaction_guard BEFORE INSERT OR UPDATE OR DELETE ON savings.teller_transaction
    FOR EACH ROW EXECUTE FUNCTION savings.guard_teller_transaction();

ALTER TABLE savings.account_number_counter ENABLE ROW LEVEL SECURITY;
ALTER TABLE savings.account_number_counter FORCE ROW LEVEL SECURITY;
ALTER TABLE savings.branch_cash_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE savings.branch_cash_account FORCE ROW LEVEL SECURITY;
ALTER TABLE savings.teller_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE savings.teller_policy FORCE ROW LEVEL SECURITY;
ALTER TABLE savings.teller_transaction ENABLE ROW LEVEL SECURITY;
ALTER TABLE savings.teller_transaction FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON savings.account_number_counter
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
CREATE POLICY tenant_isolation ON savings.branch_cash_account
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
CREATE POLICY tenant_isolation ON savings.teller_policy
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
CREATE POLICY tenant_isolation ON savings.teller_transaction
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
GRANT SELECT, INSERT, UPDATE ON savings.account_number_counter TO mfi_app;
GRANT SELECT ON savings.branch_cash_account, savings.teller_policy TO mfi_app;
GRANT SELECT, INSERT, UPDATE ON savings.teller_transaction TO mfi_app;
GRANT EXECUTE ON FUNCTION savings.next_account_number() TO mfi_app;
