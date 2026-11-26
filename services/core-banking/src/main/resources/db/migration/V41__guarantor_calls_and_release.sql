-- =============================================================================
-- V41: Calling on guarantors and releasing guarantees as loans are repaid (ADR-0044).
--   GC1 settings per tenant: the guarantor recoveries clearing account (a control account that only
--       recoveries post to), the days past due before guarantors may be called, and whether locks
--       are reduced as the loan is repaid
--   GC2 a guarantee now has a current lock (amount and hold), which only goes down, and the amount
--       recovered from it; a lock moves to a new, smaller hold on the same account, and the old hold
--       is released with it; a guarantee whose lock is used up by a call is CALLED
--   GC3 a call on an ACTIVE loan at least the set days past due (last close) is asked for by one
--       person and approved by another on the business date; its lines share the called amount
--       among the loan's open guarantees in proportion to their locks, each no more than its lock,
--       and the total no more than what is unpaid on the loan less credit already held
--   GC4 approval, for each line: the guarantor's savings are debited into the clearing account and
--       the same amount is applied to the loan as a repayment (channel INTERNAL, reference
--       GUARANTOR:<call>:<guarantee>) from the clearing account; the guarantee's lock and recovered
--       amount move by the line
--   GC5 when release as repaid is on, end of day lowers each open lock to the pledge's share of the
--       principal still outstanding, less what has been recovered from it
-- =============================================================================
CREATE TABLE lending.guarantee_settings (
    tenant_id          uuid         NOT NULL PRIMARY KEY REFERENCES tenancy.tenant (tenant_id),
    recovery_clearing  varchar(40)  NOT NULL,
    min_days_past_due  integer      NOT NULL DEFAULT 90 CHECK (min_days_past_due BETWEEN 1 AND 3650),
    release_as_repaid  boolean      NOT NULL DEFAULT false,
    updated_by         varchar(100) NOT NULL,
    updated_at         timestamptz  NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (tenant_id, recovery_clearing) REFERENCES ledger.gl_account (tenant_id, account_code)
);

CREATE FUNCTION lending.guard_guarantee_settings() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF (SELECT account_class FROM ledger.gl_account WHERE tenant_id = NEW.tenant_id AND account_code = NEW.recovery_clearing)
       IS DISTINCT FROM 'ASSET' THEN
        RAISE EXCEPTION 'GUARANTEE_SETTINGS_INVALID: the recoveries clearing account must be an ASSET account' USING ERRCODE = 'P0001';
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.recovery_clearing <> OLD.recovery_clearing
       AND EXISTS (SELECT 1 FROM lending.guarantor_call WHERE tenant_id = NEW.tenant_id) THEN
        RAISE EXCEPTION 'GUARANTEE_SETTINGS_LOCKED: the recoveries clearing account is fixed once a guarantor has been called'
            USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;

-- GC2
ALTER TABLE lending.loan_guarantee
    ADD COLUMN current_hold_id uuid,
    ADD COLUMN locked_amount   numeric(19,4),
    ADD COLUMN recovered       numeric(19,4) NOT NULL DEFAULT 0 CHECK (recovered >= 0);
UPDATE lending.loan_guarantee SET current_hold_id = hold_id, locked_amount = amount;
ALTER TABLE lending.loan_guarantee
    ALTER COLUMN current_hold_id SET NOT NULL,
    ALTER COLUMN locked_amount SET NOT NULL,
    ADD CONSTRAINT loan_guarantee_current_hold FOREIGN KEY (tenant_id, current_hold_id) REFERENCES savings.account_hold (tenant_id, hold_id),
    ADD CONSTRAINT loan_guarantee_lock_range CHECK (locked_amount >= 0 AND locked_amount + recovered <= amount);
ALTER TABLE lending.loan_guarantee DROP CONSTRAINT loan_guarantee_status_check;
ALTER TABLE lending.loan_guarantee ADD CONSTRAINT loan_guarantee_status_check
    CHECK (status IN ('PLEDGED', 'WITHDRAWN', 'RELEASED', 'CALLED'));
CREATE UNIQUE INDEX loan_guarantee_current_hold_once ON lending.loan_guarantee (tenant_id, current_hold_id);

