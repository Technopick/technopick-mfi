-- =============================================================================
-- V29 (increment 32, ADR-0032): early settlement of loans and refunds of loan credit.
--
-- Early settlement: when the credit a member has paid covers the payoff (principal outstanding,
-- interest accrued to the last close and still unpaid, unpaid penalties), the loan is settled in
-- one transaction. A new schedule version makes every unpaid instalment due today, with each
-- instalment's interest cut to what has accrued on it (or what was already paid on it, if more).
-- Scheduled interest that never accrued is not charged. The credit then pays everything and the
-- loan closes; whatever credit remains stays on the closed loan for refund.
--
-- Credit refunds: credit left on a closed loan is paid to one of the member's own savings
-- accounts, requested by one person and approved by another. Approval posts the refund (the
-- loan's overpayment account to the member's savings) and reduces the loan's credit.
-- =============================================================================
CREATE TABLE lending.settlement (
    tenant_id         uuid          NOT NULL,
    settlement_id     uuid          NOT NULL,
    idempotency_key   varchar(80)   NOT NULL,
    loan_id           uuid          NOT NULL,
    business_date     date          NOT NULL,
    schedule_version  int           NOT NULL CHECK (schedule_version >= 2),
    principal         numeric(19,2) NOT NULL CHECK (principal >= 0),
    interest          numeric(19,2) NOT NULL CHECK (interest >= 0),
    penalty           numeric(19,2) NOT NULL CHECK (penalty >= 0),
    payoff            numeric(19,2) NOT NULL CHECK (payoff > 0),
    interest_waived   numeric(19,2) NOT NULL CHECK (interest_waived >= 0),
    credit_left       numeric(19,2) NOT NULL CHECK (credit_left >= 0),
    repayment_id      uuid          NOT NULL,
    settled_by        varchar(100)  NOT NULL,
    settled_at        timestamptz   NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, settlement_id),
    UNIQUE (tenant_id, idempotency_key),
    UNIQUE (tenant_id, loan_id),
    FOREIGN KEY (tenant_id, loan_id) REFERENCES lending.loan (tenant_id, loan_id),
    FOREIGN KEY (tenant_id, repayment_id) REFERENCES lending.repayment (tenant_id, repayment_id),
    CHECK (payoff = principal + interest + penalty)
);

CREATE FUNCTION lending.guard_settlement() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'SETTLEMENT_IMMUTABLE: settlements are never changed or deleted' USING ERRCODE = 'P0001';
END $$;

CREATE TRIGGER settlement_guard BEFORE UPDATE OR DELETE ON lending.settlement
    FOR EACH ROW EXECUTE FUNCTION lending.guard_settlement();

-- At commit: the settlement closed the loan, its schedule version is the latest, every instalment
-- in it is due by the settlement date, and each instalment's interest is exactly what had accrued
-- on it (or what had been paid on it, if more): no unaccrued interest is charged.
CREATE FUNCTION lending.check_settlement() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_status text;
    v_latest int;
    v_bad int;
BEGIN
    SELECT status INTO v_status FROM lending.loan WHERE tenant_id = NEW.tenant_id AND loan_id = NEW.loan_id;
    IF v_status <> 'CLOSED' THEN
        RAISE EXCEPTION 'SETTLEMENT_INCOMPLETE: loan is % after settlement', v_status USING ERRCODE = 'P0001';
    END IF;
    SELECT max(schedule_version) INTO v_latest FROM lending.schedule_installment
     WHERE tenant_id = NEW.tenant_id AND loan_id = NEW.loan_id;
    IF v_latest <> NEW.schedule_version THEN
        RAISE EXCEPTION 'SETTLEMENT_SCHEDULE: settlement names schedule v% but the latest is v%', NEW.schedule_version, v_latest
            USING ERRCODE = 'P0001';
    END IF;
    SELECT count(*) INTO v_bad
      FROM lending.schedule_installment s
     WHERE s.tenant_id = NEW.tenant_id AND s.loan_id = NEW.loan_id AND s.schedule_version = NEW.schedule_version
       AND (s.due_date > NEW.business_date
            OR s.interest <> GREATEST(
                 COALESCE((SELECT sum(a.amount) FROM lending.loan_accrual a
                            WHERE a.tenant_id = s.tenant_id AND a.loan_id = s.loan_id AND a.installment_no = s.installment_no), 0),
                 COALESCE((SELECT sum(al.amount) FROM lending.repayment_allocation al
                             JOIN lending.repayment r ON r.tenant_id = al.tenant_id AND r.repayment_id = al.repayment_id
                            WHERE r.tenant_id = s.tenant_id AND r.loan_id = s.loan_id AND al.installment_no = s.installment_no
                              AND al.component = 'INTEREST'), 0)));
    IF v_bad > 0 THEN
        RAISE EXCEPTION 'SETTLEMENT_SCHEDULE: % instalment(s) not due by % or charging interest that never accrued',
            v_bad, NEW.business_date USING ERRCODE = 'P0001';
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER settlement_complete AFTER INSERT ON lending.settlement
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION lending.check_settlement();

