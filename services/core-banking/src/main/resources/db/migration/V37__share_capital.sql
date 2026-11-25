-- =============================================================================
-- V37: Member share capital (ADR-0040).
--   SH1 settings per tenant: par value (cents), minimum holding a multiple of it, share capital and
--       dividend source accounts are EQUITY, dividends payable and withholding tax are LIABILITY;
--       the par value and the share capital account never change once shares exist
--   SH2 the share register is a list of transactions that never change; amounts are whole shares
--       (multiples of the par value) on the current business date; a member's holding is their sum
--       and never negative
--   SH3 a purchase is paid from one of the member's own savings accounts; its entry debits that
--       account and credits share capital by exactly the amount, in the account's branch
--   SH4 only share purchases post to the share capital account; manual journals cannot (control
--       account); reconciliation check SHARE_CAPITAL compares it with the register
--   SH5 a transfer between two different members is asked for by one person and approved by
--       another; approval writes exactly one TRANSFER_OUT and one TRANSFER_IN of the amount; the
--       seller keeps at least the minimum holding; the buyer is an active member
--   SH6 a dividend run is asked for by one person and posted by another; its lines are every member
--       holding shares at the record date (a closed day), gross = holding x rate rounded to cents,
--       tax = gross x tax rate rounded to cents, net = gross - tax; totals add up; the entry debits
--       the dividend source by the gross, credits tax and each member's savings account (or
--       dividends payable for members without one) exactly; one run per financial year
-- =============================================================================
CREATE SCHEMA shares;

CREATE TABLE shares.share_settings (
    tenant_id            uuid          NOT NULL PRIMARY KEY REFERENCES tenancy.tenant (tenant_id),
    par_value            numeric(19,4) NOT NULL CHECK (par_value > 0 AND par_value = round(par_value, 2)),
    minimum_holding      numeric(19,4) NOT NULL DEFAULT 0 CHECK (minimum_holding >= 0),
    gl_share_capital     varchar(40)   NOT NULL,
    gl_dividend_source   varchar(40)   NOT NULL,
    gl_dividends_payable varchar(40)   NOT NULL,
    gl_dividend_tax      varchar(40)   NOT NULL,
    dividend_tax_rate    numeric(5,2)  NOT NULL DEFAULT 5 CHECK (dividend_tax_rate >= 0 AND dividend_tax_rate <= 100),
    updated_by           varchar(100)  NOT NULL,
    updated_at           timestamptz   NOT NULL DEFAULT clock_timestamp(),
    CHECK (mod(minimum_holding, par_value) = 0),
    CHECK (gl_share_capital NOT IN (gl_dividend_source, gl_dividends_payable, gl_dividend_tax)
           AND gl_dividend_source NOT IN (gl_dividends_payable, gl_dividend_tax) AND gl_dividends_payable <> gl_dividend_tax),
    FOREIGN KEY (tenant_id, gl_share_capital) REFERENCES ledger.gl_account (tenant_id, account_code),
    FOREIGN KEY (tenant_id, gl_dividend_source) REFERENCES ledger.gl_account (tenant_id, account_code),
    FOREIGN KEY (tenant_id, gl_dividends_payable) REFERENCES ledger.gl_account (tenant_id, account_code),
    FOREIGN KEY (tenant_id, gl_dividend_tax) REFERENCES ledger.gl_account (tenant_id, account_code)
);