CREATE OR REPLACE FUNCTION lending.guard_loan_guarantee() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    a lending.loan_application%ROWTYPE;
    h savings.account_hold%ROWTYPE;
    n savings.account_hold%ROWTYPE;
    v_owner uuid;
    v_account_status varchar;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'GUARANTEE_IMMUTABLE: guarantees are withdrawn or released, not deleted' USING ERRCODE = 'P0001';
    END IF;
    SELECT * INTO a FROM lending.loan_application WHERE tenant_id = NEW.tenant_id AND application_id = NEW.application_id;
    IF TG_OP = 'INSERT' THEN
        NEW.current_hold_id := COALESCE(NEW.current_hold_id, NEW.hold_id);
        NEW.locked_amount := COALESCE(NEW.locked_amount, NEW.amount);
        SELECT * INTO h FROM savings.account_hold WHERE tenant_id = NEW.tenant_id AND hold_id = NEW.hold_id;
        IF NEW.status <> 'PLEDGED' OR a.status <> 'SUBMITTED' OR NEW.current_hold_id <> NEW.hold_id
           OR NEW.locked_amount <> NEW.amount OR NEW.recovered <> 0 THEN
            RAISE EXCEPTION 'GUARANTEE_INVALID: guarantees are pledged on an open application' USING ERRCODE = 'P0001';
        END IF;
        IF NEW.guarantor_customer_id = a.customer_id THEN
            RAISE EXCEPTION 'GUARANTEE_SELF: a member cannot guarantee their own loan' USING ERRCODE = 'P0001';
        END IF;
        IF (SELECT status FROM customer.customer WHERE tenant_id = NEW.tenant_id AND customer_id = NEW.guarantor_customer_id) <> 'ACTIVE' THEN
            RAISE EXCEPTION 'GUARANTEE_INVALID: the guarantor must be an active member' USING ERRCODE = 'P0001';
        END IF;
        SELECT customer_id, status INTO v_owner, v_account_status FROM savings.deposit_account
         WHERE tenant_id = NEW.tenant_id AND account_id = NEW.savings_account_id;
        IF v_owner IS DISTINCT FROM NEW.guarantor_customer_id OR v_account_status <> 'ACTIVE' THEN
            RAISE EXCEPTION 'GUARANTEE_INVALID: the guarantee is locked on one of the guarantor''s own active savings accounts'
                USING ERRCODE = 'P0001';
        END IF;
        IF h.status IS DISTINCT FROM 'ACTIVE' OR h.kind <> 'EARMARK' OR h.account_id <> NEW.savings_account_id
           OR h.amount <> NEW.amount THEN
            RAISE EXCEPTION 'GUARANTEE_INVALID: the guarantee needs an active EARMARK hold of % on its account', NEW.amount
                USING ERRCODE = 'P0001';
        END IF;
        PERFORM savings.assert_available(NEW.tenant_id, NEW.savings_account_id, 0);
        RETURN NEW;
    END IF;
    IF OLD.status <> 'PLEDGED'
       OR (NEW.guarantee_id, NEW.idempotency_key, NEW.application_id, NEW.guarantor_customer_id, NEW.savings_account_id, NEW.amount,
           NEW.consent_reference, NEW.hold_id, NEW.pledged_by, NEW.pledged_at)
          IS DISTINCT FROM (OLD.guarantee_id, OLD.idempotency_key, OLD.application_id, OLD.guarantor_customer_id, OLD.savings_account_id,
           OLD.amount, OLD.consent_reference, OLD.hold_id, OLD.pledged_by, OLD.pledged_at) THEN
        RAISE EXCEPTION 'GUARANTEE_IMMUTABLE: a guarantee ends once and its terms never change' USING ERRCODE = 'P0001';
    END IF;
    SELECT * INTO h FROM savings.account_hold WHERE tenant_id = NEW.tenant_id AND hold_id = OLD.current_hold_id;
    -- GC2: a smaller lock, or a call using some or all of it
    IF NEW.locked_amount <> OLD.locked_amount OR NEW.recovered <> OLD.recovered OR NEW.current_hold_id <> OLD.current_hold_id
       OR NEW.status = 'CALLED' THEN
        IF NEW.status NOT IN ('PLEDGED', 'CALLED') OR NEW.locked_amount > OLD.locked_amount OR NEW.recovered < OLD.recovered
           OR (NEW.status = 'CALLED') <> (NEW.locked_amount = 0 AND NEW.recovered > OLD.recovered)
           OR (SELECT status FROM lending.loan WHERE tenant_id = a.tenant_id AND loan_id = a.loan_id) IS DISTINCT FROM 'ACTIVE' THEN
            RAISE EXCEPTION 'GUARANTEE_IMMUTABLE: a lock only goes down while the loan is active, and a guarantee is CALLED when used up'
                USING ERRCODE = 'P0001';
        END IF;
        IF NEW.recovered > OLD.recovered AND NOT EXISTS (
                SELECT 1 FROM lending.guarantor_call_line l JOIN lending.guarantor_call c ON c.tenant_id = l.tenant_id AND c.call_id = l.call_id
                 WHERE l.tenant_id = NEW.tenant_id AND l.guarantee_id = NEW.guarantee_id AND c.status = 'APPROVED'
                   AND l.amount = NEW.recovered - OLD.recovered AND NOT l.applied) THEN
            RAISE EXCEPTION 'GUARANTEE_IMMUTABLE: money is recovered from a guarantee only by an approved call' USING ERRCODE = 'P0001';
        END IF;
        IF NEW.locked_amount > 0 THEN
            SELECT * INTO n FROM savings.account_hold WHERE tenant_id = NEW.tenant_id AND hold_id = NEW.current_hold_id;
            IF NEW.current_hold_id = OLD.current_hold_id AND NEW.locked_amount <> OLD.locked_amount THEN
                RAISE EXCEPTION 'GUARANTEE_INVALID: a new lock needs a new hold' USING ERRCODE = 'P0001';
            END IF;
            IF n.status IS DISTINCT FROM 'ACTIVE' OR n.kind <> 'EARMARK' OR n.account_id <> NEW.savings_account_id
               OR n.amount <> NEW.locked_amount THEN
                RAISE EXCEPTION 'GUARANTEE_INVALID: the new lock needs an active EARMARK hold of % on the guarantor''s account',
                    NEW.locked_amount USING ERRCODE = 'P0001';
            END IF;
        ELSIF NEW.current_hold_id <> OLD.current_hold_id THEN
            RAISE EXCEPTION 'GUARANTEE_INVALID: a used-up guarantee keeps its last hold' USING ERRCODE = 'P0001';
        END IF;
        IF NEW.current_hold_id <> OLD.current_hold_id OR NEW.locked_amount = 0 THEN
            IF h.status = 'ACTIVE' THEN
                UPDATE savings.account_hold SET status = 'RELEASED' WHERE tenant_id = NEW.tenant_id AND hold_id = OLD.current_hold_id;
            END IF;
        END IF;
        IF NEW.status = 'CALLED' THEN
            NEW.ended_at := clock_timestamp();
        END IF;
        RETURN NEW;
    END IF;
    -- withdraw or release
    IF NEW.status = 'WITHDRAWN' AND a.status <> 'SUBMITTED' THEN
        RAISE EXCEPTION 'GUARANTEE_LOCKED: the application is %; the guarantee holds until the loan closes', a.status
            USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status = 'RELEASED' AND NOT (a.status IN ('DECLINED', 'WITHDRAWN')
            OR (SELECT status FROM lending.loan WHERE tenant_id = a.tenant_id AND loan_id = a.loan_id) = 'CLOSED') THEN
        RAISE EXCEPTION 'GUARANTEE_LOCKED: released only when the application ends or the loan closes' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status NOT IN ('WITHDRAWN', 'RELEASED') THEN
        RAISE EXCEPTION 'GUARANTEE_IMMUTABLE: % is not a way for a guarantee to end', NEW.status USING ERRCODE = 'P0001';
    END IF;
    IF h.status = 'ACTIVE' THEN
        UPDATE savings.account_hold SET status = 'RELEASED' WHERE tenant_id = NEW.tenant_id AND hold_id = OLD.current_hold_id;
    END IF;
    NEW.ended_at := clock_timestamp();
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION lending.guard_guarantee_hold() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    -- the guarantee's own trigger releases it (nested, depth > 1); nothing else may
    IF OLD.status = 'ACTIVE' AND NEW.status <> 'ACTIVE' AND pg_trigger_depth() = 1 AND EXISTS (
            SELECT 1 FROM lending.loan_guarantee g WHERE g.tenant_id = NEW.tenant_id AND g.current_hold_id = NEW.hold_id
               AND g.status = 'PLEDGED') THEN
        RAISE EXCEPTION 'GUARANTEE_LOCKED: hold % secures a loan guarantee', NEW.hold_id USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;

