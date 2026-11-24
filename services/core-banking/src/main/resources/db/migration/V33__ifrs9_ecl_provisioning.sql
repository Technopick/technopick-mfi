-- =============================================================================
-- V33 (increment 36, ADR-0036): IFRS 9 expected credit loss (ECL) provisioning for loans.
--
-- Simplified approach (provision matrix): each loan's stage comes from its days past due at a
-- closed month end (stage 2 above ecl_settings.stage2_dpd, stage 3 above stage3_dpd); its ECL is
-- its outstanding principal times the rate finance set for its product and stage. A run records
-- every loan's figures; a second finance user approves it, which posts only the change in the
-- loss allowance (expense against allowance, or a release).
--
-- The database enforces: maker-checker; one open run; one posted run per month end, in order;
-- each line's ECL is its exposure times its rate; the run total is the sum of its lines; the
-- journal posts exactly the change; nothing else posts to the allowance account; nothing is
-- changed or deleted.
-- =============================================================================
CREATE TABLE ledger.ecl_settings (
    tenant_id          uuid         PRIMARY KEY REFERENCES tenancy.tenant (tenant_id),
    allowance_account  varchar(40)  NOT NULL,
    expense_account    varchar(40)  NOT NULL,
    stage2_dpd         int          NOT NULL DEFAULT 30 CHECK (stage2_dpd BETWEEN 1 AND 180),
    stage3_dpd         int          NOT NULL DEFAULT 90 CHECK (stage3_dpd BETWEEN 2 AND 365),
    updated_by         varchar(100) NOT NULL,
    updated_at         timestamptz  NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (tenant_id, allowance_account) REFERENCES ledger.gl_account (tenant_id, account_code),
    FOREIGN KEY (tenant_id, expense_account) REFERENCES ledger.gl_account (tenant_id, account_code),
    CHECK (stage3_dpd > stage2_dpd),
    CHECK (allowance_account <> expense_account)
);

-- Rates per product and stage, in percent of exposure. product_code '*' is the default for products without their own.
CREATE TABLE ledger.ecl_rate (
    tenant_id     uuid          NOT NULL REFERENCES tenancy.tenant (tenant_id),
    product_code  varchar(32)   NOT NULL,
    stage         smallint      NOT NULL CHECK (stage BETWEEN 1 AND 3),
    rate_pct      numeric(7,4)  NOT NULL CHECK (rate_pct BETWEEN 0 AND 100),
    updated_by    varchar(100)  NOT NULL,
    updated_at    timestamptz   NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, product_code, stage)
);

CREATE TABLE ledger.ecl_run (
    tenant_id          uuid          NOT NULL,
    run_id             uuid          NOT NULL,
    idempotency_key    varchar(80)   NOT NULL,
    as_of              date          NOT NULL CHECK (as_of = (date_trunc('month', as_of) + interval '1 month - 1 day')::date),
    status             varchar(10)   NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED', 'POSTED', 'REJECTED')),
    allowance_account  varchar(40)   NOT NULL,
    expense_account    varchar(40)   NOT NULL,
    stage2_dpd         int           NOT NULL,
    stage3_dpd         int           NOT NULL,
    loans              int           NOT NULL CHECK (loans >= 0),
    required_total     numeric(19,2) NOT NULL CHECK (required_total >= 0),
    previous_total     numeric(19,2) NOT NULL CHECK (previous_total >= 0),
    change             numeric(19,2) NOT NULL,
    entry_id           uuid,
    requested_by       varchar(100)  NOT NULL,
    requested_at       timestamptz   NOT NULL DEFAULT clock_timestamp(),
    decided_by         varchar(100),
    decided_at         timestamptz,
    decision_reason    varchar(300),
    PRIMARY KEY (tenant_id, run_id),
    UNIQUE (tenant_id, idempotency_key),
    UNIQUE (tenant_id, entry_id),
    FOREIGN KEY (tenant_id, allowance_account) REFERENCES ledger.gl_account (tenant_id, account_code),
    FOREIGN KEY (tenant_id, expense_account) REFERENCES ledger.gl_account (tenant_id, account_code),
    CHECK (change = required_total - previous_total),
    CHECK (decided_by IS NULL OR decided_by <> requested_by),
    CHECK ((status = 'REQUESTED') = (decided_by IS NULL)),
    -- A posted change needs its entry; branches can also move with no net change, which posts an entry too.
    CHECK (status <> 'POSTED' OR change = 0 OR entry_id IS NOT NULL),
    CHECK (entry_id IS NULL OR status = 'POSTED')
);
CREATE UNIQUE INDEX ecl_run_one_open ON ledger.ecl_run (tenant_id) WHERE status = 'REQUESTED';
CREATE UNIQUE INDEX ecl_run_one_posted ON ledger.ecl_run (tenant_id, as_of) WHERE status = 'POSTED';
CREATE INDEX ecl_run_account ON ledger.ecl_run (tenant_id, allowance_account, entry_id) WHERE status = 'POSTED';