CREATE TABLE shares.share_transfer (
    tenant_id        uuid          NOT NULL,
    transfer_id      uuid          NOT NULL,
    idempotency_key  varchar(80)   NOT NULL,
    from_customer_id uuid          NOT NULL,
    to_customer_id   uuid          NOT NULL,
    amount           numeric(19,4) NOT NULL CHECK (amount > 0),
    reason           varchar(300)  NOT NULL CHECK (length(btrim(reason)) >= 10),
    status           varchar(12)   NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED')),
    requested_by     varchar(100)  NOT NULL,
    requested_at     timestamptz   NOT NULL DEFAULT clock_timestamp(),
    decided_by       varchar(100),
    decided_at       timestamptz,
    decision_reason  varchar(300),
    business_date    date,
    PRIMARY KEY (tenant_id, transfer_id),
    UNIQUE (tenant_id, idempotency_key),
    FOREIGN KEY (tenant_id, from_customer_id) REFERENCES customer.customer (tenant_id, customer_id),
    FOREIGN KEY (tenant_id, to_customer_id) REFERENCES customer.customer (tenant_id, customer_id),
    CHECK (from_customer_id <> to_customer_id),
    CHECK ((status = 'REQUESTED') = (decided_by IS NULL)),
    CHECK (decided_by IS NULL OR (decided_by <> requested_by AND decided_at IS NOT NULL)),
    CHECK ((status = 'APPROVED') = (business_date IS NOT NULL)),
    CHECK (status <> 'REJECTED' OR length(btrim(COALESCE(decision_reason, ''))) >= 3)
);

CREATE TABLE shares.share_transaction (
    tenant_id         uuid          NOT NULL,
    txn_id            uuid          NOT NULL,
    idempotency_key   varchar(80)   NOT NULL,
    customer_id       uuid          NOT NULL,
    kind              varchar(12)   NOT NULL CHECK (kind IN ('PURCHASE', 'TRANSFER_IN', 'TRANSFER_OUT')),
    amount            numeric(19,4) NOT NULL CHECK (amount > 0),
    business_date     date          NOT NULL,
    entry_id          uuid,
    source_account_id uuid,
    transfer_id       uuid,
    created_by        varchar(100)  NOT NULL,
    created_at        timestamptz   NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, txn_id),
    UNIQUE (tenant_id, idempotency_key),
    FOREIGN KEY (tenant_id, customer_id) REFERENCES customer.customer (tenant_id, customer_id),
    FOREIGN KEY (tenant_id, entry_id) REFERENCES ledger.journal_entry (tenant_id, entry_id),
    FOREIGN KEY (tenant_id, source_account_id) REFERENCES savings.deposit_account (tenant_id, account_id),
    FOREIGN KEY (tenant_id, transfer_id) REFERENCES shares.share_transfer (tenant_id, transfer_id),
    CHECK ((kind = 'PURCHASE') = (entry_id IS NOT NULL AND source_account_id IS NOT NULL)),
    CHECK ((kind = 'PURCHASE') = (transfer_id IS NULL))
);
CREATE INDEX share_transaction_customer ON shares.share_transaction (tenant_id, customer_id, business_date);
CREATE UNIQUE INDEX share_transaction_transfer_side ON shares.share_transaction (tenant_id, transfer_id, kind)
    WHERE transfer_id IS NOT NULL;
CREATE UNIQUE INDEX share_transaction_entry ON shares.share_transaction (tenant_id, entry_id) WHERE entry_id IS NOT NULL;