-- GC3
CREATE TABLE lending.guarantor_call (
    tenant_id       uuid          NOT NULL REFERENCES tenancy.tenant (tenant_id),
    call_id         uuid          NOT NULL,
    idempotency_key varchar(120)  NOT NULL,
    loan_id         uuid          NOT NULL,
    days_past_due   integer       NOT NULL,
    amount          numeric(19,4) NOT NULL CHECK (amount > 0),
    reason          varchar(300)  NOT NULL CHECK (length(btrim(reason)) >= 10),
    status          varchar(16)   NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED')),
    requested_by    varchar(100)  NOT NULL,
    requested_at    timestamptz   NOT NULL DEFAULT clock_timestamp(),
    decided_by      varchar(100),
    decided_at      timestamptz,
    decision_reason varchar(300),
    business_date   date,
    PRIMARY KEY (tenant_id, call_id),
    CONSTRAINT guarantor_call_idempotency UNIQUE (tenant_id, idempotency_key),
    FOREIGN KEY (tenant_id, loan_id) REFERENCES lending.loan (tenant_id, loan_id),
    CHECK ((status = 'REQUESTED') = (decided_by IS NULL)),
    CHECK ((status = 'APPROVED') = (business_date IS NOT NULL)),
    CHECK (status <> 'REJECTED' OR length(btrim(COALESCE(decision_reason, ''))) >= 3)
);
CREATE UNIQUE INDEX guarantor_call_one_open ON lending.guarantor_call (tenant_id, loan_id) WHERE status = 'REQUESTED';

