-- =============================================================================
-- V35: M-Pesa statement reconciliation (ADR-0038).
--   R1  finance uploads one statement per M-Pesa account (paybill or B2C short code) per calendar
--       day; a wrongly uploaded statement is withdrawn, never deleted
--   R2  statement lines are kept as uploaded; only their reconciliation fields change, and nothing
--       changes once the day is signed off
--   R3  a MATCHED line points at the payment or disbursement with the same M-Pesa receipt, account
--       and amount; each payment or disbursement is matched by at most one live line
--   R4  an exception is resolved only by linking ledger entries that move the clearing account by
--       exactly the linked lines' total, or by a written explanation; who resolved it is recorded
--   R5  sign-off: the day is closed, no exception is open, the signer neither uploaded the
--       statement nor resolved any of its lines, the ledger figures are re-derived here, and a
--       balance difference or unmatched ledger entries need a note
-- Lines keep only a masked phone number of the other party; names are not stored.
-- =============================================================================
CREATE TABLE payments.statement_upload (
    tenant_id          uuid          NOT NULL REFERENCES tenancy.tenant (tenant_id),
    upload_id          uuid          NOT NULL,
    account_kind       varchar(8)    NOT NULL CHECK (account_kind IN ('PAYBILL', 'B2C')),
    short_code         varchar(12)   NOT NULL,
    gl_clearing        varchar(40)   NOT NULL,
    statement_date     date          NOT NULL,
    file_name          varchar(200)  NOT NULL,
    file_sha256        char(64)      NOT NULL CHECK (file_sha256 ~ '^[0-9a-f]{64}$'),
    line_count         int           NOT NULL CHECK (line_count >= 0),
    opening_balance    numeric(19,4) NOT NULL,
    closing_balance    numeric(19,4) NOT NULL,
    total_paid_in      numeric(19,4) NOT NULL CHECK (total_paid_in >= 0),
    total_withdrawn    numeric(19,4) NOT NULL CHECK (total_withdrawn >= 0),
    balance_chain_ok   boolean       NOT NULL,
    status             varchar(12)   NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'SIGNED_OFF', 'WITHDRAWN')),
    uploaded_by        varchar(100)  NOT NULL,
    uploaded_at        timestamptz   NOT NULL DEFAULT clock_timestamp(),
    signed_off_by      varchar(100),
    signed_off_at      timestamptz,
    ledger_balance     numeric(19,4),
    difference         numeric(19,4),
    ledger_only_count  int,
    ledger_only_amount numeric(19,4),
    note               varchar(500),
    withdrawn_by       varchar(100),
    withdrawn_at       timestamptz,
    PRIMARY KEY (tenant_id, upload_id),
    FOREIGN KEY (tenant_id, gl_clearing) REFERENCES ledger.gl_account (tenant_id, account_code),
    CHECK (opening_balance + total_paid_in - total_withdrawn = closing_balance),
    CHECK ((status = 'SIGNED_OFF') = (signed_off_by IS NOT NULL AND signed_off_at IS NOT NULL AND ledger_balance IS NOT NULL
                                      AND difference IS NOT NULL AND ledger_only_count IS NOT NULL
                                      AND ledger_only_amount IS NOT NULL)),
    CHECK ((status = 'WITHDRAWN') = (withdrawn_by IS NOT NULL AND withdrawn_at IS NOT NULL AND note IS NOT NULL)),
    CHECK (signed_off_by IS NULL OR signed_off_by <> uploaded_by)
);
CREATE UNIQUE INDEX statement_upload_one_per_day ON payments.statement_upload (tenant_id, account_kind, short_code, statement_date)
    WHERE status <> 'WITHDRAWN';
CREATE UNIQUE INDEX statement_upload_file_once ON payments.statement_upload (tenant_id, file_sha256)
    WHERE status <> 'WITHDRAWN';

