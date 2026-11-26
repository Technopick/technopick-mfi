-- =============================================================================
-- V39: Member exit (ADR-0042).
--   MX1 settings per tenant: notice period in days and the member refunds payable account
--       (LIABILITY); the account does not change while refunds are unpaid
--   MX2 an exit is asked for on the business date with a reason; the notice period is copied;
--       one open request per member; it is approved by someone other than the asker, on or after
--       the end of the notice period, and only when nothing is outstanding (membership.blockers):
--       no unfinished loans or applications, no loan credit, no active fixed deposits, no shares
--       or pending share transfers, no holds, no pending teller approvals, no overdrawn account
--   MX3 approval settles every open savings account of the member: its balance, and the savings
--       interest accrued since its last monthly credit less withholding tax, move to refunds
--       payable in one entry checked line by line; the accounts close and the member is EXITED
--   MX4 a member becomes EXITED only through an approved exit, and stays EXITED
--   MX5 the refund is paid once, in full, from an ASSET account, asked for by one person and
--       approved by another; refunds payable is a control account and reconciles with unpaid exits
-- =============================================================================
CREATE SCHEMA membership;

-- MX4: EXITED is a final customer status reached from ACTIVE or BLOCKED
DO $$
DECLARE v_name text;
BEGIN
    SELECT conname INTO v_name FROM pg_constraint
     WHERE conrelid = 'customer.customer'::regclass AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%PENDING_VERIFICATION%BLOCKED%'
       AND pg_get_constraintdef(oid) NOT LIKE '%verified_by%';
    EXECUTE format('ALTER TABLE customer.customer DROP CONSTRAINT %I', v_name);
END $$;
ALTER TABLE customer.customer ADD CONSTRAINT customer_status_check
    CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'BLOCKED', 'EXITED'));

CREATE OR REPLACE FUNCTION customer.guard_customer() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'CUSTOMER_IMMUTABLE: customers are never deleted' USING ERRCODE = 'P0001';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PENDING_VERIFICATION' THEN
            RAISE EXCEPTION 'CUSTOMER_STATUS: customers start PENDING_VERIFICATION' USING ERRCODE = 'P0001';
        END IF;
        IF NEW.date_of_birth > (current_date - interval '18 years')::date THEN
            RAISE EXCEPTION 'CUSTOMER_UNDERAGE: customers must be 18 or older' USING ERRCODE = 'P0001';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.customer_id <> OLD.customer_id OR NEW.customer_number <> OLD.customer_number
       OR NEW.idempotency_key <> OLD.idempotency_key OR NEW.created_by <> OLD.created_by
       OR NEW.created_at <> OLD.created_at OR NEW.consent_version <> OLD.consent_version
       OR NEW.consent_at <> OLD.consent_at THEN
        RAISE EXCEPTION 'CUSTOMER_IMMUTABLE: registration record never changes' USING ERRCODE = 'P0001';
    END IF;
    IF OLD.status <> 'PENDING_VERIFICATION' AND (
           NEW.first_name IS DISTINCT FROM OLD.first_name OR NEW.middle_name IS DISTINCT FROM OLD.middle_name
        OR NEW.last_name IS DISTINCT FROM OLD.last_name OR NEW.date_of_birth IS DISTINCT FROM OLD.date_of_birth
        OR NEW.id_type IS DISTINCT FROM OLD.id_type OR NEW.id_number IS DISTINCT FROM OLD.id_number) THEN
        RAISE EXCEPTION 'CUSTOMER_IDENTITY_FIXED: verified identity details never change' USING ERRCODE = 'P0001';
    END IF;
    IF OLD.verified_by IS NOT NULL AND (NEW.verified_by IS DISTINCT FROM OLD.verified_by
        OR NEW.verified_at IS DISTINCT FROM OLD.verified_at
        OR NEW.verification_note IS DISTINCT FROM OLD.verification_note) THEN
        RAISE EXCEPTION 'CUSTOMER_IMMUTABLE: the verification record never changes' USING ERRCODE = 'P0001';
    END IF;
    IF OLD.status = 'EXITED' AND (NEW.status <> 'EXITED' OR NEW.msisdn IS DISTINCT FROM OLD.msisdn
                                  OR NEW.branch_code IS DISTINCT FROM OLD.branch_code) THEN
        RAISE EXCEPTION 'CUSTOMER_EXITED: a member who has left is not changed' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
           (OLD.status = 'PENDING_VERIFICATION' AND NEW.status = 'ACTIVE')
        OR (OLD.status = 'ACTIVE' AND NEW.status = 'BLOCKED')
        OR (OLD.status = 'BLOCKED' AND NEW.status = 'ACTIVE')
        OR (OLD.status IN ('ACTIVE', 'BLOCKED') AND NEW.status = 'EXITED')) THEN
        RAISE EXCEPTION 'CUSTOMER_STATUS: % -> % not allowed', OLD.status, NEW.status USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status = 'EXITED' AND OLD.status <> 'EXITED' AND NOT EXISTS (
            SELECT 1 FROM membership.exit_request r WHERE r.tenant_id = NEW.tenant_id AND r.customer_id = NEW.customer_id
               AND r.status = 'APPROVED') THEN
        RAISE EXCEPTION 'CUSTOMER_STATUS: a member leaves only through an approved exit' USING ERRCODE = 'P0001';
    END IF;
    IF OLD.status = 'PENDING_VERIFICATION' AND NEW.status = 'ACTIVE' THEN
        NEW.verified_at := clock_timestamp();
    END IF;
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END $$;

