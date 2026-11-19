-- =============================================================================
-- V23: Manual journals with maker-checker; accounting period opening and close.
--   Q1  a manual journal has 2-50 lines, positive amounts, balances per currency, and is dated
--       in an OPEN period no later than the current business date
--   Q2  manual journals never touch control accounts (customer subledgers, loan receivables and
--       overpayments, savings interest payable, till cash): those change only through their
--       business flows, so reconciliation stays meaningful
--   Q3  a different person decides; a rejection needs a reason; POSTED needs the journal entry,
--       whose lines are exactly the journal's lines; decisions are final
--   Y1  periods are calendar months named YYYY-MM, opened contiguously after the latest period
--   Y2  a period closes only after its last day has been closed by end of day, with no manual
--       journal still pending in it; if that day's reconciliation recorded differences the
--       closer must explain them; closing is final in the application
-- =============================================================================

-- Q2 ------------------------------------------------------------------------
CREATE FUNCTION ledger.is_control_account(p_tenant uuid, p_code varchar) RETURNS boolean
    LANGUAGE sql STABLE
AS $$
    SELECT EXISTS (SELECT 1 FROM ledger.gl_account WHERE tenant_id = p_tenant AND account_code = p_code AND requires_subledger)
        OR EXISTS (SELECT 1 FROM lending.loan_product_version
                    WHERE tenant_id = p_tenant
                      AND p_code IN (gl_principal, gl_interest_receivable, gl_penalty_receivable, gl_overpayment))
        OR EXISTS (SELECT 1 FROM savings.deposit_product WHERE tenant_id = p_tenant AND gl_interest_payable = p_code)
        OR EXISTS (SELECT 1 FROM savings.till WHERE tenant_id = p_tenant AND gl_cash = p_code)
$$;

CREATE TABLE ledger.manual_journal (
    tenant_id        uuid          NOT NULL,
    journal_id       uuid          NOT NULL,
    idempotency_key  varchar(80)   NOT NULL,
    business_date    date          NOT NULL,
    narrative        varchar(300)  NOT NULL CHECK (length(btrim(narrative)) >= 10),
    status           varchar(16)   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'POSTED', 'REJECTED')),
    requested_by     varchar(100)  NOT NULL,
    requested_at     timestamptz   NOT NULL DEFAULT clock_timestamp(),
    decided_by       varchar(100),
    decided_at       timestamptz,
    decision_reason  varchar(300),
    entry_id         uuid,
    PRIMARY KEY (tenant_id, journal_id),
    CONSTRAINT manual_journal_key UNIQUE (tenant_id, idempotency_key),
    FOREIGN KEY (tenant_id, entry_id) REFERENCES ledger.journal_entry (tenant_id, entry_id),
    CHECK (decided_by IS NULL OR decided_by <> requested_by),
    CHECK ((status = 'PENDING') = (decided_by IS NULL)),
    CHECK ((status = 'POSTED') = (entry_id IS NOT NULL)),
    CHECK (status <> 'REJECTED' OR length(btrim(COALESCE(decision_reason, ''))) >= 3)
);

CREATE TABLE ledger.manual_journal_line (
    tenant_id     uuid          NOT NULL,
    journal_id    uuid          NOT NULL,
    line_no       int           NOT NULL CHECK (line_no BETWEEN 1 AND 50),
    account_code  varchar(40)   NOT NULL,
    side          char(1)       NOT NULL CHECK (side IN ('D', 'C')),
    amount        numeric(19,4) NOT NULL CHECK (amount > 0),
    currency      char(3)       NOT NULL,
    branch_code   varchar(16)   NOT NULL,
    narrative     varchar(200),
    PRIMARY KEY (tenant_id, journal_id, line_no),
    FOREIGN KEY (tenant_id, journal_id) REFERENCES ledger.manual_journal (tenant_id, journal_id),
    FOREIGN KEY (tenant_id, account_code) REFERENCES ledger.gl_account (tenant_id, account_code),
    FOREIGN KEY (tenant_id, branch_code) REFERENCES tenancy.branch (tenant_id, branch_code)
);

CREATE FUNCTION ledger.check_manual_journal_line() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    a ledger.gl_account%ROWTYPE;
BEGIN
    IF (SELECT status FROM ledger.manual_journal WHERE tenant_id = NEW.tenant_id AND journal_id = NEW.journal_id) <> 'PENDING' THEN
        RAISE EXCEPTION 'MANUAL_JOURNAL_FINAL: lines are added only while the journal is pending' USING ERRCODE = 'P0001';
    END IF;
    SELECT * INTO a FROM ledger.gl_account WHERE tenant_id = NEW.tenant_id AND account_code = NEW.account_code;
    IF NOT a.postable OR a.status <> 'ACTIVE' OR a.currency <> NEW.currency THEN
        RAISE EXCEPTION 'MANUAL_JOURNAL_ACCOUNT: % is not an active postable % account', NEW.account_code, NEW.currency
            USING ERRCODE = 'P0001';
    END IF;
    IF ledger.is_control_account(NEW.tenant_id, NEW.account_code) THEN
        RAISE EXCEPTION 'MANUAL_JOURNAL_CONTROL_ACCOUNT: % is a control account; it changes only through its business flows',
            NEW.account_code USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER manual_journal_line_check BEFORE INSERT ON ledger.manual_journal_line
    FOR EACH ROW EXECUTE FUNCTION ledger.check_manual_journal_line();