CREATE TABLE shares.dividend_run (
    tenant_id            uuid          NOT NULL,
    run_id               uuid          NOT NULL,
    idempotency_key      varchar(80)   NOT NULL,
    financial_year       int           NOT NULL CHECK (financial_year BETWEEN 2000 AND 2100),
    record_date          date          NOT NULL,
    rate_pct             numeric(7,4)  NOT NULL CHECK (rate_pct > 0 AND rate_pct <= 100),
    tax_rate             numeric(5,2)  NOT NULL CHECK (tax_rate >= 0 AND tax_rate <= 100),
    gl_dividend_source   varchar(40)   NOT NULL,
    gl_dividends_payable varchar(40)   NOT NULL,
    gl_dividend_tax      varchar(40)   NOT NULL,
    members              int           NOT NULL CHECK (members >= 0),
    shares_total         numeric(19,4) NOT NULL CHECK (shares_total >= 0),
    gross_total          numeric(19,4) NOT NULL CHECK (gross_total >= 0),
    tax_total            numeric(19,4) NOT NULL CHECK (tax_total >= 0),
    net_total            numeric(19,4) NOT NULL CHECK (net_total >= 0),
    to_savings_total     numeric(19,4) NOT NULL CHECK (to_savings_total >= 0),
    to_payable_total     numeric(19,4) NOT NULL CHECK (to_payable_total >= 0),
    status               varchar(12)   NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED', 'POSTED', 'REJECTED')),
    requested_by         varchar(100)  NOT NULL,
    requested_at         timestamptz   NOT NULL DEFAULT clock_timestamp(),
    decided_by           varchar(100),
    decided_at           timestamptz,
    decision_reason      varchar(300),
    business_date        date,
    entry_id             uuid,
    PRIMARY KEY (tenant_id, run_id),
    UNIQUE (tenant_id, idempotency_key),
    FOREIGN KEY (tenant_id, entry_id) REFERENCES ledger.journal_entry (tenant_id, entry_id),
    CHECK (record_date >= make_date(financial_year, 1, 1)),
    CHECK (net_total = gross_total - tax_total AND to_savings_total + to_payable_total = net_total),
    CHECK ((status = 'REQUESTED') = (decided_by IS NULL)),
    CHECK (decided_by IS NULL OR (decided_by <> requested_by AND decided_at IS NOT NULL)),
    CHECK ((status = 'POSTED') = (entry_id IS NOT NULL AND business_date IS NOT NULL)),
    CHECK (status <> 'REJECTED' OR length(btrim(COALESCE(decision_reason, ''))) >= 3)
);
CREATE UNIQUE INDEX dividend_run_one_per_year ON shares.dividend_run (tenant_id, financial_year) WHERE status <> 'REJECTED';

CREATE TABLE shares.dividend_line (
    tenant_id     uuid          NOT NULL,
    run_id        uuid          NOT NULL,
    customer_id   uuid          NOT NULL,
    shares_amount numeric(19,4) NOT NULL CHECK (shares_amount > 0),
    gross         numeric(19,4) NOT NULL CHECK (gross >= 0),
    tax           numeric(19,4) NOT NULL CHECK (tax >= 0),
    net           numeric(19,4) NOT NULL,
    account_id    uuid,
    branch_code   varchar(16)   NOT NULL,
    PRIMARY KEY (tenant_id, run_id, customer_id),
    FOREIGN KEY (tenant_id, run_id) REFERENCES shares.dividend_run (tenant_id, run_id),
    FOREIGN KEY (tenant_id, customer_id) REFERENCES customer.customer (tenant_id, customer_id),
    FOREIGN KEY (tenant_id, account_id) REFERENCES savings.deposit_account (tenant_id, account_id),
    CHECK (net = gross - tax)
);

-- A member's holding (from the register) at the end of a business date; all dates when NULL.
CREATE FUNCTION shares.holding(p_tenant uuid, p_customer uuid, p_as_of date) RETURNS numeric
    LANGUAGE sql STABLE
AS $$
    SELECT COALESCE(sum(CASE kind WHEN 'TRANSFER_OUT' THEN -amount ELSE amount END), 0)
      FROM shares.share_transaction
     WHERE tenant_id = p_tenant AND customer_id = p_customer AND (p_as_of IS NULL OR business_date <= p_as_of)
$$;

-- SH1
CREATE FUNCTION shares.check_share_settings() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF (SELECT account_class FROM ledger.gl_account WHERE tenant_id = NEW.tenant_id AND account_code = NEW.gl_share_capital) <> 'EQUITY'
       OR (SELECT account_class FROM ledger.gl_account WHERE tenant_id = NEW.tenant_id AND account_code = NEW.gl_dividend_source) <> 'EQUITY'
       OR (SELECT account_class FROM ledger.gl_account WHERE tenant_id = NEW.tenant_id AND account_code = NEW.gl_dividends_payable) <> 'LIABILITY'
       OR (SELECT account_class FROM ledger.gl_account WHERE tenant_id = NEW.tenant_id AND account_code = NEW.gl_dividend_tax) <> 'LIABILITY' THEN
        RAISE EXCEPTION 'SHARE_SETTINGS_INVALID: share capital and dividend source must be EQUITY, dividends payable and tax LIABILITY accounts'
            USING ERRCODE = 'P0001';
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.par_value <> OLD.par_value OR NEW.gl_share_capital <> OLD.gl_share_capital)
       AND EXISTS (SELECT 1 FROM shares.share_transaction WHERE tenant_id = NEW.tenant_id) THEN
        RAISE EXCEPTION 'SHARE_SETTINGS_LOCKED: shares have been issued; the par value and share capital account are fixed'
            USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER share_settings_check BEFORE INSERT OR UPDATE ON shares.share_settings
    FOR EACH ROW EXECUTE FUNCTION shares.check_share_settings();

