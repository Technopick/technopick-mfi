-- =============================================================================
-- V18: Savings interest - daily accrual during end of day, periodic posting to accounts,
--      withholding tax; end of day refuses to complete while tills are open.
--   I1  a product earns interest only with an expense, a payable and (if taxed) a tax account
--   I2  accrual rows are written only for the current business date while its EOD run is
--       RUNNING, once per account and date; the balance is the account's ledger balance at
--       that date, the rate is the product's rate, and the daily amount is computed here
--       (balance x rate / 100 / 365, ACT/365 fixed, 10 decimals)
--   I3  the posted (2-decimal) amount is the rounded cumulative accrual of the open period less
--       what was already posted in that period, so rounding never drifts
--   I4  each accrual entry posts exactly the sum of its accrual rows (expense / payable)
--   I5  an interest posting covers the account's accruals since its previous posting, its gross
--       equals their sum, tax = gross x product tax rate (rounded half-up to cents), net = gross - tax, and its
--       entry pays exactly that to the account
--   I6  accruals and postings never change; EOD cannot complete while a till session is open
-- =============================================================================

ALTER TABLE savings.deposit_product
    ADD COLUMN interest_rate        numeric(9,6)  NOT NULL DEFAULT 0 CHECK (interest_rate >= 0 AND interest_rate <= 100),
    ADD COLUMN interest_min_balance numeric(19,4) NOT NULL DEFAULT 0 CHECK (interest_min_balance >= 0),
    ADD COLUMN withholding_tax_rate numeric(5,2)  NOT NULL DEFAULT 0
        CHECK (withholding_tax_rate >= 0 AND withholding_tax_rate <= 100),
    ADD COLUMN gl_interest_expense  varchar(40),
    ADD COLUMN gl_interest_payable  varchar(40),
    ADD COLUMN gl_withholding_tax   varchar(40),
    ADD FOREIGN KEY (tenant_id, gl_interest_expense) REFERENCES ledger.gl_account (tenant_id, account_code),
    ADD FOREIGN KEY (tenant_id, gl_interest_payable) REFERENCES ledger.gl_account (tenant_id, account_code),
    ADD FOREIGN KEY (tenant_id, gl_withholding_tax) REFERENCES ledger.gl_account (tenant_id, account_code),
    ADD CONSTRAINT deposit_product_interest_gls
        CHECK (interest_rate = 0 OR (gl_interest_expense IS NOT NULL AND gl_interest_payable IS NOT NULL)),
    ADD CONSTRAINT deposit_product_tax_gl
        CHECK (withholding_tax_rate = 0 OR gl_withholding_tax IS NOT NULL);

CREATE TABLE savings.interest_posting (
    tenant_id    uuid          NOT NULL,
    posting_id   uuid          NOT NULL,
    account_id   uuid          NOT NULL,
    period_start date          NOT NULL,
    period_end   date          NOT NULL,
    gross        numeric(19,4) NOT NULL CHECK (gross > 0),
    tax          numeric(19,4) NOT NULL CHECK (tax >= 0),
    net          numeric(19,4) NOT NULL CHECK (net >= 0),
    tax_rate     numeric(5,2)  NOT NULL,
    entry_id     uuid          NOT NULL,
    PRIMARY KEY (tenant_id, posting_id),
    CONSTRAINT interest_posting_once UNIQUE (tenant_id, account_id, period_end),
    CONSTRAINT interest_posting_entry UNIQUE (tenant_id, entry_id),
    FOREIGN KEY (tenant_id, account_id) REFERENCES savings.deposit_account (tenant_id, account_id),
    FOREIGN KEY (tenant_id, entry_id) REFERENCES ledger.journal_entry (tenant_id, entry_id),
    CHECK (period_start <= period_end),
    CHECK (net = gross - tax)
);

