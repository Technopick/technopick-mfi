-- =============================================================================
-- V34 (increment 37, ADR-0037): loan restructuring and partial prepayment.
--
-- Both change a loan's schedule through a new schedule version, requested by one person and
-- approved by another on the same business date:
--   RESTRUCTURE - unpaid principal (including overdue principal) is planned again over a new number
--                 of instalments, optionally after interest-only grace months. Overdue interest and
--                 penalties stay due. The loan counts as restructured (IFRS 9: at least stage 2 for
--                 a probation period).
--   PREPAYMENT  - with no arrears and enough credit, extra principal is added to the next
--                 instalment (the member's credit pays it on that date) and the rest is planned
--                 again to shorten the term or lower the instalment.
--
-- The database enforces, for every new schedule version: it comes from a settlement or an approved
-- reschedule (lending.check_schedule_version); for a reschedule (lending.check_schedule_change): instalments due on or before the change date keep
-- their due date and interest and never carry less principal than was paid on them; the
-- instalment accruing at the change keeps its due date and interest; no instalment's interest is
-- below what has accrued or been paid on it, nor its principal below what was paid; every
-- instalment that has accruals, payments or penalties is still in the schedule; due dates rise with
-- the instalment number.
-- =============================================================================
CREATE TABLE lending.loan_reschedule (
    tenant_id          uuid          NOT NULL,
    reschedule_id      uuid          NOT NULL,
    idempotency_key    varchar(80)   NOT NULL,
    loan_id            uuid          NOT NULL,
    kind               varchar(12)   NOT NULL CHECK (kind IN ('RESTRUCTURE', 'PREPAYMENT')),
    status             varchar(10)   NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED')),
    business_date      date          NOT NULL,
    from_version       int           NOT NULL CHECK (from_version >= 1),
    to_version         int,
    instalments        int           CHECK (instalments BETWEEN 1 AND 120),
    grace_months       int           NOT NULL DEFAULT 0 CHECK (grace_months BETWEEN 0 AND 12),
    prepayment         numeric(19,2) CHECK (prepayment > 0),
    prepayment_mode    varchar(20)   CHECK (prepayment_mode IN ('SHORTEN_TERM', 'REDUCE_INSTALMENT')),
    outstanding        numeric(19,2) NOT NULL CHECK (outstanding > 0),
    reason             varchar(300)  NOT NULL CHECK (length(btrim(reason)) >= 10),
    requested_by       varchar(100)  NOT NULL,
    requested_at       timestamptz   NOT NULL DEFAULT clock_timestamp(),
    decided_by         varchar(100),
    decided_at         timestamptz,
    decision_reason    varchar(300),
    PRIMARY KEY (tenant_id, reschedule_id),
    UNIQUE (tenant_id, idempotency_key),
    FOREIGN KEY (tenant_id, loan_id) REFERENCES lending.loan (tenant_id, loan_id),
    CHECK (kind <> 'RESTRUCTURE' OR (instalments IS NOT NULL AND prepayment IS NULL AND prepayment_mode IS NULL)),
    CHECK (kind <> 'PREPAYMENT' OR (prepayment IS NOT NULL AND prepayment_mode IS NOT NULL AND grace_months = 0)),
    CHECK (decided_by IS NULL OR decided_by <> requested_by),
    CHECK ((status = 'REQUESTED') = (decided_by IS NULL)),
    CHECK ((status = 'APPROVED') = (to_version IS NOT NULL)),
    CHECK (to_version IS NULL OR to_version = from_version + 1)
);
CREATE UNIQUE INDEX loan_reschedule_one_open ON lending.loan_reschedule (tenant_id, loan_id) WHERE status = 'REQUESTED';
CREATE INDEX loan_reschedule_restructured ON lending.loan_reschedule (tenant_id, loan_id, business_date)
    WHERE status = 'APPROVED' AND kind = 'RESTRUCTURE';

CREATE FUNCTION lending.check_loan_reschedule() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_today date;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'RESCHEDULE_IMMUTABLE: reschedules are never deleted' USING ERRCODE = 'P0001';
    END IF;
    SELECT current_date_ INTO v_today FROM tenancy.business_date WHERE tenant_id = NEW.tenant_id;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'REQUESTED' OR NEW.business_date IS DISTINCT FROM v_today THEN
            RAISE EXCEPTION 'RESCHEDULE_INVALID: a reschedule is requested for today''s business date' USING ERRCODE = 'P0001';
        END IF;
        IF (SELECT status FROM lending.loan WHERE tenant_id = NEW.tenant_id AND loan_id = NEW.loan_id) IS DISTINCT FROM 'ACTIVE' THEN
            RAISE EXCEPTION 'RESCHEDULE_LOAN_NOT_ACTIVE: only active loans are rescheduled' USING ERRCODE = 'P0001';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status <> 'REQUESTED' THEN
        RAISE EXCEPTION 'RESCHEDULE_IMMUTABLE: reschedule already %', OLD.status USING ERRCODE = 'P0001';
    END IF;
    IF (NEW.reschedule_id, NEW.idempotency_key, NEW.loan_id, NEW.kind, NEW.business_date, NEW.from_version, NEW.instalments,
        NEW.grace_months, NEW.prepayment, NEW.prepayment_mode, NEW.outstanding, NEW.reason, NEW.requested_by, NEW.requested_at)
       IS DISTINCT FROM (OLD.reschedule_id, OLD.idempotency_key, OLD.loan_id, OLD.kind, OLD.business_date, OLD.from_version,
        OLD.instalments, OLD.grace_months, OLD.prepayment, OLD.prepayment_mode, OLD.outstanding, OLD.reason, OLD.requested_by,
        OLD.requested_at) THEN
        RAISE EXCEPTION 'RESCHEDULE_IMMUTABLE: only the decision is recorded later' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status = 'APPROVED' AND NEW.business_date <> v_today THEN
        RAISE EXCEPTION 'RESCHEDULE_STALE: requested on %, the business date is now %; request again', NEW.business_date, v_today
            USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER loan_reschedule_rules BEFORE INSERT OR UPDATE OR DELETE ON lending.loan_reschedule
    FOR EACH ROW EXECUTE FUNCTION lending.check_loan_reschedule();

-- At commit, for every new schedule version (see the header).
CREATE FUNCTION lending.check_schedule_change() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_date date;
    v_bad text;
BEGIN
    IF NEW.schedule_version = 1 THEN
        RETURN NULL;
    END IF;
    -- Only once per version (the check covers the whole version).
    IF NEW.installment_no <> (SELECT min(installment_no) FROM lending.schedule_installment
                               WHERE tenant_id = NEW.tenant_id AND loan_id = NEW.loan_id AND schedule_version = NEW.schedule_version) THEN
        RETURN NULL;
    END IF;
    IF EXISTS (SELECT 1 FROM lending.settlement WHERE tenant_id = NEW.tenant_id AND loan_id = NEW.loan_id
                                                  AND schedule_version = NEW.schedule_version) THEN
        RETURN NULL;  -- early settlement has its own checks (V29)
    END IF;
    SELECT business_date INTO v_date FROM lending.loan_reschedule
     WHERE tenant_id = NEW.tenant_id AND loan_id = NEW.loan_id AND to_version = NEW.schedule_version AND status = 'APPROVED';
    IF v_date IS NULL THEN
        RETURN NULL;  -- refused by lending.check_schedule_version (SCHEDULE_VERSION_UNEXPLAINED)
    END IF;
    WITH old AS (
        SELECT * FROM lending.schedule_installment
         WHERE tenant_id = NEW.tenant_id AND loan_id = NEW.loan_id AND schedule_version = NEW.schedule_version - 1),
    new AS (
        SELECT * FROM lending.schedule_installment
         WHERE tenant_id = NEW.tenant_id AND loan_id = NEW.loan_id AND schedule_version = NEW.schedule_version),
    accruing AS (
        SELECT installment_no FROM old WHERE due_date > v_date ORDER BY due_date, installment_no LIMIT 1),
    paid AS (
        SELECT a.installment_no,
               COALESCE(sum(a.amount) FILTER (WHERE a.component = 'PRINCIPAL'), 0) AS principal,
               COALESCE(sum(a.amount) FILTER (WHERE a.component = 'INTEREST'), 0) AS interest
          FROM lending.repayment_allocation a
          JOIN lending.repayment r ON r.tenant_id = a.tenant_id AND r.repayment_id = a.repayment_id
         WHERE r.tenant_id = NEW.tenant_id AND r.loan_id = NEW.loan_id GROUP BY a.installment_no),
    accrued AS (
        SELECT installment_no, sum(amount) AS amount FROM lending.loan_accrual
         WHERE tenant_id = NEW.tenant_id AND loan_id = NEW.loan_id GROUP BY installment_no),
    used AS (
        SELECT installment_no FROM paid UNION SELECT installment_no FROM accrued
        UNION SELECT installment_no FROM lending.penalty_charge WHERE tenant_id = NEW.tenant_id AND loan_id = NEW.loan_id)
    SELECT reason INTO v_bad FROM (
        SELECT 'instalment ' || o.installment_no || ' was due on or before the change and must keep its date and interest' AS reason
          FROM old o LEFT JOIN new n ON n.installment_no = o.installment_no
         WHERE o.due_date <= v_date AND (n.installment_no IS NULL OR n.due_date <> o.due_date OR n.interest <> o.interest)
        UNION ALL
        SELECT 'instalment ' || o.installment_no || ' is accruing interest and must keep its date and interest'
          FROM old o JOIN accruing c ON c.installment_no = o.installment_no LEFT JOIN new n ON n.installment_no = o.installment_no
         WHERE n.installment_no IS NULL OR n.due_date <> o.due_date OR n.interest <> o.interest
        UNION ALL
        SELECT 'instalment ' || n.installment_no || ' carries less than was paid or accrued on it'
          FROM new n LEFT JOIN paid p ON p.installment_no = n.installment_no LEFT JOIN accrued a ON a.installment_no = n.installment_no
         WHERE n.principal < COALESCE(p.principal, 0) OR n.interest < GREATEST(COALESCE(p.interest, 0), COALESCE(a.amount, 0))
        UNION ALL
        SELECT 'instalment ' || u.installment_no || ' has payments, accruals or penalties and was dropped'
          FROM used u LEFT JOIN new n ON n.installment_no = u.installment_no WHERE n.installment_no IS NULL
        UNION ALL
        SELECT 'due dates must rise with the instalment number'
          FROM new a JOIN new b ON b.installment_no = a.installment_no + 1 WHERE b.due_date <= a.due_date
    ) problems LIMIT 1;
    IF v_bad IS NOT NULL THEN
        RAISE EXCEPTION 'SCHEDULE_CHANGE_INVALID: %', v_bad USING ERRCODE = 'P0001';
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER schedule_version_change AFTER INSERT ON lending.schedule_installment
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION lending.check_schedule_change();

-- V29's rule, extended: a schedule version after the first exists only for a settlement or an approved reschedule.
CREATE OR REPLACE FUNCTION lending.check_schedule_version() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.schedule_version > 1
       AND NOT EXISTS (SELECT 1 FROM lending.settlement
                        WHERE tenant_id = NEW.tenant_id AND loan_id = NEW.loan_id AND schedule_version = NEW.schedule_version)
       AND NOT EXISTS (SELECT 1 FROM lending.loan_reschedule
                        WHERE tenant_id = NEW.tenant_id AND loan_id = NEW.loan_id AND to_version = NEW.schedule_version
                          AND status = 'APPROVED') THEN
        RAISE EXCEPTION 'SCHEDULE_VERSION_UNEXPLAINED: schedule v% of a loan needs a settlement or an approved reschedule',
            NEW.schedule_version USING ERRCODE = 'P0001';
    END IF;
    RETURN NULL;
END $$;

-- True if the loan was restructured on or before p_as_of and less than p_months ago.
CREATE FUNCTION lending.restructured_within(p_tenant uuid, p_loan uuid, p_as_of date, p_months int) RETURNS boolean
    LANGUAGE sql STABLE
AS $$
    SELECT EXISTS (SELECT 1 FROM lending.loan_reschedule
                    WHERE tenant_id = p_tenant AND loan_id = p_loan AND status = 'APPROVED' AND kind = 'RESTRUCTURE'
                      AND business_date <= p_as_of AND business_date > p_as_of - make_interval(months => p_months))
$$;

-- IFRS 9: restructured loans stay at least in stage 2 for a probation period (ADR-0036/0037).
ALTER TABLE ledger.ecl_settings
    ADD COLUMN restructure_probation_months int NOT NULL DEFAULT 6 CHECK (restructure_probation_months BETWEEN 0 AND 24);
ALTER TABLE ledger.ecl_run
    ADD COLUMN restructure_probation_months int NOT NULL DEFAULT 6 CHECK (restructure_probation_months BETWEEN 0 AND 24);

-- SMS to the member about the new schedule (ADR-0035).
ALTER TABLE notify.sms_message DROP CONSTRAINT sms_message_template_check;
ALTER TABLE notify.sms_message ADD CONSTRAINT sms_message_template_check
    CHECK (template IN ('PAYMENT_LOAN', 'PAYMENT_SAVINGS', 'LOAN_DUE_SOON', 'LOAN_MISSED', 'PIN_SETUP_READY', 'PIN_SET',
                        'PIN_CHANGED', 'PIN_LOCKED', 'LOAN_RESCHEDULED'));
-- SERVICE: changes to the member's contract, always sent (like SECURITY).
ALTER TABLE notify.sms_message DROP CONSTRAINT sms_message_category_check;
ALTER TABLE notify.sms_message ADD CONSTRAINT sms_message_category_check
    CHECK (category IN ('TRANSACTIONAL', 'REMINDER', 'SECURITY', 'SERVICE'));

ALTER TABLE lending.loan_reschedule ENABLE ROW LEVEL SECURITY;
ALTER TABLE lending.loan_reschedule FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON lending.loan_reschedule
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());

GRANT SELECT, INSERT, UPDATE ON lending.loan_reschedule TO mfi_app;
GRANT EXECUTE ON FUNCTION lending.restructured_within(uuid, uuid, date, int) TO mfi_app;