CREATE TABLE payments.statement_line (
    tenant_id          uuid          NOT NULL,
    upload_id          uuid          NOT NULL,
    line_no            int           NOT NULL CHECK (line_no > 0),
    receipt_no         varchar(20)   NOT NULL CHECK (receipt_no ~ '^[A-Z0-9]{8,20}$'),
    completed_at       timestamptz   NOT NULL,
    initiated_at       timestamptz,
    details            varchar(200)  NOT NULL,
    txn_status         varchar(20)   NOT NULL,
    paid_in            numeric(19,4) NOT NULL CHECK (paid_in >= 0),
    withdrawn          numeric(19,4) NOT NULL CHECK (withdrawn >= 0),
    balance            numeric(19,4),
    reason_type        varchar(80),
    other_party_masked varchar(20),
    linked_txn         varchar(20),
    account_ref        varchar(40),
    category           varchar(12)   NOT NULL
        CHECK (category IN ('RECEIPT', 'PAYMENT', 'CHARGE', 'TRANSFER', 'FUNDING', 'REVERSAL', 'OTHER')),
    status             varchar(12)   NOT NULL CHECK (status IN ('MATCHED', 'EXCEPTION', 'RESOLVED', 'IGNORED')),
    exception_code     varchar(32)
        CHECK (exception_code IN ('NOT_IN_LEDGER', 'AMOUNT_MISMATCH', 'DISBURSEMENT_ATTENTION', 'ALREADY_MATCHED')),
    payment_id         uuid,
    disbursement_id    uuid,
    journal_entry_id   uuid,
    resolution         varchar(16)   CHECK (resolution IN ('LINKED_JOURNAL', 'EXPLAINED')),
    resolution_note    varchar(300),
    resolved_by        varchar(100),
    resolved_at        timestamptz,
    active             boolean       NOT NULL DEFAULT true,
    PRIMARY KEY (tenant_id, upload_id, line_no),
    FOREIGN KEY (tenant_id, upload_id) REFERENCES payments.statement_upload (tenant_id, upload_id),
    FOREIGN KEY (tenant_id, payment_id) REFERENCES payments.provider_payment (tenant_id, payment_id),
    FOREIGN KEY (tenant_id, disbursement_id) REFERENCES payments.b2c_disbursement (tenant_id, disbursement_id),
    FOREIGN KEY (tenant_id, journal_entry_id) REFERENCES ledger.journal_entry (tenant_id, entry_id),
    CHECK (NOT (paid_in > 0 AND withdrawn > 0)),
    CHECK ((status = 'IGNORED') = (txn_status <> 'Completed')),
    CHECK (status <> 'MATCHED' OR (num_nonnulls(payment_id, disbursement_id) = 1 AND journal_entry_id IS NULL
                                    AND exception_code IS NULL AND resolution IS NULL)),
    CHECK (status <> 'EXCEPTION' OR (exception_code IS NOT NULL AND num_nonnulls(payment_id, disbursement_id, journal_entry_id) = 0
                                      AND resolution IS NULL)),
    CHECK (status <> 'RESOLVED' OR (exception_code IS NOT NULL AND resolution IS NOT NULL AND resolved_by IS NOT NULL
                                     AND resolved_at IS NOT NULL AND length(btrim(resolution_note)) >= 10
                                     AND num_nonnulls(payment_id, disbursement_id) = 0
                                     AND (resolution = 'LINKED_JOURNAL') = (journal_entry_id IS NOT NULL))),
    CHECK (status <> 'IGNORED' OR (exception_code IS NULL AND num_nonnulls(payment_id, disbursement_id, journal_entry_id) = 0))
);
CREATE UNIQUE INDEX statement_line_payment_once ON payments.statement_line (tenant_id, payment_id)
    WHERE active AND payment_id IS NOT NULL;
CREATE UNIQUE INDEX statement_line_disbursement_once ON payments.statement_line (tenant_id, disbursement_id)
    WHERE active AND disbursement_id IS NOT NULL;