CREATE TRIGGER manual_journal_line_immutable BEFORE UPDATE OR DELETE ON ledger.manual_journal_line
    FOR EACH ROW EXECUTE FUNCTION ledger.reject_mutation();

-- Q1 (at commit): 2-50 lines, balanced per currency, dated in an open period not after today.
CREATE FUNCTION ledger.check_manual_journal_shape() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    j ledger.manual_journal%ROWTYPE;
    v_lines int;
    v_unbalanced int;
BEGIN
    SELECT * INTO j FROM ledger.manual_journal WHERE tenant_id = NEW.tenant_id AND journal_id = NEW.journal_id;
    SELECT count(*) INTO v_lines FROM ledger.manual_journal_line WHERE tenant_id = j.tenant_id AND journal_id = j.journal_id;
    SELECT count(*) INTO v_unbalanced FROM (
        SELECT currency FROM ledger.manual_journal_line WHERE tenant_id = j.tenant_id AND journal_id = j.journal_id
         GROUP BY currency HAVING sum(CASE side WHEN 'D' THEN amount ELSE -amount END) <> 0) u;
    IF v_lines < 2 OR v_unbalanced > 0 THEN
        RAISE EXCEPTION 'MANUAL_JOURNAL_UNBALANCED: % line(s), % currency(ies) out of balance', v_lines, v_unbalanced
            USING ERRCODE = 'P0001';
    END IF;
    IF j.status = 'PENDING' AND (
           j.business_date > (SELECT current_date_ FROM tenancy.business_date WHERE tenant_id = j.tenant_id)
           OR NOT EXISTS (SELECT 1 FROM ledger.accounting_period p
                           WHERE p.tenant_id = j.tenant_id AND p.status = 'OPEN'
                             AND j.business_date BETWEEN p.start_date AND p.end_date)) THEN
        RAISE EXCEPTION 'MANUAL_JOURNAL_DATE: % is after the business date or not in an open period', j.business_date
            USING ERRCODE = 'P0001';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER manual_journal_shape AFTER INSERT ON ledger.manual_journal
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ledger.check_manual_journal_shape();

-- Q3 ------------------------------------------------------------------------
CREATE FUNCTION ledger.guard_manual_journal() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_mismatch int;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'MANUAL_JOURNAL_FINAL: manual journals are never deleted' USING ERRCODE = 'P0001';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PENDING' THEN
            RAISE EXCEPTION 'MANUAL_JOURNAL_STATUS: manual journals start PENDING' USING ERRCODE = 'P0001';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status <> 'PENDING' THEN
        RAISE EXCEPTION 'MANUAL_JOURNAL_FINAL: a decided journal cannot change' USING ERRCODE = 'P0001';
    END IF;
    IF (NEW.journal_id, NEW.idempotency_key, NEW.business_date, NEW.narrative, NEW.requested_by)
       IS DISTINCT FROM (OLD.journal_id, OLD.idempotency_key, OLD.business_date, OLD.narrative, OLD.requested_by) THEN
        RAISE EXCEPTION 'MANUAL_JOURNAL_FINAL: the request cannot change' USING ERRCODE = 'P0001';
    END IF;
    NEW.decided_at := clock_timestamp();
    IF NEW.status = 'POSTED' THEN
        IF (SELECT business_date FROM ledger.journal_entry WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id)
           <> NEW.business_date THEN
            RAISE EXCEPTION 'MANUAL_JOURNAL_ENTRY: entry is not dated %', NEW.business_date USING ERRCODE = 'P0001';
        END IF;
        SELECT count(*) INTO v_mismatch FROM (
            (SELECT line_no, account_code, side, amount, currency, branch_code FROM ledger.manual_journal_line
              WHERE tenant_id = NEW.tenant_id AND journal_id = NEW.journal_id
             EXCEPT
             SELECT line_no, account_code, side, amount, currency, branch_code FROM ledger.journal_line
              WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id)
            UNION ALL
            (SELECT line_no, account_code, side, amount, currency, branch_code FROM ledger.journal_line
              WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id
             EXCEPT
             SELECT line_no, account_code, side, amount, currency, branch_code FROM ledger.manual_journal_line
              WHERE tenant_id = NEW.tenant_id AND journal_id = NEW.journal_id)) d;
        IF v_mismatch > 0 THEN
            RAISE EXCEPTION 'MANUAL_JOURNAL_ENTRY: the posted entry differs from the approved lines' USING ERRCODE = 'P0001';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER manual_journal_guard BEFORE INSERT OR UPDATE OR DELETE ON ledger.manual_journal
    FOR EACH ROW EXECUTE FUNCTION ledger.guard_manual_journal();