-- MX1
CREATE TABLE membership.exit_settings (
    tenant_id          uuid         NOT NULL PRIMARY KEY REFERENCES tenancy.tenant (tenant_id),
    notice_days        integer      NOT NULL DEFAULT 60 CHECK (notice_days BETWEEN 0 AND 365),
    gl_refunds_payable varchar(40)  NOT NULL,
    updated_by         varchar(100) NOT NULL,
    updated_at         timestamptz  NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (tenant_id, gl_refunds_payable) REFERENCES ledger.gl_account (tenant_id, account_code)
);

-- MX2
CREATE TABLE membership.exit_request (
    tenant_id          uuid          NOT NULL REFERENCES tenancy.tenant (tenant_id),
    exit_id            uuid          NOT NULL,
    idempotency_key    varchar(120)  NOT NULL,
    customer_id        uuid          NOT NULL,
    reason             varchar(300)  NOT NULL CHECK (length(btrim(reason)) >= 10),
    requested_on       date          NOT NULL,
    notice_days        integer       NOT NULL CHECK (notice_days >= 0),
    earliest_exit_date date          NOT NULL,
    status             varchar(16)   NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED', 'CANCELLED')),
    requested_by       varchar(100)  NOT NULL,
    requested_at       timestamptz   NOT NULL DEFAULT clock_timestamp(),
    decided_by         varchar(100),
    decided_at         timestamptz,
    decision_reason    varchar(300),
    business_date      date,
    gl_refunds_payable varchar(40),
    refund_total       numeric(19,4) CHECK (refund_total >= 0),
    entry_id           uuid,
    PRIMARY KEY (tenant_id, exit_id),
    CONSTRAINT exit_request_idempotency UNIQUE (tenant_id, idempotency_key),
    FOREIGN KEY (tenant_id, customer_id) REFERENCES customer.customer (tenant_id, customer_id),
    FOREIGN KEY (tenant_id, entry_id) REFERENCES ledger.journal_entry (tenant_id, entry_id),
    FOREIGN KEY (tenant_id, gl_refunds_payable) REFERENCES ledger.gl_account (tenant_id, account_code),
    CHECK (earliest_exit_date = requested_on + notice_days),
    CHECK ((status = 'REQUESTED') = (decided_by IS NULL)),
    CHECK (status NOT IN ('REJECTED', 'CANCELLED') OR length(btrim(COALESCE(decision_reason, ''))) >= 3),
    CHECK ((status = 'APPROVED') = (business_date IS NOT NULL AND refund_total IS NOT NULL AND gl_refunds_payable IS NOT NULL))
);
CREATE UNIQUE INDEX exit_request_one_open ON membership.exit_request (tenant_id, customer_id) WHERE status IN ('REQUESTED', 'APPROVED');
CREATE INDEX exit_request_entry_idx ON membership.exit_request (tenant_id, entry_id) WHERE entry_id IS NOT NULL;

