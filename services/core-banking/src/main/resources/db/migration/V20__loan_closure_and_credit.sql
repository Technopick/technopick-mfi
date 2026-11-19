-- =============================================================================
-- V20: Loan closure when fully repaid; overpaid credit applied to installments as they fall due.
--   P1  a loan closes only with a closure record, and only when every installment of its current
--       schedule is fully paid (principal, interest) and every penalty charged is paid
--   P2  the closure record keeps the credit (overpayment) still held for the customer
--   P3  a repayment is accepted only while the loan is ACTIVE when it is recorded; the
--       commit-time check also allows the loan to close in the same transaction
--   P4  credit applied (channel INTERNAL, reference CREDIT:...) never exceeds the credit the loan
--       holds: earlier overpayments less credit already applied, and it is fully allocated
-- =============================================================================

CREATE TABLE lending.loan_closure (
    tenant_id      uuid          NOT NULL,
    loan_id        uuid          NOT NULL,
    closed_on      date          NOT NULL,
    reason         varchar(16)   NOT NULL CHECK (reason IN ('REPAID')),
    credit_balance numeric(19,4) NOT NULL CHECK (credit_balance >= 0),
    closed_by      varchar(100)  NOT NULL,
    closed_at      timestamptz   NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, loan_id),
    FOREIGN KEY (tenant_id, loan_id) REFERENCES lending.loan (tenant_id, loan_id)
);

-- Unpaid amounts across the loan's whole current schedule, and penalties charged but unpaid.
CREATE FUNCTION lending.loan_unpaid(p_tenant uuid, p_loan uuid) RETURNS numeric
    LANGUAGE sql STABLE
AS $$
    WITH schedule AS (
        SELECT installment_no, principal, interest FROM lending.schedule_installment
         WHERE tenant_id = p_tenant AND loan_id = p_loan
           AND schedule_version = (SELECT max(schedule_version) FROM lending.schedule_installment
                                    WHERE tenant_id = p_tenant AND loan_id = p_loan)),
    paid AS (
        SELECT a.installment_no, a.component, sum(a.amount) AS amount
          FROM lending.repayment_allocation a
          JOIN lending.repayment r ON r.tenant_id = a.tenant_id AND r.repayment_id = a.repayment_id
         WHERE r.tenant_id = p_tenant AND r.loan_id = p_loan
         GROUP BY a.installment_no, a.component)
    SELECT COALESCE(sum(GREATEST(s.principal - COALESCE(pp.amount, 0), 0)
                      + GREATEST(s.interest - COALESCE(pi.amount, 0), 0)), 0)
         + GREATEST(COALESCE((SELECT sum(amount) FROM lending.penalty_charge
                               WHERE tenant_id = p_tenant AND loan_id = p_loan), 0)
                  - COALESCE((SELECT sum(amount) FROM paid WHERE component = 'PENALTY'), 0), 0)
      FROM schedule s
      LEFT JOIN paid pp ON pp.installment_no = s.installment_no AND pp.component = 'PRINCIPAL'
      LEFT JOIN paid pi ON pi.installment_no = s.installment_no AND pi.component = 'INTEREST'
$$;

-- Credit the loan holds: overpayments less credit already applied.
CREATE FUNCTION lending.loan_credit(p_tenant uuid, p_loan uuid) RETURNS numeric
    LANGUAGE sql STABLE
AS $$
    SELECT COALESCE(sum(unallocated), 0)
         - COALESCE(sum(amount) FILTER (WHERE channel = 'INTERNAL' AND provider_ref LIKE 'CREDIT:%'), 0)
      FROM lending.repayment WHERE tenant_id = p_tenant AND loan_id = p_loan
$$;

-- P1, P2 -------------------------------------------------------------------
CREATE FUNCTION lending.check_loan_closure() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_unpaid numeric;
BEGIN
    IF (SELECT status FROM lending.loan WHERE tenant_id = NEW.tenant_id AND loan_id = NEW.loan_id) <> 'ACTIVE' THEN
        RAISE EXCEPTION 'LENDING_LOAN_NOT_ACTIVE: only an ACTIVE loan can close' USING ERRCODE = 'P0001';
    END IF;
    v_unpaid := lending.loan_unpaid(NEW.tenant_id, NEW.loan_id);
    IF v_unpaid <> 0 THEN
        RAISE EXCEPTION 'LENDING_NOT_REPAID: % is still unpaid', v_unpaid USING ERRCODE = 'P0001';
    END IF;
    IF NEW.credit_balance <> lending.loan_credit(NEW.tenant_id, NEW.loan_id)
       OR NEW.closed_on <> (SELECT current_date_ FROM tenancy.business_date WHERE tenant_id = NEW.tenant_id) THEN
        RAISE EXCEPTION 'LENDING_CLOSURE_MISMATCH: closure must carry the credit held and the business date'
            USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER loan_closure_check BEFORE INSERT ON lending.loan_closure
    FOR EACH ROW EXECUTE FUNCTION lending.check_loan_closure();
