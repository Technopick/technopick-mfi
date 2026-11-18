-- =============================================================================
-- V4: business date, end-of-day runs, accrual and penalty records, arrears snapshots, PAR.
--
-- Rules enforced HERE:
--   E1  each tenant has one business date; it only moves forward by exactly one day, and
--       only after that date's EOD run has COMPLETED (no skipped or repeated dates)
--   E2  EOD run status: RUNNING -> COMPLETED | FAILED, FAILED -> RUNNING (retry); COMPLETED final
--   E3  accrual and penalty records are written only for the current business date while its
--       EOD run is RUNNING, once per loan/installment/date, tied to this loan's own journal
--       entry for that event (idempotency-key format from LoanPostingRules), and amounts
--       agree with the entry; accrual never exceeds the installment's scheduled interest
--   E4  arrears snapshots: insert-only, one per loan per date, ACTIVE loans only
--   E5  PAR view per branch and date (metric par/v1); RLS + grants
-- =============================================================================

CREATE TABLE tenancy.business_date (
    tenant_id     uuid        PRIMARY KEY REFERENCES tenancy.tenant (tenant_id),
    current_date_ date        NOT NULL,
    updated_at    timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE ledger.eod_run (
    tenant_id       uuid        NOT NULL REFERENCES tenancy.tenant (tenant_id),
    business_date   date        NOT NULL,
    status          varchar(16) NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    started_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
    finished_at     timestamptz,
    loans_processed int         NOT NULL DEFAULT 0 CHECK (loans_processed >= 0),
    failure_reason  varchar(500),
    PRIMARY KEY (tenant_id, business_date),
    CHECK ((status = 'RUNNING') = (finished_at IS NULL))
);

CREATE FUNCTION tenancy.guard_business_date() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'EOD_BUSINESS_DATE: business date rows are never deleted' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.tenant_id <> OLD.tenant_id THEN
        RAISE EXCEPTION 'EOD_BUSINESS_DATE: tenant cannot change' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.current_date_ <> OLD.current_date_ + 1 THEN
        RAISE EXCEPTION 'EOD_BUSINESS_DATE: can only advance by one day (% -> %)', OLD.current_date_, NEW.current_date_
            USING ERRCODE = 'P0001';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ledger.eod_run
                    WHERE tenant_id = OLD.tenant_id AND business_date = OLD.current_date_ AND status = 'COMPLETED') THEN
        RAISE EXCEPTION 'EOD_NOT_COMPLETED: EOD for % has not completed', OLD.current_date_ USING ERRCODE = 'P0001';
    END IF;
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END $$;

CREATE TRIGGER business_date_guard
    BEFORE UPDATE OR DELETE ON tenancy.business_date
    FOR EACH ROW EXECUTE FUNCTION tenancy.guard_business_date();

CREATE FUNCTION ledger.guard_eod_run() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_current date;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'EOD_RUN_IMMUTABLE: EOD runs are never deleted' USING ERRCODE = 'P0001';
    END IF;
    IF TG_OP = 'INSERT' THEN
        SELECT current_date_ INTO v_current FROM tenancy.business_date WHERE tenant_id = NEW.tenant_id;
        IF v_current IS DISTINCT FROM NEW.business_date OR NEW.status <> 'RUNNING' THEN
            RAISE EXCEPTION 'EOD_WRONG_DATE: EOD can only start RUNNING for the current business date %', v_current
                USING ERRCODE = 'P0001';
        END IF;
        RETURN NEW;
    END IF;
    IF (NEW.tenant_id, NEW.business_date) IS DISTINCT FROM (OLD.tenant_id, OLD.business_date) THEN
        RAISE EXCEPTION 'EOD_RUN_IMMUTABLE: run identity cannot change' USING ERRCODE = 'P0001';
    END IF;
    IF NOT ((OLD.status = 'RUNNING' AND NEW.status IN ('COMPLETED', 'FAILED'))
         OR (OLD.status = 'FAILED' AND NEW.status = 'RUNNING')
         OR (OLD.status = 'RUNNING' AND NEW.status = 'RUNNING')) THEN
        RAISE EXCEPTION 'EOD_RUN_STATUS: % -> % not allowed', OLD.status, NEW.status USING ERRCODE = 'P0001';
    END IF;
    NEW.finished_at := CASE WHEN NEW.status = 'RUNNING' THEN NULL ELSE clock_timestamp() END;
    RETURN NEW;
