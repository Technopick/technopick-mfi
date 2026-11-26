-- =============================================================================
-- V38: Fixed (term) deposits (ADR-0041).
--   FD1 products: term in months, annual rate, rate paid on early withdrawal (not above the rate),
--       whether early withdrawal is allowed, minimum and maximum amount, withholding tax rate;
--       principal and interest payable are LIABILITY control accounts, interest expense is EXPENSE,
--       tax is LIABILITY, all in the product's currency; accounts and currency never change; rates
--       and limits may change and apply to new deposits and rollovers only
--   FD2 a deposit copies the product's terms when placed; it belongs to an active member and is
--       paid from, and later paid to, one of that member's own active savings accounts; it is
--       placed on the business date and matures term months later; its placement entry debits the
--       savings account and credits principal by exactly the amount, in the account's branch
--   FD3 interest accrues at each close for each night held: cumulative interest for close d is
--       round(principal x rate x nights / 36500, 2), nights = min(d, maturity - 1) - start + 1;
--       each close posts the difference, grouped per product and branch into one entry
--       (Dr interest expense / Cr interest payable) whose totals equal its rows
--   FD4 at the close of the day before maturity the deposit matures: interest earned is the full
--       term's interest; tax = round(earned x tax rate / 100, 2); the net and, unless rolled over,
--       the principal go to the member's savings account; a rollover opens a new deposit from the
--       maturity date at the product's current terms with the principal (or principal and net)
--   FD5 early withdrawal is asked for by one person and approved by another; it earns the early
--       rate for the nights held; interest accrued above that is reversed to expense; principal
--       and net interest go to the savings account
--   FD6 the closure entry moves exactly: principal by the change in principal, interest payable
--       by all interest accrued, expense by the difference between earned and accrued, tax, and
--       the savings account; nothing else posts to a deposit principal or interest payable account
--       (control accounts; reconciliation checks TERM_DEPOSIT_PRINCIPAL, TERM_DEPOSIT_INTEREST)
--   FD7 a savings account cannot be closed while a deposit pays to it; deposits never change
--       except their maturity instruction while active and their status when closed
-- =============================================================================
CREATE SCHEMA termdep;

CREATE TABLE termdep.product (
    tenant_id                uuid          NOT NULL REFERENCES tenancy.tenant (tenant_id),
    product_code             varchar(32)   NOT NULL CHECK (product_code ~ '^[A-Z0-9][A-Z0-9-]{1,31}$'),
    name                     varchar(120)  NOT NULL CHECK (length(trim(name)) >= 3),
    currency                 char(3)       NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    term_months              integer       NOT NULL CHECK (term_months BETWEEN 1 AND 120),
    rate_pct                 numeric(7,4)  NOT NULL CHECK (rate_pct > 0 AND rate_pct <= 50),
    early_rate_pct           numeric(7,4)  NOT NULL DEFAULT 0 CHECK (early_rate_pct >= 0),
    early_withdrawal_allowed boolean       NOT NULL DEFAULT true,
    min_amount               numeric(19,4) NOT NULL CHECK (min_amount > 0 AND min_amount = round(min_amount, 2)),
    max_amount               numeric(19,4) CHECK (max_amount = round(max_amount, 2)),
    tax_rate_pct             numeric(5,2)  NOT NULL CHECK (tax_rate_pct >= 0 AND tax_rate_pct <= 100),
    gl_principal             varchar(40)   NOT NULL,
    gl_interest_payable      varchar(40)   NOT NULL,
    gl_interest_expense      varchar(40)   NOT NULL,
    gl_tax                   varchar(40)   NOT NULL,
    status                   varchar(16)   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'RETIRED')),
    updated_by               varchar(100)  NOT NULL,
    updated_at               timestamptz   NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, product_code),
    CHECK (early_rate_pct <= rate_pct),
    CHECK (max_amount IS NULL OR max_amount >= min_amount),
    CHECK (gl_principal NOT IN (gl_interest_payable, gl_interest_expense, gl_tax)
           AND gl_interest_payable NOT IN (gl_interest_expense, gl_tax) AND gl_interest_expense <> gl_tax),
    FOREIGN KEY (tenant_id, gl_principal) REFERENCES ledger.gl_account (tenant_id, account_code),
    FOREIGN KEY (tenant_id, gl_interest_payable) REFERENCES ledger.gl_account (tenant_id, account_code),
    FOREIGN KEY (tenant_id, gl_interest_expense) REFERENCES ledger.gl_account (tenant_id, account_code),
    FOREIGN KEY (tenant_id, gl_tax) REFERENCES ledger.gl_account (tenant_id, account_code)
);

-- FD1
CREATE FUNCTION termdep.guard_product() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_bad int;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'TERMDEP_PRODUCT_FIXED: products are retired, not deleted' USING ERRCODE = 'P0001';
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.currency <> OLD.currency OR NEW.gl_principal <> OLD.gl_principal
            OR NEW.gl_interest_payable <> OLD.gl_interest_payable OR NEW.gl_interest_expense <> OLD.gl_interest_expense
            OR NEW.gl_tax <> OLD.gl_tax) THEN
        RAISE EXCEPTION 'TERMDEP_PRODUCT_FIXED: a product''s currency and accounts never change; retire it and create another'
            USING ERRCODE = 'P0001';
    END IF;
    SELECT count(*) INTO v_bad FROM (VALUES (NEW.gl_principal, 'LIABILITY'), (NEW.gl_interest_payable, 'LIABILITY'),
                                            (NEW.gl_interest_expense, 'EXPENSE'), (NEW.gl_tax, 'LIABILITY')) AS x(code, cls)
      LEFT JOIN ledger.gl_account g ON g.tenant_id = NEW.tenant_id AND g.account_code = x.code
     WHERE g.account_class IS DISTINCT FROM x.cls OR g.currency IS DISTINCT FROM NEW.currency;
    IF v_bad > 0 THEN
        RAISE EXCEPTION 'TERMDEP_PRODUCT_INVALID: principal, interest payable and tax must be LIABILITY accounts and interest expense an EXPENSE account, all in %',
            NEW.currency USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER product_guard BEFORE INSERT OR UPDATE OR DELETE ON termdep.product
    FOR EACH ROW EXECUTE FUNCTION termdep.guard_product();

