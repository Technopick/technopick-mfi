-- =============================================================================
-- V19: Loan end of day - penalty and arrears terms on the product version, interest paid
--      before it accrued is not recognised twice, penalties within the product's rule,
--      arrears snapshots only for the day being closed.
--   N1  a product version carries its penalty rule (penalty/v1) and arrears tolerance
--   N2  each accrual row records the part already taken to income at repayment; it must equal
--       amount - (max(A1, P) - max(A0, P)) where A0/A1 are the installment's accruals before and
--       after this row and P is the interest paid on it; the day's accrual entry debits interest
--       receivable by exactly the rows' amount less that part (no entry when nothing is left)
--   N3  a penalty needs a rule, only after the grace days, never above the cap, FLAT_ONCE exactly
--       once at the flat amount (or what the cap leaves), DAILY_RATE at most rate x unpaid
--   N4  arrears snapshots are written only for the current business date during its EOD, with
--       outstanding principal = principal disbursed less principal repaid
-- =============================================================================

ALTER TABLE lending.loan_product_version
    ADD COLUMN penalty_method     varchar(16)   NOT NULL DEFAULT 'NONE'
        CHECK (penalty_method IN ('NONE', 'FLAT_ONCE', 'DAILY_RATE')),
    ADD COLUMN penalty_grace_days int           NOT NULL DEFAULT 0 CHECK (penalty_grace_days >= 0),
    ADD COLUMN penalty_flat       numeric(19,4) CHECK (penalty_flat > 0),
    ADD COLUMN penalty_daily_rate numeric(9,6)  CHECK (penalty_daily_rate > 0 AND penalty_daily_rate <= 0.01),
    ADD COLUMN penalty_cap        numeric(19,4) CHECK (penalty_cap >= 0),
    ADD COLUMN arrears_tolerance  numeric(19,4) NOT NULL DEFAULT 0 CHECK (arrears_tolerance >= 0),
    ADD CONSTRAINT penalty_rule_complete CHECK (
        (penalty_method <> 'FLAT_ONCE' OR penalty_flat IS NOT NULL)
        AND (penalty_method <> 'DAILY_RATE' OR penalty_daily_rate IS NOT NULL));

-- N2 ------------------------------------------------------------------------
ALTER TABLE lending.loan_accrual
    ADD COLUMN recognized_earlier numeric(19,4) NOT NULL DEFAULT 0 CHECK (recognized_earlier >= 0),
    ALTER COLUMN entry_id DROP NOT NULL,
    ADD CONSTRAINT loan_accrual_recognized CHECK (recognized_earlier <= amount),
    ADD CONSTRAINT loan_accrual_entry CHECK (entry_id IS NOT NULL OR recognized_earlier = amount);

CREATE OR REPLACE FUNCTION lending.check_eod_record() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_key text;
    v_expected_key text;
    v_entry_date date;
    v_a0 numeric;
    v_paid numeric;
    v_expected numeric;
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
    IF TG_TABLE_NAME = 'loan_accrual' THEN
        SELECT COALESCE(sum(amount), 0) INTO v_a0 FROM lending.loan_accrual
         WHERE tenant_id = NEW.tenant_id AND loan_id = NEW.loan_id AND installment_no = NEW.installment_no;
        SELECT COALESCE(sum(al.amount), 0) INTO v_paid
          FROM lending.repayment_allocation al
          JOIN lending.repayment r ON r.tenant_id = al.tenant_id AND r.repayment_id = al.repayment_id
         WHERE al.tenant_id = NEW.tenant_id AND r.loan_id = NEW.loan_id AND al.installment_no = NEW.installment_no
           AND al.component = 'INTEREST';
        v_expected := NEW.amount - (GREATEST(v_a0 + NEW.amount, v_paid) - GREATEST(v_a0, v_paid));
        IF NEW.recognized_earlier <> v_expected THEN
            RAISE EXCEPTION 'EOD_RECOGNITION_MISMATCH: installment % recognised earlier % but repayments make it %',
                NEW.installment_no, NEW.recognized_earlier, v_expected USING ERRCODE = 'P0001';
        END IF;
        IF NEW.entry_id IS NULL THEN
            RETURN NEW;
        END IF;
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

CREATE OR REPLACE FUNCTION lending.check_eod_amounts() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_records numeric;
    v_entry numeric;
    v_accrued numeric;
    v_scheduled numeric;
    v_gl varchar(40);
    v_entries int;
BEGIN
    IF TG_TABLE_NAME = 'loan_accrual' THEN
        SELECT v.gl_interest_receivable INTO v_gl FROM lending.loan l
          JOIN lending.loan_product_version v ON v.tenant_id = l.tenant_id AND v.version_id = l.product_version_id
         WHERE l.tenant_id = NEW.tenant_id AND l.loan_id = NEW.loan_id;
        SELECT sum(amount - recognized_earlier), count(DISTINCT entry_id) INTO v_records, v_entries FROM lending.loan_accrual
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
        IF v_entries > 1 THEN
            RAISE EXCEPTION 'EOD_ENTRY_MISMATCH: one accrual entry per loan and day' USING ERRCODE = 'P0001';
        END IF;
        IF NEW.entry_id IS NULL THEN
            RETURN NULL;  -- nothing of this row is posted (loan_accrual_entry); the entry's rows are checked with it
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