-- At commit: a schedule version after the first exists only for a settlement.
CREATE FUNCTION lending.check_schedule_version() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.schedule_version > 1 AND NOT EXISTS (
            SELECT 1 FROM lending.settlement
             WHERE tenant_id = NEW.tenant_id AND loan_id = NEW.loan_id AND schedule_version = NEW.schedule_version) THEN
        RAISE EXCEPTION 'SCHEDULE_VERSION_UNEXPLAINED: schedule v% of a loan needs a settlement', NEW.schedule_version
            USING ERRCODE = 'P0001';
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER schedule_version_explained AFTER INSERT ON lending.schedule_installment
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION lending.check_schedule_version();

-- ---------------------------------------------------------------------------
-- Credit refunds
-- ---------------------------------------------------------------------------
CREATE TABLE lending.credit_refund (
    tenant_id          uuid          NOT NULL,
    refund_id          uuid          NOT NULL,
    idempotency_key    varchar(80)   NOT NULL,
    loan_id            uuid          NOT NULL,
    amount             numeric(19,2) NOT NULL CHECK (amount > 0),
    method             varchar(16)   NOT NULL CHECK (method IN ('SAVINGS')),
    deposit_account_id uuid          NOT NULL,
    reason             varchar(300)  NOT NULL CHECK (length(btrim(reason)) >= 10),
    status             varchar(16)   NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED')),
    requested_by       varchar(100)  NOT NULL,
    requested_at       timestamptz   NOT NULL DEFAULT clock_timestamp(),
    decided_by         varchar(100),
    decided_at         timestamptz,
    decision_reason    varchar(300),
    business_date      date,
    entry_id           uuid,
    PRIMARY KEY (tenant_id, refund_id),
    UNIQUE (tenant_id, idempotency_key),
    FOREIGN KEY (tenant_id, loan_id) REFERENCES lending.loan (tenant_id, loan_id),
    FOREIGN KEY (tenant_id, deposit_account_id) REFERENCES savings.deposit_account (tenant_id, account_id),
    FOREIGN KEY (tenant_id, entry_id) REFERENCES ledger.journal_entry (tenant_id, entry_id),
    CHECK (decided_by IS NULL OR decided_by <> requested_by),
    CHECK ((status = 'REQUESTED') = (decided_by IS NULL)),
    CHECK ((status = 'APPROVED') = (entry_id IS NOT NULL)),
    CHECK ((status = 'APPROVED') = (business_date IS NOT NULL))
);
CREATE UNIQUE INDEX credit_refund_one_open ON lending.credit_refund (tenant_id, loan_id) WHERE status = 'REQUESTED';

-- Credit left on a loan: unallocated money received, less credit used to pay instalments, less refunds made.
CREATE OR REPLACE FUNCTION lending.loan_credit(p_tenant uuid, p_loan uuid) RETURNS numeric
    LANGUAGE sql STABLE
AS $$
    SELECT COALESCE((SELECT sum(unallocated)
                         - COALESCE(sum(amount) FILTER (WHERE channel = 'INTERNAL' AND provider_ref LIKE 'CREDIT:%'), 0)
                       FROM lending.repayment WHERE tenant_id = p_tenant AND loan_id = p_loan), 0)
         - COALESCE((SELECT sum(amount) FROM lending.credit_refund
                      WHERE tenant_id = p_tenant AND loan_id = p_loan AND status = 'APPROVED'), 0)
$$;