END $$;

CREATE TRIGGER eod_run_guard
    BEFORE INSERT OR UPDATE OR DELETE ON ledger.eod_run
    FOR EACH ROW EXECUTE FUNCTION ledger.guard_eod_run();

-- ---------------------------------------------------------------------------
-- Accrual and penalty records
-- ---------------------------------------------------------------------------
CREATE TABLE lending.loan_accrual (
    tenant_id      uuid          NOT NULL,
    loan_id        uuid          NOT NULL,
    installment_no int           NOT NULL,
    business_date  date          NOT NULL,
    amount         numeric(19,4) NOT NULL CHECK (amount > 0),
    entry_id       uuid          NOT NULL,
    PRIMARY KEY (tenant_id, loan_id, installment_no, business_date),
    FOREIGN KEY (tenant_id, loan_id) REFERENCES lending.loan (tenant_id, loan_id),
    FOREIGN KEY (tenant_id, entry_id) REFERENCES ledger.journal_entry (tenant_id, entry_id)
);

CREATE TABLE lending.penalty_charge (
    tenant_id      uuid          NOT NULL,
    loan_id        uuid          NOT NULL,
    installment_no int           NOT NULL,
    business_date  date          NOT NULL,
    amount         numeric(19,4) NOT NULL CHECK (amount > 0),
    entry_id       uuid          NOT NULL,
    PRIMARY KEY (tenant_id, loan_id, installment_no, business_date),
    CONSTRAINT penalty_charge_one_entry UNIQUE (tenant_id, entry_id),
    FOREIGN KEY (tenant_id, loan_id) REFERENCES lending.loan (tenant_id, loan_id),
    FOREIGN KEY (tenant_id, entry_id) REFERENCES ledger.journal_entry (tenant_id, entry_id)
);

-- Common: current business date with a RUNNING EOD, ACTIVE loan, entry belongs to this event.
CREATE FUNCTION lending.check_eod_record() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_key text;
    v_expected_key text;
    v_entry_date date;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM tenancy.business_date b
                     JOIN ledger.eod_run r ON r.tenant_id = b.tenant_id AND r.business_date = b.current_date_
                    WHERE b.tenant_id = NEW.tenant_id AND b.current_date_ = NEW.business_date
                      AND r.status = 'RUNNING') THEN
        RAISE EXCEPTION 'EOD_WRONG_DATE: % is not the current business date with a RUNNING EOD', NEW.business_date
            USING ERRCODE = 'P0001';
    END IF;
    IF (SELECT status FROM lending.loan WHERE tenant_id = NEW.tenant_id AND loan_id = NEW.loan_id) <> 'ACTIVE' THEN
        RAISE EXCEPTION 'LENDING_LOAN_NOT_ACTIVE: EOD records need an ACTIVE loan' USING ERRCODE = 'P0001';
    END IF;
    SELECT idempotency_key, business_date INTO v_key, v_entry_date FROM ledger.journal_entry
     WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id;
    v_expected_key := 'loan:' || NEW.loan_id || CASE TG_TABLE_NAME
        WHEN 'loan_accrual'   THEN ':ACCRUE:' || NEW.business_date
        WHEN 'penalty_charge' THEN ':PENALTY:' || NEW.installment_no || ':' || NEW.business_date END;
    IF v_key IS DISTINCT FROM v_expected_key OR v_entry_date <> NEW.business_date THEN
        RAISE EXCEPTION 'EOD_ENTRY_MISMATCH: entry % is not %', NEW.entry_id, v_expected_key USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER loan_accrual_check BEFORE INSERT ON lending.loan_accrual
    FOR EACH ROW EXECUTE FUNCTION lending.check_eod_record();
CREATE TRIGGER penalty_charge_check BEFORE INSERT ON lending.penalty_charge
    FOR EACH ROW EXECUTE FUNCTION lending.check_eod_record();

