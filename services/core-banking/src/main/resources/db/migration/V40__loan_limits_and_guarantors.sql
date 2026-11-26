-- =============================================================================
-- V40: Loan limits and guarantors (ADR-0043).
--   LG1 a loan product may have a limit rule: a multiple of the member's own cover (savings, less
--       what they have pledged for others, plus shares if counted), less their other loans'
--       outstanding principal if deducted; and whether guarantees must cover the part of a loan
--       above the member's own cover
--   LG2 an approved application is within the limit and, where required, its pledges cover the
--       part above own cover (checked with the values at approval)
--   LG3 a guarantee is pledged by an active member other than the borrower, on a submitted
--       application, from one of their own active savings accounts, with a signed-consent
--       reference; it locks the amount with an EARMARK hold on that account, which the guarantor
--       must have available
--   LG4 a pledge is withdrawn only while the application is open, and released when the
--       application is declined or withdrawn or the loan closes; its hold is released with it
-- =============================================================================
CREATE TABLE lending.loan_limit_rule (
    tenant_id           uuid         NOT NULL REFERENCES tenancy.tenant (tenant_id),
    product_code        varchar(32)  NOT NULL,
    savings_multiple    numeric(5,2) NOT NULL CHECK (savings_multiple > 0 AND savings_multiple <= 20),
    include_shares      boolean      NOT NULL DEFAULT true,
    deduct_outstanding  boolean      NOT NULL DEFAULT true,
    guarantees_required boolean      NOT NULL DEFAULT false,
    updated_by          varchar(100) NOT NULL,
    updated_at          timestamptz  NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, product_code),
    FOREIGN KEY (tenant_id, product_code) REFERENCES lending.loan_product (tenant_id, product_code)
);

CREATE TABLE lending.loan_guarantee (
    tenant_id             uuid          NOT NULL REFERENCES tenancy.tenant (tenant_id),
    guarantee_id          uuid          NOT NULL,
    idempotency_key       varchar(120)  NOT NULL,
    application_id        uuid          NOT NULL,
    guarantor_customer_id uuid          NOT NULL,
    savings_account_id    uuid          NOT NULL,
    amount                numeric(19,4) NOT NULL CHECK (amount > 0 AND amount = round(amount, 2)),
    consent_reference     varchar(100)  NOT NULL CHECK (length(btrim(consent_reference)) >= 3),
    hold_id               uuid          NOT NULL,
    status                varchar(16)   NOT NULL DEFAULT 'PLEDGED' CHECK (status IN ('PLEDGED', 'WITHDRAWN', 'RELEASED')),
    pledged_by            varchar(100)  NOT NULL,
    pledged_at            timestamptz   NOT NULL DEFAULT clock_timestamp(),
    ended_by              varchar(100),
    ended_at              timestamptz,
    end_reason            varchar(300),
    PRIMARY KEY (tenant_id, guarantee_id),
    CONSTRAINT loan_guarantee_idempotency UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT loan_guarantee_hold UNIQUE (tenant_id, hold_id),
    FOREIGN KEY (tenant_id, application_id) REFERENCES lending.loan_application (tenant_id, application_id),
    FOREIGN KEY (tenant_id, guarantor_customer_id) REFERENCES customer.customer (tenant_id, customer_id),
    FOREIGN KEY (tenant_id, savings_account_id) REFERENCES savings.deposit_account (tenant_id, account_id),
    FOREIGN KEY (tenant_id, hold_id) REFERENCES savings.account_hold (tenant_id, hold_id),
    CHECK ((status = 'PLEDGED') = (ended_at IS NULL))
);
CREATE UNIQUE INDEX loan_guarantee_one_per_guarantor ON lending.loan_guarantee (tenant_id, application_id, guarantor_customer_id)
    WHERE status = 'PLEDGED';
CREATE INDEX loan_guarantee_guarantor_idx ON lending.loan_guarantee (tenant_id, guarantor_customer_id) WHERE status = 'PLEDGED';

