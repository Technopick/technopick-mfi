-- =============================================================================
-- V21: Loan write-off with maker-checker, and recoveries after write-off.
--   X1  a write-off is requested for an ACTIVE loan that holds no credit and whose latest
--       arrears snapshot is at least the tenant's minimum days past due (no policy = no write-off);
--       at most one PENDING or APPROVED write-off per loan
--   X2  a different person decides; a rejection needs a reason; decisions are final
--   X3  approval records the amounts outstanding at that moment (principal, interest receivable,
--       penalties unpaid) and an entry that debits write-off expense and credits exactly those
--       receivables; the loan becomes WRITTEN_OFF in the same transaction, and only that way
--   X4  a recovery is recorded only for a WRITTEN_OFF loan, never beyond what was written off, and
--       its entry credits the product's recovery account by exactly the amount
-- =============================================================================

ALTER TABLE lending.loan_product_version
    ADD COLUMN gl_write_off_recovery varchar(40),
    ADD FOREIGN KEY (tenant_id, gl_write_off_recovery) REFERENCES ledger.gl_account (tenant_id, account_code);

CREATE TABLE lending.write_off_policy (
    tenant_id          uuid PRIMARY KEY REFERENCES tenancy.tenant (tenant_id),
    min_days_past_due  int  NOT NULL CHECK (min_days_past_due >= 0)
);

CREATE TABLE lending.write_off (
    tenant_id        uuid          NOT NULL,
    write_off_id     uuid          NOT NULL,
    idempotency_key  varchar(80)   NOT NULL,
    loan_id          uuid          NOT NULL,
    status           varchar(16)   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    reason           varchar(300)  NOT NULL CHECK (length(btrim(reason)) >= 10),
    days_past_due    int           NOT NULL,
    requested_by     varchar(100)  NOT NULL,
    requested_at     timestamptz   NOT NULL DEFAULT clock_timestamp(),
    decided_by       varchar(100),
    decided_at       timestamptz,
    decision_reason  varchar(300),
    principal        numeric(19,4) CHECK (principal >= 0),
    interest         numeric(19,4) CHECK (interest >= 0),
    penalty          numeric(19,4) CHECK (penalty >= 0),
    entry_id         uuid,
    PRIMARY KEY (tenant_id, write_off_id),
    CONSTRAINT write_off_key UNIQUE (tenant_id, idempotency_key),
    FOREIGN KEY (tenant_id, loan_id) REFERENCES lending.loan (tenant_id, loan_id),
    FOREIGN KEY (tenant_id, entry_id) REFERENCES ledger.journal_entry (tenant_id, entry_id),
    CHECK (decided_by IS NULL OR decided_by <> requested_by),
    CHECK ((status = 'PENDING') = (decided_by IS NULL)),
    CHECK (status <> 'REJECTED' OR length(btrim(COALESCE(decision_reason, ''))) >= 3),
    CHECK ((status = 'APPROVED') = (entry_id IS NOT NULL AND principal IS NOT NULL AND interest IS NOT NULL
                                    AND penalty IS NOT NULL))
);
CREATE UNIQUE INDEX write_off_one_live ON lending.write_off (tenant_id, loan_id) WHERE status <> 'REJECTED';

CREATE TABLE lending.write_off_recovery (
    tenant_id     uuid          NOT NULL,
    recovery_id   uuid          NOT NULL,
    loan_id       uuid          NOT NULL,
    amount        numeric(19,4) NOT NULL CHECK (amount > 0),
    channel       varchar(24)   NOT NULL CHECK (channel IN ('MPESA_C2B', 'MPESA_STK', 'TELLER', 'BANK')),
    provider_ref  varchar(100)  NOT NULL,
    entry_id      uuid          NOT NULL,
    recorded_by   varchar(100)  NOT NULL,
    recorded_at   timestamptz   NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, recovery_id),
    CONSTRAINT write_off_recovery_once UNIQUE (tenant_id, channel, provider_ref),
    CONSTRAINT write_off_recovery_entry UNIQUE (tenant_id, entry_id),
    FOREIGN KEY (tenant_id, loan_id) REFERENCES lending.loan (tenant_id, loan_id),
    FOREIGN KEY (tenant_id, entry_id) REFERENCES ledger.journal_entry (tenant_id, entry_id)
);

