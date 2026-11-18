-- =============================================================================
-- V2: deposit accounts, customer subledger, holds and the available-balance guard.
--
-- Rules enforced HERE:
--   S1  a GL control account flagged requires_subledger accepts only lines that name a
--       deposit account, and a subledger line must post to that account's control GL
--       in its currency -> the subledger always reconciles to the control account
--   S2  hold life cycle: ACTIVE -> CAPTURED | RELEASED | EXPIRED, terminal states final,
--       CAPTURED iff linked to a posted journal entry, holds never deleted
--   S3  available = ledger balance + overdraft limit - active holds never goes below zero
--       (checked under a row lock on the account, so concurrent holds/debits serialise)
--   S4  tenant isolation via RLS, least-privilege grants
--
-- Isolation note: the guard relies on READ COMMITTED (the application default). Each
-- statement inside the guard function sees rows committed by transactions that held the
-- account lock before it.
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS savings;

ALTER TABLE ledger.gl_account ADD COLUMN requires_subledger boolean NOT NULL DEFAULT false;

CREATE TABLE savings.deposit_product (
    tenant_id          uuid         NOT NULL REFERENCES tenancy.tenant (tenant_id),
    product_code       varchar(32)  NOT NULL,
    name               varchar(120) NOT NULL,
    currency           char(3)      NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    gl_control_account varchar(40)  NOT NULL,
    overdraft_allowed  boolean      NOT NULL DEFAULT false,
    PRIMARY KEY (tenant_id, product_code),
    FOREIGN KEY (tenant_id, gl_control_account) REFERENCES ledger.gl_account (tenant_id, account_code)
);

CREATE TABLE savings.deposit_account (
    tenant_id          uuid          NOT NULL REFERENCES tenancy.tenant (tenant_id),
    account_id         uuid          NOT NULL,
    account_number     varchar(34)   NOT NULL,
    customer_ref       varchar(64)   NOT NULL,
    product_code       varchar(32)   NOT NULL,
    currency           char(3)       NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    branch_code        varchar(16)   NOT NULL,
    gl_control_account varchar(40)   NOT NULL,
    status             varchar(16)   NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DORMANT', 'BLOCKED', 'DEBIT_BLOCKED', 'CLOSED')),
    overdraft_limit    numeric(19,4) NOT NULL DEFAULT 0 CHECK (overdraft_limit >= 0),
    opened_on          date          NOT NULL,
    PRIMARY KEY (tenant_id, account_id),
    CONSTRAINT deposit_account_number_unique UNIQUE (tenant_id, account_number),
    FOREIGN KEY (tenant_id, product_code) REFERENCES savings.deposit_product (tenant_id, product_code),
    FOREIGN KEY (tenant_id, branch_code) REFERENCES tenancy.branch (tenant_id, branch_code),
    FOREIGN KEY (tenant_id, gl_control_account) REFERENCES ledger.gl_account (tenant_id, account_code)
);

-- Account must match its product's currency and control account; overdraft only if allowed.
CREATE FUNCTION savings.check_account_product() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    p savings.deposit_product%ROWTYPE;
BEGIN
    SELECT * INTO p FROM savings.deposit_product
     WHERE tenant_id = NEW.tenant_id AND product_code = NEW.product_code;
    IF p.currency <> NEW.currency OR p.gl_control_account <> NEW.gl_control_account THEN
        RAISE EXCEPTION 'SAVINGS_PRODUCT_MISMATCH: account % must use currency % and control %',
            NEW.account_number, p.currency, p.gl_control_account USING ERRCODE = 'P0001';
    END IF;
    IF NEW.overdraft_limit > 0 AND NOT p.overdraft_allowed THEN
        RAISE EXCEPTION 'SAVINGS_OVERDRAFT_NOT_ALLOWED: product % does not allow overdrafts', p.product_code
            USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER deposit_account_product_check
    BEFORE INSERT OR UPDATE ON savings.deposit_account
    FOR EACH ROW EXECUTE FUNCTION savings.check_account_product();