CREATE TABLE termdep.number_counter (
    tenant_id   uuid   NOT NULL PRIMARY KEY REFERENCES tenancy.tenant (tenant_id),
    last_number bigint NOT NULL CHECK (last_number > 0)
);

CREATE FUNCTION termdep.next_number(p_tenant uuid) RETURNS varchar
    LANGUAGE sql
AS $$
    INSERT INTO termdep.number_counter (tenant_id, last_number) VALUES (p_tenant, 1)
    ON CONFLICT (tenant_id) DO UPDATE SET last_number = termdep.number_counter.last_number + 1
    RETURNING 'FD' || lpad(last_number::text, 8, '0');
$$;

CREATE TABLE termdep.deposit (
    tenant_id                uuid          NOT NULL REFERENCES tenancy.tenant (tenant_id),
    deposit_id               uuid          NOT NULL,
    deposit_number           varchar(12)   NOT NULL CHECK (deposit_number ~ '^FD[0-9]{8}$'),
    idempotency_key          varchar(120)  NOT NULL,
    customer_id              uuid          NOT NULL,
    product_code             varchar(32)   NOT NULL,
    savings_account_id       uuid          NOT NULL,
    branch_code              varchar(16)   NOT NULL,
    currency                 char(3)       NOT NULL,
    principal                numeric(19,4) NOT NULL CHECK (principal > 0 AND principal = round(principal, 2)),
    rate_pct                 numeric(7,4)  NOT NULL,
    early_rate_pct           numeric(7,4)  NOT NULL,
    early_withdrawal_allowed boolean       NOT NULL,
    tax_rate_pct             numeric(5,2)  NOT NULL,
    term_months              integer       NOT NULL,
    start_date               date          NOT NULL,
    maturity_date            date          NOT NULL,
    maturity_instruction     varchar(20)   NOT NULL CHECK (maturity_instruction IN ('PAYOUT', 'ROLLOVER_PRINCIPAL', 'ROLLOVER_ALL')),
    instruction_changed_by   varchar(100),
    instruction_changed_at   timestamptz,
    status                   varchar(20)   NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'MATURED', 'WITHDRAWN_EARLY', 'ROLLED_OVER')),
    placement_entry_id       uuid,
    rolled_from              uuid,
    created_by               varchar(100)  NOT NULL,
    created_at               timestamptz   NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, deposit_id),
    CONSTRAINT deposit_number_unique UNIQUE (tenant_id, deposit_number),
    CONSTRAINT deposit_idempotency UNIQUE (tenant_id, idempotency_key),
    CHECK (maturity_date > start_date),
    CHECK (placement_entry_id IS NOT NULL OR rolled_from IS NOT NULL),
    FOREIGN KEY (tenant_id, customer_id) REFERENCES customer.customer (tenant_id, customer_id),
    FOREIGN KEY (tenant_id, product_code) REFERENCES termdep.product (tenant_id, product_code),
    FOREIGN KEY (tenant_id, savings_account_id) REFERENCES savings.deposit_account (tenant_id, account_id),
    FOREIGN KEY (tenant_id, branch_code) REFERENCES tenancy.branch (tenant_id, branch_code),
    FOREIGN KEY (tenant_id, placement_entry_id) REFERENCES ledger.journal_entry (tenant_id, entry_id),
    FOREIGN KEY (tenant_id, rolled_from) REFERENCES termdep.deposit (tenant_id, deposit_id)
);
CREATE INDEX deposit_customer_idx ON termdep.deposit (tenant_id, customer_id);
CREATE INDEX deposit_active_idx ON termdep.deposit (tenant_id, maturity_date) WHERE status = 'ACTIVE';
CREATE INDEX deposit_account_idx ON termdep.deposit (tenant_id, savings_account_id) WHERE status = 'ACTIVE';
CREATE INDEX deposit_entry_idx ON termdep.deposit (tenant_id, placement_entry_id) WHERE placement_entry_id IS NOT NULL;
CREATE UNIQUE INDEX deposit_rolled_once ON termdep.deposit (tenant_id, rolled_from) WHERE rolled_from IS NOT NULL;