-- MX3: one row per savings account settled
CREATE TABLE membership.exit_account (
    tenant_id      uuid          NOT NULL,
    exit_id        uuid          NOT NULL,
    account_id     uuid          NOT NULL,
    balance        numeric(19,4) NOT NULL CHECK (balance >= 0),
    interest_gross numeric(19,4) NOT NULL CHECK (interest_gross >= 0),
    interest_tax   numeric(19,4) NOT NULL CHECK (interest_tax >= 0),
    refund         numeric(19,4) NOT NULL,
    PRIMARY KEY (tenant_id, exit_id, account_id),
    CONSTRAINT exit_account_once UNIQUE (tenant_id, account_id),
    FOREIGN KEY (tenant_id, exit_id) REFERENCES membership.exit_request (tenant_id, exit_id),
    FOREIGN KEY (tenant_id, account_id) REFERENCES savings.deposit_account (tenant_id, account_id),
    CHECK (refund = balance + interest_gross - interest_tax)
);

-- MX5
CREATE TABLE membership.refund_payment (
    tenant_id       uuid          NOT NULL REFERENCES tenancy.tenant (tenant_id),
    payment_id      uuid          NOT NULL,
    idempotency_key varchar(120)  NOT NULL,
    exit_id         uuid          NOT NULL,
    amount          numeric(19,4) NOT NULL CHECK (amount > 0),
    method          varchar(16)   NOT NULL CHECK (method IN ('BANK_TRANSFER', 'CHEQUE', 'CASH', 'MPESA')),
    paid_from       varchar(40)   NOT NULL,
    reference       varchar(100)  NOT NULL CHECK (length(btrim(reference)) >= 3),
    status          varchar(16)   NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED')),
    requested_by    varchar(100)  NOT NULL,
    requested_at    timestamptz   NOT NULL DEFAULT clock_timestamp(),
    decided_by      varchar(100),
    decided_at      timestamptz,
    decision_reason varchar(300),
    business_date   date,
    entry_id        uuid,
    PRIMARY KEY (tenant_id, payment_id),
    CONSTRAINT refund_payment_idempotency UNIQUE (tenant_id, idempotency_key),
    FOREIGN KEY (tenant_id, exit_id) REFERENCES membership.exit_request (tenant_id, exit_id),
    FOREIGN KEY (tenant_id, paid_from) REFERENCES ledger.gl_account (tenant_id, account_code),
    FOREIGN KEY (tenant_id, entry_id) REFERENCES ledger.journal_entry (tenant_id, entry_id),
    CHECK ((status = 'REQUESTED') = (decided_by IS NULL)),
    CHECK ((status = 'APPROVED') = (entry_id IS NOT NULL AND business_date IS NOT NULL)),
    CHECK (status <> 'REJECTED' OR length(btrim(COALESCE(decision_reason, ''))) >= 3)
);
CREATE UNIQUE INDEX refund_payment_one ON membership.refund_payment (tenant_id, exit_id) WHERE status <> 'REJECTED';
CREATE INDEX refund_payment_entry_idx ON membership.refund_payment (tenant_id, entry_id) WHERE entry_id IS NOT NULL;

-- savings interest accrued on an account since its last monthly credit (V18)
CREATE FUNCTION membership.unposted_interest(p_tenant uuid, p_account uuid) RETURNS numeric
    LANGUAGE sql STABLE
AS $$
    SELECT COALESCE(sum(i.posted), 0) FROM savings.interest_accrual i
     WHERE i.tenant_id = p_tenant AND i.account_id = p_account
       AND i.business_date > COALESCE((SELECT max(p.period_end) FROM savings.interest_posting p
                                        WHERE p.tenant_id = p_tenant AND p.account_id = p_account), '-infinity'::date);