CREATE TABLE lending.guarantor_call_line (
    tenant_id         uuid          NOT NULL,
    call_id           uuid          NOT NULL,
    guarantee_id      uuid          NOT NULL,
    amount            numeric(19,4) NOT NULL CHECK (amount > 0 AND amount = round(amount, 2)),
    applied           boolean       NOT NULL DEFAULT false,
    transfer_entry_id uuid,
    repayment_id      uuid,
    PRIMARY KEY (tenant_id, call_id, guarantee_id),
    FOREIGN KEY (tenant_id, call_id) REFERENCES lending.guarantor_call (tenant_id, call_id),
    FOREIGN KEY (tenant_id, guarantee_id) REFERENCES lending.loan_guarantee (tenant_id, guarantee_id),
    FOREIGN KEY (tenant_id, transfer_entry_id) REFERENCES ledger.journal_entry (tenant_id, entry_id),
    FOREIGN KEY (tenant_id, repayment_id) REFERENCES lending.repayment (tenant_id, repayment_id),
    CHECK (applied = (transfer_entry_id IS NOT NULL AND repayment_id IS NOT NULL))
);
CREATE INDEX guarantor_call_line_entry_idx ON lending.guarantor_call_line (tenant_id, transfer_entry_id) WHERE transfer_entry_id IS NOT NULL;

CREATE FUNCTION lending.guarantor_call_due(p_tenant uuid, p_loan uuid) RETURNS integer
    LANGUAGE sql STABLE
AS $$
    SELECT COALESCE((SELECT days_past_due FROM lending.arrears_snapshot WHERE tenant_id = p_tenant AND loan_id = p_loan
                      ORDER BY as_of DESC LIMIT 1), 0);
$$;

