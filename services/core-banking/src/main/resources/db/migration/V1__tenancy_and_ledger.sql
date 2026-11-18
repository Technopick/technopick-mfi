-- =============================================================================
-- V1: tenancy foundation and the double-entry ledger.
--
-- Safety-critical rules enforced HERE (not only in Java):
--   L1  every posted entry balances per currency and per branch     (deferred trigger)
--   L2  posted entries and lines can never be updated or deleted     (row + truncate triggers)
--   L3  postings only into OPEN accounting periods                   (before-insert trigger)
--   L4  idempotency key unique per tenant                            (unique constraint)
--   L5  an entry is reversed at most once, and a reversal mirrors it (unique + deferred trigger)
--   L6  tenant isolation via row-level security keyed on app.tenant_id
--   L7  money is numeric(19,4) and strictly positive per line        (check)
--
-- Prerequisite (created by infrastructure bootstrap, never by migrations):
--   CREATE ROLE mfi_app LOGIN PASSWORD '<from secret store>' NOSUPERUSER NOBYPASSRLS;
-- Migrations run as the schema owner; the application connects as mfi_app.
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS tenancy;
CREATE SCHEMA IF NOT EXISTS ledger;

-- Current tenant from the transaction-local setting. Returns NULL when unset,
-- which makes every RLS predicate false (fail closed).
CREATE FUNCTION tenancy.current_tenant() RETURNS uuid
    LANGUAGE sql STABLE
AS $$ SELECT nullif(current_setting('app.tenant_id', true), '')::uuid $$;

-- ---------------------------------------------------------------------------
-- Tenancy
-- ---------------------------------------------------------------------------
CREATE TABLE tenancy.tenant (
    tenant_id      uuid PRIMARY KEY,
    code           varchar(32)  NOT NULL UNIQUE,
    legal_name     varchar(200) NOT NULL,
    licence_type   varchar(32)  NOT NULL
        CHECK (licence_type IN ('MICROFINANCE_BANK', 'NON_DEPOSIT_TAKING_CREDIT_PROVIDER',
                                'DEPOSIT_TAKING_SACCO', 'NON_WITHDRAWABLE_DEPOSIT_SACCO', 'OTHER')),
    deposits_allowed boolean    NOT NULL,
    base_currency  char(3)      NOT NULL CHECK (base_currency ~ '^[A-Z]{3}$'),
    time_zone      varchar(64)  NOT NULL DEFAULT 'Africa/Nairobi',
    status         varchar(16)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    created_at     timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE tenancy.branch (
    tenant_id   uuid        NOT NULL REFERENCES tenancy.tenant (tenant_id),
    branch_code varchar(16) NOT NULL,
    name        varchar(120) NOT NULL,
    status      varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CLOSED')),
    PRIMARY KEY (tenant_id, branch_code)
);

-- ---------------------------------------------------------------------------
-- Chart of accounts and periods
-- ---------------------------------------------------------------------------
CREATE TABLE ledger.gl_account (
    tenant_id     uuid        NOT NULL REFERENCES tenancy.tenant (tenant_id),
    account_code  varchar(40) NOT NULL,
    name          varchar(160) NOT NULL,
    account_class varchar(16) NOT NULL
        CHECK (account_class IN ('ASSET', 'LIABILITY', 'EQUITY', 'INCOME', 'EXPENSE')),
    currency      char(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    postable      boolean     NOT NULL DEFAULT true,
    status        varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'BLOCKED')),
    PRIMARY KEY (tenant_id, account_code)
);

CREATE TABLE ledger.accounting_period (
    tenant_id  uuid        NOT NULL REFERENCES tenancy.tenant (tenant_id),
    period_id  varchar(16) NOT NULL,           -- e.g. 2026-11
    start_date date        NOT NULL,
    end_date   date        NOT NULL,
    status     varchar(16) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED')),
    PRIMARY KEY (tenant_id, period_id),
    CHECK (end_date >= start_date)
);
-- Periods of one tenant must not overlap.
CREATE EXTENSION IF NOT EXISTS btree_gist;
ALTER TABLE ledger.accounting_period
    ADD CONSTRAINT accounting_period_no_overlap
    EXCLUDE USING gist (tenant_id WITH =, daterange(start_date, end_date, '[]') WITH &&);

-- ---------------------------------------------------------------------------
-- Journals (posted only; drafts and approvals live in their own tables)
-- ---------------------------------------------------------------------------
CREATE TABLE ledger.journal_entry (
    tenant_id          uuid         NOT NULL REFERENCES tenancy.tenant (tenant_id),
    entry_id           uuid         NOT NULL,
    idempotency_key    varchar(200) NOT NULL,
    business_date      date         NOT NULL,
    source             varchar(40)  NOT NULL,   -- LOAN, SAVINGS, TELLER, SWITCH, MPESA, REVERSAL, MANUAL ...
    correlation_id     varchar(100) NOT NULL,
    switch_reference   varchar(100),            -- STAN/RRN or End-to-End ID for switched items
    reverses_entry_id  uuid,
    actor              varchar(100) NOT NULL,   -- user id, or channel/system principal
    posted_at          timestamptz  NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, entry_id),
    CONSTRAINT journal_entry_idempotency UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT journal_entry_reversed_once UNIQUE (tenant_id, reverses_entry_id),
    CONSTRAINT journal_entry_reverses_fk FOREIGN KEY (tenant_id, reverses_entry_id)
        REFERENCES ledger.journal_entry (tenant_id, entry_id),
    CHECK (reverses_entry_id IS NULL OR reverses_entry_id <> entry_id)
);