$$;

-- MX2: what stops a member leaving, in plain words (empty when nothing does)
CREATE FUNCTION membership.blockers(p_tenant uuid, p_customer uuid) RETURNS text[]
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
    SELECT count(*) INTO n FROM termdep.deposit WHERE tenant_id = p_tenant AND customer_id = p_customer AND status = 'ACTIVE';
    IF n > 0 THEN v := v || format('%s active fixed deposit(s)', n); END IF;
    v_amount := shares.holding(p_tenant, p_customer, NULL);
    IF v_amount > 0 THEN v := v || format('%s in shares to transfer to another member', round(v_amount, 2)); END IF;
    SELECT count(*) INTO n FROM shares.share_transfer WHERE tenant_id = p_tenant AND status = 'REQUESTED'
       AND p_customer IN (from_customer_id, to_customer_id);
    IF n > 0 THEN v := v || format('%s share transfer(s) waiting for a decision', n); END IF;
    SELECT count(*) INTO n FROM savings.account_hold h JOIN savings.deposit_account a
        ON a.tenant_id = h.tenant_id AND a.account_id = h.account_id
     WHERE h.tenant_id = p_tenant AND a.customer_id = p_customer AND h.status = 'ACTIVE';
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

CREATE FUNCTION membership.guard_exit_request() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_today date;
    v_status varchar;
    v_blockers text[];
    s membership.exit_settings%ROWTYPE;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'EXIT_IMMUTABLE: exits are rejected or cancelled, not deleted' USING ERRCODE = 'P0001';
    END IF;
    SELECT current_date_ INTO v_today FROM tenancy.business_date WHERE tenant_id = NEW.tenant_id;
    SELECT * INTO s FROM membership.exit_settings WHERE tenant_id = NEW.tenant_id;
    IF TG_OP = 'INSERT' THEN
        SELECT status INTO v_status FROM customer.customer WHERE tenant_id = NEW.tenant_id AND customer_id = NEW.customer_id;
        IF s.tenant_id IS NULL THEN
            RAISE EXCEPTION 'EXIT_SETTINGS_MISSING: finance must set the notice period and refunds account first' USING ERRCODE = 'P0001';
        END IF;
        IF v_status NOT IN ('ACTIVE', 'BLOCKED') OR NEW.status <> 'REQUESTED' THEN
            RAISE EXCEPTION 'EXIT_INVALID: only a current member can ask to leave' USING ERRCODE = 'P0001';
        END IF;
        IF NEW.requested_on <> v_today OR NEW.notice_days <> s.notice_days THEN
            RAISE EXCEPTION 'EXIT_INVALID: an exit is asked for on the business date with the current notice period of % days',
                s.notice_days USING ERRCODE = 'P0001';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status <> 'REQUESTED'
       OR (NEW.exit_id, NEW.idempotency_key, NEW.customer_id, NEW.reason, NEW.requested_on, NEW.notice_days, NEW.earliest_exit_date,
           NEW.requested_by, NEW.requested_at)
          IS DISTINCT FROM (OLD.exit_id, OLD.idempotency_key, OLD.customer_id, OLD.reason, OLD.requested_on, OLD.notice_days,
           OLD.earliest_exit_date, OLD.requested_by, OLD.requested_at) THEN
        RAISE EXCEPTION 'EXIT_IMMUTABLE: an exit request is decided once' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status IN ('APPROVED', 'REJECTED') AND NEW.decided_by = NEW.requested_by THEN
        RAISE EXCEPTION 'EXIT_SAME_PERSON: the person who asked cannot decide' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status = 'APPROVED' THEN
        IF v_today < NEW.earliest_exit_date THEN
            RAISE EXCEPTION 'EXIT_NOTICE: the notice period runs until %', NEW.earliest_exit_date USING ERRCODE = 'P0001';
        END IF;
        IF NEW.business_date <> v_today OR NEW.gl_refunds_payable <> s.gl_refunds_payable THEN
            RAISE EXCEPTION 'EXIT_INVALID: approved on the business date into the current refunds account' USING ERRCODE = 'P0001';
        END IF;
        v_blockers := membership.blockers(NEW.tenant_id, NEW.customer_id);
        IF cardinality(v_blockers) > 0 THEN
            RAISE EXCEPTION 'EXIT_BLOCKED: %', array_to_string(v_blockers, '; ') USING ERRCODE = 'P0001';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER exit_request_guard BEFORE INSERT OR UPDATE OR DELETE ON membership.exit_request
    FOR EACH ROW EXECUTE FUNCTION membership.guard_exit_request();