CREATE FUNCTION lending.guard_guarantor_call() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    s lending.guarantee_settings%ROWTYPE;
    v_status varchar;
    v_dpd int;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'GUARANTOR_CALL_IMMUTABLE: calls are rejected, not deleted' USING ERRCODE = 'P0001';
    END IF;
    SELECT * INTO s FROM lending.guarantee_settings WHERE tenant_id = NEW.tenant_id;
    SELECT status INTO v_status FROM lending.loan WHERE tenant_id = NEW.tenant_id AND loan_id = NEW.loan_id;
    v_dpd := lending.guarantor_call_due(NEW.tenant_id, NEW.loan_id);
    IF TG_OP = 'INSERT' THEN
        IF s.tenant_id IS NULL THEN
            RAISE EXCEPTION 'GUARANTEE_SETTINGS_MISSING: finance must set up guarantor calls first' USING ERRCODE = 'P0001';
        END IF;
        IF NEW.status <> 'REQUESTED' OR v_status <> 'ACTIVE' OR NEW.days_past_due <> v_dpd OR v_dpd < s.min_days_past_due THEN
            RAISE EXCEPTION 'GUARANTOR_CALL_INVALID: guarantors are called on an active loan at least % days past due (this one: %)',
                s.min_days_past_due, v_dpd USING ERRCODE = 'P0001';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status <> 'REQUESTED'
       OR (NEW.call_id, NEW.idempotency_key, NEW.loan_id, NEW.days_past_due, NEW.amount, NEW.reason, NEW.requested_by, NEW.requested_at)
          IS DISTINCT FROM (OLD.call_id, OLD.idempotency_key, OLD.loan_id, OLD.days_past_due, OLD.amount, OLD.reason, OLD.requested_by,
           OLD.requested_at) THEN
        RAISE EXCEPTION 'GUARANTOR_CALL_IMMUTABLE: a call is decided once' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.decided_by = NEW.requested_by THEN
        RAISE EXCEPTION 'GUARANTOR_CALL_SAME_PERSON: the person who asked cannot decide' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status = 'APPROVED' THEN
        IF v_status <> 'ACTIVE' OR NEW.business_date <> (SELECT current_date_ FROM tenancy.business_date WHERE tenant_id = NEW.tenant_id) THEN
            RAISE EXCEPTION 'GUARANTOR_CALL_INVALID: approved on the business date while the loan is active' USING ERRCODE = 'P0001';
        END IF;
        IF NEW.amount > lending.loan_unpaid(NEW.tenant_id, NEW.loan_id) - lending.loan_credit(NEW.tenant_id, NEW.loan_id) THEN
            RAISE EXCEPTION 'GUARANTOR_CALL_TOO_MUCH: % is more than the % still owed', NEW.amount,
                lending.loan_unpaid(NEW.tenant_id, NEW.loan_id) - lending.loan_credit(NEW.tenant_id, NEW.loan_id) USING ERRCODE = 'P0001';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER guarantor_call_guard BEFORE INSERT OR UPDATE OR DELETE ON lending.guarantor_call
    FOR EACH ROW EXECUTE FUNCTION lending.guard_guarantor_call();

CREATE FUNCTION lending.guard_guarantor_call_line() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    c lending.guarantor_call%ROWTYPE;
    g lending.loan_guarantee%ROWTYPE;
    v_loan uuid;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'GUARANTOR_CALL_IMMUTABLE: call lines are never deleted' USING ERRCODE = 'P0001';
    END IF;
    SELECT * INTO c FROM lending.guarantor_call WHERE tenant_id = NEW.tenant_id AND call_id = NEW.call_id;
    SELECT * INTO g FROM lending.loan_guarantee WHERE tenant_id = NEW.tenant_id AND guarantee_id = NEW.guarantee_id;
    SELECT loan_id INTO v_loan FROM lending.loan_application WHERE tenant_id = g.tenant_id AND application_id = g.application_id;
    IF TG_OP = 'INSERT' THEN
        IF c.status <> 'REQUESTED' OR v_loan IS DISTINCT FROM c.loan_id OR g.status <> 'PLEDGED' OR NEW.amount > g.locked_amount
           OR NEW.applied THEN
            RAISE EXCEPTION 'GUARANTOR_CALL_INVALID: a line takes no more than the open lock of one of the loan''s guarantees'
                USING ERRCODE = 'P0001';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.applied OR NOT NEW.applied OR (NEW.call_id, NEW.guarantee_id, NEW.amount) IS DISTINCT FROM (OLD.call_id, OLD.guarantee_id, OLD.amount)
       OR c.status <> 'APPROVED' THEN
        RAISE EXCEPTION 'GUARANTOR_CALL_IMMUTABLE: a line is applied once, when its call is approved' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER guarantor_call_line_guard BEFORE INSERT OR UPDATE OR DELETE ON lending.guarantor_call_line
    FOR EACH ROW EXECUTE FUNCTION lending.guard_guarantor_call_line();

-- GC3, GC4 (checked at commit)
CREATE FUNCTION lending.check_guarantor_call() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    s lending.guarantee_settings%ROWTYPE;
    v_total numeric;
    v_bad int;