-- Interest receivable of a loan: per installment, interest accrued less interest paid (never below 0).
CREATE FUNCTION lending.loan_interest_receivable(p_tenant uuid, p_loan uuid) RETURNS numeric
    LANGUAGE sql STABLE
AS $$
    SELECT COALESCE(sum(GREATEST(COALESCE(a.accrued, 0) - COALESCE(p.paid, 0), 0)), 0)
      FROM (SELECT installment_no, sum(amount) AS accrued FROM lending.loan_accrual
             WHERE tenant_id = p_tenant AND loan_id = p_loan GROUP BY installment_no) a
      FULL JOIN (SELECT al.installment_no, sum(al.amount) AS paid
                   FROM lending.repayment_allocation al
                   JOIN lending.repayment r ON r.tenant_id = al.tenant_id AND r.repayment_id = al.repayment_id
                  WHERE r.tenant_id = p_tenant AND r.loan_id = p_loan AND al.component = 'INTEREST'
                  GROUP BY al.installment_no) p ON p.installment_no = a.installment_no
$$;

CREATE FUNCTION lending.loan_principal_outstanding(p_tenant uuid, p_loan uuid) RETURNS numeric
    LANGUAGE sql STABLE
AS $$
    SELECT l.principal - COALESCE((SELECT sum(al.amount) FROM lending.repayment_allocation al
                                     JOIN lending.repayment r ON r.tenant_id = al.tenant_id AND r.repayment_id = al.repayment_id
                                    WHERE r.tenant_id = p_tenant AND r.loan_id = p_loan AND al.component = 'PRINCIPAL'), 0)
      FROM lending.loan l WHERE l.tenant_id = p_tenant AND l.loan_id = p_loan
$$;

CREATE FUNCTION lending.loan_penalty_unpaid(p_tenant uuid, p_loan uuid) RETURNS numeric
    LANGUAGE sql STABLE
AS $$
    SELECT GREATEST(COALESCE((SELECT sum(amount) FROM lending.penalty_charge WHERE tenant_id = p_tenant AND loan_id = p_loan), 0)
                  - COALESCE((SELECT sum(al.amount) FROM lending.repayment_allocation al
                                JOIN lending.repayment r ON r.tenant_id = al.tenant_id AND r.repayment_id = al.repayment_id
                               WHERE r.tenant_id = p_tenant AND r.loan_id = p_loan AND al.component = 'PENALTY'), 0), 0)
$$;