-- Requests and decisions follow the rules; at commit an approval left the loan's credit at zero
-- or more, and its entry moved exactly the refund from the loan's overpayment account to the
-- member's own savings account.
CREATE FUNCTION lending.check_credit_refund() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_loan lending.loan%ROWTYPE;
    v_account savings.deposit_account%ROWTYPE;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'REFUND_IMMUTABLE: refunds are never deleted' USING ERRCODE = 'P0001';
    END IF;
    IF TG_OP = 'INSERT' AND NEW.status <> 'REQUESTED' THEN
        RAISE EXCEPTION 'REFUND_NOT_ALLOWED: a refund starts as a request' USING ERRCODE = 'P0001';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF OLD.status <> 'REQUESTED' THEN
            RAISE EXCEPTION 'REFUND_IMMUTABLE: refund already %', OLD.status USING ERRCODE = 'P0001';
        END IF;
        IF (NEW.loan_id, NEW.amount, NEW.method, NEW.deposit_account_id, NEW.reason, NEW.requested_by, NEW.idempotency_key)
           IS DISTINCT FROM (OLD.loan_id, OLD.amount, OLD.method, OLD.deposit_account_id, OLD.reason, OLD.requested_by,
                             OLD.idempotency_key) THEN
            RAISE EXCEPTION 'REFUND_IMMUTABLE: only the decision is recorded later' USING ERRCODE = 'P0001';
        END IF;
    END IF;
    SELECT * INTO v_loan FROM lending.loan WHERE tenant_id = NEW.tenant_id AND loan_id = NEW.loan_id;
    SELECT * INTO v_account FROM savings.deposit_account WHERE tenant_id = NEW.tenant_id AND account_id = NEW.deposit_account_id;
    IF NEW.status IN ('REQUESTED', 'APPROVED') THEN
        IF v_loan.status <> 'CLOSED' THEN
            RAISE EXCEPTION 'REFUND_NOT_ALLOWED: only credit on a closed loan is refunded (loan is %)', v_loan.status
                USING ERRCODE = 'P0001';
        END IF;
        IF v_loan.customer_id IS NULL OR v_account.customer_id IS DISTINCT FROM v_loan.customer_id
           OR v_account.status IS DISTINCT FROM 'ACTIVE' OR v_account.currency IS DISTINCT FROM v_loan.currency THEN
            RAISE EXCEPTION 'REFUND_ACCOUNT_INVALID: % must be an active % savings account of the same member',
                v_account.account_number, v_loan.currency USING ERRCODE = 'P0001';
        END IF;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER credit_refund_rules BEFORE INSERT OR UPDATE OR DELETE ON lending.credit_refund
    FOR EACH ROW EXECUTE FUNCTION lending.check_credit_refund();

CREATE FUNCTION lending.check_credit_refund_posting() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_overpayment text;
    v_control text;
    v_out numeric;
    v_in numeric;
BEGIN
    IF NEW.status <> 'APPROVED' THEN
        RETURN NULL;
    END IF;
    IF lending.loan_credit(NEW.tenant_id, NEW.loan_id) < 0 THEN
        RAISE EXCEPTION 'REFUND_EXCEEDS_CREDIT: refund % leaves the loan''s credit at %', NEW.amount,
            lending.loan_credit(NEW.tenant_id, NEW.loan_id) USING ERRCODE = 'P0001';
    END IF;
    SELECT v.gl_overpayment INTO v_overpayment FROM lending.loan l
      JOIN lending.loan_product_version v ON v.tenant_id = l.tenant_id AND v.version_id = l.product_version_id
     WHERE l.tenant_id = NEW.tenant_id AND l.loan_id = NEW.loan_id;
    SELECT gl_control_account INTO v_control FROM savings.deposit_account
     WHERE tenant_id = NEW.tenant_id AND account_id = NEW.deposit_account_id;
    SELECT COALESCE(sum(amount) FILTER (WHERE side = 'D' AND account_code = v_overpayment), 0),
           COALESCE(sum(amount) FILTER (WHERE side = 'C' AND account_code = v_control AND subledger_account_id = NEW.deposit_account_id), 0)
      INTO v_out, v_in
      FROM ledger.journal_line WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id;
    IF v_out <> NEW.amount OR v_in <> NEW.amount THEN
        RAISE EXCEPTION 'REFUND_POSTING_MISMATCH: refund % but entry moves % out of % and % into the member''s account',
            NEW.amount, v_out, v_overpayment, v_in USING ERRCODE = 'P0001';
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER credit_refund_posted AFTER INSERT OR UPDATE ON lending.credit_refund
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION lending.check_credit_refund_posting();

ALTER TABLE lending.settlement ENABLE ROW LEVEL SECURITY;
ALTER TABLE lending.settlement FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON lending.settlement
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
ALTER TABLE lending.credit_refund ENABLE ROW LEVEL SECURITY;
ALTER TABLE lending.credit_refund FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON lending.credit_refund
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());

GRANT SELECT, INSERT ON lending.settlement TO mfi_app;
GRANT SELECT, INSERT, UPDATE ON lending.credit_refund TO mfi_app;