-- Y1, Y2 ----------------------------------------------------------------------
ALTER TABLE ledger.accounting_period
    ADD COLUMN closed_by  varchar(100),
    ADD COLUMN closed_at  timestamptz,
    ADD COLUMN close_note varchar(500);

CREATE FUNCTION ledger.guard_accounting_period() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_latest date;
    v_today date;
    v_differences int;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'PERIOD_FINAL: accounting periods are never deleted' USING ERRCODE = 'P0001';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.start_date <> date_trunc('month', NEW.start_date)::date
           OR NEW.end_date <> (date_trunc('month', NEW.start_date) + interval '1 month - 1 day')::date
           OR NEW.period_id <> to_char(NEW.start_date, 'YYYY-MM') THEN
            RAISE EXCEPTION 'PERIOD_SHAPE: periods are calendar months named YYYY-MM' USING ERRCODE = 'P0001';
        END IF;
        SELECT max(end_date) INTO v_latest FROM ledger.accounting_period WHERE tenant_id = NEW.tenant_id;
        IF v_latest IS NOT NULL AND NEW.start_date <> v_latest + 1 THEN
            RAISE EXCEPTION 'PERIOD_NOT_CONTIGUOUS: the next period starts on %', v_latest + 1 USING ERRCODE = 'P0001';
        END IF;
        IF NEW.status = 'CLOSED' AND EXISTS (SELECT 1 FROM ledger.journal_entry WHERE tenant_id = NEW.tenant_id
                                               AND business_date BETWEEN NEW.start_date AND NEW.end_date) THEN
            RAISE EXCEPTION 'PERIOD_CLOSE_RULES: a period with entries is closed by closing it' USING ERRCODE = 'P0001';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status = 'CLOSED' THEN
        RAISE EXCEPTION 'PERIOD_FINAL: a closed period cannot change or reopen here' USING ERRCODE = 'P0001';
    END IF;
    IF (NEW.period_id, NEW.start_date, NEW.end_date) IS DISTINCT FROM (OLD.period_id, OLD.start_date, OLD.end_date) THEN
        RAISE EXCEPTION 'PERIOD_FINAL: period dates cannot change' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status = 'CLOSED' THEN
        SELECT current_date_ INTO v_today FROM tenancy.business_date WHERE tenant_id = NEW.tenant_id;
        IF v_today IS NULL OR v_today <= NEW.end_date THEN
            RAISE EXCEPTION 'PERIOD_CLOSE_RULES: % has not been closed by end of day yet', NEW.end_date USING ERRCODE = 'P0001';
        END IF;
        IF EXISTS (SELECT 1 FROM ledger.manual_journal WHERE tenant_id = NEW.tenant_id AND status = 'PENDING'
                      AND business_date BETWEEN NEW.start_date AND NEW.end_date) THEN
            RAISE EXCEPTION 'PERIOD_CLOSE_RULES: manual journals dated in the period are still pending' USING ERRCODE = 'P0001';
        END IF;
        SELECT count(*) INTO v_differences FROM ledger.reconciliation_result
         WHERE tenant_id = NEW.tenant_id AND business_date = NEW.end_date AND difference <> 0;
        IF NEW.closed_by IS NULL OR (v_differences > 0 AND length(btrim(COALESCE(NEW.close_note, ''))) < 10) THEN
            RAISE EXCEPTION 'PERIOD_CLOSE_RULES: closing needs the closer, and an explanation of % reconciliation difference(s)',
                v_differences USING ERRCODE = 'P0001';
        END IF;
        NEW.closed_at := clock_timestamp();
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER accounting_period_guard BEFORE INSERT OR UPDATE OR DELETE ON ledger.accounting_period
    FOR EACH ROW EXECUTE FUNCTION ledger.guard_accounting_period();

ALTER TABLE ledger.manual_journal ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.manual_journal FORCE ROW LEVEL SECURITY;
ALTER TABLE ledger.manual_journal_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.manual_journal_line FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ledger.manual_journal
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
CREATE POLICY tenant_isolation ON ledger.manual_journal_line
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
GRANT SELECT, INSERT, UPDATE ON ledger.manual_journal TO mfi_app;
GRANT SELECT, INSERT ON ledger.manual_journal_line TO mfi_app;
GRANT EXECUTE ON FUNCTION ledger.is_control_account(uuid, varchar) TO mfi_app;