CREATE FUNCTION membership.guard_exit_account() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    r membership.exit_request%ROWTYPE;
    a savings.deposit_account%ROWTYPE;
    v_balance numeric;
    v_interest numeric;
    v_tax_rate numeric;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'EXIT_IMMUTABLE: settled accounts never change' USING ERRCODE = 'P0001';
    END IF;
    SELECT * INTO r FROM membership.exit_request WHERE tenant_id = NEW.tenant_id AND exit_id = NEW.exit_id;
    SELECT * INTO a FROM savings.deposit_account WHERE tenant_id = NEW.tenant_id AND account_id = NEW.account_id;
    -- the balance before the exit's own entry (posted before or after these rows)
    SELECT b.ledger_balance + COALESCE((SELECT sum(CASE l.side WHEN 'D' THEN l.amount ELSE -l.amount END) FROM ledger.journal_line l
                                         WHERE l.tenant_id = NEW.tenant_id AND l.entry_id = r.entry_id
                                           AND l.subledger_account_id = NEW.account_id), 0)
      INTO v_balance FROM savings.account_balance b WHERE b.tenant_id = NEW.tenant_id AND b.account_id = NEW.account_id;
    v_interest := membership.unposted_interest(NEW.tenant_id, NEW.account_id);
    SELECT withholding_tax_rate INTO v_tax_rate FROM savings.deposit_product WHERE tenant_id = NEW.tenant_id AND product_code = a.product_code;
    IF r.status <> 'APPROVED' OR a.customer_id <> r.customer_id OR a.status = 'CLOSED' OR NEW.balance <> v_balance
       OR NEW.interest_gross <> v_interest OR NEW.interest_tax <> round(v_interest * v_tax_rate / 100, 2) THEN
        RAISE EXCEPTION 'EXIT_ACCOUNT_INVALID: account % holds % with % interest accrued (tax rate %)', a.account_number, v_balance,
            v_interest, v_tax_rate USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER exit_account_guard BEFORE INSERT OR UPDATE OR DELETE ON membership.exit_account
    FOR EACH ROW EXECUTE FUNCTION membership.guard_exit_account();

-- MX3, MX4 (checked at commit)
CREATE FUNCTION membership.check_exit() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_open int;
    v_rows int;
    v_total numeric;
    v_bad int;
    v_refund_cr numeric;
    v_other int;