-- SH2, SH3, SH5 (row checks)
CREATE FUNCTION shares.guard_share_transaction() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    s shares.share_settings%ROWTYPE;
    t shares.share_transfer%ROWTYPE;
    v_today date;
    v_owner uuid;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'SHARE_IMMUTABLE: the share register never changes' USING ERRCODE = 'P0001';
    END IF;
    SELECT * INTO s FROM shares.share_settings WHERE tenant_id = NEW.tenant_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'SHARE_SETTINGS_MISSING: share capital is not set up' USING ERRCODE = 'P0001';
    END IF;
    IF mod(NEW.amount, s.par_value) <> 0 THEN
        RAISE EXCEPTION 'SHARE_NOT_WHOLE: % is not a whole number of shares of %', NEW.amount, s.par_value USING ERRCODE = 'P0001';
    END IF;
    SELECT current_date_ INTO v_today FROM tenancy.business_date WHERE tenant_id = NEW.tenant_id;
    IF NEW.business_date <> v_today THEN
        RAISE EXCEPTION 'SHARE_DATE: share transactions are dated the business date %', v_today USING ERRCODE = 'P0001';
    END IF;
    IF NEW.kind = 'PURCHASE' THEN
        IF (SELECT status FROM customer.customer WHERE tenant_id = NEW.tenant_id AND customer_id = NEW.customer_id) <> 'ACTIVE' THEN
            RAISE EXCEPTION 'SHARE_MEMBER_INACTIVE: only active members buy shares' USING ERRCODE = 'P0001';
        END IF;
        SELECT customer_id INTO v_owner FROM savings.deposit_account
         WHERE tenant_id = NEW.tenant_id AND account_id = NEW.source_account_id;
        IF v_owner IS DISTINCT FROM NEW.customer_id THEN
            RAISE EXCEPTION 'SHARE_ACCOUNT_NOT_OWN: shares are paid from the member''s own savings account' USING ERRCODE = 'P0001';
        END IF;
    ELSE
        SELECT * INTO t FROM shares.share_transfer WHERE tenant_id = NEW.tenant_id AND transfer_id = NEW.transfer_id;
        IF t.status <> 'APPROVED' OR t.amount <> NEW.amount OR t.business_date <> NEW.business_date
           OR (NEW.kind = 'TRANSFER_OUT' AND NEW.customer_id <> t.from_customer_id)
           OR (NEW.kind = 'TRANSFER_IN' AND NEW.customer_id <> t.to_customer_id) THEN
            RAISE EXCEPTION 'SHARE_TRANSFER_MISMATCH: register rows must follow the approved transfer' USING ERRCODE = 'P0001';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER share_transaction_guard BEFORE INSERT OR UPDATE OR DELETE ON shares.share_transaction
    FOR EACH ROW EXECUTE FUNCTION shares.guard_share_transaction();

-- SH2, SH3, SH5 (checked at commit, when the entry and both sides of a transfer exist)
CREATE FUNCTION shares.check_share_transaction() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    s shares.share_settings%ROWTYPE;
    v_holding numeric;
    v_control varchar;
    v_branch varchar;
    v_capital numeric;
    v_debit numeric;
    v_other int;