CREATE TABLE savings.interest_accrual (
    tenant_id     uuid          NOT NULL,
    account_id    uuid          NOT NULL,
    business_date date          NOT NULL,
    product_code  varchar(32)   NOT NULL,
    branch_code   varchar(16)   NOT NULL,
    balance       numeric(19,4) NOT NULL CHECK (balance > 0),
    rate          numeric(9,6)  NOT NULL CHECK (rate > 0),
    daily_exact   numeric(29,10) GENERATED ALWAYS AS (round(balance * rate / 36500, 10)) STORED,
    posted        numeric(19,4) NOT NULL CHECK (posted >= 0),
    entry_id      uuid,
    PRIMARY KEY (tenant_id, account_id, business_date),
    FOREIGN KEY (tenant_id, account_id) REFERENCES savings.deposit_account (tenant_id, account_id),
    FOREIGN KEY (tenant_id, entry_id) REFERENCES ledger.journal_entry (tenant_id, entry_id),
    CHECK ((posted > 0) = (entry_id IS NOT NULL))
);
CREATE INDEX interest_accrual_entry_idx ON savings.interest_accrual (tenant_id, entry_id) WHERE entry_id IS NOT NULL;

-- I2, I3: checked on insert.
CREATE FUNCTION savings.check_interest_accrual() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    a savings.deposit_account%ROWTYPE;
    p savings.deposit_product%ROWTYPE;
    v_balance numeric;
    v_last_end date;
    v_prev_exact numeric;
    v_prev_posted numeric;
    v_expected numeric;
    v_key text;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM tenancy.business_date b
                     JOIN ledger.eod_run r ON r.tenant_id = b.tenant_id AND r.business_date = b.current_date_
                    WHERE b.tenant_id = NEW.tenant_id AND b.current_date_ = NEW.business_date AND r.status = 'RUNNING') THEN
        RAISE EXCEPTION 'EOD_WRONG_DATE: % is not the current business date with a RUNNING EOD', NEW.business_date
            USING ERRCODE = 'P0001';
    END IF;
    SELECT * INTO a FROM savings.deposit_account WHERE tenant_id = NEW.tenant_id AND account_id = NEW.account_id;
    SELECT * INTO p FROM savings.deposit_product WHERE tenant_id = a.tenant_id AND product_code = a.product_code;
    IF a.status = 'CLOSED' OR NEW.product_code <> a.product_code OR NEW.branch_code <> a.branch_code
       OR NEW.rate <> p.interest_rate THEN
        RAISE EXCEPTION 'INTEREST_ACCRUAL_MISMATCH: account %, product or rate do not match', a.account_number
            USING ERRCODE = 'P0001';
    END IF;
    SELECT COALESCE(sum(CASE l.side WHEN 'C' THEN l.amount ELSE -l.amount END), 0) INTO v_balance
      FROM ledger.journal_line l
      JOIN ledger.journal_entry e ON e.tenant_id = l.tenant_id AND e.entry_id = l.entry_id
     WHERE l.tenant_id = NEW.tenant_id AND l.subledger_account_id = NEW.account_id AND e.business_date <= NEW.business_date;
    IF v_balance <> NEW.balance OR v_balance < p.interest_min_balance THEN
        RAISE EXCEPTION 'INTEREST_ACCRUAL_BALANCE: balance % is not the ledger balance % (minimum %)',
            NEW.balance, v_balance, p.interest_min_balance USING ERRCODE = 'P0001';
    END IF;
    SELECT max(period_end) INTO v_last_end FROM savings.interest_posting
     WHERE tenant_id = NEW.tenant_id AND account_id = NEW.account_id;
    SELECT COALESCE(sum(daily_exact), 0), COALESCE(sum(posted), 0) INTO v_prev_exact, v_prev_posted
      FROM savings.interest_accrual
     WHERE tenant_id = NEW.tenant_id AND account_id = NEW.account_id
       AND business_date > COALESCE(v_last_end, '-infinity'::date);
    v_expected := round(v_prev_exact + round(NEW.balance * NEW.rate / 36500, 10), 2) - v_prev_posted;
    IF NEW.posted <> v_expected THEN
        RAISE EXCEPTION 'INTEREST_ACCRUAL_ROUNDING: posted % but the period needs %', NEW.posted, v_expected
            USING ERRCODE = 'P0001';
    END IF;
    IF NEW.entry_id IS NOT NULL THEN
        SELECT idempotency_key INTO v_key FROM ledger.journal_entry WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id;
        IF v_key IS DISTINCT FROM 'savings-accrual:' || NEW.product_code || ':' || NEW.branch_code || ':' || NEW.business_date THEN
            RAISE EXCEPTION 'EOD_ENTRY_MISMATCH: entry % is not the accrual entry', NEW.entry_id USING ERRCODE = 'P0001';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER interest_accrual_check BEFORE INSERT ON savings.interest_accrual
    FOR EACH ROW EXECUTE FUNCTION savings.check_interest_accrual();