BEGIN
    IF NEW.status <> 'APPROVED' THEN
        RETURN NULL;
    END IF;
    IF (SELECT status FROM customer.customer WHERE tenant_id = NEW.tenant_id AND customer_id = NEW.customer_id) <> 'EXITED' THEN
        RAISE EXCEPTION 'EXIT_INCOMPLETE: the member must be EXITED' USING ERRCODE = 'P0001';
    END IF;
    SELECT count(*) INTO v_open FROM savings.deposit_account
     WHERE tenant_id = NEW.tenant_id AND customer_id = NEW.customer_id AND status <> 'CLOSED';
    SELECT count(*), COALESCE(sum(refund), 0) INTO v_rows, v_total FROM membership.exit_account
     WHERE tenant_id = NEW.tenant_id AND exit_id = NEW.exit_id;
    IF v_open > 0 OR v_total <> NEW.refund_total OR v_rows <> (SELECT count(*) FROM savings.deposit_account d
            JOIN membership.exit_account x ON x.tenant_id = d.tenant_id AND x.account_id = d.account_id
           WHERE d.tenant_id = NEW.tenant_id AND d.customer_id = NEW.customer_id AND x.exit_id = NEW.exit_id) THEN
        RAISE EXCEPTION 'EXIT_INCOMPLETE: % account(s) still open; settled % totalling % against %', v_open, v_rows, v_total,
            NEW.refund_total USING ERRCODE = 'P0001';
    END IF;
    -- every settled account's balance and interest leave in the exit entry, which credits refunds payable by the total
    SELECT count(*) INTO v_bad FROM membership.exit_account x
      JOIN savings.deposit_account a ON a.tenant_id = x.tenant_id AND a.account_id = x.account_id
     WHERE x.tenant_id = NEW.tenant_id AND x.exit_id = NEW.exit_id
       AND (COALESCE((SELECT sum(CASE l.side WHEN 'D' THEN l.amount ELSE -l.amount END) FROM ledger.journal_line l
                       WHERE l.tenant_id = x.tenant_id AND l.entry_id = NEW.entry_id AND l.subledger_account_id = x.account_id), 0)
            <> x.balance);
    SELECT COALESCE(sum(CASE side WHEN 'C' THEN amount ELSE -amount END) FILTER (WHERE account_code = NEW.gl_refunds_payable), 0)
      INTO v_refund_cr FROM ledger.journal_line WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id;
    -- interest payable debited and tax credited, per product account, exactly
    SELECT count(*) INTO v_other FROM (
        SELECT g.code, sum(g.amount) AS expected FROM (
            SELECT p.gl_interest_payable AS code, x.interest_gross AS amount
              FROM membership.exit_account x
              JOIN savings.deposit_account a ON a.tenant_id = x.tenant_id AND a.account_id = x.account_id
              JOIN savings.deposit_product p ON p.tenant_id = a.tenant_id AND p.product_code = a.product_code
             WHERE x.tenant_id = NEW.tenant_id AND x.exit_id = NEW.exit_id AND x.interest_gross > 0
            UNION ALL
            SELECT p.gl_withholding_tax, -x.interest_tax
              FROM membership.exit_account x
              JOIN savings.deposit_account a ON a.tenant_id = x.tenant_id AND a.account_id = x.account_id
              JOIN savings.deposit_product p ON p.tenant_id = a.tenant_id AND p.product_code = a.product_code
             WHERE x.tenant_id = NEW.tenant_id AND x.exit_id = NEW.exit_id AND x.interest_tax > 0) g
         GROUP BY g.code) e
      FULL JOIN (SELECT account_code AS code, sum(CASE side WHEN 'D' THEN amount ELSE -amount END) AS posted
                   FROM ledger.journal_line WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id
                    AND subledger_account_id IS NULL AND account_code <> NEW.gl_refunds_payable
                  GROUP BY account_code) p ON p.code = e.code
     WHERE COALESCE(e.expected, 0) <> COALESCE(p.posted, 0);
    IF v_bad > 0 OR v_refund_cr <> NEW.refund_total OR v_other > 0
       OR (NEW.entry_id IS NULL AND NEW.refund_total <> 0)
       OR EXISTS (SELECT 1 FROM ledger.journal_line l WHERE l.tenant_id = NEW.tenant_id AND l.entry_id = NEW.entry_id
                     AND l.subledger_account_id IS NOT NULL
                     AND l.subledger_account_id NOT IN (SELECT account_id FROM membership.exit_account
                                                         WHERE tenant_id = NEW.tenant_id AND exit_id = NEW.exit_id)) THEN
        RAISE EXCEPTION 'EXIT_POSTING_MISMATCH: the exit entry does not settle the accounts exactly (refunds credited % of %)',
            v_refund_cr, NEW.refund_total USING ERRCODE = 'P0001';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER exit_request_settled AFTER UPDATE ON membership.exit_request
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION membership.check_exit();