-- ---------------------------------------------------------------------------
-- S1: customer subledger on journal lines
-- ---------------------------------------------------------------------------
ALTER TABLE ledger.journal_line ADD COLUMN subledger_account_id uuid;
ALTER TABLE ledger.journal_line
    ADD CONSTRAINT journal_line_subledger_fk FOREIGN KEY (tenant_id, subledger_account_id)
    REFERENCES savings.deposit_account (tenant_id, account_id);
CREATE INDEX journal_line_subledger_idx ON ledger.journal_line (tenant_id, subledger_account_id)
    WHERE subledger_account_id IS NOT NULL;

CREATE FUNCTION ledger.check_line_subledger() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_requires boolean;
    acc savings.deposit_account%ROWTYPE;
BEGIN
    SELECT requires_subledger INTO v_requires FROM ledger.gl_account
     WHERE tenant_id = NEW.tenant_id AND account_code = NEW.account_code;
    IF NEW.subledger_account_id IS NULL THEN
        IF v_requires THEN
            RAISE EXCEPTION 'LEDGER_SUBLEDGER_REQUIRED: postings to % must name a deposit account', NEW.account_code
                USING ERRCODE = 'P0001';
        END IF;
        RETURN NEW;
    END IF;
    SELECT * INTO acc FROM savings.deposit_account
     WHERE tenant_id = NEW.tenant_id AND account_id = NEW.subledger_account_id;
    IF acc.gl_control_account <> NEW.account_code OR acc.currency <> NEW.currency THEN
        RAISE EXCEPTION 'LEDGER_SUBLEDGER_MISMATCH: account % belongs to control % (%), line posts to % (%)',
            acc.account_number, acc.gl_control_account, acc.currency, NEW.account_code, NEW.currency
            USING ERRCODE = 'P0001';
    END IF;
    IF acc.status = 'CLOSED' THEN
        RAISE EXCEPTION 'SAVINGS_ACCOUNT_CLOSED: %', acc.account_number USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER journal_line_subledger_check
    BEFORE INSERT ON ledger.journal_line
    FOR EACH ROW EXECUTE FUNCTION ledger.check_line_subledger();

-- ---------------------------------------------------------------------------
-- S2: holds
-- ---------------------------------------------------------------------------
CREATE TABLE savings.account_hold (
    tenant_id            uuid          NOT NULL,
    hold_id              uuid          NOT NULL,
    account_id           uuid          NOT NULL,
    kind                 varchar(16)   NOT NULL CHECK (kind IN ('AUTHORIZATION', 'EARMARK', 'LEGAL')),
    amount               numeric(19,4) NOT NULL CHECK (amount > 0),
    currency             char(3)       NOT NULL,
    reference            varchar(100),
    idempotency_key      varchar(200)  NOT NULL,
    expires_at           timestamptz,
    status               varchar(16)   NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'CAPTURED', 'RELEASED', 'EXPIRED')),
    captured_by_entry_id uuid,
    created_at           timestamptz   NOT NULL DEFAULT clock_timestamp(),
    closed_at            timestamptz,
    PRIMARY KEY (tenant_id, hold_id),
    CONSTRAINT account_hold_idempotency UNIQUE (tenant_id, idempotency_key),
    FOREIGN KEY (tenant_id, account_id) REFERENCES savings.deposit_account (tenant_id, account_id),
    FOREIGN KEY (tenant_id, captured_by_entry_id) REFERENCES ledger.journal_entry (tenant_id, entry_id),
    CONSTRAINT account_hold_capture_link CHECK ((status = 'CAPTURED') = (captured_by_entry_id IS NOT NULL)),
    CONSTRAINT account_hold_auth_expires CHECK (kind <> 'AUTHORIZATION' OR expires_at IS NOT NULL),
    CONSTRAINT account_hold_closed_at CHECK ((status = 'ACTIVE') = (closed_at IS NULL))
);
CREATE INDEX account_hold_active_idx ON savings.account_hold (tenant_id, account_id) WHERE status = 'ACTIVE';
CREATE INDEX account_hold_expiry_idx ON savings.account_hold (expires_at) WHERE status = 'ACTIVE';