-- N3 ------------------------------------------------------------------------
CREATE FUNCTION lending.check_penalty_rule() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v lending.loan_product_version%ROWTYPE;
    v_due date;
    v_sched numeric;
    v_paid numeric;
    v_prior numeric;
    v_prior_count int;
    v_room numeric;
BEGIN
    SELECT pv.* INTO v FROM lending.loan l
      JOIN lending.loan_product_version pv ON pv.tenant_id = l.tenant_id AND pv.version_id = l.product_version_id
     WHERE l.tenant_id = NEW.tenant_id AND l.loan_id = NEW.loan_id;
    SELECT due_date, principal + interest INTO v_due, v_sched FROM lending.schedule_installment
     WHERE tenant_id = NEW.tenant_id AND loan_id = NEW.loan_id AND installment_no = NEW.installment_no
       AND schedule_version = (SELECT max(schedule_version) FROM lending.schedule_installment
                                WHERE tenant_id = NEW.tenant_id AND loan_id = NEW.loan_id);
    SELECT COALESCE(sum(amount), 0), count(*) INTO v_prior, v_prior_count FROM lending.penalty_charge
     WHERE tenant_id = NEW.tenant_id AND loan_id = NEW.loan_id AND installment_no = NEW.installment_no;
    SELECT COALESCE(sum(al.amount), 0) INTO v_paid
      FROM lending.repayment_allocation al
      JOIN lending.repayment r ON r.tenant_id = al.tenant_id AND r.repayment_id = al.repayment_id
     WHERE al.tenant_id = NEW.tenant_id AND r.loan_id = NEW.loan_id AND al.installment_no = NEW.installment_no
       AND al.component IN ('PRINCIPAL', 'INTEREST');
    v_room := CASE WHEN v.penalty_cap IS NULL THEN NULL ELSE v.penalty_cap - v_prior END;
    IF v.penalty_method = 'NONE' OR v_due IS NULL OR NEW.business_date - v_due <= v.penalty_grace_days
       OR v_sched - v_paid <= 0 OR (v_room IS NOT NULL AND NEW.amount > v_room)
       OR (v.penalty_method = 'FLAT_ONCE' AND (v_prior_count > 0 OR NEW.amount <> LEAST(v.penalty_flat, COALESCE(v_room, v.penalty_flat))))
       OR (v.penalty_method = 'DAILY_RATE' AND NEW.amount > round((v_sched - v_paid) * v.penalty_daily_rate, 2)) THEN
        RAISE EXCEPTION 'EOD_PENALTY_RULE: penalty % on installment % is outside the product rule (%)',
            NEW.amount, NEW.installment_no, v.penalty_method USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER penalty_charge_rule BEFORE INSERT ON lending.penalty_charge
    FOR EACH ROW EXECUTE FUNCTION lending.check_penalty_rule();

-- N4 ------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lending.check_snapshot() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_principal numeric;
    v_repaid numeric;
BEGIN
    IF (SELECT status FROM lending.loan WHERE tenant_id = NEW.tenant_id AND loan_id = NEW.loan_id) <> 'ACTIVE' THEN
        RAISE EXCEPTION 'LENDING_LOAN_NOT_ACTIVE: snapshots are for ACTIVE loans' USING ERRCODE = 'P0001';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM tenancy.business_date b
                     JOIN ledger.eod_run r ON r.tenant_id = b.tenant_id AND r.business_date = b.current_date_
                    WHERE b.tenant_id = NEW.tenant_id AND b.current_date_ = NEW.as_of AND r.status = 'RUNNING') THEN
        RAISE EXCEPTION 'EOD_WRONG_DATE: snapshots are taken during the EOD of %', NEW.as_of USING ERRCODE = 'P0001';
    END IF;
    SELECT principal INTO v_principal FROM lending.loan WHERE tenant_id = NEW.tenant_id AND loan_id = NEW.loan_id;
    SELECT COALESCE(sum(al.amount), 0) INTO v_repaid
      FROM lending.repayment_allocation al
      JOIN lending.repayment r ON r.tenant_id = al.tenant_id AND r.repayment_id = al.repayment_id
     WHERE al.tenant_id = NEW.tenant_id AND r.loan_id = NEW.loan_id AND al.component = 'PRINCIPAL';
    IF NEW.outstanding_principal <> v_principal - v_repaid THEN
        RAISE EXCEPTION 'EOD_SNAPSHOT_PRINCIPAL: % is not principal % less repaid %', NEW.outstanding_principal,
            v_principal, v_repaid USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;