BEGIN
    SELECT * INTO s FROM shares.share_settings WHERE tenant_id = NEW.tenant_id;
    v_holding := shares.holding(NEW.tenant_id, NEW.customer_id, NULL);
    IF v_holding < 0 THEN
        RAISE EXCEPTION 'SHARE_NEGATIVE: the member would hold % in shares', v_holding USING ERRCODE = 'P0001';
    END IF;
    IF NEW.kind = 'TRANSFER_OUT' AND v_holding < s.minimum_holding THEN
        RAISE EXCEPTION 'SHARE_BELOW_MINIMUM: the seller would keep % in shares, below the minimum %', v_holding, s.minimum_holding
            USING ERRCODE = 'P0001';
    END IF;
    IF NEW.kind = 'PURCHASE' THEN
        SELECT gl_control_account, branch_code INTO v_control, v_branch FROM savings.deposit_account
         WHERE tenant_id = NEW.tenant_id AND account_id = NEW.source_account_id;
        SELECT COALESCE(sum(CASE WHEN account_code = s.gl_share_capital AND branch_code = v_branch
                                 THEN CASE side WHEN 'C' THEN amount ELSE -amount END END), 0),
               COALESCE(sum(CASE WHEN account_code = v_control AND subledger_account_id = NEW.source_account_id
                                 THEN CASE side WHEN 'D' THEN amount ELSE -amount END END), 0),
               count(*) FILTER (WHERE account_code NOT IN (s.gl_share_capital, v_control))
          INTO v_capital, v_debit, v_other
          FROM ledger.journal_line WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id;
        IF v_capital <> NEW.amount OR v_debit <> NEW.amount OR v_other > 0 THEN
            RAISE EXCEPTION 'SHARE_POSTING_MISMATCH: purchase of % but entry moves share capital % and the account %',
                NEW.amount, v_capital, v_debit USING ERRCODE = 'P0001';
        END IF;
    ELSIF (SELECT count(*) FROM shares.share_transaction WHERE tenant_id = NEW.tenant_id AND transfer_id = NEW.transfer_id) <> 2 THEN
        RAISE EXCEPTION 'SHARE_TRANSFER_MISMATCH: a transfer writes one TRANSFER_OUT and one TRANSFER_IN' USING ERRCODE = 'P0001';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER share_transaction_posting AFTER INSERT ON shares.share_transaction
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION shares.check_share_transaction();

-- SH5 transfers
CREATE FUNCTION shares.guard_share_transfer() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    s shares.share_settings%ROWTYPE;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'SHARE_IMMUTABLE: transfers are never deleted' USING ERRCODE = 'P0001';
    END IF;
    SELECT * INTO s FROM shares.share_settings WHERE tenant_id = NEW.tenant_id;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'REQUESTED' THEN
            RAISE EXCEPTION 'SHARE_TRANSFER_INVALID: transfers start REQUESTED' USING ERRCODE = 'P0001';
        END IF;
        IF s.par_value IS NULL OR mod(NEW.amount, s.par_value) <> 0 THEN
            RAISE EXCEPTION 'SHARE_NOT_WHOLE: % is not a whole number of shares', NEW.amount USING ERRCODE = 'P0001';
        END IF;
    ELSE
        IF OLD.status <> 'REQUESTED' THEN
            RAISE EXCEPTION 'SHARE_IMMUTABLE: transfer is already %', OLD.status USING ERRCODE = 'P0001';
        END IF;
        IF (NEW.transfer_id, NEW.idempotency_key, NEW.from_customer_id, NEW.to_customer_id, NEW.amount, NEW.reason,
            NEW.requested_by, NEW.requested_at)
           IS DISTINCT FROM (OLD.transfer_id, OLD.idempotency_key, OLD.from_customer_id, OLD.to_customer_id, OLD.amount,
                             OLD.reason, OLD.requested_by, OLD.requested_at) THEN
            RAISE EXCEPTION 'SHARE_IMMUTABLE: what was asked never changes' USING ERRCODE = 'P0001';
        END IF;
    END IF;
    IF NEW.status IN ('REQUESTED', 'APPROVED')
       AND (SELECT status FROM customer.customer WHERE tenant_id = NEW.tenant_id AND customer_id = NEW.to_customer_id) <> 'ACTIVE' THEN
        RAISE EXCEPTION 'SHARE_MEMBER_INACTIVE: shares go only to an active member' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status = 'APPROVED' AND NEW.business_date <> (SELECT current_date_ FROM tenancy.business_date WHERE tenant_id = NEW.tenant_id) THEN
        RAISE EXCEPTION 'SHARE_DATE: transfers are approved on the business date' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER share_transfer_guard BEFORE INSERT OR UPDATE OR DELETE ON shares.share_transfer
    FOR EACH ROW EXECUTE FUNCTION shares.guard_share_transfer();