BEGIN
    SELECT * INTO s FROM lending.guarantee_settings WHERE tenant_id = NEW.tenant_id;
    SELECT COALESCE(sum(amount), 0) INTO v_total FROM lending.guarantor_call_line WHERE tenant_id = NEW.tenant_id AND call_id = NEW.call_id;
    IF v_total <> NEW.amount THEN
        RAISE EXCEPTION 'GUARANTOR_CALL_MISMATCH: lines total % for a call of %', v_total, NEW.amount USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status = 'APPROVED' THEN
        -- every line applied: the guarantor debited into clearing, and the same amount repaid from clearing
        SELECT count(*) INTO v_bad FROM lending.guarantor_call_line l
          JOIN lending.loan_guarantee g ON g.tenant_id = l.tenant_id AND g.guarantee_id = l.guarantee_id
          LEFT JOIN lending.repayment r ON r.tenant_id = l.tenant_id AND r.repayment_id = l.repayment_id
         WHERE l.tenant_id = NEW.tenant_id AND l.call_id = NEW.call_id
           AND (NOT l.applied
                OR r.loan_id IS DISTINCT FROM NEW.loan_id OR r.amount <> l.amount OR r.channel <> 'INTERNAL'
                OR r.provider_ref <> 'GUARANTOR:' || NEW.call_id || ':' || l.guarantee_id
                OR COALESCE((SELECT sum(CASE side WHEN 'D' THEN amount ELSE -amount END) FROM ledger.journal_line
                              WHERE tenant_id = l.tenant_id AND entry_id = l.transfer_entry_id
                                AND subledger_account_id = g.savings_account_id), 0) <> l.amount
                OR COALESCE((SELECT sum(CASE side WHEN 'C' THEN amount ELSE -amount END) FROM ledger.journal_line
                              WHERE tenant_id = l.tenant_id AND entry_id = l.transfer_entry_id
                                AND account_code = s.recovery_clearing), 0) <> l.amount
                OR (SELECT count(*) FROM ledger.journal_line WHERE tenant_id = l.tenant_id AND entry_id = l.transfer_entry_id) <> 2
                OR COALESCE((SELECT sum(CASE side WHEN 'D' THEN amount ELSE -amount END) FROM ledger.journal_line
                              WHERE tenant_id = l.tenant_id AND entry_id = r.journal_entry_id
                                AND account_code = s.recovery_clearing), 0) <> l.amount);
        IF v_bad > 0 THEN
            RAISE EXCEPTION 'GUARANTOR_CALL_MISMATCH: % line(s) not moved exactly from the guarantor to the loan', v_bad USING ERRCODE = 'P0001';
        END IF;
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER guarantor_call_settled AFTER INSERT OR UPDATE ON lending.guarantor_call
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION lending.check_guarantor_call();

-- GC1: only guarantor recoveries post to the clearing account
CREATE FUNCTION lending.check_recovery_clearing_line() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM lending.guarantee_settings WHERE tenant_id = NEW.tenant_id AND recovery_clearing = NEW.account_code)
       AND NOT EXISTS (SELECT 1 FROM lending.guarantor_call_line WHERE tenant_id = NEW.tenant_id AND transfer_entry_id = NEW.entry_id)
       AND NOT EXISTS (SELECT 1 FROM lending.repayment r JOIN lending.guarantor_call_line l
                          ON l.tenant_id = r.tenant_id AND l.repayment_id = r.repayment_id
                        WHERE r.tenant_id = NEW.tenant_id AND r.journal_entry_id = NEW.entry_id) THEN
        RAISE EXCEPTION 'GUARANTEE_DIRECT_POSTING: % carries guarantor recoveries only', NEW.account_code USING ERRCODE = 'P0001';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER journal_line_recovery_clearing AFTER INSERT ON ledger.journal_line
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION lending.check_recovery_clearing_line();

CREATE TRIGGER guarantee_settings_guard BEFORE INSERT OR UPDATE ON lending.guarantee_settings
    FOR EACH ROW EXECUTE FUNCTION lending.guard_guarantee_settings();