-- FD2, FD7
CREATE FUNCTION termdep.guard_deposit() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    p termdep.product%ROWTYPE;
    a savings.deposit_account%ROWTYPE;
    o termdep.deposit%ROWTYPE;
    v_today date;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'TERMDEP_IMMUTABLE: deposits are never deleted' USING ERRCODE = 'P0001';
    END IF;
    SELECT current_date_ INTO v_today FROM tenancy.business_date WHERE tenant_id = NEW.tenant_id;
    IF TG_OP = 'UPDATE' THEN
        IF (NEW.deposit_id, NEW.deposit_number, NEW.idempotency_key, NEW.customer_id, NEW.product_code, NEW.savings_account_id,
            NEW.branch_code, NEW.currency, NEW.principal, NEW.rate_pct, NEW.early_rate_pct, NEW.early_withdrawal_allowed,
            NEW.tax_rate_pct, NEW.term_months, NEW.start_date, NEW.maturity_date, NEW.placement_entry_id, NEW.rolled_from,
            NEW.created_by, NEW.created_at)
           IS DISTINCT FROM
           (OLD.deposit_id, OLD.deposit_number, OLD.idempotency_key, OLD.customer_id, OLD.product_code, OLD.savings_account_id,
            OLD.branch_code, OLD.currency, OLD.principal, OLD.rate_pct, OLD.early_rate_pct, OLD.early_withdrawal_allowed,
            OLD.tax_rate_pct, OLD.term_months, OLD.start_date, OLD.maturity_date, OLD.placement_entry_id, OLD.rolled_from,
            OLD.created_by, OLD.created_at) THEN
            RAISE EXCEPTION 'TERMDEP_IMMUTABLE: a deposit''s terms never change' USING ERRCODE = 'P0001';
        END IF;
        IF OLD.status <> 'ACTIVE' THEN
            RAISE EXCEPTION 'TERMDEP_IMMUTABLE: deposit % is already %', OLD.deposit_number, OLD.status USING ERRCODE = 'P0001';
        END IF;
        IF NEW.maturity_instruction <> OLD.maturity_instruction
           AND (NEW.status <> 'ACTIVE' OR NEW.instruction_changed_by IS NULL) THEN
            RAISE EXCEPTION 'TERMDEP_IMMUTABLE: the maturity instruction changes only on an active deposit, with who changed it'
                USING ERRCODE = 'P0001';
        END IF;
        RETURN NEW;
    END IF;
    -- insert
    IF NEW.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'TERMDEP_DEPOSIT_INVALID: a deposit starts ACTIVE' USING ERRCODE = 'P0001';
    END IF;
    SELECT * INTO p FROM termdep.product WHERE tenant_id = NEW.tenant_id AND product_code = NEW.product_code;
    IF p.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'TERMDEP_PRODUCT_RETIRED: product % takes no new deposits', NEW.product_code USING ERRCODE = 'P0001';
    END IF;
    IF (SELECT status FROM customer.customer WHERE tenant_id = NEW.tenant_id AND customer_id = NEW.customer_id) <> 'ACTIVE' THEN
        RAISE EXCEPTION 'TERMDEP_MEMBER_INACTIVE: only active members place deposits' USING ERRCODE = 'P0001';
    END IF;
    SELECT * INTO a FROM savings.deposit_account WHERE tenant_id = NEW.tenant_id AND account_id = NEW.savings_account_id;
    IF a.customer_id IS DISTINCT FROM NEW.customer_id OR a.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'TERMDEP_ACCOUNT_NOT_OWN: a deposit uses one of the member''s own active savings accounts' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.currency <> p.currency OR a.currency <> p.currency OR NEW.branch_code <> a.branch_code
       OR NEW.rate_pct <> p.rate_pct OR NEW.early_rate_pct <> p.early_rate_pct
       OR NEW.early_withdrawal_allowed <> p.early_withdrawal_allowed OR NEW.tax_rate_pct <> p.tax_rate_pct
       OR NEW.term_months <> p.term_months
       OR NEW.maturity_date <> (NEW.start_date + make_interval(months => NEW.term_months))::date THEN
        RAISE EXCEPTION 'TERMDEP_DEPOSIT_INVALID: a deposit copies its product''s current terms and matures % months after it starts',
            p.term_months USING ERRCODE = 'P0001';
    END IF;
    IF NEW.rolled_from IS NULL THEN
        IF NEW.start_date <> v_today THEN
            RAISE EXCEPTION 'TERMDEP_DATE: deposits are placed on the business date %', v_today USING ERRCODE = 'P0001';
        END IF;
        IF NEW.principal < p.min_amount OR NEW.principal > COALESCE(p.max_amount, NEW.principal) THEN
            RAISE EXCEPTION 'TERMDEP_AMOUNT: product % takes % to %', p.product_code, p.min_amount, COALESCE(p.max_amount::text, 'any amount')
                USING ERRCODE = 'P0001';
        END IF;
    ELSE
        SELECT * INTO o FROM termdep.deposit WHERE tenant_id = NEW.tenant_id AND deposit_id = NEW.rolled_from;
        IF o.status <> 'ACTIVE' OR o.customer_id <> NEW.customer_id OR o.savings_account_id <> NEW.savings_account_id
           OR o.product_code <> NEW.product_code OR NEW.start_date <> o.maturity_date OR o.maturity_date > v_today + 1 THEN
            RAISE EXCEPTION 'TERMDEP_DEPOSIT_INVALID: a rollover continues a maturing deposit from its maturity date' USING ERRCODE = 'P0001';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER deposit_guard BEFORE INSERT OR UPDATE OR DELETE ON termdep.deposit
    FOR EACH ROW EXECUTE FUNCTION termdep.guard_deposit();