CREATE INDEX statement_line_receipt ON payments.statement_line (tenant_id, receipt_no);
CREATE INDEX statement_line_journal ON payments.statement_line (tenant_id, journal_entry_id) WHERE journal_entry_id IS NOT NULL;

-- The clearing account configured for an M-Pesa account.
CREATE FUNCTION payments.statement_clearing_account(p_tenant uuid, p_kind varchar, p_short_code varchar) RETURNS varchar
    LANGUAGE sql STABLE
AS $$
    SELECT CASE p_kind
        WHEN 'PAYBILL' THEN (SELECT gl_clearing FROM payments.mpesa_paybill WHERE tenant_id = p_tenant AND short_code = p_short_code)
        WHEN 'B2C' THEN (SELECT gl_clearing FROM payments.mpesa_b2c_account WHERE tenant_id = p_tenant AND short_code = p_short_code)
    END
$$;

-- Ledger balance (debits less credits) of an account at the end of a business date, all branches.
CREATE FUNCTION payments.clearing_balance(p_tenant uuid, p_account varchar, p_as_of date) RETURNS numeric
    LANGUAGE sql STABLE
AS $$
    SELECT COALESCE(sum(CASE l.side WHEN 'D' THEN l.amount ELSE -l.amount END), 0)
      FROM ledger.journal_line l
      JOIN ledger.journal_entry e ON e.tenant_id = l.tenant_id AND e.entry_id = l.entry_id
     WHERE l.tenant_id = p_tenant AND l.account_code = p_account AND e.business_date <= p_as_of
$$;

-- Entries that moved the statement's clearing account on its date and that no live statement line
-- accounts for (through its payment, its disbursement or a linked journal).
CREATE FUNCTION payments.statement_ledger_only(p_tenant uuid, p_upload uuid)
    RETURNS TABLE (entry_id uuid, business_date date, source varchar, reference varchar, posted_at timestamptz, net numeric)
    LANGUAGE sql STABLE
AS $$
    WITH u AS (
        SELECT tenant_id, gl_clearing, statement_date FROM payments.statement_upload
         WHERE tenant_id = p_tenant AND upload_id = p_upload
    ), accounted AS (
        SELECT p.journal_entry_id AS entry_id FROM payments.statement_line s
          JOIN payments.provider_payment p ON p.tenant_id = s.tenant_id AND p.payment_id = s.payment_id
         WHERE s.tenant_id = p_tenant AND s.active
        UNION
        SELECT d.journal_entry_id FROM payments.statement_line s
          JOIN payments.b2c_disbursement d ON d.tenant_id = s.tenant_id AND d.disbursement_id = s.disbursement_id
         WHERE s.tenant_id = p_tenant AND s.active
        UNION
        SELECT s.journal_entry_id FROM payments.statement_line s
         WHERE s.tenant_id = p_tenant AND s.active AND s.journal_entry_id IS NOT NULL
    )
    SELECT e.entry_id, e.business_date, e.source, COALESCE(e.switch_reference, e.idempotency_key)::varchar, e.posted_at,
           sum(CASE l.side WHEN 'D' THEN l.amount ELSE -l.amount END)
      FROM u
      JOIN ledger.journal_line l ON l.tenant_id = u.tenant_id AND l.account_code = u.gl_clearing
      JOIN ledger.journal_entry e ON e.tenant_id = l.tenant_id AND e.entry_id = l.entry_id AND e.business_date = u.statement_date
     WHERE e.entry_id NOT IN (SELECT a.entry_id FROM accounted a WHERE a.entry_id IS NOT NULL)
     GROUP BY e.entry_id, e.business_date, e.source, e.switch_reference, e.idempotency_key, e.posted_at
$$;