-- MX5
CREATE FUNCTION membership.guard_refund_payment() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    r membership.exit_request%ROWTYPE;
    v_class varchar;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'EXIT_IMMUTABLE: refund payments are rejected, not deleted' USING ERRCODE = 'P0001';
    END IF;
    SELECT * INTO r FROM membership.exit_request WHERE tenant_id = NEW.tenant_id AND exit_id = NEW.exit_id;
    IF TG_OP = 'INSERT' THEN
        SELECT account_class INTO v_class FROM ledger.gl_account WHERE tenant_id = NEW.tenant_id AND account_code = NEW.paid_from;
        IF r.status <> 'APPROVED' OR NEW.amount <> r.refund_total OR NEW.status <> 'REQUESTED' THEN
            RAISE EXCEPTION 'REFUND_INVALID: the refund of an approved exit is paid once, in full (%)', r.refund_total USING ERRCODE = 'P0001';
        END IF;
        IF v_class <> 'ASSET' OR ledger.is_control_account(NEW.tenant_id, NEW.paid_from) THEN
            RAISE EXCEPTION 'REFUND_INVALID: refunds are paid from a bank or cash ASSET account, not %', NEW.paid_from USING ERRCODE = 'P0001';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status <> 'REQUESTED'
       OR (NEW.payment_id, NEW.idempotency_key, NEW.exit_id, NEW.amount, NEW.method, NEW.paid_from, NEW.reference, NEW.requested_by,
           NEW.requested_at)
          IS DISTINCT FROM (OLD.payment_id, OLD.idempotency_key, OLD.exit_id, OLD.amount, OLD.method, OLD.paid_from, OLD.reference,
           OLD.requested_by, OLD.requested_at) THEN
        RAISE EXCEPTION 'EXIT_IMMUTABLE: a refund payment is decided once' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.decided_by = NEW.requested_by THEN
        RAISE EXCEPTION 'EXIT_SAME_PERSON: the person who recorded the payment cannot approve it' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status = 'APPROVED' AND NEW.business_date <> (SELECT current_date_ FROM tenancy.business_date WHERE tenant_id = NEW.tenant_id) THEN
        RAISE EXCEPTION 'REFUND_INVALID: refunds are paid on the business date' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER refund_payment_guard BEFORE INSERT OR UPDATE OR DELETE ON membership.refund_payment
    FOR EACH ROW EXECUTE FUNCTION membership.guard_refund_payment();

CREATE FUNCTION membership.check_refund_payment() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    r membership.exit_request%ROWTYPE;
    v_payable numeric;
    v_paid numeric;
    v_other int;
BEGIN
    IF NEW.status <> 'APPROVED' THEN
        RETURN NULL;
    END IF;
    SELECT * INTO r FROM membership.exit_request WHERE tenant_id = NEW.tenant_id AND exit_id = NEW.exit_id;
    SELECT COALESCE(sum(amount) FILTER (WHERE account_code = r.gl_refunds_payable AND side = 'D'), 0),
           COALESCE(sum(amount) FILTER (WHERE account_code = NEW.paid_from AND side = 'C'), 0),
           count(*) FILTER (WHERE NOT ((account_code = r.gl_refunds_payable AND side = 'D') OR (account_code = NEW.paid_from AND side = 'C')))
      INTO v_payable, v_paid, v_other
      FROM ledger.journal_line WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id;
    IF v_payable <> NEW.amount OR v_paid <> NEW.amount OR v_other > 0 THEN
        RAISE EXCEPTION 'REFUND_POSTING_MISMATCH: payment of % posts % / %', NEW.amount, v_payable, v_paid USING ERRCODE = 'P0001';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER refund_payment_posting AFTER UPDATE ON membership.refund_payment
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION membership.check_refund_payment();