CREATE TABLE ledger.ecl_run_line (
    tenant_id      uuid          NOT NULL,
    run_id         uuid          NOT NULL,
    loan_id        uuid          NOT NULL,
    product_code   varchar(32)   NOT NULL,
    branch_code    varchar(16)   NOT NULL,
    days_past_due  int           NOT NULL CHECK (days_past_due >= 0),
    stage          smallint      NOT NULL CHECK (stage BETWEEN 1 AND 3),
    exposure       numeric(19,2) NOT NULL CHECK (exposure >= 0),
    rate_pct       numeric(7,4)  NOT NULL CHECK (rate_pct BETWEEN 0 AND 100),
    ecl            numeric(19,2) NOT NULL,
    PRIMARY KEY (tenant_id, run_id, loan_id),
    FOREIGN KEY (tenant_id, run_id) REFERENCES ledger.ecl_run (tenant_id, run_id),
    FOREIGN KEY (tenant_id, loan_id) REFERENCES lending.loan (tenant_id, loan_id),
    FOREIGN KEY (tenant_id, branch_code) REFERENCES tenancy.branch (tenant_id, branch_code),
    CHECK (ecl = round(exposure * rate_pct / 100, 2))
);

-- Settings: the allowance is a contra asset used for nothing else; the expense an expense account.
-- Once a run has posted, the allowance account cannot change (its balance is the posted runs').
CREATE FUNCTION ledger.check_ecl_settings() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF (SELECT account_class FROM ledger.gl_account WHERE tenant_id = NEW.tenant_id AND account_code = NEW.allowance_account)
       IS DISTINCT FROM 'ASSET' THEN
        RAISE EXCEPTION 'ECL_ACCOUNT_INVALID: the loss allowance % must be an asset account (it is deducted from loans)',
            NEW.allowance_account USING ERRCODE = 'P0001';
    END IF;
    IF (SELECT account_class FROM ledger.gl_account WHERE tenant_id = NEW.tenant_id AND account_code = NEW.expense_account)
       IS DISTINCT FROM 'EXPENSE' THEN
        RAISE EXCEPTION 'ECL_ACCOUNT_INVALID: the impairment expense % must be an expense account', NEW.expense_account
            USING ERRCODE = 'P0001';
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.allowance_account IS DISTINCT FROM OLD.allowance_account
       AND EXISTS (SELECT 1 FROM ledger.ecl_run WHERE tenant_id = NEW.tenant_id AND status = 'POSTED' AND change <> 0) THEN
        RAISE EXCEPTION 'ECL_ACCOUNT_LOCKED: provisions have been posted to %; the allowance account cannot change',
            OLD.allowance_account USING ERRCODE = 'P0001';
    END IF;
    IF (TG_OP = 'INSERT' OR NEW.allowance_account IS DISTINCT FROM OLD.allowance_account)
       AND (ledger.is_control_account(NEW.tenant_id, NEW.allowance_account)
            OR EXISTS (SELECT 1 FROM ledger.journal_line WHERE tenant_id = NEW.tenant_id AND account_code = NEW.allowance_account)) THEN
        RAISE EXCEPTION 'ECL_ACCOUNT_INVALID: % is already used; the loss allowance needs an account of its own',
            NEW.allowance_account USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER ecl_settings_rules BEFORE INSERT OR UPDATE ON ledger.ecl_settings
    FOR EACH ROW EXECUTE FUNCTION ledger.check_ecl_settings();

-- Runs: created open, for a closed month end not before the last posted one; only the decision changes later.
CREATE FUNCTION ledger.check_ecl_run() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_posted numeric;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'ECL_IMMUTABLE: provision runs are never deleted' USING ERRCODE = 'P0001';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'REQUESTED' THEN
            RAISE EXCEPTION 'ECL_INVALID: a run starts as a request' USING ERRCODE = 'P0001';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM ledger.eod_run WHERE tenant_id = NEW.tenant_id AND business_date = NEW.as_of
                                                     AND status = 'COMPLETED') THEN
            RAISE EXCEPTION 'ECL_DAY_NOT_CLOSED: % has not been closed by end of day', NEW.as_of USING ERRCODE = 'P0001';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status <> 'REQUESTED' THEN
        RAISE EXCEPTION 'ECL_IMMUTABLE: run already %', OLD.status USING ERRCODE = 'P0001';
    END IF;
    IF (NEW.run_id, NEW.idempotency_key, NEW.as_of, NEW.allowance_account, NEW.expense_account, NEW.stage2_dpd, NEW.stage3_dpd,
        NEW.loans, NEW.required_total, NEW.previous_total, NEW.requested_by, NEW.requested_at)
       IS DISTINCT FROM (OLD.run_id, OLD.idempotency_key, OLD.as_of, OLD.allowance_account, OLD.expense_account, OLD.stage2_dpd,
        OLD.stage3_dpd, OLD.loans, OLD.required_total, OLD.previous_total, OLD.requested_by, OLD.requested_at) THEN
        RAISE EXCEPTION 'ECL_IMMUTABLE: only the decision is recorded later' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status = 'POSTED' THEN
        IF EXISTS (SELECT 1 FROM ledger.ecl_run WHERE tenant_id = NEW.tenant_id AND status = 'POSTED' AND as_of > NEW.as_of) THEN
            RAISE EXCEPTION 'ECL_OUT_OF_ORDER: a later month end has already been posted' USING ERRCODE = 'P0001';
        END IF;
        SELECT COALESCE(sum(change), 0) INTO v_posted FROM ledger.ecl_run
         WHERE tenant_id = NEW.tenant_id AND status = 'POSTED' AND allowance_account = NEW.allowance_account;
        IF v_posted <> NEW.previous_total THEN
            RAISE EXCEPTION 'ECL_STALE: the allowance is now % (the run assumed %); request a new run', v_posted, NEW.previous_total
                USING ERRCODE = 'P0001';
        END IF;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER ecl_run_rules BEFORE INSERT OR UPDATE OR DELETE ON ledger.ecl_run
    FOR EACH ROW EXECUTE FUNCTION ledger.check_ecl_run();