-- X1, X2, X3 ------------------------------------------------------------------
CREATE FUNCTION lending.guard_write_off() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_min int;
    v_dpd int;
    v_gl lending.loan_product_version%ROWTYPE;
    v_dr numeric;
    v_cr_p numeric;
    v_cr_i numeric;
    v_cr_x numeric;
    v_other int;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'WRITE_OFF_FINAL: write-offs are never deleted' USING ERRCODE = 'P0001';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PENDING' THEN
            RAISE EXCEPTION 'WRITE_OFF_STATUS: write-offs start PENDING' USING ERRCODE = 'P0001';
        END IF;
        IF (SELECT status FROM lending.loan WHERE tenant_id = NEW.tenant_id AND loan_id = NEW.loan_id) <> 'ACTIVE' THEN
            RAISE EXCEPTION 'LENDING_LOAN_NOT_ACTIVE: only an ACTIVE loan can be written off' USING ERRCODE = 'P0001';
        END IF;
        SELECT min_days_past_due INTO v_min FROM lending.write_off_policy WHERE tenant_id = NEW.tenant_id;
        IF v_min IS NULL THEN
            RAISE EXCEPTION 'WRITE_OFF_NO_POLICY: no write-off policy is configured for this tenant' USING ERRCODE = 'P0001';
        END IF;
        SELECT days_past_due INTO v_dpd FROM lending.arrears_snapshot
         WHERE tenant_id = NEW.tenant_id AND loan_id = NEW.loan_id ORDER BY as_of DESC LIMIT 1;
        IF v_dpd IS NULL OR v_dpd < v_min OR NEW.days_past_due <> v_dpd THEN
            RAISE EXCEPTION 'WRITE_OFF_NOT_ELIGIBLE: % days past due at the last end of day; the policy needs %',
                COALESCE(v_dpd, 0), v_min USING ERRCODE = 'P0001';
        END IF;
        IF lending.loan_credit(NEW.tenant_id, NEW.loan_id) <> 0 THEN
            RAISE EXCEPTION 'WRITE_OFF_CREDIT_HELD: the loan holds overpaid money; apply or refund it first'
                USING ERRCODE = 'P0001';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status <> 'PENDING' THEN
        RAISE EXCEPTION 'WRITE_OFF_FINAL: a decided write-off cannot change' USING ERRCODE = 'P0001';
    END IF;
    IF (NEW.write_off_id, NEW.loan_id, NEW.idempotency_key, NEW.reason, NEW.requested_by, NEW.days_past_due)
       IS DISTINCT FROM (OLD.write_off_id, OLD.loan_id, OLD.idempotency_key, OLD.reason, OLD.requested_by, OLD.days_past_due) THEN
        RAISE EXCEPTION 'WRITE_OFF_FINAL: the request cannot change' USING ERRCODE = 'P0001';
    END IF;
    NEW.decided_at := clock_timestamp();
    IF NEW.status = 'APPROVED' THEN
        IF NEW.principal <> lending.loan_principal_outstanding(NEW.tenant_id, NEW.loan_id)
           OR NEW.interest <> lending.loan_interest_receivable(NEW.tenant_id, NEW.loan_id)
           OR NEW.penalty <> lending.loan_penalty_unpaid(NEW.tenant_id, NEW.loan_id)
           OR lending.loan_credit(NEW.tenant_id, NEW.loan_id) <> 0 THEN
            RAISE EXCEPTION 'WRITE_OFF_AMOUNTS: amounts are not what the loan owes now' USING ERRCODE = 'P0001';
        END IF;
        SELECT v.* INTO v_gl FROM lending.loan l
          JOIN lending.loan_product_version v ON v.tenant_id = l.tenant_id AND v.version_id = l.product_version_id
         WHERE l.tenant_id = NEW.tenant_id AND l.loan_id = NEW.loan_id;
        IF v_gl.gl_write_off_recovery IS NULL THEN
            RAISE EXCEPTION 'WRITE_OFF_NO_RECOVERY_ACCOUNT: the product has no recovery account for later receipts'
                USING ERRCODE = 'P0001';
        END IF;
        SELECT COALESCE(sum(amount) FILTER (WHERE side = 'D' AND account_code = v_gl.gl_write_off), 0),
               COALESCE(sum(amount) FILTER (WHERE side = 'C' AND account_code = v_gl.gl_principal), 0),
               COALESCE(sum(amount) FILTER (WHERE side = 'C' AND account_code = v_gl.gl_interest_receivable), 0),
               COALESCE(sum(amount) FILTER (WHERE side = 'C' AND account_code = v_gl.gl_penalty_receivable), 0),
               count(*) FILTER (WHERE NOT ((side = 'D' AND account_code = v_gl.gl_write_off)
                                        OR (side = 'C' AND account_code IN (v_gl.gl_principal, v_gl.gl_interest_receivable,
                                                                            v_gl.gl_penalty_receivable))))
          INTO v_dr, v_cr_p, v_cr_i, v_cr_x, v_other
          FROM ledger.journal_line WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id;
        IF v_dr <> NEW.principal + NEW.interest + NEW.penalty OR v_cr_p <> NEW.principal OR v_cr_i <> NEW.interest
           OR v_cr_x <> NEW.penalty OR v_other <> 0 THEN
            RAISE EXCEPTION 'WRITE_OFF_ENTRY: entry does not write off exactly what is owed' USING ERRCODE = 'P0001';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER write_off_guard BEFORE INSERT OR UPDATE OR DELETE ON lending.write_off
    FOR EACH ROW EXECUTE FUNCTION lending.guard_write_off();

-- X3: approved <=> loan WRITTEN_OFF, checked at commit both ways.
CREATE FUNCTION lending.check_write_off_loan() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_status varchar;
    v_approved boolean;
    v_loan uuid := CASE TG_TABLE_NAME WHEN 'loan' THEN NEW.loan_id ELSE NEW.loan_id END;
BEGIN
    SELECT status INTO v_status FROM lending.loan WHERE tenant_id = NEW.tenant_id AND loan_id = v_loan;
    v_approved := EXISTS (SELECT 1 FROM lending.write_off
                           WHERE tenant_id = NEW.tenant_id AND loan_id = v_loan AND status = 'APPROVED');
    IF (v_status = 'WRITTEN_OFF') <> v_approved THEN
        RAISE EXCEPTION 'WRITE_OFF_LOAN_STATUS: a loan is WRITTEN_OFF exactly when its write-off is approved'
            USING ERRCODE = 'P0001';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER write_off_loan_status AFTER UPDATE ON lending.write_off
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION lending.check_write_off_loan();
CREATE CONSTRAINT TRIGGER loan_written_off_status AFTER UPDATE ON lending.loan
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION lending.check_write_off_loan();