CREATE FUNCTION payments.guard_statement_upload() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_today   date;
    v_ledger  numeric;
    v_count   int;
    v_amount  numeric;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'STATEMENT_IMMUTABLE: statements are withdrawn, never deleted' USING ERRCODE = 'P0001';
    END IF;
    SELECT current_date_ INTO v_today FROM tenancy.business_date WHERE tenant_id = NEW.tenant_id;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'OPEN' THEN
            RAISE EXCEPTION 'STATEMENT_INVALID: statements start OPEN' USING ERRCODE = 'P0001';
        END IF;
        IF NEW.statement_date > v_today THEN
            RAISE EXCEPTION 'STATEMENT_INVALID: statement date % is after the business date %', NEW.statement_date, v_today
                USING ERRCODE = 'P0001';
        END IF;
        IF NEW.gl_clearing IS DISTINCT FROM payments.statement_clearing_account(NEW.tenant_id, NEW.account_kind, NEW.short_code) THEN
            RAISE EXCEPTION 'STATEMENT_INVALID: % short code % is not configured with clearing account %',
                NEW.account_kind, NEW.short_code, NEW.gl_clearing USING ERRCODE = 'P0001';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status <> 'OPEN' THEN
        RAISE EXCEPTION 'STATEMENT_IMMUTABLE: statement is %', OLD.status USING ERRCODE = 'P0001';
    END IF;
    IF (to_jsonb(NEW) - 'status' - 'signed_off_by' - 'signed_off_at' - 'ledger_balance' - 'difference' - 'ledger_only_count'
            - 'ledger_only_amount' - 'note' - 'withdrawn_by' - 'withdrawn_at')
       IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'signed_off_by' - 'signed_off_at' - 'ledger_balance' - 'difference'
            - 'ledger_only_count' - 'ledger_only_amount' - 'note' - 'withdrawn_by' - 'withdrawn_at') THEN
        RAISE EXCEPTION 'STATEMENT_IMMUTABLE: the uploaded statement never changes' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.status = 'WITHDRAWN' THEN
        IF EXISTS (SELECT 1 FROM payments.statement_line WHERE tenant_id = NEW.tenant_id AND upload_id = NEW.upload_id AND active) THEN
            RAISE EXCEPTION 'STATEMENT_INVALID: release the statement''s lines before withdrawing it' USING ERRCODE = 'P0001';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.status = 'SIGNED_OFF' THEN
        IF NEW.statement_date >= v_today THEN
            RAISE EXCEPTION 'RECON_DAY_OPEN: % is still the business date; sign off after end of day', NEW.statement_date
                USING ERRCODE = 'P0001';
        END IF;
        IF EXISTS (SELECT 1 FROM payments.statement_line WHERE tenant_id = NEW.tenant_id AND upload_id = NEW.upload_id
                                                           AND active AND status = 'EXCEPTION') THEN
            RAISE EXCEPTION 'RECON_EXCEPTIONS_OPEN: resolve every exception before signing off' USING ERRCODE = 'P0001';
        END IF;
        IF EXISTS (SELECT 1 FROM payments.statement_line WHERE tenant_id = NEW.tenant_id AND upload_id = NEW.upload_id
                                                           AND resolved_by = NEW.signed_off_by) THEN
            RAISE EXCEPTION 'RECON_SAME_PERSON: the signer resolved lines on this statement' USING ERRCODE = 'P0001';
        END IF;
        v_ledger := payments.clearing_balance(NEW.tenant_id, NEW.gl_clearing, NEW.statement_date);
        SELECT count(*), COALESCE(sum(net), 0) INTO v_count, v_amount
          FROM payments.statement_ledger_only(NEW.tenant_id, NEW.upload_id);
        IF NEW.ledger_balance <> v_ledger OR NEW.difference <> NEW.closing_balance - v_ledger
           OR NEW.ledger_only_count <> v_count OR NEW.ledger_only_amount <> v_amount THEN
            RAISE EXCEPTION 'RECON_STALE: the ledger changed since the figures were read; review and sign off again'
                USING ERRCODE = 'P0001';
        END IF;
        IF (NEW.difference <> 0 OR NEW.ledger_only_count > 0) AND length(btrim(COALESCE(NEW.note, ''))) < 10 THEN
            RAISE EXCEPTION 'RECON_NOTE_REQUIRED: explain the balance difference and the unmatched ledger entries'
                USING ERRCODE = 'P0001';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER statement_upload_guard BEFORE INSERT OR UPDATE OR DELETE ON payments.statement_upload
    FOR EACH ROW EXECUTE FUNCTION payments.guard_statement_upload();