-- At commit: the run's total and count are its lines'; a posted run's entry moves the allowance by exactly the change,
-- after which the allowance held in every branch is that run's provision for the branch.
CREATE FUNCTION ledger.check_ecl_run_totals() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_sum numeric;
    v_count int;
    v_allowance numeric;
    v_expense numeric;
    v_branch text;
BEGIN
    SELECT COALESCE(sum(ecl), 0), count(*) INTO v_sum, v_count FROM ledger.ecl_run_line
     WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id;
    IF v_sum <> NEW.required_total OR v_count <> NEW.loans THEN
        RAISE EXCEPTION 'ECL_TOTAL_MISMATCH: run says % over % loans, its lines % over %', NEW.required_total, NEW.loans,
            v_sum, v_count USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status = 'POSTED' AND NEW.entry_id IS NOT NULL THEN
        SELECT COALESCE(sum(CASE WHEN account_code = NEW.allowance_account THEN CASE side WHEN 'C' THEN amount ELSE -amount END END), 0),
               COALESCE(sum(CASE WHEN account_code = NEW.expense_account THEN CASE side WHEN 'D' THEN amount ELSE -amount END END), 0)
          INTO v_allowance, v_expense
          FROM ledger.journal_line WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id;
        IF v_allowance <> NEW.change OR v_expense <> NEW.change THEN
            RAISE EXCEPTION 'ECL_POSTING_MISMATCH: change % but entry moves allowance % and expense %', NEW.change, v_allowance,
                v_expense USING ERRCODE = 'P0001';
        END IF;
    END IF;
    IF NEW.status = 'POSTED' THEN
        SELECT b.branch_code INTO v_branch FROM (
            SELECT branch_code, sum(CASE side WHEN 'C' THEN amount ELSE -amount END) AS held FROM ledger.journal_line
             WHERE tenant_id = NEW.tenant_id AND account_code = NEW.allowance_account GROUP BY branch_code) b
          FULL JOIN (
            SELECT branch_code, sum(ecl) AS wanted FROM ledger.ecl_run_line
             WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id GROUP BY branch_code) w ON w.branch_code = b.branch_code
         WHERE COALESCE(b.held, 0) <> COALESCE(w.wanted, 0) LIMIT 1;
        IF FOUND THEN
            RAISE EXCEPTION 'ECL_BRANCH_MISMATCH: after posting, the allowance held in a branch is not this run''s provision for it'
                USING ERRCODE = 'P0001';
        END IF;
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER ecl_run_totals AFTER INSERT OR UPDATE ON ledger.ecl_run
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ledger.check_ecl_run_totals();