-- MX1: the refunds account does not change while refunds are unpaid
CREATE FUNCTION membership.guard_exit_settings() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF (SELECT account_class FROM ledger.gl_account WHERE tenant_id = NEW.tenant_id AND account_code = NEW.gl_refunds_payable)
       IS DISTINCT FROM 'LIABILITY' THEN
        RAISE EXCEPTION 'EXIT_SETTINGS_INVALID: member refunds payable must be a LIABILITY account' USING ERRCODE = 'P0001';
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.gl_refunds_payable <> OLD.gl_refunds_payable AND EXISTS (
            SELECT 1 FROM membership.exit_request r WHERE r.tenant_id = NEW.tenant_id AND r.status = 'APPROVED' AND r.refund_total > 0
               AND NOT EXISTS (SELECT 1 FROM membership.refund_payment p WHERE p.tenant_id = r.tenant_id AND p.exit_id = r.exit_id
                                  AND p.status = 'APPROVED')) THEN
        RAISE EXCEPTION 'EXIT_SETTINGS_LOCKED: refunds are still unpaid from %', OLD.gl_refunds_payable USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER exit_settings_guard BEFORE INSERT OR UPDATE ON membership.exit_settings
    FOR EACH ROW EXECUTE FUNCTION membership.guard_exit_settings();

-- MX5: only exits and refund payments post to the refunds account
CREATE FUNCTION membership.check_refunds_line() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF (EXISTS (SELECT 1 FROM membership.exit_settings WHERE tenant_id = NEW.tenant_id AND gl_refunds_payable = NEW.account_code)
        OR EXISTS (SELECT 1 FROM membership.exit_request WHERE tenant_id = NEW.tenant_id AND gl_refunds_payable = NEW.account_code))
       AND NOT EXISTS (SELECT 1 FROM membership.exit_request WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id)
       AND NOT EXISTS (SELECT 1 FROM membership.refund_payment WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id) THEN
        RAISE EXCEPTION 'EXIT_DIRECT_POSTING: % holds member refunds; only exits and refund payments post to it', NEW.account_code
            USING ERRCODE = 'P0001';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER journal_line_member_refunds AFTER INSERT ON ledger.journal_line
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION membership.check_refunds_line();

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
$$;

ALTER TABLE ledger.reconciliation_result DROP CONSTRAINT reconciliation_result_check_code_check;
ALTER TABLE ledger.reconciliation_result ADD CONSTRAINT reconciliation_result_check_code_check
    CHECK (check_code IN ('SAVINGS_CONTROL', 'SAVINGS_INTEREST_PAYABLE', 'LOAN_PRINCIPAL', 'LOAN_INTEREST_RECEIVABLE',
                          'LOAN_PENALTY_RECEIVABLE', 'LOAN_OVERPAYMENT', 'LOAN_INTEREST_SUSPENSE', 'LOAN_LOSS_ALLOWANCE',
                          'SHARE_CAPITAL', 'TERM_DEPOSIT_PRINCIPAL', 'TERM_DEPOSIT_INTEREST', 'MEMBER_REFUNDS_PAYABLE'));

ALTER TABLE membership.exit_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE membership.exit_settings FORCE ROW LEVEL SECURITY;
ALTER TABLE membership.exit_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE membership.exit_request FORCE ROW LEVEL SECURITY;
ALTER TABLE membership.exit_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE membership.exit_account FORCE ROW LEVEL SECURITY;
ALTER TABLE membership.refund_payment ENABLE ROW LEVEL SECURITY;
ALTER TABLE membership.refund_payment FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON membership.exit_settings
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
CREATE POLICY tenant_isolation ON membership.exit_request
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
CREATE POLICY tenant_isolation ON membership.exit_account
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
CREATE POLICY tenant_isolation ON membership.refund_payment
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());

GRANT USAGE ON SCHEMA membership TO mfi_app;
GRANT SELECT, INSERT, UPDATE ON membership.exit_settings TO mfi_app;
GRANT SELECT, INSERT, UPDATE ON membership.exit_request TO mfi_app;
GRANT SELECT, INSERT ON membership.exit_account TO mfi_app;
GRANT SELECT, INSERT, UPDATE ON membership.refund_payment TO mfi_app;
GRANT EXECUTE ON FUNCTION membership.blockers(uuid, uuid), membership.unposted_interest(uuid, uuid) TO mfi_app;