CREATE TRIGGER loan_closure_immutable BEFORE UPDATE OR DELETE ON lending.loan_closure
    FOR EACH ROW EXECUTE FUNCTION ledger.reject_mutation();

CREATE FUNCTION lending.check_loan_closed() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'CLOSED' AND OLD.status <> 'CLOSED' AND (
           NOT EXISTS (SELECT 1 FROM lending.loan_closure WHERE tenant_id = NEW.tenant_id AND loan_id = NEW.loan_id)
           OR lending.loan_unpaid(NEW.tenant_id, NEW.loan_id) <> 0) THEN
        RAISE EXCEPTION 'LENDING_NOT_REPAID: a loan closes only when fully repaid, with its closure record'
            USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER loan_closed_check BEFORE UPDATE ON lending.loan
    FOR EACH ROW EXECUTE FUNCTION lending.check_loan_closed();

-- P3, P4 -------------------------------------------------------------------
CREATE FUNCTION lending.check_repayment_insert() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF (SELECT status FROM lending.loan WHERE tenant_id = NEW.tenant_id AND loan_id = NEW.loan_id) <> 'ACTIVE' THEN
        RAISE EXCEPTION 'LENDING_LOAN_NOT_ACTIVE: repayments need an ACTIVE loan' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.channel = 'INTERNAL' AND NEW.provider_ref LIKE 'CREDIT:%'
       AND (NEW.amount > lending.loan_credit(NEW.tenant_id, NEW.loan_id) OR NEW.unallocated <> 0) THEN
        RAISE EXCEPTION 'LENDING_CREDIT_EXCEEDED: % is more than the credit held (%) or not fully allocated',
            NEW.amount, lending.loan_credit(NEW.tenant_id, NEW.loan_id) USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER repayment_insert_check BEFORE INSERT ON lending.repayment
    FOR EACH ROW EXECUTE FUNCTION lending.check_repayment_insert();

CREATE OR REPLACE FUNCTION lending.check_repayment() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    r lending.repayment%ROWTYPE;
    v_alloc numeric;
    v_loan_status varchar;
BEGIN
    SELECT * INTO r FROM lending.repayment WHERE tenant_id = NEW.tenant_id AND repayment_id = NEW.repayment_id;
    SELECT COALESCE(sum(amount), 0) INTO v_alloc FROM lending.repayment_allocation
     WHERE tenant_id = r.tenant_id AND repayment_id = r.repayment_id;
    IF v_alloc + r.unallocated <> r.amount THEN
        RAISE EXCEPTION 'LENDING_ALLOCATION_MISMATCH: allocated % + unallocated % <> payment %',
            v_alloc, r.unallocated, r.amount USING ERRCODE = 'P0001';
    END IF;
    IF (SELECT COALESCE(sum(amount), 0) FROM ledger.journal_line
         WHERE tenant_id = r.tenant_id AND entry_id = r.journal_entry_id AND side = 'D' AND currency = r.currency)
       <> r.amount THEN
        RAISE EXCEPTION 'LENDING_REPAYMENT_ENTRY_MISMATCH: journal entry % does not total %',
            r.journal_entry_id, r.amount USING ERRCODE = 'P0001';
    END IF;
    SELECT status INTO v_loan_status FROM lending.loan WHERE tenant_id = r.tenant_id AND loan_id = r.loan_id;
    IF v_loan_status NOT IN ('ACTIVE', 'CLOSED') THEN
        RAISE EXCEPTION 'LENDING_LOAN_NOT_ACTIVE: repayments need an ACTIVE loan (is %)', v_loan_status
            USING ERRCODE = 'P0001';
    END IF;
    RETURN NULL;
END $$;

ALTER TABLE lending.loan_closure ENABLE ROW LEVEL SECURITY;
ALTER TABLE lending.loan_closure FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON lending.loan_closure
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
GRANT SELECT, INSERT ON lending.loan_closure TO mfi_app;
GRANT EXECUTE ON FUNCTION lending.loan_unpaid(uuid, uuid), lending.loan_credit(uuid, uuid) TO mfi_app;