CREATE FUNCTION savings.guard_hold_update() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'SAVINGS_HOLD_IMMUTABLE: holds are never deleted' USING ERRCODE = 'P0001';
    END IF;
    IF OLD.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'SAVINGS_HOLD_FINAL: hold % is already %', OLD.hold_id, OLD.status USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status = 'ACTIVE' THEN
        RAISE EXCEPTION 'SAVINGS_HOLD_IMMUTABLE: an active hold can only change status' USING ERRCODE = 'P0001';
    END IF;
    IF (NEW.tenant_id, NEW.hold_id, NEW.account_id, NEW.kind, NEW.amount, NEW.currency, NEW.reference,
        NEW.idempotency_key, NEW.expires_at, NEW.created_at)
       IS DISTINCT FROM
       (OLD.tenant_id, OLD.hold_id, OLD.account_id, OLD.kind, OLD.amount, OLD.currency, OLD.reference,
        OLD.idempotency_key, OLD.expires_at, OLD.created_at) THEN
        RAISE EXCEPTION 'SAVINGS_HOLD_IMMUTABLE: only status, capture link and closed_at may change'
            USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status = 'CAPTURED' AND NOT EXISTS (
            SELECT 1 FROM ledger.journal_line
             WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.captured_by_entry_id
               AND subledger_account_id = NEW.account_id AND side = 'D') THEN
        RAISE EXCEPTION 'SAVINGS_CAPTURE_MISMATCH: entry % does not debit the held account',
            NEW.captured_by_entry_id USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status = 'EXPIRED' AND (OLD.expires_at IS NULL OR OLD.expires_at > clock_timestamp()) THEN
        RAISE EXCEPTION 'SAVINGS_HOLD_NOT_EXPIRED: hold % expires at %', OLD.hold_id, OLD.expires_at
            USING ERRCODE = 'P0001';
    END IF;
    NEW.closed_at := clock_timestamp();
    RETURN NEW;
END $$;

CREATE TRIGGER account_hold_transition
    BEFORE UPDATE OR DELETE ON savings.account_hold
    FOR EACH ROW EXECUTE FUNCTION savings.guard_hold_update();
CREATE TRIGGER account_hold_no_truncate
    BEFORE TRUNCATE ON savings.account_hold
    FOR EACH STATEMENT EXECUTE FUNCTION savings.guard_hold_update();

-- ---------------------------------------------------------------------------
-- S3: available balance
-- ---------------------------------------------------------------------------
CREATE VIEW savings.account_balance WITH (security_invoker = true) AS
SELECT a.tenant_id,
       a.account_id,
       a.account_number,
       a.currency,
       a.overdraft_limit,
       COALESCE(l.ledger_balance, 0) AS ledger_balance,
       COALESCE(h.active_holds, 0)   AS active_holds,
       COALESCE(l.ledger_balance, 0) + a.overdraft_limit - COALESCE(h.active_holds, 0) AS available_balance
  FROM savings.deposit_account a
  LEFT JOIN (SELECT tenant_id, subledger_account_id,
                    sum(CASE side WHEN 'C' THEN amount ELSE -amount END) AS ledger_balance
               FROM ledger.journal_line
              WHERE subledger_account_id IS NOT NULL
              GROUP BY tenant_id, subledger_account_id) l
         ON l.tenant_id = a.tenant_id AND l.subledger_account_id = a.account_id
  LEFT JOIN (SELECT tenant_id, account_id, sum(amount) AS active_holds
               FROM savings.account_hold
              WHERE status = 'ACTIVE'
              GROUP BY tenant_id, account_id) h
         ON h.tenant_id = a.tenant_id AND h.account_id = a.account_id;

-- Locks the account row (serialising concurrent holds and debits on it), then checks
-- available >= 0 with a fresh snapshot. p_extra is an amount not yet visible (a hold being
-- inserted by the calling BEFORE trigger).
CREATE FUNCTION savings.assert_available(p_tenant uuid, p_account uuid, p_extra numeric) RETURNS void
    LANGUAGE plpgsql VOLATILE