-- FD2 (checked at commit): the placement entry, or the closure a rollover came from; FD4-FD6: a closed deposit has its closure
CREATE FUNCTION termdep.check_deposit() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    p termdep.product%ROWTYPE;
    v_control varchar;
    v_principal numeric;
    v_debit numeric;
    v_other int;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.status <> OLD.status AND NOT EXISTS (
                SELECT 1 FROM termdep.closure c WHERE c.tenant_id = NEW.tenant_id AND c.deposit_id = NEW.deposit_id) THEN
            RAISE EXCEPTION 'TERMDEP_CLOSURE_MISSING: deposit % closed without a closure record', NEW.deposit_number USING ERRCODE = 'P0001';
        END IF;
        RETURN NULL;
    END IF;
    IF NEW.rolled_from IS NOT NULL THEN
        IF NOT EXISTS (SELECT 1 FROM termdep.closure c WHERE c.tenant_id = NEW.tenant_id AND c.deposit_id = NEW.rolled_from
                          AND c.new_deposit_id = NEW.deposit_id AND c.new_principal = NEW.principal
                          AND c.entry_id IS NOT DISTINCT FROM NEW.placement_entry_id) THEN
            RAISE EXCEPTION 'TERMDEP_ROLLOVER_MISMATCH: a rollover''s principal must be what the maturity carried over' USING ERRCODE = 'P0001';
        END IF;
        RETURN NULL;
    END IF;
    SELECT * INTO p FROM termdep.product WHERE tenant_id = NEW.tenant_id AND product_code = NEW.product_code;
    SELECT gl_control_account INTO v_control FROM savings.deposit_account
     WHERE tenant_id = NEW.tenant_id AND account_id = NEW.savings_account_id;
    SELECT COALESCE(sum(CASE WHEN account_code = p.gl_principal AND branch_code = NEW.branch_code
                             THEN CASE side WHEN 'C' THEN amount ELSE -amount END END), 0),
           COALESCE(sum(CASE WHEN account_code = v_control AND subledger_account_id = NEW.savings_account_id
                             THEN CASE side WHEN 'D' THEN amount ELSE -amount END END), 0),
           count(*) FILTER (WHERE account_code NOT IN (p.gl_principal, v_control))
      INTO v_principal, v_debit, v_other
      FROM ledger.journal_line WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.placement_entry_id;
    IF v_principal <> NEW.principal OR v_debit <> NEW.principal OR v_other > 0 THEN
        RAISE EXCEPTION 'TERMDEP_POSTING_MISMATCH: deposit of % but the entry moves principal % and the account %',
            NEW.principal, v_principal, v_debit USING ERRCODE = 'P0001';
    END IF;
    IF EXISTS (SELECT 1 FROM termdep.deposit d WHERE d.tenant_id = NEW.tenant_id AND d.placement_entry_id = NEW.placement_entry_id
                  AND d.deposit_id <> NEW.deposit_id) THEN
        RAISE EXCEPTION 'TERMDEP_POSTING_MISMATCH: one placement entry per deposit' USING ERRCODE = 'P0001';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER deposit_posting AFTER INSERT OR UPDATE ON termdep.deposit
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION termdep.check_deposit();

-- FD3, FD4: accruals and maturities happen inside the close of the business date
CREATE FUNCTION termdep.in_end_of_day(p_tenant uuid, p_date date) RETURNS boolean
    LANGUAGE sql STABLE
AS $$
    SELECT EXISTS (SELECT 1 FROM tenancy.business_date b
                     JOIN ledger.eod_run r ON r.tenant_id = b.tenant_id AND r.business_date = b.current_date_
                    WHERE b.tenant_id = p_tenant AND b.current_date_ = p_date AND r.status = 'RUNNING');
$$;

CREATE TABLE termdep.accrual_batch (
    tenant_id     uuid          NOT NULL REFERENCES tenancy.tenant (tenant_id),
    entry_id      uuid          NOT NULL,
    business_date date          NOT NULL,
    product_code  varchar(32)   NOT NULL,
    branch_code   varchar(16)   NOT NULL,
    deposits      integer       NOT NULL CHECK (deposits > 0),
    total         numeric(19,4) NOT NULL CHECK (total > 0),
    created_by    varchar(100)  NOT NULL,
    PRIMARY KEY (tenant_id, entry_id),
    CONSTRAINT accrual_batch_once UNIQUE (tenant_id, business_date, product_code, branch_code),
    FOREIGN KEY (tenant_id, entry_id) REFERENCES ledger.journal_entry (tenant_id, entry_id),
    FOREIGN KEY (tenant_id, product_code) REFERENCES termdep.product (tenant_id, product_code)
);

CREATE TABLE termdep.accrual (
    tenant_id     uuid          NOT NULL REFERENCES tenancy.tenant (tenant_id),
    deposit_id    uuid          NOT NULL,
    business_date date          NOT NULL,
    nights        integer       NOT NULL CHECK (nights > 0),
    cumulative    numeric(19,4) NOT NULL CHECK (cumulative > 0),
    posted        numeric(19,4) NOT NULL CHECK (posted > 0),
    entry_id      uuid          NOT NULL,
    PRIMARY KEY (tenant_id, deposit_id, business_date),
    FOREIGN KEY (tenant_id, deposit_id) REFERENCES termdep.deposit (tenant_id, deposit_id),
    FOREIGN KEY (tenant_id, entry_id) REFERENCES termdep.accrual_batch (tenant_id, entry_id)
);
CREATE INDEX accrual_entry_idx ON termdep.accrual (tenant_id, entry_id);

