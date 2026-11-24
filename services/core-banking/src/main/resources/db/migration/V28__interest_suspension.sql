-- =============================================================================
-- V28 (increment 31, ADR-0031): interest in suspense on non-performing loans (Reg 42 of
-- LN 95/2010: interest on loans classified substandard, doubtful or loss is not income until paid).
--
-- Switched on per tenant by finance (ledger.prudential_settings). At every end of day a
-- non-performing loan's interest in suspense is brought to its unpaid accrued interest, and a
-- performing loan's to zero; the difference is moved between interest income and the suspense
-- account (a contra asset, credit balance). Interest paid on a suspended loan therefore becomes
-- income at that day's close. Closure releases what is left to income; write-off releases it
-- against the write-off expense, because suspended interest was never income.
--
-- lending.interest_suspense records each movement per loan; the database checks that every
-- movement matches its journal entry, that no loan's suspense goes negative or above its unpaid
-- interest, and that nothing else posts to a suspense account.
-- =============================================================================
ALTER TABLE ledger.prudential_settings
    ADD COLUMN interest_suspension_enabled boolean NOT NULL DEFAULT false,
    ADD COLUMN interest_suspense_account varchar(40),
    ADD FOREIGN KEY (tenant_id, interest_suspense_account) REFERENCES ledger.gl_account (tenant_id, account_code),
    ADD CHECK (NOT interest_suspension_enabled OR interest_suspense_account IS NOT NULL);

CREATE FUNCTION ledger.check_suspense_account_setting() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_class text;
BEGIN
    IF NEW.interest_suspense_account IS NULL
       OR NEW.interest_suspense_account IS NOT DISTINCT FROM (CASE WHEN TG_OP = 'UPDATE' THEN OLD.interest_suspense_account END) THEN
        RETURN NEW;
    END IF;
    SELECT account_class INTO v_class FROM ledger.gl_account
     WHERE tenant_id = NEW.tenant_id AND account_code = NEW.interest_suspense_account;
    IF v_class IS DISTINCT FROM 'ASSET' THEN
        RAISE EXCEPTION 'SUSPENSE_ACCOUNT_INVALID: % must be an asset account (interest in suspense is deducted from loans)',
            NEW.interest_suspense_account USING ERRCODE = 'P0001';
    END IF;
    IF ledger.is_control_account(NEW.tenant_id, NEW.interest_suspense_account)
       OR EXISTS (SELECT 1 FROM ledger.journal_line WHERE tenant_id = NEW.tenant_id AND account_code = NEW.interest_suspense_account)
       OR EXISTS (SELECT 1 FROM ledger.regulatory_mapping WHERE tenant_id = NEW.tenant_id AND account_code = NEW.interest_suspense_account) THEN
        RAISE EXCEPTION 'SUSPENSE_ACCOUNT_INVALID: % is already used; interest in suspense needs an account of its own',
            NEW.interest_suspense_account USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER prudential_settings_suspense_account BEFORE INSERT OR UPDATE ON ledger.prudential_settings
    FOR EACH ROW EXECUTE FUNCTION ledger.check_suspense_account_setting();

CREATE TABLE lending.interest_suspense (
    tenant_id      uuid          NOT NULL,
    movement_id    bigint        GENERATED ALWAYS AS IDENTITY,
    loan_id        uuid          NOT NULL,
    business_date  date          NOT NULL,
    account_code   varchar(40)   NOT NULL,
    amount         numeric(19,2) NOT NULL CHECK (amount <> 0),
    reason         varchar(16)   NOT NULL CHECK (reason IN ('END_OF_DAY', 'CLOSURE', 'WRITE_OFF')),
    entry_id       uuid          NOT NULL,
    PRIMARY KEY (tenant_id, movement_id),
    UNIQUE (tenant_id, entry_id),
    FOREIGN KEY (tenant_id, loan_id) REFERENCES lending.loan (tenant_id, loan_id),
    FOREIGN KEY (tenant_id, account_code) REFERENCES ledger.gl_account (tenant_id, account_code),
    FOREIGN KEY (tenant_id, entry_id) REFERENCES ledger.journal_entry (tenant_id, entry_id)
);
CREATE INDEX interest_suspense_loan ON lending.interest_suspense (tenant_id, loan_id, business_date);
-- Every journal line asks whether its account is a suspense account (check_suspense_line): keep that an index probe.
CREATE INDEX interest_suspense_account ON lending.interest_suspense (tenant_id, account_code, entry_id);
COMMENT ON COLUMN lending.interest_suspense.amount IS 'Positive: interest moved into suspense; negative: released';