CREATE FUNCTION payments.guard_statement_line() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    u payments.statement_upload%ROWTYPE;
    p payments.provider_payment%ROWTYPE;
    d payments.b2c_disbursement%ROWTYPE;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'STATEMENT_IMMUTABLE: statement lines are never deleted' USING ERRCODE = 'P0001';
    END IF;
    SELECT * INTO u FROM payments.statement_upload WHERE tenant_id = NEW.tenant_id AND upload_id = NEW.upload_id;
    IF u.status <> 'OPEN' THEN
        RAISE EXCEPTION 'STATEMENT_IMMUTABLE: statement is %', u.status USING ERRCODE = 'P0001';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF NOT OLD.active THEN
            RAISE EXCEPTION 'STATEMENT_IMMUTABLE: line was released' USING ERRCODE = 'P0001';
        END IF;
        IF (to_jsonb(NEW) - 'status' - 'exception_code' - 'payment_id' - 'disbursement_id' - 'journal_entry_id' - 'resolution'
                - 'resolution_note' - 'resolved_by' - 'resolved_at' - 'active')
           IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'exception_code' - 'payment_id' - 'disbursement_id' - 'journal_entry_id'
                - 'resolution' - 'resolution_note' - 'resolved_by' - 'resolved_at' - 'active') THEN
            RAISE EXCEPTION 'STATEMENT_IMMUTABLE: statement lines are kept as uploaded' USING ERRCODE = 'P0001';
        END IF;
        IF NOT NEW.active THEN
            IF (to_jsonb(NEW) - 'active') IS DISTINCT FROM (to_jsonb(OLD) - 'active') THEN
                RAISE EXCEPTION 'STATEMENT_INVALID: releasing a line changes nothing else' USING ERRCODE = 'P0001';
            END IF;
            RETURN NEW;
        END IF;
        IF OLD.status IN ('MATCHED', 'RESOLVED', 'IGNORED') AND (to_jsonb(NEW) IS DISTINCT FROM to_jsonb(OLD)) THEN
            RAISE EXCEPTION 'STATEMENT_IMMUTABLE: line is already %', OLD.status USING ERRCODE = 'P0001';
        END IF;
    ELSE
        IF NOT NEW.active OR NEW.status = 'RESOLVED' THEN
            RAISE EXCEPTION 'STATEMENT_INVALID: lines start live and unresolved' USING ERRCODE = 'P0001';
        END IF;
    END IF;
    IF NEW.status = 'MATCHED' THEN
        IF NEW.payment_id IS NOT NULL THEN
            SELECT * INTO p FROM payments.provider_payment WHERE tenant_id = NEW.tenant_id AND payment_id = NEW.payment_id;
            IF u.account_kind <> 'PAYBILL' OR p.provider_ref <> NEW.receipt_no OR p.short_code <> u.short_code
               OR p.amount <> NEW.paid_in THEN
                RAISE EXCEPTION 'STATEMENT_MATCH_INVALID: line % does not match payment %', NEW.line_no, p.provider_ref
                    USING ERRCODE = 'P0001';
            END IF;
        ELSE
            SELECT * INTO d FROM payments.b2c_disbursement WHERE tenant_id = NEW.tenant_id AND disbursement_id = NEW.disbursement_id;
            IF u.account_kind <> 'B2C' OR d.transaction_id IS DISTINCT FROM NEW.receipt_no OR d.short_code <> u.short_code
               OR d.status <> 'COMPLETED' OR d.amount <> NEW.withdrawn THEN
                RAISE EXCEPTION 'STATEMENT_MATCH_INVALID: line % does not match disbursement %', NEW.line_no, d.transaction_id
                    USING ERRCODE = 'P0001';
            END IF;
        END IF;
    END IF;
    IF NEW.status = 'RESOLVED' AND TG_OP = 'UPDATE' AND OLD.status <> 'EXCEPTION' THEN
        RAISE EXCEPTION 'STATEMENT_INVALID: only exceptions are resolved' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER statement_line_guard BEFORE INSERT OR UPDATE OR DELETE ON payments.statement_line
    FOR EACH ROW EXECUTE FUNCTION payments.guard_statement_line();