CREATE FUNCTION termdep.guard_accrual() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    d termdep.deposit%ROWTYPE;
    b termdep.accrual_batch%ROWTYPE;
    v_today date;
    v_nights int;
    v_cumulative numeric;
    v_previous numeric;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'TERMDEP_IMMUTABLE: accruals never change' USING ERRCODE = 'P0001';
    END IF;
    SELECT current_date_ INTO v_today FROM tenancy.business_date WHERE tenant_id = NEW.tenant_id;
    SELECT * INTO d FROM termdep.deposit WHERE tenant_id = NEW.tenant_id AND deposit_id = NEW.deposit_id;
    SELECT * INTO b FROM termdep.accrual_batch WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id;
    v_nights := LEAST(NEW.business_date, d.maturity_date - 1) - d.start_date + 1;
    v_cumulative := round(d.principal * d.rate_pct * v_nights / 36500, 2);
    SELECT COALESCE(max(cumulative), 0) INTO v_previous FROM termdep.accrual
     WHERE tenant_id = NEW.tenant_id AND deposit_id = NEW.deposit_id AND business_date < NEW.business_date;
    IF d.status <> 'ACTIVE' OR NEW.business_date <> v_today OR NEW.business_date < d.start_date
       OR b.business_date <> NEW.business_date OR b.product_code <> d.product_code OR b.branch_code <> d.branch_code
       OR NEW.nights <> v_nights OR NEW.cumulative <> v_cumulative OR NEW.posted <> v_cumulative - v_previous THEN
        RAISE EXCEPTION 'TERMDEP_ACCRUAL_INVALID: deposit % on % accrues % for % nights (% already)',
            d.deposit_number, NEW.business_date, v_cumulative - v_previous, v_nights, v_previous USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER accrual_guard BEFORE INSERT OR UPDATE OR DELETE ON termdep.accrual
    FOR EACH ROW EXECUTE FUNCTION termdep.guard_accrual();

CREATE FUNCTION termdep.guard_accrual_batch() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'TERMDEP_IMMUTABLE: accruals never change' USING ERRCODE = 'P0001';
    END IF;
    IF NOT termdep.in_end_of_day(NEW.tenant_id, NEW.business_date) THEN
        RAISE EXCEPTION 'TERMDEP_NOT_IN_EOD: interest accrues only in a running end of day for the business date' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER accrual_batch_guard BEFORE INSERT OR UPDATE OR DELETE ON termdep.accrual_batch
    FOR EACH ROW EXECUTE FUNCTION termdep.guard_accrual_batch();

CREATE FUNCTION termdep.check_accrual_batch() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    p termdep.product%ROWTYPE;
    v_count int;
    v_sum numeric;
    v_expense numeric;
    v_payable numeric;
    v_other int;
BEGIN
    SELECT * INTO p FROM termdep.product WHERE tenant_id = NEW.tenant_id AND product_code = NEW.product_code;
    SELECT count(*), COALESCE(sum(posted), 0) INTO v_count, v_sum FROM termdep.accrual
     WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id;
    SELECT COALESCE(sum(amount) FILTER (WHERE account_code = p.gl_interest_expense AND side = 'D' AND branch_code = NEW.branch_code), 0),
           COALESCE(sum(amount) FILTER (WHERE account_code = p.gl_interest_payable AND side = 'C' AND branch_code = NEW.branch_code), 0),
           count(*) FILTER (WHERE NOT ((account_code = p.gl_interest_expense AND side = 'D')
                                       OR (account_code = p.gl_interest_payable AND side = 'C'))
                                  OR branch_code <> NEW.branch_code)
      INTO v_expense, v_payable, v_other
      FROM ledger.journal_line WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id;
    IF v_count <> NEW.deposits OR v_sum <> NEW.total OR v_expense <> NEW.total OR v_payable <> NEW.total OR v_other > 0 THEN
        RAISE EXCEPTION 'TERMDEP_ACCRUAL_MISMATCH: batch of % deposits totalling % has % rows of % and posts % / %',
            NEW.deposits, NEW.total, v_count, v_sum, v_expense, v_payable USING ERRCODE = 'P0001';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER accrual_batch_totals AFTER INSERT ON termdep.accrual_batch
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION termdep.check_accrual_batch();

-- FD5
CREATE TABLE termdep.early_request (
    tenant_id       uuid          NOT NULL REFERENCES tenancy.tenant (tenant_id),
    request_id      uuid          NOT NULL,
    idempotency_key varchar(120)  NOT NULL,
    deposit_id      uuid          NOT NULL,
    reason          varchar(300)  NOT NULL CHECK (length(trim(reason)) >= 10),
    status          varchar(16)   NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED')),
    requested_by    varchar(100)  NOT NULL,
    requested_at    timestamptz   NOT NULL DEFAULT clock_timestamp(),
    decided_by      varchar(100),
    decided_at      timestamptz,
    decision_reason varchar(300),
    PRIMARY KEY (tenant_id, request_id),
    CONSTRAINT early_request_idempotency UNIQUE (tenant_id, idempotency_key),
    FOREIGN KEY (tenant_id, deposit_id) REFERENCES termdep.deposit (tenant_id, deposit_id),
    CHECK ((status = 'REQUESTED') = (decided_by IS NULL)),
    CHECK (status <> 'REJECTED' OR length(trim(decision_reason)) >= 3)
);
CREATE UNIQUE INDEX early_request_one_open ON termdep.early_request (tenant_id, deposit_id) WHERE status <> 'REJECTED';

