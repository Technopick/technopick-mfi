-- =============================================================================
-- V22: Subledger-to-control reconciliation results, recorded by end of day.
--   G1  one result per business date, check and GL account; written only during that date's
--       RUNNING end of day; never changed afterwards
--   G2  difference = ledger balance - subledger balance (both in the account's natural sign)
-- =============================================================================

CREATE TABLE ledger.reconciliation_result (
    tenant_id          uuid          NOT NULL REFERENCES tenancy.tenant (tenant_id),
    business_date      date          NOT NULL,
    check_code         varchar(32)   NOT NULL CHECK (check_code IN ('SAVINGS_CONTROL', 'SAVINGS_INTEREST_PAYABLE',
                                         'LOAN_PRINCIPAL', 'LOAN_INTEREST_RECEIVABLE', 'LOAN_PENALTY_RECEIVABLE',
                                         'LOAN_OVERPAYMENT')),
    account_code       varchar(40)   NOT NULL,
    currency           char(3)       NOT NULL,
    ledger_balance     numeric(19,4) NOT NULL,
    subledger_balance  numeric(19,4) NOT NULL,
    difference         numeric(19,4) GENERATED ALWAYS AS (ledger_balance - subledger_balance) STORED,
    items              int           NOT NULL CHECK (items >= 0),
    checked_at         timestamptz   NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, business_date, check_code, account_code),
    FOREIGN KEY (tenant_id, account_code) REFERENCES ledger.gl_account (tenant_id, account_code)
);

CREATE FUNCTION ledger.check_reconciliation_result() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM tenancy.business_date b
                     JOIN ledger.eod_run r ON r.tenant_id = b.tenant_id AND r.business_date = b.current_date_
                    WHERE b.tenant_id = NEW.tenant_id AND b.current_date_ = NEW.business_date AND r.status = 'RUNNING') THEN
        RAISE EXCEPTION 'EOD_WRONG_DATE: reconciliation results are recorded during the EOD of %', NEW.business_date
            USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER reconciliation_result_check BEFORE INSERT ON ledger.reconciliation_result
    FOR EACH ROW EXECUTE FUNCTION ledger.check_reconciliation_result();
CREATE TRIGGER reconciliation_result_immutable BEFORE UPDATE OR DELETE ON ledger.reconciliation_result
    FOR EACH ROW EXECUTE FUNCTION ledger.reject_mutation();

ALTER TABLE ledger.reconciliation_result ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.reconciliation_result FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ledger.reconciliation_result
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
GRANT SELECT, INSERT ON ledger.reconciliation_result TO mfi_app;