-- LG1: what a member may borrow on a product (no row when the product has no rule)
CREATE FUNCTION lending.loan_limit(p_tenant uuid, p_customer uuid, p_product varchar, p_exclude_loan uuid,
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
    SELECT COALESCE(sum(amount), 0) INTO pledged_for_others FROM lending.loan_guarantee
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

-- LG2
CREATE FUNCTION lending.check_application_limits() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_product varchar;
    l record;
    v_pledged numeric;
BEGIN
    IF NEW.status <> 'APPROVED' OR OLD.status = 'APPROVED' OR NEW.customer_id IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT product_code INTO v_product FROM lending.loan_product_version
     WHERE tenant_id = NEW.tenant_id AND version_id = NEW.product_version_id;
    SELECT * INTO l FROM lending.loan_limit(NEW.tenant_id, NEW.customer_id, v_product, NEW.loan_id);
    IF NOT FOUND THEN
        RETURN NEW;
    END IF;
    IF NEW.principal > l.limit_amount THEN
        RAISE EXCEPTION 'LOAN_LIMIT_EXCEEDED: % asked for, the member may borrow % (% x own cover %, less % outstanding)',
            NEW.principal, l.limit_amount, l.savings_multiple, l.own_cover, l.outstanding USING ERRCODE = 'P0001';
    END IF;
    IF l.guarantees_required THEN
        SELECT COALESCE(sum(amount), 0) INTO v_pledged FROM lending.loan_guarantee
         WHERE tenant_id = NEW.tenant_id AND application_id = NEW.application_id AND status = 'PLEDGED';
        IF v_pledged < GREATEST(NEW.principal - l.own_cover, 0) THEN
            RAISE EXCEPTION 'LOAN_GUARANTEE_SHORT: guarantees of % cover % of the % above the member''s own cover',
                GREATEST(NEW.principal - l.own_cover, 0), v_pledged, GREATEST(NEW.principal - l.own_cover, 0) USING ERRCODE = 'P0001';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER loan_application_limits BEFORE UPDATE ON lending.loan_application
    FOR EACH ROW EXECUTE FUNCTION lending.check_application_limits();

-- LG3, LG4
CREATE FUNCTION lending.guard_loan_guarantee() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    a lending.loan_application%ROWTYPE;
    h savings.account_hold%ROWTYPE;
    v_owner uuid;
    v_account_status varchar;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'GUARANTEE_IMMUTABLE: guarantees are withdrawn or released, not deleted' USING ERRCODE = 'P0001';
    END IF;
    SELECT * INTO a FROM lending.loan_application WHERE tenant_id = NEW.tenant_id AND application_id = NEW.application_id;
    SELECT * INTO h FROM savings.account_hold WHERE tenant_id = NEW.tenant_id AND hold_id = NEW.hold_id;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PLEDGED' OR a.status <> 'SUBMITTED' THEN
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
    IF NEW.status = 'WITHDRAWN' AND a.status <> 'SUBMITTED' THEN
        RAISE EXCEPTION 'GUARANTEE_LOCKED: the application is %; the guarantee holds until the loan closes', a.status
            USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status = 'RELEASED' AND NOT (a.status IN ('DECLINED', 'WITHDRAWN')
            OR (SELECT status FROM lending.loan WHERE tenant_id = a.tenant_id AND loan_id = a.loan_id) = 'CLOSED') THEN
        RAISE EXCEPTION 'GUARANTEE_LOCKED: released only when the application ends or the loan closes' USING ERRCODE = 'P0001';
    END IF;
    IF h.status = 'ACTIVE' THEN
        UPDATE savings.account_hold SET status = 'RELEASED' WHERE tenant_id = NEW.tenant_id AND hold_id = NEW.hold_id;
    END IF;
    NEW.ended_at := clock_timestamp();
    RETURN NEW;
END $$;
CREATE TRIGGER loan_guarantee_guard BEFORE INSERT OR UPDATE OR DELETE ON lending.loan_guarantee
    FOR EACH ROW EXECUTE FUNCTION lending.guard_loan_guarantee();

-- a guarantee's hold is released only with the guarantee
CREATE FUNCTION lending.guard_guarantee_hold() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    -- the guarantee's own trigger releases it (nested, depth > 1); nothing else may
    IF OLD.status = 'ACTIVE' AND NEW.status <> 'ACTIVE' AND pg_trigger_depth() = 1 AND EXISTS (
            SELECT 1 FROM lending.loan_guarantee g WHERE g.tenant_id = NEW.tenant_id AND g.hold_id = NEW.hold_id
               AND g.status = 'PLEDGED') THEN
        RAISE EXCEPTION 'GUARANTEE_LOCKED: hold % secures a loan guarantee', NEW.hold_id USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER account_hold_guarantee BEFORE UPDATE ON savings.account_hold
    FOR EACH ROW EXECUTE FUNCTION lending.guard_guarantee_hold();

-- LG4: guarantees end with the application or the loan
CREATE FUNCTION lending.release_guarantees_on_application() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status IN ('DECLINED', 'WITHDRAWN') AND OLD.status = 'SUBMITTED' THEN
        UPDATE lending.loan_guarantee SET status = 'RELEASED', ended_by = COALESCE(NEW.decided_by, 'system'),
               end_reason = 'Application ' || lower(NEW.status)
         WHERE tenant_id = NEW.tenant_id AND application_id = NEW.application_id AND status = 'PLEDGED';
    END IF;
    RETURN NULL;
END $$;
CREATE TRIGGER loan_application_guarantees AFTER UPDATE ON lending.loan_application
    FOR EACH ROW EXECUTE FUNCTION lending.release_guarantees_on_application();

CREATE FUNCTION lending.release_guarantees_on_loan() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'CLOSED' AND OLD.status <> 'CLOSED' THEN
        UPDATE lending.loan_guarantee g SET status = 'RELEASED', ended_by = 'system', end_reason = 'Loan repaid'
          FROM lending.loan_application a
         WHERE a.tenant_id = NEW.tenant_id AND a.loan_id = NEW.loan_id
           AND g.tenant_id = a.tenant_id AND g.application_id = a.application_id AND g.status = 'PLEDGED';
    END IF;
    RETURN NULL;
END $$;
CREATE TRIGGER loan_guarantees_released AFTER UPDATE OF status ON lending.loan
    FOR EACH ROW EXECUTE FUNCTION lending.release_guarantees_on_loan();

-- a guarantor with open guarantees cannot leave (V39), named plainly
CREATE OR REPLACE FUNCTION membership.blockers(p_tenant uuid, p_customer uuid) RETURNS text[]
    LANGUAGE plpgsql STABLE
AS $$
DECLARE
    v text[] := ARRAY[]::text[];
    n int;
    v_amount numeric;
BEGIN
    SELECT count(*) INTO n FROM lending.loan WHERE tenant_id = p_tenant AND customer_id = p_customer
       AND status IN ('APPROVED', 'ACTIVE');
    IF n > 0 THEN v := v || format('%s loan(s) not yet repaid', n); END IF;
    SELECT count(*) INTO n FROM lending.loan WHERE tenant_id = p_tenant AND customer_id = p_customer AND status = 'WRITTEN_OFF'
       AND lending.loan_unpaid(tenant_id, loan_id) > 0;
    IF n > 0 THEN v := v || format('%s written-off loan(s) still owed', n); END IF;
    SELECT COALESCE(sum(lending.loan_credit(tenant_id, loan_id)), 0) INTO v_amount FROM lending.loan
     WHERE tenant_id = p_tenant AND customer_id = p_customer AND status = 'CLOSED';
    IF v_amount > 0 THEN v := v || format('%s credit on closed loans to refund first', round(v_amount, 2)); END IF;
    SELECT count(*) INTO n FROM lending.loan_application WHERE tenant_id = p_tenant AND customer_id = p_customer
       AND status = 'SUBMITTED';
    IF n > 0 THEN v := v || format('%s loan application(s) still open', n); END IF;
    SELECT count(*), COALESCE(sum(amount), 0) INTO n, v_amount FROM lending.loan_guarantee
     WHERE tenant_id = p_tenant AND guarantor_customer_id = p_customer AND status = 'PLEDGED';
    IF n > 0 THEN v := v || format('%s guarantee(s) for other members'' loans (%s)', n, round(v_amount, 2)); END IF;
    SELECT count(*) INTO n FROM termdep.deposit WHERE tenant_id = p_tenant AND customer_id = p_customer AND status = 'ACTIVE';
    IF n > 0 THEN v := v || format('%s active fixed deposit(s)', n); END IF;
    v_amount := shares.holding(p_tenant, p_customer, NULL);
    IF v_amount > 0 THEN v := v || format('%s in shares to transfer to another member', round(v_amount, 2)); END IF;
    SELECT count(*) INTO n FROM shares.share_transfer WHERE tenant_id = p_tenant AND status = 'REQUESTED'
       AND p_customer IN (from_customer_id, to_customer_id);
    IF n > 0 THEN v := v || format('%s share transfer(s) waiting for a decision', n); END IF;
    SELECT count(*) INTO n FROM savings.account_hold h JOIN savings.deposit_account a
        ON a.tenant_id = h.tenant_id AND a.account_id = h.account_id
     WHERE h.tenant_id = p_tenant AND a.customer_id = p_customer AND h.status = 'ACTIVE'
       AND NOT EXISTS (SELECT 1 FROM lending.loan_guarantee g WHERE g.tenant_id = h.tenant_id AND g.hold_id = h.hold_id);
    IF n > 0 THEN v := v || format('%s hold(s) on savings', n); END IF;
    SELECT count(*) INTO n FROM savings.teller_transaction t JOIN savings.deposit_account a
        ON a.tenant_id = t.tenant_id AND a.account_id = t.account_id
     WHERE t.tenant_id = p_tenant AND a.customer_id = p_customer AND t.status = 'PENDING_APPROVAL';
    IF n > 0 THEN v := v || format('%s teller transaction(s) waiting for approval', n); END IF;
    SELECT count(*) INTO n FROM savings.deposit_account a JOIN savings.account_balance b
        ON b.tenant_id = a.tenant_id AND b.account_id = a.account_id
     WHERE a.tenant_id = p_tenant AND a.customer_id = p_customer AND a.status <> 'CLOSED' AND b.ledger_balance < 0;
    IF n > 0 THEN v := v || format('%s overdrawn savings account(s)', n); END IF;
    RETURN v;
END $$;

ALTER TABLE lending.loan_limit_rule ENABLE ROW LEVEL SECURITY;
ALTER TABLE lending.loan_limit_rule FORCE ROW LEVEL SECURITY;
ALTER TABLE lending.loan_guarantee ENABLE ROW LEVEL SECURITY;
ALTER TABLE lending.loan_guarantee FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON lending.loan_limit_rule
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
CREATE POLICY tenant_isolation ON lending.loan_guarantee
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
GRANT SELECT, INSERT, UPDATE, DELETE ON lending.loan_limit_rule TO mfi_app;
GRANT SELECT, INSERT, UPDATE ON lending.loan_guarantee TO mfi_app;
GRANT EXECUTE ON FUNCTION lending.loan_limit(uuid, uuid, varchar, uuid) TO mfi_app;