CREATE FUNCTION termdep.guard_early_request() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    d termdep.deposit%ROWTYPE;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'TERMDEP_IMMUTABLE: requests are rejected, not deleted' USING ERRCODE = 'P0001';
    END IF;
    SELECT * INTO d FROM termdep.deposit WHERE tenant_id = NEW.tenant_id AND deposit_id = NEW.deposit_id;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'REQUESTED' OR d.status <> 'ACTIVE' THEN
            RAISE EXCEPTION 'TERMDEP_EARLY_INVALID: early withdrawal is asked for on an active deposit' USING ERRCODE = 'P0001';
        END IF;
        IF NOT d.early_withdrawal_allowed THEN
            RAISE EXCEPTION 'TERMDEP_NO_EARLY: deposit % cannot be withdrawn before maturity', d.deposit_number USING ERRCODE = 'P0001';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status <> 'REQUESTED' OR (NEW.request_id, NEW.idempotency_key, NEW.deposit_id, NEW.reason, NEW.requested_by, NEW.requested_at)
           IS DISTINCT FROM (OLD.request_id, OLD.idempotency_key, OLD.deposit_id, OLD.reason, OLD.requested_by, OLD.requested_at) THEN
        RAISE EXCEPTION 'TERMDEP_IMMUTABLE: a request is decided once' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.decided_by = NEW.requested_by THEN
        RAISE EXCEPTION 'TERMDEP_SAME_PERSON: the person who asked cannot decide' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status = 'APPROVED' AND d.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'TERMDEP_EARLY_INVALID: deposit % is already %', d.deposit_number, d.status USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER early_request_guard BEFORE INSERT OR UPDATE OR DELETE ON termdep.early_request
    FOR EACH ROW EXECUTE FUNCTION termdep.guard_early_request();

-- FD4-FD6
CREATE TABLE termdep.closure (
    tenant_id        uuid          NOT NULL REFERENCES tenancy.tenant (tenant_id),
    deposit_id       uuid          NOT NULL,
    kind             varchar(16)   NOT NULL CHECK (kind IN ('MATURITY', 'EARLY')),
    business_date    date          NOT NULL,
    nights           integer       NOT NULL CHECK (nights >= 0),
    rate_applied     numeric(7,4)  NOT NULL,
    interest_earned  numeric(19,4) NOT NULL CHECK (interest_earned >= 0),
    accrued_before   numeric(19,4) NOT NULL CHECK (accrued_before >= 0),
    tax              numeric(19,4) NOT NULL CHECK (tax >= 0),
    net_interest     numeric(19,4) NOT NULL,
    to_savings       numeric(19,4) NOT NULL CHECK (to_savings >= 0),
    new_principal    numeric(19,4) NOT NULL DEFAULT 0 CHECK (new_principal >= 0),
    new_deposit_id   uuid,
    early_request_id uuid,
    entry_id         uuid,
    created_by       varchar(100)  NOT NULL,
    created_at       timestamptz   NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, deposit_id),
    CHECK (net_interest = interest_earned - tax),
    CHECK ((new_principal > 0) = (new_deposit_id IS NOT NULL)),
    CHECK ((kind = 'EARLY') = (early_request_id IS NOT NULL)),
    CHECK (kind = 'MATURITY' OR new_principal = 0),
    FOREIGN KEY (tenant_id, deposit_id) REFERENCES termdep.deposit (tenant_id, deposit_id),
    FOREIGN KEY (tenant_id, new_deposit_id) REFERENCES termdep.deposit (tenant_id, deposit_id),
    FOREIGN KEY (tenant_id, early_request_id) REFERENCES termdep.early_request (tenant_id, request_id),
    FOREIGN KEY (tenant_id, entry_id) REFERENCES ledger.journal_entry (tenant_id, entry_id)
);
CREATE INDEX closure_entry_idx ON termdep.closure (tenant_id, entry_id) WHERE entry_id IS NOT NULL;

CREATE FUNCTION termdep.guard_closure() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    d termdep.deposit%ROWTYPE;
    r termdep.early_request%ROWTYPE;
    v_today date;
    v_nights int;
    v_rate numeric;
    v_earned numeric;
    v_tax numeric;
    v_accrued numeric;
    v_payout_ok boolean;
    v_instruction_ok boolean;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'TERMDEP_IMMUTABLE: closures never change' USING ERRCODE = 'P0001';
    END IF;
    SELECT current_date_ INTO v_today FROM tenancy.business_date WHERE tenant_id = NEW.tenant_id;
    SELECT * INTO d FROM termdep.deposit WHERE tenant_id = NEW.tenant_id AND deposit_id = NEW.deposit_id;
    IF d.status <> 'ACTIVE' OR NEW.business_date <> v_today THEN
        RAISE EXCEPTION 'TERMDEP_CLOSURE_INVALID: deposit % is % (closures are dated the business date)', d.deposit_number, d.status
            USING ERRCODE = 'P0001';
    END IF;
    IF NEW.kind = 'MATURITY' THEN
        IF NOT termdep.in_end_of_day(NEW.tenant_id, v_today) THEN
            RAISE EXCEPTION 'TERMDEP_NOT_IN_EOD: deposits mature only in a running end of day' USING ERRCODE = 'P0001';
        END IF;
        IF d.maturity_date > v_today + 1 THEN
            RAISE EXCEPTION 'TERMDEP_CLOSURE_INVALID: deposit % matures on %', d.deposit_number, d.maturity_date USING ERRCODE = 'P0001';
        END IF;
        v_nights := d.maturity_date - d.start_date;
        v_rate := d.rate_pct;
    ELSE
        SELECT * INTO r FROM termdep.early_request WHERE tenant_id = NEW.tenant_id AND request_id = NEW.early_request_id;
        IF r.status IS DISTINCT FROM 'APPROVED' OR r.deposit_id <> NEW.deposit_id OR NOT d.early_withdrawal_allowed
           OR v_today >= d.maturity_date THEN
            RAISE EXCEPTION 'TERMDEP_CLOSURE_INVALID: early withdrawal of % needs an approved request before maturity', d.deposit_number
                USING ERRCODE = 'P0001';
        END IF;
        v_nights := v_today - d.start_date;
        v_rate := d.early_rate_pct;
    END IF;
    v_earned := round(d.principal * v_rate * v_nights / 36500, 2);
    v_tax := round(v_earned * d.tax_rate_pct / 100, 2);
    SELECT COALESCE(sum(posted), 0) INTO v_accrued FROM termdep.accrual WHERE tenant_id = NEW.tenant_id AND deposit_id = NEW.deposit_id;
    v_payout_ok := NEW.to_savings = d.principal + v_earned - v_tax AND NEW.new_principal = 0;
    v_instruction_ok := CASE d.maturity_instruction
        WHEN 'ROLLOVER_PRINCIPAL' THEN NEW.to_savings = v_earned - v_tax AND NEW.new_principal = d.principal
        WHEN 'ROLLOVER_ALL' THEN NEW.to_savings = 0 AND NEW.new_principal = d.principal + v_earned - v_tax
        ELSE v_payout_ok END;
    IF NEW.nights <> v_nights OR NEW.rate_applied <> v_rate OR NEW.interest_earned <> v_earned OR NEW.tax <> v_tax
       OR NEW.accrued_before <> v_accrued OR NOT (v_payout_ok OR (NEW.kind = 'MATURITY' AND v_instruction_ok)) THEN
        RAISE EXCEPTION 'TERMDEP_CLOSURE_INVALID: deposit % earns % for % nights at % percent, tax %, accrued %',
            d.deposit_number, v_earned, v_nights, v_rate, v_tax, v_accrued USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER closure_guard BEFORE INSERT OR UPDATE OR DELETE ON termdep.closure
    FOR EACH ROW EXECUTE FUNCTION termdep.guard_closure();