CREATE FUNCTION shares.check_share_transfer_pair() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'APPROVED'
       AND (SELECT count(*) FROM shares.share_transaction WHERE tenant_id = NEW.tenant_id AND transfer_id = NEW.transfer_id) <> 2 THEN
        RAISE EXCEPTION 'SHARE_TRANSFER_MISMATCH: an approved transfer writes one TRANSFER_OUT and one TRANSFER_IN'
            USING ERRCODE = 'P0001';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER share_transfer_pair AFTER UPDATE ON shares.share_transfer
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION shares.check_share_transfer_pair();

-- SH4: only share purchases post to the share capital account.
CREATE FUNCTION shares.check_share_capital_line() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM shares.share_settings WHERE tenant_id = NEW.tenant_id AND gl_share_capital = NEW.account_code)
       AND NOT EXISTS (SELECT 1 FROM shares.share_transaction WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id
                                                               AND kind = 'PURCHASE') THEN
        RAISE EXCEPTION 'SHARE_DIRECT_POSTING: % is share capital; only share purchases post to it', NEW.account_code
            USING ERRCODE = 'P0001';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER journal_line_share_capital AFTER INSERT ON ledger.journal_line
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION shares.check_share_capital_line();

CREATE OR REPLACE FUNCTION ledger.is_control_account(p_tenant uuid, p_code varchar) RETURNS boolean
    LANGUAGE sql STABLE
AS $$
    SELECT EXISTS (SELECT 1 FROM ledger.gl_account WHERE tenant_id = p_tenant AND account_code = p_code AND requires_subledger)
        OR EXISTS (SELECT 1 FROM lending.loan_product_version
                    WHERE tenant_id = p_tenant
                      AND p_code IN (gl_principal, gl_interest_receivable, gl_penalty_receivable, gl_overpayment))
        OR EXISTS (SELECT 1 FROM savings.deposit_product WHERE tenant_id = p_tenant AND gl_interest_payable = p_code)
        OR EXISTS (SELECT 1 FROM savings.till WHERE tenant_id = p_tenant AND gl_cash = p_code)
        OR EXISTS (SELECT 1 FROM ledger.prudential_settings WHERE tenant_id = p_tenant AND interest_suspense_account = p_code)
        OR EXISTS (SELECT 1 FROM lending.interest_suspense WHERE tenant_id = p_tenant AND account_code = p_code)
        OR EXISTS (SELECT 1 FROM ledger.ecl_settings WHERE tenant_id = p_tenant AND allowance_account = p_code)
        OR EXISTS (SELECT 1 FROM shares.share_settings WHERE tenant_id = p_tenant AND gl_share_capital = p_code)
$$;

ALTER TABLE ledger.reconciliation_result DROP CONSTRAINT reconciliation_result_check_code_check;
ALTER TABLE ledger.reconciliation_result ADD CONSTRAINT reconciliation_result_check_code_check
    CHECK (check_code IN ('SAVINGS_CONTROL', 'SAVINGS_INTEREST_PAYABLE', 'LOAN_PRINCIPAL', 'LOAN_INTEREST_RECEIVABLE',
                          'LOAN_PENALTY_RECEIVABLE', 'LOAN_OVERPAYMENT', 'LOAN_INTEREST_SUSPENSE', 'LOAN_LOSS_ALLOWANCE',
                          'SHARE_CAPITAL'));