-- R4: the live lines linked to a journal entry add up to what that entry moved on the clearing
-- account, and the entry is not already accounted for by a payment or a disbursement.
CREATE FUNCTION payments.check_statement_link() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_gl      varchar;
    v_lines   numeric;
    v_entry   numeric;
    v_gls     int;
BEGIN
    IF NEW.journal_entry_id IS NULL THEN
        RETURN NULL;
    END IF;
    SELECT count(DISTINCT u.gl_clearing), min(u.gl_clearing), COALESCE(sum(s.paid_in - s.withdrawn), 0)
      INTO v_gls, v_gl, v_lines
      FROM payments.statement_line s
      JOIN payments.statement_upload u ON u.tenant_id = s.tenant_id AND u.upload_id = s.upload_id
     WHERE s.tenant_id = NEW.tenant_id AND s.journal_entry_id = NEW.journal_entry_id AND s.active;
    IF v_gls = 0 THEN
        RETURN NULL;  -- every line linked to it was released
    END IF;
    IF v_gls > 1 THEN
        RAISE EXCEPTION 'STATEMENT_LINK_INVALID: one entry is linked from statements of different clearing accounts'
            USING ERRCODE = 'P0001';
    END IF;
    IF EXISTS (SELECT 1 FROM payments.provider_payment WHERE tenant_id = NEW.tenant_id AND journal_entry_id = NEW.journal_entry_id)
       OR EXISTS (SELECT 1 FROM payments.b2c_disbursement WHERE tenant_id = NEW.tenant_id AND journal_entry_id = NEW.journal_entry_id) THEN
        RAISE EXCEPTION 'STATEMENT_LINK_INVALID: entry belongs to an M-Pesa payment or disbursement; it is matched, not linked'
            USING ERRCODE = 'P0001';
    END IF;
    SELECT COALESCE(sum(CASE side WHEN 'D' THEN amount ELSE -amount END), 0) INTO v_entry
      FROM ledger.journal_line WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.journal_entry_id AND account_code = v_gl;
    IF v_entry <> v_lines THEN
        RAISE EXCEPTION 'STATEMENT_LINK_INVALID: the entry moves % on %, the linked lines total %', v_entry, v_gl, v_lines
            USING ERRCODE = 'P0001';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER statement_line_link AFTER INSERT OR UPDATE ON payments.statement_line
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION payments.check_statement_link();

ALTER TABLE payments.statement_upload ENABLE ROW LEVEL SECURITY;
ALTER TABLE payments.statement_upload FORCE ROW LEVEL SECURITY;
ALTER TABLE payments.statement_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE payments.statement_line FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payments.statement_upload
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
CREATE POLICY tenant_isolation ON payments.statement_line
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());

GRANT SELECT, INSERT, UPDATE ON payments.statement_upload TO mfi_app;
GRANT SELECT, INSERT, UPDATE ON payments.statement_line TO mfi_app;
GRANT EXECUTE ON FUNCTION payments.statement_clearing_account(uuid, varchar, varchar) TO mfi_app;
GRANT EXECUTE ON FUNCTION payments.clearing_balance(uuid, varchar, date) TO mfi_app;
GRANT EXECUTE ON FUNCTION payments.statement_ledger_only(uuid, uuid) TO mfi_app;