CREATE FUNCTION lending.interest_in_suspense(p_tenant uuid, p_loan uuid) RETURNS numeric
    LANGUAGE sql STABLE
AS $$
    SELECT COALESCE(sum(amount), 0) FROM lending.interest_suspense WHERE tenant_id = p_tenant AND loan_id = p_loan
$$;

CREATE FUNCTION lending.guard_interest_suspense() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'SUSPENSE_IMMUTABLE: interest suspense movements are never changed or deleted' USING ERRCODE = 'P0001';
END $$;

CREATE TRIGGER interest_suspense_guard BEFORE UPDATE OR DELETE ON lending.interest_suspense
    FOR EACH ROW EXECUTE FUNCTION lending.guard_interest_suspense();

-- At commit: the movement equals its entry's net credit on the suspense account, and the loan's
-- suspense stays between zero and its unpaid accrued interest.
CREATE FUNCTION lending.check_interest_suspense() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_posted numeric;
    v_balance numeric;
    v_receivable numeric;
BEGIN
    SELECT COALESCE(sum(CASE side WHEN 'C' THEN amount ELSE -amount END), 0) INTO v_posted
      FROM ledger.journal_line WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id AND account_code = NEW.account_code;
    IF v_posted <> NEW.amount THEN
        RAISE EXCEPTION 'SUSPENSE_MISMATCH: movement % but entry posts % to %', NEW.amount, v_posted, NEW.account_code
            USING ERRCODE = 'P0001';
    END IF;
    v_balance := lending.interest_in_suspense(NEW.tenant_id, NEW.loan_id);
    v_receivable := lending.loan_interest_receivable(NEW.tenant_id, NEW.loan_id);
    IF v_balance < 0 OR v_balance > v_receivable THEN
        RAISE EXCEPTION 'SUSPENSE_OUT_OF_RANGE: loan % would hold % in suspense against % unpaid interest',
            NEW.loan_id, v_balance, v_receivable USING ERRCODE = 'P0001';
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER interest_suspense_matches AFTER INSERT ON lending.interest_suspense
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION lending.check_interest_suspense();

-- At commit: every line on a suspense account belongs to a suspense movement.
CREATE FUNCTION ledger.check_suspense_line() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF (EXISTS (SELECT 1 FROM ledger.prudential_settings WHERE tenant_id = NEW.tenant_id AND interest_suspense_account = NEW.account_code)
        OR EXISTS (SELECT 1 FROM lending.interest_suspense WHERE tenant_id = NEW.tenant_id AND account_code = NEW.account_code))
       AND NOT EXISTS (SELECT 1 FROM lending.interest_suspense
                        WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.entry_id AND account_code = NEW.account_code) THEN
        RAISE EXCEPTION 'SUSPENSE_DIRECT_POSTING: % is an interest suspense account; only suspense movements post to it',
            NEW.account_code USING ERRCODE = 'P0001';
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER journal_line_suspense AFTER INSERT ON ledger.journal_line
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ledger.check_suspense_line();

-- The suspense account is a control account: manual journals are refused on it (V23).
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
$$;

ALTER TABLE ledger.reconciliation_result DROP CONSTRAINT reconciliation_result_check_code_check;
ALTER TABLE ledger.reconciliation_result ADD CONSTRAINT reconciliation_result_check_code_check
    CHECK (check_code IN ('SAVINGS_CONTROL', 'SAVINGS_INTEREST_PAYABLE', 'LOAN_PRINCIPAL', 'LOAN_INTEREST_RECEIVABLE',
                          'LOAN_PENALTY_RECEIVABLE', 'LOAN_OVERPAYMENT', 'LOAN_INTEREST_SUSPENSE'));

ALTER TABLE lending.interest_suspense ENABLE ROW LEVEL SECURITY;
ALTER TABLE lending.interest_suspense FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON lending.interest_suspense
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());

GRANT SELECT, INSERT ON lending.interest_suspense TO mfi_app;
GRANT EXECUTE ON FUNCTION lending.interest_in_suspense(uuid, uuid) TO mfi_app;