CREATE TABLE ledger.journal_line (
    tenant_id    uuid          NOT NULL,
    entry_id     uuid          NOT NULL,
    line_no      smallint      NOT NULL CHECK (line_no > 0),
    account_code varchar(40)   NOT NULL,
    side         char(1)       NOT NULL CHECK (side IN ('D', 'C')),
    amount       numeric(19,4) NOT NULL CHECK (amount > 0),
    currency     char(3)       NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    branch_code  varchar(16)   NOT NULL,
    narrative    varchar(200),
    PRIMARY KEY (tenant_id, entry_id, line_no),
    FOREIGN KEY (tenant_id, entry_id) REFERENCES ledger.journal_entry (tenant_id, entry_id),
    FOREIGN KEY (tenant_id, account_code) REFERENCES ledger.gl_account (tenant_id, account_code),
    FOREIGN KEY (tenant_id, branch_code) REFERENCES tenancy.branch (tenant_id, branch_code)
);
CREATE INDEX journal_line_account_idx ON ledger.journal_line (tenant_id, account_code);
CREATE INDEX journal_entry_date_idx ON ledger.journal_entry (tenant_id, business_date);
CREATE INDEX journal_entry_switch_ref_idx ON ledger.journal_entry (tenant_id, switch_reference)
    WHERE switch_reference IS NOT NULL;

-- ---------------------------------------------------------------------------
-- L3: business date must fall in an OPEN period
-- ---------------------------------------------------------------------------
CREATE FUNCTION ledger.check_period_open() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1
                     FROM ledger.accounting_period p
                    WHERE p.tenant_id = NEW.tenant_id
                      AND NEW.business_date BETWEEN p.start_date AND p.end_date
                      AND p.status = 'OPEN') THEN
        RAISE EXCEPTION 'LEDGER_PERIOD_CLOSED: no open accounting period for % on %',
            NEW.tenant_id, NEW.business_date
            USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER journal_entry_period_open
    BEFORE INSERT ON ledger.journal_entry
    FOR EACH ROW EXECUTE FUNCTION ledger.check_period_open();

-- Lines must post to postable, active accounts in the line's currency.
CREATE FUNCTION ledger.check_line_account() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    acc ledger.gl_account%ROWTYPE;
BEGIN
    SELECT * INTO acc FROM ledger.gl_account
     WHERE tenant_id = NEW.tenant_id AND account_code = NEW.account_code;
    IF NOT acc.postable OR acc.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'LEDGER_ACCOUNT_NOT_POSTABLE: %', NEW.account_code USING ERRCODE = 'P0001';
    END IF;
    IF acc.currency <> NEW.currency THEN
        RAISE EXCEPTION 'LEDGER_CURRENCY_MISMATCH: account % is %, line is %',
            NEW.account_code, acc.currency, NEW.currency USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER journal_line_account_check
    BEFORE INSERT ON ledger.journal_line
    FOR EACH ROW EXECUTE FUNCTION ledger.check_line_account();

-- ---------------------------------------------------------------------------
-- L1 + L5: checked once per entry at COMMIT (deferred constraint triggers)
-- ---------------------------------------------------------------------------
CREATE FUNCTION ledger.check_entry_balanced() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_line_count int;
    v_unbalanced record;
    v_reverses uuid;