-- At commit: amounts agree with the posted entries; accrual within scheduled interest.
CREATE FUNCTION lending.check_eod_amounts() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_records numeric;
    v_entry numeric;
    v_accrued numeric;
    v_scheduled numeric;
    v_gl varchar(40);
BEGIN
    IF TG_TABLE_NAME = 'loan_accrual' THEN
        SELECT v.gl_interest_receivable INTO v_gl FROM lending.loan l
          JOIN lending.loan_product_version v ON v.tenant_id = l.tenant_id AND v.version_id = l.product_version_id
         WHERE l.tenant_id = NEW.tenant_id AND l.loan_id = NEW.loan_id;
        SELECT sum(amount) INTO v_records FROM lending.loan_accrual
         WHERE tenant_id = NEW.tenant_id AND loan_id = NEW.loan_id AND business_date = NEW.business_date;
        SELECT sum(amount) INTO v_accrued FROM lending.loan_accrual
         WHERE tenant_id = NEW.tenant_id AND loan_id = NEW.loan_id AND installment_no = NEW.installment_no;
        SELECT interest INTO v_scheduled FROM lending.schedule_installment
         WHERE tenant_id = NEW.tenant_id AND loan_id = NEW.loan_id AND installment_no = NEW.installment_no
           AND schedule_version = (SELECT max(schedule_version) FROM lending.schedule_installment
                                    WHERE tenant_id = NEW.tenant_id AND loan_id = NEW.loan_id);
        IF v_scheduled IS NULL OR v_accrued > v_scheduled THEN
            RAISE EXCEPTION 'EOD_OVER_ACCRUAL: installment % accrued % of scheduled %',
                NEW.installment_no, v_accrued, v_scheduled USING ERRCODE = 'P0001';
        END IF;
    ELSE
        SELECT v.gl_penalty_receivable INTO v_gl FROM lending.loan l
          JOIN lending.loan_product_version v ON v.tenant_id = l.tenant_id AND v.version_id = l.product_version_id
         WHERE l.tenant_id = NEW.tenant_id AND l.loan_id = NEW.loan_id;
        v_records := NEW.amount;
    END IF;
    SELECT COALESCE(sum(amount), 0) INTO v_entry FROM ledger.journal_line
     WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id AND account_code = v_gl AND side = 'D';
    IF v_entry <> v_records THEN
        RAISE EXCEPTION 'EOD_AMOUNT_MISMATCH: records % vs entry %', v_records, v_entry USING ERRCODE = 'P0001';
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER loan_accrual_amounts AFTER INSERT ON lending.loan_accrual
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION lending.check_eod_amounts();
CREATE CONSTRAINT TRIGGER penalty_charge_amounts AFTER INSERT ON lending.penalty_charge
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION lending.check_eod_amounts();

-- ---------------------------------------------------------------------------
-- Arrears snapshots and PAR
-- ---------------------------------------------------------------------------
CREATE TABLE lending.arrears_snapshot (
    tenant_id             uuid          NOT NULL,
    loan_id               uuid          NOT NULL,
    as_of                 date          NOT NULL,
    days_past_due         int           NOT NULL CHECK (days_past_due >= 0),
    bucket                varchar(16)   NOT NULL
        CHECK (bucket IN ('CURRENT', 'DPD_1_30', 'DPD_31_60', 'DPD_61_90', 'DPD_91_180', 'DPD_OVER_180')),
    classification        varchar(16)   NOT NULL
        CHECK (classification IN ('NORMAL', 'WATCH', 'SUBSTANDARD', 'DOUBTFUL', 'LOSS')),
    arrears_amount        numeric(19,4) NOT NULL CHECK (arrears_amount >= 0),
    outstanding_principal numeric(19,4) NOT NULL CHECK (outstanding_principal >= 0),
    PRIMARY KEY (tenant_id, loan_id, as_of),
    FOREIGN KEY (tenant_id, loan_id) REFERENCES lending.loan (tenant_id, loan_id),
    -- Bucket must agree with DPD (arrears/v1 boundaries).
    CHECK (bucket = CASE WHEN days_past_due = 0 THEN 'CURRENT' WHEN days_past_due <= 30 THEN 'DPD_1_30'
                         WHEN days_past_due <= 60 THEN 'DPD_31_60' WHEN days_past_due <= 90 THEN 'DPD_61_90'
                         WHEN days_past_due <= 180 THEN 'DPD_91_180' ELSE 'DPD_OVER_180' END)
);