-- Lines are written only while their run is open, never changed.
CREATE FUNCTION ledger.check_ecl_run_line() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'ECL_IMMUTABLE: provision lines are never changed or deleted' USING ERRCODE = 'P0001';
    END IF;
    IF (SELECT status FROM ledger.ecl_run WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id) IS DISTINCT FROM 'REQUESTED' THEN
        RAISE EXCEPTION 'ECL_IMMUTABLE: lines are added only to an open run' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER ecl_run_line_rules BEFORE INSERT OR UPDATE OR DELETE ON ledger.ecl_run_line
    FOR EACH ROW EXECUTE FUNCTION ledger.check_ecl_run_line();

-- Only posted provision runs post to a loss allowance account.
CREATE FUNCTION ledger.check_allowance_line() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF (EXISTS (SELECT 1 FROM ledger.ecl_settings WHERE tenant_id = NEW.tenant_id AND allowance_account = NEW.account_code)
        OR EXISTS (SELECT 1 FROM ledger.ecl_run WHERE tenant_id = NEW.tenant_id AND allowance_account = NEW.account_code))
       AND NOT EXISTS (SELECT 1 FROM ledger.ecl_run WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id
                                                     AND status = 'POSTED' AND allowance_account = NEW.account_code) THEN
        RAISE EXCEPTION 'ECL_DIRECT_POSTING: % is the loan loss allowance; only approved provision runs post to it',
            NEW.account_code USING ERRCODE = 'P0001';
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER journal_line_allowance AFTER INSERT ON ledger.journal_line
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ledger.check_allowance_line();

-- The allowance is a control account: manual journals are refused on it (V23).
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
$$;

ALTER TABLE ledger.reconciliation_result DROP CONSTRAINT reconciliation_result_check_code_check;
ALTER TABLE ledger.reconciliation_result ADD CONSTRAINT reconciliation_result_check_code_check
    CHECK (check_code IN ('SAVINGS_CONTROL', 'SAVINGS_INTEREST_PAYABLE', 'LOAN_PRINCIPAL', 'LOAN_INTEREST_RECEIVABLE',
                          'LOAN_PENALTY_RECEIVABLE', 'LOAN_OVERPAYMENT', 'LOAN_INTEREST_SUSPENSE', 'LOAN_LOSS_ALLOWANCE'));

ALTER TABLE ledger.ecl_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.ecl_settings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ledger.ecl_settings
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
ALTER TABLE ledger.ecl_rate ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.ecl_rate FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ledger.ecl_rate
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
ALTER TABLE ledger.ecl_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.ecl_run FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ledger.ecl_run
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
ALTER TABLE ledger.ecl_run_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.ecl_run_line FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ledger.ecl_run_line
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());

GRANT SELECT, INSERT, UPDATE ON ledger.ecl_settings TO mfi_app;
GRANT SELECT, INSERT, UPDATE ON ledger.ecl_rate TO mfi_app;
GRANT SELECT, INSERT, UPDATE ON ledger.ecl_run TO mfi_app;
GRANT SELECT, INSERT ON ledger.ecl_run_line TO mfi_app;