-- X4 ----------------------------------------------------------------------------
CREATE FUNCTION lending.check_write_off_recovery() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    w lending.write_off%ROWTYPE;
    v_recovered numeric;
    v_gl varchar(40);
    v_cr numeric;
BEGIN
    SELECT * INTO w FROM lending.write_off
     WHERE tenant_id = NEW.tenant_id AND loan_id = NEW.loan_id AND status = 'APPROVED';
    IF w.write_off_id IS NULL
       OR (SELECT status FROM lending.loan WHERE tenant_id = NEW.tenant_id AND loan_id = NEW.loan_id) <> 'WRITTEN_OFF' THEN
        RAISE EXCEPTION 'WRITE_OFF_RECOVERY_LOAN: recoveries are for written-off loans' USING ERRCODE = 'P0001';
    END IF;
    SELECT COALESCE(sum(amount), 0) INTO v_recovered FROM lending.write_off_recovery
     WHERE tenant_id = NEW.tenant_id AND loan_id = NEW.loan_id;
    IF v_recovered + NEW.amount > w.principal + w.interest + w.penalty THEN
        RAISE EXCEPTION 'WRITE_OFF_RECOVERY_EXCEEDED: % recovered of % written off', v_recovered + NEW.amount,
            w.principal + w.interest + w.penalty USING ERRCODE = 'P0001';
    END IF;
    SELECT v.gl_write_off_recovery INTO v_gl FROM lending.loan l
      JOIN lending.loan_product_version v ON v.tenant_id = l.tenant_id AND v.version_id = l.product_version_id
     WHERE l.tenant_id = NEW.tenant_id AND l.loan_id = NEW.loan_id;
    SELECT COALESCE(sum(amount), 0) INTO v_cr FROM ledger.journal_line
     WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id AND account_code = v_gl AND side = 'C';
    IF v_cr <> NEW.amount THEN
        RAISE EXCEPTION 'WRITE_OFF_RECOVERY_ENTRY: entry credits % to recoveries, not %', v_cr, NEW.amount
            USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER write_off_recovery_check BEFORE INSERT ON lending.write_off_recovery
    FOR EACH ROW EXECUTE FUNCTION lending.check_write_off_recovery();
CREATE TRIGGER write_off_recovery_immutable BEFORE UPDATE OR DELETE ON lending.write_off_recovery
    FOR EACH ROW EXECUTE FUNCTION ledger.reject_mutation();

-- M-Pesa receipts can now be recoveries.
ALTER TABLE payments.provider_payment DROP CONSTRAINT provider_payment_status_check;
ALTER TABLE payments.provider_payment DROP CONSTRAINT provider_payment_target_check;
ALTER TABLE payments.provider_payment
    ADD CONSTRAINT provider_payment_status_check CHECK (status IN ('APPLIED', 'LOAN_REPAYMENT', 'LOAN_RECOVERY', 'UNMATCHED')),
    ADD CONSTRAINT provider_payment_target_check CHECK (
        (status = 'APPLIED') = (deposit_account_id IS NOT NULL)
        AND (status IN ('LOAN_REPAYMENT', 'LOAN_RECOVERY')) = (loan_id IS NOT NULL));

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['lending.write_off_policy', 'lending.write_off', 'lending.write_off_recovery'] LOOP
        EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %s USING (tenant_id = tenancy.current_tenant()) '
                       'WITH CHECK (tenant_id = tenancy.current_tenant())', t);
    END LOOP;
END $$;
GRANT SELECT ON lending.write_off_policy TO mfi_app;
GRANT SELECT, INSERT, UPDATE ON lending.write_off TO mfi_app;
GRANT SELECT, INSERT ON lending.write_off_recovery TO mfi_app;
GRANT EXECUTE ON FUNCTION lending.loan_interest_receivable(uuid, uuid), lending.loan_principal_outstanding(uuid, uuid),
    lending.loan_penalty_unpaid(uuid, uuid) TO mfi_app;