CREATE FUNCTION lending.check_snapshot() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF (SELECT status FROM lending.loan WHERE tenant_id = NEW.tenant_id AND loan_id = NEW.loan_id) <> 'ACTIVE' THEN
        RAISE EXCEPTION 'LENDING_LOAN_NOT_ACTIVE: snapshots are for ACTIVE loans' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER arrears_snapshot_check BEFORE INSERT ON lending.arrears_snapshot
    FOR EACH ROW EXECUTE FUNCTION lending.check_snapshot();

CREATE TRIGGER loan_accrual_immutable BEFORE UPDATE OR DELETE ON lending.loan_accrual
    FOR EACH ROW EXECUTE FUNCTION ledger.reject_mutation();
CREATE TRIGGER penalty_charge_immutable BEFORE UPDATE OR DELETE ON lending.penalty_charge
    FOR EACH ROW EXECUTE FUNCTION ledger.reject_mutation();
CREATE TRIGGER arrears_snapshot_immutable BEFORE UPDATE OR DELETE ON lending.arrears_snapshot
    FOR EACH ROW EXECUTE FUNCTION ledger.reject_mutation();

-- PAR (par/v1): numerator = outstanding principal of loans with DPD > n; denominator = all
-- outstanding principal in the snapshot; ratio NULL when the denominator is zero.
CREATE VIEW lending.par_by_branch WITH (security_invoker = true) AS
SELECT s.tenant_id,
       s.as_of,
       l.branch_code,
       l.currency,
       count(*)                                                        AS loans,
       sum(s.outstanding_principal)                                    AS portfolio,
       sum(s.outstanding_principal) FILTER (WHERE s.days_past_due > 1)  AS at_risk_1,
       sum(s.outstanding_principal) FILTER (WHERE s.days_past_due > 30) AS at_risk_30,
       sum(s.outstanding_principal) FILTER (WHERE s.days_past_due > 60) AS at_risk_60,
       sum(s.outstanding_principal) FILTER (WHERE s.days_past_due > 90) AS at_risk_90,
       round(COALESCE(sum(s.outstanding_principal) FILTER (WHERE s.days_past_due > 1), 0)
             / NULLIF(sum(s.outstanding_principal), 0), 4)              AS par_1,
       round(COALESCE(sum(s.outstanding_principal) FILTER (WHERE s.days_past_due > 30), 0)
             / NULLIF(sum(s.outstanding_principal), 0), 4)              AS par_30,
       round(COALESCE(sum(s.outstanding_principal) FILTER (WHERE s.days_past_due > 60), 0)
             / NULLIF(sum(s.outstanding_principal), 0), 4)              AS par_60,
       round(COALESCE(sum(s.outstanding_principal) FILTER (WHERE s.days_past_due > 90), 0)
             / NULLIF(sum(s.outstanding_principal), 0), 4)              AS par_90
  FROM lending.arrears_snapshot s
  JOIN lending.loan l ON l.tenant_id = s.tenant_id AND l.loan_id = s.loan_id
 GROUP BY s.tenant_id, s.as_of, l.branch_code, l.currency;

-- ---------------------------------------------------------------------------
-- RLS and grants
-- ---------------------------------------------------------------------------
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['tenancy.business_date', 'ledger.eod_run', 'lending.loan_accrual',
                             'lending.penalty_charge', 'lending.arrears_snapshot'] LOOP
        EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %s USING (tenant_id = tenancy.current_tenant()) '
                       'WITH CHECK (tenant_id = tenancy.current_tenant())', t);
    END LOOP;
END $$;

GRANT SELECT, INSERT, UPDATE ON tenancy.business_date, ledger.eod_run TO mfi_app;
GRANT SELECT, INSERT ON lending.loan_accrual, lending.penalty_charge, lending.arrears_snapshot TO mfi_app;
GRANT SELECT ON lending.par_by_branch TO mfi_app;