-- locks, not pledges, count against a guarantor's own cover and exit
CREATE OR REPLACE FUNCTION lending.loan_limit(p_tenant uuid, p_customer uuid, p_product varchar, p_exclude_loan uuid,
                                              OUT savings numeric, OUT pledged_for_others numeric, OUT shares numeric,
                                              OUT outstanding numeric, OUT own_cover numeric, OUT savings_multiple numeric,
                                              OUT limit_amount numeric, OUT guarantees_required boolean)
    RETURNS SETOF record
    LANGUAGE plpgsql STABLE
AS $$
DECLARE
    r lending.loan_limit_rule%ROWTYPE;
BEGIN
    SELECT * INTO r FROM lending.loan_limit_rule WHERE tenant_id = p_tenant AND product_code = p_product;
    IF NOT FOUND THEN
        RETURN;
    END IF;
    SELECT COALESCE(sum(b.ledger_balance), 0) INTO savings FROM savings.deposit_account a
      JOIN savings.account_balance b ON b.tenant_id = a.tenant_id AND b.account_id = a.account_id
     WHERE a.tenant_id = p_tenant AND a.customer_id = p_customer AND a.status <> 'CLOSED';
    SELECT COALESCE(sum(locked_amount), 0) INTO pledged_for_others FROM lending.loan_guarantee
     WHERE tenant_id = p_tenant AND guarantor_customer_id = p_customer AND status = 'PLEDGED';
    shares := CASE WHEN r.include_shares THEN shares.holding(p_tenant, p_customer, NULL) ELSE 0 END;
    SELECT COALESCE(sum(lending.loan_principal_outstanding(tenant_id, loan_id)), 0) INTO outstanding FROM lending.loan
     WHERE tenant_id = p_tenant AND customer_id = p_customer AND status IN ('APPROVED', 'ACTIVE')
       AND loan_id IS DISTINCT FROM p_exclude_loan;
    own_cover := GREATEST(savings - pledged_for_others + shares, 0);
    savings_multiple := r.savings_multiple;
    limit_amount := GREATEST(round(r.savings_multiple * own_cover, 2) - CASE WHEN r.deduct_outstanding THEN outstanding ELSE 0 END, 0);
    guarantees_required := r.guarantees_required;
    RETURN NEXT;
END $$;

DO $$
BEGIN
    -- membership.blockers (V40) sums pledges; it now sums the current locks
    EXECUTE replace(pg_get_functiondef('membership.blockers(uuid, uuid)'::regprocedure),
        'SELECT count(*), COALESCE(sum(amount), 0) INTO n, v_amount FROM lending.loan_guarantee',
        'SELECT count(*), COALESCE(sum(locked_amount), 0) INTO n, v_amount FROM lending.loan_guarantee');
    EXECUTE replace(pg_get_functiondef('membership.blockers(uuid, uuid)'::regprocedure),
        'AND NOT EXISTS (SELECT 1 FROM lending.loan_guarantee g WHERE g.tenant_id = h.tenant_id AND g.hold_id = h.hold_id)',
        'AND NOT EXISTS (SELECT 1 FROM lending.loan_guarantee g WHERE g.tenant_id = h.tenant_id AND g.current_hold_id = h.hold_id)');
END $$;

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
        OR EXISTS (SELECT 1 FROM membership.exit_settings WHERE tenant_id = p_tenant AND gl_refunds_payable = p_code)
        OR EXISTS (SELECT 1 FROM lending.guarantee_settings WHERE tenant_id = p_tenant AND recovery_clearing = p_code)
$$;

ALTER TABLE lending.guarantee_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE lending.guarantee_settings FORCE ROW LEVEL SECURITY;
ALTER TABLE lending.guarantor_call ENABLE ROW LEVEL SECURITY;
ALTER TABLE lending.guarantor_call FORCE ROW LEVEL SECURITY;
ALTER TABLE lending.guarantor_call_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE lending.guarantor_call_line FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON lending.guarantee_settings
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
CREATE POLICY tenant_isolation ON lending.guarantor_call
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
CREATE POLICY tenant_isolation ON lending.guarantor_call_line
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
GRANT SELECT, INSERT, UPDATE ON lending.guarantee_settings TO mfi_app;
GRANT SELECT, INSERT, UPDATE ON lending.guarantor_call TO mfi_app;
GRANT SELECT, INSERT, UPDATE ON lending.guarantor_call_line TO mfi_app;
GRANT EXECUTE ON FUNCTION lending.guarantor_call_due(uuid, uuid) TO mfi_app;