-- I4: at commit, each accrual entry debits expense and credits payable by exactly its rows' total.
CREATE FUNCTION savings.check_interest_accrual_entry() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_rows numeric;
    v_dr numeric;
    v_cr numeric;
    p savings.deposit_product%ROWTYPE;
BEGIN
    IF NEW.entry_id IS NULL THEN
        RETURN NULL;
    END IF;
    SELECT * INTO p FROM savings.deposit_product WHERE tenant_id = NEW.tenant_id AND product_code = NEW.product_code;
    SELECT sum(posted) INTO v_rows FROM savings.interest_accrual WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id;
    SELECT COALESCE(sum(amount) FILTER (WHERE side = 'D' AND account_code = p.gl_interest_expense), 0),
           COALESCE(sum(amount) FILTER (WHERE side = 'C' AND account_code = p.gl_interest_payable), 0)
      INTO v_dr, v_cr FROM ledger.journal_line WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id;
    IF v_dr <> v_rows OR v_cr <> v_rows THEN
        RAISE EXCEPTION 'EOD_AMOUNT_MISMATCH: accrual rows % vs entry % / %', v_rows, v_dr, v_cr USING ERRCODE = 'P0001';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER interest_accrual_amounts AFTER INSERT ON savings.interest_accrual
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION savings.check_interest_accrual_entry();

-- I5: a posting covers exactly the accruals since the previous posting.
CREATE FUNCTION savings.check_interest_posting() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_last_end date;
    v_first date;
    v_gross numeric;
    v_rate numeric;
    v_key text;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM tenancy.business_date b
                     JOIN ledger.eod_run r ON r.tenant_id = b.tenant_id AND r.business_date = b.current_date_
                    WHERE b.tenant_id = NEW.tenant_id AND b.current_date_ = NEW.period_end AND r.status = 'RUNNING') THEN
        RAISE EXCEPTION 'EOD_WRONG_DATE: interest is posted during end of day of the period end' USING ERRCODE = 'P0001';
    END IF;
    SELECT max(period_end) INTO v_last_end FROM savings.interest_posting
     WHERE tenant_id = NEW.tenant_id AND account_id = NEW.account_id;
    SELECT min(business_date), sum(posted) INTO v_first, v_gross FROM savings.interest_accrual
     WHERE tenant_id = NEW.tenant_id AND account_id = NEW.account_id
       AND business_date > COALESCE(v_last_end, '-infinity'::date) AND business_date <= NEW.period_end;
    IF NEW.period_start <> COALESCE(v_last_end + 1, v_first) OR NEW.gross <> v_gross THEN
        RAISE EXCEPTION 'INTEREST_POSTING_PERIOD: posting % to % gross % does not match accruals from % gross %',
            NEW.period_start, NEW.period_end, NEW.gross, COALESCE(v_last_end + 1, v_first), v_gross USING ERRCODE = 'P0001';
    END IF;
    SELECT p.withholding_tax_rate INTO v_rate FROM savings.deposit_account a
      JOIN savings.deposit_product p ON p.tenant_id = a.tenant_id AND p.product_code = a.product_code
     WHERE a.tenant_id = NEW.tenant_id AND a.account_id = NEW.account_id;
    IF NEW.tax_rate <> v_rate OR NEW.tax <> round(NEW.gross * v_rate / 100, 2) THEN
        RAISE EXCEPTION 'INTEREST_POSTING_TAX: tax % at % does not match the product rate %', NEW.tax, NEW.tax_rate, v_rate
            USING ERRCODE = 'P0001';
    END IF;
    SELECT idempotency_key INTO v_key FROM ledger.journal_entry WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id;
    IF v_key IS DISTINCT FROM 'savings-interest:' || NEW.account_id || ':' || NEW.period_end THEN
        RAISE EXCEPTION 'EOD_ENTRY_MISMATCH: entry % is not this posting''s entry', NEW.entry_id USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER interest_posting_check BEFORE INSERT ON savings.interest_posting
    FOR EACH ROW EXECUTE FUNCTION savings.check_interest_posting();