-- SH6 dividends
CREATE FUNCTION shares.guard_dividend_run() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    s shares.share_settings%ROWTYPE;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'DIVIDEND_IMMUTABLE: dividend runs are never deleted' USING ERRCODE = 'P0001';
    END IF;
    IF TG_OP = 'INSERT' THEN
        SELECT * INTO s FROM shares.share_settings WHERE tenant_id = NEW.tenant_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'SHARE_SETTINGS_MISSING: share capital is not set up' USING ERRCODE = 'P0001';
        END IF;
        IF NEW.status <> 'REQUESTED' OR NEW.tax_rate <> s.dividend_tax_rate
           OR (NEW.gl_dividend_source, NEW.gl_dividends_payable, NEW.gl_dividend_tax)
              IS DISTINCT FROM (s.gl_dividend_source, s.gl_dividends_payable, s.gl_dividend_tax) THEN
            RAISE EXCEPTION 'DIVIDEND_INVALID: a run starts REQUESTED with the current settings' USING ERRCODE = 'P0001';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM ledger.eod_run WHERE tenant_id = NEW.tenant_id AND business_date = NEW.record_date
                                                     AND status = 'COMPLETED') THEN
            RAISE EXCEPTION 'DIVIDEND_DAY_NOT_CLOSED: the record date % has not been closed by end of day', NEW.record_date
                USING ERRCODE = 'P0001';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status <> 'REQUESTED' THEN
        RAISE EXCEPTION 'DIVIDEND_IMMUTABLE: run is already %', OLD.status USING ERRCODE = 'P0001';
    END IF;
    IF (to_jsonb(NEW) - 'status' - 'decided_by' - 'decided_at' - 'decision_reason' - 'business_date' - 'entry_id')
       IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'decided_by' - 'decided_at' - 'decision_reason' - 'business_date' - 'entry_id') THEN
        RAISE EXCEPTION 'DIVIDEND_IMMUTABLE: what was calculated never changes' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status = 'POSTED' AND NEW.business_date <> (SELECT current_date_ FROM tenancy.business_date WHERE tenant_id = NEW.tenant_id) THEN
        RAISE EXCEPTION 'DIVIDEND_INVALID: dividends are posted on the business date' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER dividend_run_guard BEFORE INSERT OR UPDATE OR DELETE ON shares.dividend_run
    FOR EACH ROW EXECUTE FUNCTION shares.guard_dividend_run();

CREATE FUNCTION shares.guard_dividend_line() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    r shares.dividend_run%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'DIVIDEND_IMMUTABLE: dividend lines never change' USING ERRCODE = 'P0001';
    END IF;
    SELECT * INTO r FROM shares.dividend_run WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id;
    IF r.status <> 'REQUESTED' THEN
        RAISE EXCEPTION 'DIVIDEND_IMMUTABLE: lines are added only to an open run' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.shares_amount <> shares.holding(NEW.tenant_id, NEW.customer_id, r.record_date)
       OR NEW.gross <> round(NEW.shares_amount * r.rate_pct / 100, 2)
       OR NEW.tax <> round(NEW.gross * r.tax_rate / 100, 2) THEN
        RAISE EXCEPTION 'DIVIDEND_LINE_INVALID: line for % does not follow the holding at % and the rates', NEW.customer_id,
            r.record_date USING ERRCODE = 'P0001';
    END IF;
    IF NEW.account_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM savings.deposit_account WHERE tenant_id = NEW.tenant_id AND account_id = NEW.account_id
                                                AND customer_id = NEW.customer_id AND status = 'ACTIVE') THEN
        RAISE EXCEPTION 'DIVIDEND_LINE_INVALID: dividends are paid to the member''s own active savings account' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER dividend_line_guard BEFORE INSERT OR UPDATE OR DELETE ON shares.dividend_line
    FOR EACH ROW EXECUTE FUNCTION shares.guard_dividend_line();

CREATE FUNCTION shares.check_dividend_run() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_members int;
    v_holders int;
    v_shares numeric;
    v_gross numeric;
    v_tax numeric;
    v_savings numeric;
    v_payable numeric;
    v_e_source numeric;
    v_e_tax numeric;
    v_e_payable numeric;
    v_missing int;