CREATE FUNCTION termdep.check_closure() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    d termdep.deposit%ROWTYPE;
    p termdep.product%ROWTYPE;
    v_control varchar;
    v_status varchar;
    v_principal numeric;
    v_payable numeric;
    v_expense numeric;
    v_tax numeric;
    v_savings numeric;
    v_other int;
BEGIN
    SELECT * INTO d FROM termdep.deposit WHERE tenant_id = NEW.tenant_id AND deposit_id = NEW.deposit_id;
    SELECT * INTO p FROM termdep.product WHERE tenant_id = NEW.tenant_id AND product_code = d.product_code;
    v_status := CASE WHEN NEW.kind = 'EARLY' THEN 'WITHDRAWN_EARLY' WHEN NEW.new_principal > 0 THEN 'ROLLED_OVER' ELSE 'MATURED' END;
    IF d.status <> v_status THEN
        RAISE EXCEPTION 'TERMDEP_CLOSURE_INVALID: deposit % should now be %', d.deposit_number, v_status USING ERRCODE = 'P0001';
    END IF;
    IF NEW.new_deposit_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM termdep.deposit n WHERE n.tenant_id = NEW.tenant_id AND n.deposit_id = NEW.new_deposit_id
               AND n.rolled_from = NEW.deposit_id AND n.principal = NEW.new_principal) THEN
        RAISE EXCEPTION 'TERMDEP_ROLLOVER_MISMATCH: the new deposit must carry %', NEW.new_principal USING ERRCODE = 'P0001';
    END IF;
    SELECT gl_control_account INTO v_control FROM savings.deposit_account
     WHERE tenant_id = NEW.tenant_id AND account_id = d.savings_account_id;
    -- signed so that a credit is positive
    SELECT COALESCE(sum(CASE WHEN account_code = p.gl_principal THEN CASE side WHEN 'C' THEN amount ELSE -amount END END), 0),
           COALESCE(sum(CASE WHEN account_code = p.gl_interest_payable THEN CASE side WHEN 'C' THEN amount ELSE -amount END END), 0),
           COALESCE(sum(CASE WHEN account_code = p.gl_interest_expense THEN CASE side WHEN 'C' THEN amount ELSE -amount END END), 0),
           COALESCE(sum(CASE WHEN account_code = p.gl_tax THEN CASE side WHEN 'C' THEN amount ELSE -amount END END), 0),
           COALESCE(sum(CASE WHEN account_code = v_control AND subledger_account_id = d.savings_account_id
                             THEN CASE side WHEN 'C' THEN amount ELSE -amount END END), 0),
           count(*) FILTER (WHERE account_code NOT IN (p.gl_principal, p.gl_interest_payable, p.gl_interest_expense, p.gl_tax, v_control)
                               OR (account_code = v_control AND subledger_account_id IS DISTINCT FROM d.savings_account_id)
                               OR branch_code <> d.branch_code)
      INTO v_principal, v_payable, v_expense, v_tax, v_savings, v_other
      FROM ledger.journal_line WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id;
    IF v_principal <> NEW.new_principal - d.principal OR v_payable <> -NEW.accrued_before
       OR v_expense <> NEW.accrued_before - NEW.interest_earned OR v_tax <> NEW.tax OR v_savings <> NEW.to_savings OR v_other > 0 THEN
        RAISE EXCEPTION 'TERMDEP_POSTING_MISMATCH: the closure entry for % does not match (principal %, payable %, expense %, tax %, savings %)',
            d.deposit_number, v_principal, v_payable, v_expense, v_tax, v_savings USING ERRCODE = 'P0001';
    END IF;
    IF NEW.entry_id IS NULL AND (NEW.new_principal <> d.principal OR NEW.accrued_before <> 0 OR NEW.interest_earned <> 0
                                 OR NEW.to_savings <> 0) THEN
        RAISE EXCEPTION 'TERMDEP_POSTING_MISMATCH: the closure of % moves money and needs an entry', d.deposit_number USING ERRCODE = 'P0001';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER closure_posting AFTER INSERT ON termdep.closure
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION termdep.check_closure();