-- I5: at commit, the entry moves gross out of payable, net to the account and tax to the tax account.
CREATE FUNCTION savings.check_interest_posting_entry() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    p savings.deposit_product%ROWTYPE;
    a savings.deposit_account%ROWTYPE;
    v_payable numeric;
    v_account numeric;
    v_tax numeric;
    v_other int;
BEGIN
    SELECT * INTO a FROM savings.deposit_account WHERE tenant_id = NEW.tenant_id AND account_id = NEW.account_id;
    SELECT * INTO p FROM savings.deposit_product WHERE tenant_id = a.tenant_id AND product_code = a.product_code;
    SELECT COALESCE(sum(amount) FILTER (WHERE side = 'D' AND account_code = p.gl_interest_payable), 0),
           COALESCE(sum(amount) FILTER (WHERE side = 'C' AND subledger_account_id = NEW.account_id), 0),
           COALESCE(sum(amount) FILTER (WHERE side = 'C' AND account_code = p.gl_withholding_tax), 0),
           count(*) FILTER (WHERE NOT ((side = 'D' AND account_code = p.gl_interest_payable)
                                    OR (side = 'C' AND subledger_account_id = NEW.account_id)
                                    OR (side = 'C' AND account_code = p.gl_withholding_tax)))
      INTO v_payable, v_account, v_tax, v_other
      FROM ledger.journal_line WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id;
    IF v_payable <> NEW.gross OR v_account <> NEW.net OR v_tax <> NEW.tax OR v_other <> 0 THEN
        RAISE EXCEPTION 'INTEREST_POSTING_ENTRY: entry does not pay gross % as net % and tax %', NEW.gross, NEW.net, NEW.tax
            USING ERRCODE = 'P0001';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER interest_posting_amounts AFTER INSERT ON savings.interest_posting
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION savings.check_interest_posting_entry();

CREATE TRIGGER interest_accrual_immutable BEFORE UPDATE OR DELETE ON savings.interest_accrual
    FOR EACH ROW EXECUTE FUNCTION ledger.reject_mutation();
CREATE TRIGGER interest_posting_immutable BEFORE UPDATE OR DELETE ON savings.interest_posting
    FOR EACH ROW EXECUTE FUNCTION ledger.reject_mutation();

-- I6: the business day closes only when every till session of that day or earlier is closed.
CREATE FUNCTION ledger.check_eod_tills() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'COMPLETED' AND EXISTS (
           SELECT 1 FROM savings.till_session
            WHERE tenant_id = NEW.tenant_id AND status <> 'CLOSED' AND business_date <= NEW.business_date) THEN
        RAISE EXCEPTION 'EOD_TILLS_OPEN: close and balance every till before end of day' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER eod_run_tills BEFORE UPDATE ON ledger.eod_run
    FOR EACH ROW EXECUTE FUNCTION ledger.check_eod_tills();

ALTER TABLE savings.interest_accrual ENABLE ROW LEVEL SECURITY;
ALTER TABLE savings.interest_accrual FORCE ROW LEVEL SECURITY;
ALTER TABLE savings.interest_posting ENABLE ROW LEVEL SECURITY;
ALTER TABLE savings.interest_posting FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON savings.interest_accrual
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
CREATE POLICY tenant_isolation ON savings.interest_posting
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
GRANT SELECT, INSERT ON savings.interest_accrual, savings.interest_posting TO mfi_app;