BEGIN
    SELECT count(*) INTO v_line_count
      FROM ledger.journal_line
     WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id;
    IF v_line_count < 2 THEN
        RAISE EXCEPTION 'LEDGER_UNBALANCED: entry % has % line(s); at least 2 required',
            NEW.entry_id, v_line_count USING ERRCODE = 'P0001';
    END IF;

    SELECT currency, branch_code,
           sum(CASE side WHEN 'D' THEN amount ELSE -amount END) AS net
      INTO v_unbalanced
      FROM ledger.journal_line
     WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id
     GROUP BY currency, branch_code
    HAVING sum(CASE side WHEN 'D' THEN amount ELSE -amount END) <> 0
     LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'LEDGER_UNBALANCED: entry % is out by % % in branch %',
            NEW.entry_id, v_unbalanced.net, v_unbalanced.currency, v_unbalanced.branch_code
            USING ERRCODE = 'P0001';
    END IF;

    SELECT reverses_entry_id INTO v_reverses
      FROM ledger.journal_entry
     WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id;
    IF v_reverses IS NOT NULL THEN
        IF EXISTS (SELECT 1 FROM ledger.journal_entry
                    WHERE tenant_id = NEW.tenant_id AND entry_id = v_reverses
                      AND reverses_entry_id IS NOT NULL) THEN
            RAISE EXCEPTION 'LEDGER_REVERSAL_OF_REVERSAL: entry % reverses a reversal', NEW.entry_id
                USING ERRCODE = 'P0001';
        END IF;
        -- The reversal must mirror the original exactly (same accounts, branches,
        -- currencies and amounts, opposite sides), compared as multisets.
        IF EXISTS (
            (SELECT account_code, branch_code, currency, CASE side WHEN 'D' THEN 'C' ELSE 'D' END, amount
               FROM ledger.journal_line WHERE tenant_id = NEW.tenant_id AND entry_id = v_reverses
             EXCEPT ALL
             SELECT account_code, branch_code, currency, side, amount
               FROM ledger.journal_line WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id)
            UNION ALL
            (SELECT account_code, branch_code, currency, side, amount
               FROM ledger.journal_line WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id
             EXCEPT ALL
             SELECT account_code, branch_code, currency, CASE side WHEN 'D' THEN 'C' ELSE 'D' END, amount
               FROM ledger.journal_line WHERE tenant_id = NEW.tenant_id AND entry_id = v_reverses)
        ) THEN
            RAISE EXCEPTION 'LEDGER_REVERSAL_MISMATCH: entry % does not mirror entry %', NEW.entry_id, v_reverses
                USING ERRCODE = 'P0001';
        END IF;
    END IF;
    RETURN NULL;
END $$;

-- Fires for the entry row (catches entries with zero lines) ...
CREATE CONSTRAINT TRIGGER journal_entry_balanced
    AFTER INSERT ON ledger.journal_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger.check_entry_balanced();

-- ... and for each line (NEW has tenant_id + entry_id in both tables).
CREATE CONSTRAINT TRIGGER journal_line_balanced
    AFTER INSERT ON ledger.journal_line
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger.check_entry_balanced();

-- ---------------------------------------------------------------------------
-- L2: posted history is immutable
-- ---------------------------------------------------------------------------
CREATE FUNCTION ledger.reject_mutation() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'LEDGER_IMMUTABLE: % on %.% is not allowed; post a reversal instead',
        TG_OP, TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = 'P0001';
END $$;

CREATE TRIGGER journal_entry_immutable
    BEFORE UPDATE OR DELETE ON ledger.journal_entry
    FOR EACH ROW EXECUTE FUNCTION ledger.reject_mutation();
CREATE TRIGGER journal_line_immutable
    BEFORE UPDATE OR DELETE ON ledger.journal_line
    FOR EACH ROW EXECUTE FUNCTION ledger.reject_mutation();
CREATE TRIGGER journal_entry_no_truncate
    BEFORE TRUNCATE ON ledger.journal_entry
    FOR EACH STATEMENT EXECUTE FUNCTION ledger.reject_mutation();
CREATE TRIGGER journal_line_no_truncate
    BEFORE TRUNCATE ON ledger.journal_line
    FOR EACH STATEMENT EXECUTE FUNCTION ledger.reject_mutation();

-- ---------------------------------------------------------------------------
-- L6: row-level security (FORCE so the table owner is subject to it too)
-- ---------------------------------------------------------------------------
ALTER TABLE tenancy.branch            ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenancy.branch            FORCE ROW LEVEL SECURITY;
ALTER TABLE ledger.gl_account         ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.gl_account         FORCE ROW LEVEL SECURITY;
ALTER TABLE ledger.accounting_period  ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.accounting_period  FORCE ROW LEVEL SECURITY;
ALTER TABLE ledger.journal_entry      ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.journal_entry      FORCE ROW LEVEL SECURITY;
ALTER TABLE ledger.journal_line       ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.journal_line       FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON tenancy.branch
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
CREATE POLICY tenant_isolation ON ledger.gl_account
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
CREATE POLICY tenant_isolation ON ledger.accounting_period
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
CREATE POLICY tenant_isolation ON ledger.journal_entry
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
CREATE POLICY tenant_isolation ON ledger.journal_line
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());

-- tenancy.tenant is platform-administered: the app role may read only its own row.
ALTER TABLE tenancy.tenant ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_self ON tenancy.tenant FOR SELECT USING (tenant_id = tenancy.current_tenant());

-- ---------------------------------------------------------------------------
-- Least-privilege grants for the application role
-- ---------------------------------------------------------------------------
REVOKE ALL ON SCHEMA tenancy, ledger FROM PUBLIC;
GRANT USAGE ON SCHEMA tenancy, ledger TO mfi_app;
GRANT SELECT ON tenancy.tenant TO mfi_app;
GRANT SELECT, INSERT, UPDATE ON tenancy.branch TO mfi_app;
GRANT SELECT, INSERT, UPDATE ON ledger.gl_account, ledger.accounting_period TO mfi_app;
-- Journals: append and read only. No UPDATE/DELETE/TRUNCATE grant exists at all.
GRANT SELECT, INSERT ON ledger.journal_entry, ledger.journal_line TO mfi_app;
GRANT EXECUTE ON FUNCTION tenancy.current_tenant() TO mfi_app;