BEGIN
    SELECT count(*), COALESCE(sum(shares_amount), 0), COALESCE(sum(gross), 0), COALESCE(sum(tax), 0),
           COALESCE(sum(net) FILTER (WHERE account_id IS NOT NULL), 0), COALESCE(sum(net) FILTER (WHERE account_id IS NULL), 0)
      INTO v_members, v_shares, v_gross, v_tax, v_savings, v_payable
      FROM shares.dividend_line WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id;
    SELECT count(*) INTO v_holders FROM (
        SELECT customer_id FROM shares.share_transaction
         WHERE tenant_id = NEW.tenant_id AND business_date <= NEW.record_date
         GROUP BY customer_id HAVING sum(CASE kind WHEN 'TRANSFER_OUT' THEN -amount ELSE amount END) > 0) h;
    IF v_members <> NEW.members OR v_members <> v_holders OR v_shares <> NEW.shares_total OR v_gross <> NEW.gross_total
       OR v_tax <> NEW.tax_total OR v_savings <> NEW.to_savings_total OR v_payable <> NEW.to_payable_total THEN
        RAISE EXCEPTION 'DIVIDEND_TOTAL_MISMATCH: the run''s totals are not its lines'' (or a member holding shares has no line)'
            USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status = 'POSTED' THEN
        SELECT COALESCE(sum(CASE WHEN account_code = NEW.gl_dividend_source THEN CASE side WHEN 'D' THEN amount ELSE -amount END END), 0),
               COALESCE(sum(CASE WHEN account_code = NEW.gl_dividend_tax THEN CASE side WHEN 'C' THEN amount ELSE -amount END END), 0),
               COALESCE(sum(CASE WHEN account_code = NEW.gl_dividends_payable THEN CASE side WHEN 'C' THEN amount ELSE -amount END END), 0)
          INTO v_e_source, v_e_tax, v_e_payable
          FROM ledger.journal_line WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id;
        SELECT count(*) INTO v_missing FROM shares.dividend_line d
         WHERE d.tenant_id = NEW.tenant_id AND d.run_id = NEW.run_id AND d.account_id IS NOT NULL AND d.net > 0
           AND NOT EXISTS (SELECT 1 FROM ledger.journal_line l
                            WHERE l.tenant_id = d.tenant_id AND l.entry_id = NEW.entry_id AND l.side = 'C'
                              AND l.subledger_account_id = d.account_id AND l.amount = d.net);
        IF v_e_source <> NEW.gross_total OR v_e_tax <> NEW.tax_total OR v_e_payable <> NEW.to_payable_total OR v_missing > 0 THEN
            RAISE EXCEPTION 'DIVIDEND_POSTING_MISMATCH: the entry does not pay the run''s lines exactly' USING ERRCODE = 'P0001';
        END IF;
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER dividend_run_totals AFTER INSERT OR UPDATE ON shares.dividend_run
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION shares.check_dividend_run();

ALTER TABLE shares.share_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE shares.share_settings FORCE ROW LEVEL SECURITY;
ALTER TABLE shares.share_transfer ENABLE ROW LEVEL SECURITY;
ALTER TABLE shares.share_transfer FORCE ROW LEVEL SECURITY;
ALTER TABLE shares.share_transaction ENABLE ROW LEVEL SECURITY;
ALTER TABLE shares.share_transaction FORCE ROW LEVEL SECURITY;
ALTER TABLE shares.dividend_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE shares.dividend_run FORCE ROW LEVEL SECURITY;
ALTER TABLE shares.dividend_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE shares.dividend_line FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON shares.share_settings
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
CREATE POLICY tenant_isolation ON shares.share_transfer
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
CREATE POLICY tenant_isolation ON shares.share_transaction
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
CREATE POLICY tenant_isolation ON shares.dividend_run
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
CREATE POLICY tenant_isolation ON shares.dividend_line
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());

GRANT USAGE ON SCHEMA shares TO mfi_app;
GRANT SELECT, INSERT, UPDATE ON shares.share_settings TO mfi_app;
GRANT SELECT, INSERT, UPDATE ON shares.share_transfer TO mfi_app;
GRANT SELECT, INSERT ON shares.share_transaction TO mfi_app;
GRANT SELECT, INSERT, UPDATE ON shares.dividend_run TO mfi_app;
GRANT SELECT, INSERT ON shares.dividend_line TO mfi_app;
GRANT EXECUTE ON FUNCTION shares.holding(uuid, uuid, date) TO mfi_app;