AS $$
DECLARE
    v_number varchar(34);
    v_available numeric;
BEGIN
    SELECT account_number INTO v_number FROM savings.deposit_account
     WHERE tenant_id = p_tenant AND account_id = p_account
       FOR NO KEY UPDATE;
    SELECT available_balance INTO v_available FROM savings.account_balance
     WHERE tenant_id = p_tenant AND account_id = p_account;
    IF v_available - p_extra < 0 THEN
        RAISE EXCEPTION 'SAVINGS_INSUFFICIENT_AVAILABLE: account % would have available %',
            v_number, v_available - p_extra USING ERRCODE = 'P0001';
    END IF;
END $$;

-- New holds: checked immediately (fail fast, and take the account lock early).
CREATE FUNCTION savings.check_hold_insert() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    acc savings.deposit_account%ROWTYPE;
BEGIN
    IF NEW.status <> 'ACTIVE' OR NEW.captured_by_entry_id IS NOT NULL OR NEW.closed_at IS NOT NULL THEN
        RAISE EXCEPTION 'SAVINGS_HOLD_IMMUTABLE: holds are created ACTIVE' USING ERRCODE = 'P0001';
    END IF;
    SELECT * INTO acc FROM savings.deposit_account WHERE tenant_id = NEW.tenant_id AND account_id = NEW.account_id;
    IF acc.currency <> NEW.currency THEN
        RAISE EXCEPTION 'SAVINGS_CURRENCY_MISMATCH: hold in %, account in %', NEW.currency, acc.currency
            USING ERRCODE = 'P0001';
    END IF;
    IF NEW.kind = 'AUTHORIZATION' THEN
        PERFORM savings.assert_available(NEW.tenant_id, NEW.account_id, NEW.amount);
    END IF;
    -- EARMARK and LEGAL holds may exceed available (e.g. a court order on an empty account);
    -- they still block later debits through the guard below.
    RETURN NEW;
END $$;

CREATE TRIGGER account_hold_insert_check
    BEFORE INSERT ON savings.account_hold
    FOR EACH ROW EXECUTE FUNCTION savings.check_hold_insert();

-- Debits to a deposit account: checked at COMMIT, after any hold in the same transaction
-- has been captured, so capture (debit + hold CAPTURED) nets correctly.
CREATE FUNCTION savings.check_debit_available() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.subledger_account_id IS NOT NULL AND NEW.side = 'D' THEN
        PERFORM savings.assert_available(NEW.tenant_id, NEW.subledger_account_id, 0);
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER journal_line_available_guard
    AFTER INSERT ON ledger.journal_line
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION savings.check_debit_available();

-- ---------------------------------------------------------------------------
-- S4: RLS and grants
-- ---------------------------------------------------------------------------
ALTER TABLE savings.deposit_product ENABLE ROW LEVEL SECURITY;
ALTER TABLE savings.deposit_product FORCE ROW LEVEL SECURITY;
ALTER TABLE savings.deposit_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE savings.deposit_account FORCE ROW LEVEL SECURITY;
ALTER TABLE savings.account_hold    ENABLE ROW LEVEL SECURITY;
ALTER TABLE savings.account_hold    FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON savings.deposit_product
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
CREATE POLICY tenant_isolation ON savings.deposit_account
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
CREATE POLICY tenant_isolation ON savings.account_hold
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());

REVOKE ALL ON SCHEMA savings FROM PUBLIC;
GRANT USAGE ON SCHEMA savings TO mfi_app;
GRANT SELECT, INSERT, UPDATE ON savings.deposit_product, savings.deposit_account TO mfi_app;
-- Holds: insert and status transitions only (the trigger restricts what UPDATE may change).
GRANT SELECT, INSERT, UPDATE ON savings.account_hold TO mfi_app;
GRANT SELECT ON savings.account_balance TO mfi_app;
GRANT EXECUTE ON FUNCTION savings.assert_available(uuid, uuid, numeric) TO mfi_app;