-- an approved early request has its closure
CREATE FUNCTION termdep.check_early_request() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'APPROVED' AND NOT EXISTS (SELECT 1 FROM termdep.closure c WHERE c.tenant_id = NEW.tenant_id
                                                  AND c.early_request_id = NEW.request_id AND c.deposit_id = NEW.deposit_id) THEN
        RAISE EXCEPTION 'TERMDEP_CLOSURE_MISSING: an approved early withdrawal pays out in the same transaction' USING ERRCODE = 'P0001';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER early_request_closure AFTER UPDATE ON termdep.early_request
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION termdep.check_early_request();

-- FD6: only placements, accruals and closures post to deposit principal and interest payable.
CREATE FUNCTION termdep.check_control_line() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM termdep.product WHERE tenant_id = NEW.tenant_id
                  AND NEW.account_code IN (gl_principal, gl_interest_payable))
       AND NOT EXISTS (SELECT 1 FROM termdep.deposit WHERE tenant_id = NEW.tenant_id AND placement_entry_id = NEW.entry_id)
       AND NOT EXISTS (SELECT 1 FROM termdep.accrual_batch WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id)
       AND NOT EXISTS (SELECT 1 FROM termdep.closure WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id) THEN
        RAISE EXCEPTION 'TERMDEP_DIRECT_POSTING: % holds fixed deposits; only placements, accruals and closures post to it',
            NEW.account_code USING ERRCODE = 'P0001';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER journal_line_term_deposit AFTER INSERT ON ledger.journal_line
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION termdep.check_control_line();

-- FD7
CREATE FUNCTION termdep.guard_savings_closure() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'CLOSED' AND OLD.status <> 'CLOSED'
       AND EXISTS (SELECT 1 FROM termdep.deposit WHERE tenant_id = NEW.tenant_id AND savings_account_id = NEW.account_id
                      AND status = 'ACTIVE') THEN
        RAISE EXCEPTION 'TERMDEP_ACCOUNT_IN_USE: account % receives a fixed deposit at maturity', NEW.account_number
            USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER deposit_account_term_deposits BEFORE UPDATE OF status ON savings.deposit_account
    FOR EACH ROW EXECUTE FUNCTION termdep.guard_savings_closure();

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
        OR EXISTS (SELECT 1 FROM termdep.product WHERE tenant_id = p_tenant AND p_code IN (gl_principal, gl_interest_payable))
$$;

ALTER TABLE ledger.reconciliation_result DROP CONSTRAINT reconciliation_result_check_code_check;
ALTER TABLE ledger.reconciliation_result ADD CONSTRAINT reconciliation_result_check_code_check
    CHECK (check_code IN ('SAVINGS_CONTROL', 'SAVINGS_INTEREST_PAYABLE', 'LOAN_PRINCIPAL', 'LOAN_INTEREST_RECEIVABLE',
                          'LOAN_PENALTY_RECEIVABLE', 'LOAN_OVERPAYMENT', 'LOAN_INTEREST_SUSPENSE', 'LOAN_LOSS_ALLOWANCE',
                          'SHARE_CAPITAL', 'TERM_DEPOSIT_PRINCIPAL', 'TERM_DEPOSIT_INTEREST'));

ALTER TABLE termdep.product ENABLE ROW LEVEL SECURITY;
ALTER TABLE termdep.product FORCE ROW LEVEL SECURITY;
ALTER TABLE termdep.number_counter ENABLE ROW LEVEL SECURITY;
ALTER TABLE termdep.number_counter FORCE ROW LEVEL SECURITY;
ALTER TABLE termdep.deposit ENABLE ROW LEVEL SECURITY;
ALTER TABLE termdep.deposit FORCE ROW LEVEL SECURITY;
ALTER TABLE termdep.accrual_batch ENABLE ROW LEVEL SECURITY;
ALTER TABLE termdep.accrual_batch FORCE ROW LEVEL SECURITY;
ALTER TABLE termdep.accrual ENABLE ROW LEVEL SECURITY;
ALTER TABLE termdep.accrual FORCE ROW LEVEL SECURITY;
ALTER TABLE termdep.early_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE termdep.early_request FORCE ROW LEVEL SECURITY;
ALTER TABLE termdep.closure ENABLE ROW LEVEL SECURITY;
ALTER TABLE termdep.closure FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON termdep.product
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
CREATE POLICY tenant_isolation ON termdep.number_counter
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
CREATE POLICY tenant_isolation ON termdep.deposit
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
CREATE POLICY tenant_isolation ON termdep.accrual_batch
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
CREATE POLICY tenant_isolation ON termdep.accrual
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
CREATE POLICY tenant_isolation ON termdep.early_request
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
CREATE POLICY tenant_isolation ON termdep.closure
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());

GRANT USAGE ON SCHEMA termdep TO mfi_app;
GRANT SELECT, INSERT, UPDATE ON termdep.product TO mfi_app;
GRANT SELECT, INSERT, UPDATE ON termdep.number_counter TO mfi_app;
GRANT SELECT, INSERT, UPDATE ON termdep.deposit TO mfi_app;
GRANT SELECT, INSERT ON termdep.accrual_batch TO mfi_app;
GRANT SELECT, INSERT ON termdep.accrual TO mfi_app;
GRANT SELECT, INSERT, UPDATE ON termdep.early_request TO mfi_app;
GRANT SELECT, INSERT ON termdep.closure TO mfi_app;
GRANT EXECUTE ON FUNCTION termdep.next_number(uuid) TO mfi_app;
GRANT EXECUTE ON FUNCTION termdep.in_end_of_day(uuid, date) TO mfi_app;
