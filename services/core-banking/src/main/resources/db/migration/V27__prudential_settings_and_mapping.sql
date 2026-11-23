-- =============================================================================
-- V27 (increment 30, ADR-0030): inputs for SASRA prudential returns.
--
-- ledger.prudential_settings: one row per tenant. The defaults are the minimums in the Sacco
-- Societies (Deposit-taking Sacco Business) Regulations, 2010 (LN 95/2010): Reg 9 capital,
-- Reg 13 liquidity, Regs 41 and 44 loan classification and loan loss allowance. Compliance must
-- confirm them for each tenant (the Authority may set higher ratios under Reg 10).
--
-- ledger.regulatory_mapping: which ledger accounts feed which line of the returns. Each line only
-- accepts accounts of the class that makes sense for it, so a mapping cannot turn a liability into
-- capital. Accounts not mapped are listed by the report, never silently used.
-- =============================================================================
CREATE TABLE ledger.prudential_settings (
    tenant_id                    uuid          PRIMARY KEY REFERENCES tenancy.tenant (tenant_id),
    min_core_capital             numeric(19,2) NOT NULL DEFAULT 10000000 CHECK (min_core_capital >= 0),
    min_core_to_assets_pct       numeric(5,2)  NOT NULL DEFAULT 10 CHECK (min_core_to_assets_pct BETWEEN 0 AND 100),
    min_institutional_to_assets_pct numeric(5,2) NOT NULL DEFAULT 8 CHECK (min_institutional_to_assets_pct BETWEEN 0 AND 100),
    min_core_to_deposits_pct     numeric(5,2)  NOT NULL DEFAULT 8 CHECK (min_core_to_deposits_pct BETWEEN 0 AND 100),
    min_liquidity_pct            numeric(5,2)  NOT NULL DEFAULT 15 CHECK (min_liquidity_pct BETWEEN 0 AND 100),
    watch_max_dpd                int           NOT NULL DEFAULT 30,
    substandard_max_dpd          int           NOT NULL DEFAULT 180,
    doubtful_max_dpd             int           NOT NULL DEFAULT 360,
    provision_performing_pct     numeric(5,2)  NOT NULL DEFAULT 1,
    provision_watch_pct          numeric(5,2)  NOT NULL DEFAULT 5,
    provision_substandard_pct    numeric(5,2)  NOT NULL DEFAULT 25,
    provision_doubtful_pct       numeric(5,2)  NOT NULL DEFAULT 50,
    provision_loss_pct           numeric(5,2)  NOT NULL DEFAULT 100,
    include_current_year_surplus boolean       NOT NULL DEFAULT true,
    updated_by                   varchar(200)  NOT NULL,
    updated_at                   timestamptz   NOT NULL DEFAULT clock_timestamp(),
    CHECK (0 < watch_max_dpd AND watch_max_dpd < substandard_max_dpd AND substandard_max_dpd < doubtful_max_dpd),
    CHECK (0 <= provision_performing_pct AND provision_performing_pct <= provision_watch_pct
           AND provision_watch_pct <= provision_substandard_pct AND provision_substandard_pct <= provision_doubtful_pct
           AND provision_doubtful_pct <= provision_loss_pct AND provision_loss_pct <= 100)
);

CREATE TABLE ledger.regulatory_line (
    line          varchar(40)  PRIMARY KEY,
    account_class varchar(16)  NOT NULL CHECK (account_class IN ('ASSET', 'LIABILITY', 'EQUITY')),
    description   varchar(300) NOT NULL
);

INSERT INTO ledger.regulatory_line (line, account_class, description) VALUES
  ('SHARE_CAPITAL',          'EQUITY',    'Fully paid-up members'' shares and capital issued (core capital, Reg 3)'),
  ('DISCLOSED_RESERVES',     'EQUITY',    'Disclosed reserves (core and institutional capital, Reg 3)'),
  ('RETAINED_EARNINGS',      'EQUITY',    'Retained earnings (core and institutional capital, Reg 3)'),
  ('GRANTS_DONATIONS',       'EQUITY',    'Grants and donations (core and institutional capital, Reg 3)'),
  ('EXCLUDED_RESERVES',      'EQUITY',    'Fair value and credit risk reserves: excluded from core capital (SASRA circular, December 2025)'),
  ('MEMBER_DEPOSITS',        'LIABILITY', 'Member savings deposits: total deposits (Reg 9) and liquidity denominator (Reg 13)'),
  ('SHORT_TERM_LIABILITIES', 'LIABILITY', 'Other short-term liabilities: liquidity denominator (Reg 13)'),
  ('CASH',                   'ASSET',     'Notes and coins (liquid asset, Reg 13)'),
  ('BANK_BALANCES',          'ASSET',     'Balances with banks licensed under the Banking Act (liquid asset, Reg 13)'),
  ('BANK_AMOUNTS_OWED',      'LIABILITY', 'Amounts owed to those banks: netted off bank balances (Reg 13)'),
  ('GOVERNMENT_SECURITIES',  'ASSET',     'Treasury bills and government bonds tradable in the secondary market (liquid asset, Reg 13)'),
  ('SACCO_DEPOSITS_90D',     'ASSET',     'Deposits in other Saccos maturing within 90 days (liquid asset, Reg 13)'),
  ('SACCO_AMOUNTS_OWED',     'LIABILITY', 'Amounts owed to those Saccos: netted off Sacco deposits (Reg 13)'),
  ('LOAN_LOSS_ALLOWANCE',    'ASSET',     'Allowance for loan loss held (contra asset; compared with the required allowance, Reg 44)');

CREATE TABLE ledger.regulatory_mapping (
    tenant_id    uuid         NOT NULL,
    account_code varchar(40)  NOT NULL,
    line         varchar(40)  NOT NULL REFERENCES ledger.regulatory_line (line),
    updated_by   varchar(200) NOT NULL,
    updated_at   timestamptz  NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, account_code),
    FOREIGN KEY (tenant_id, account_code) REFERENCES ledger.gl_account (tenant_id, account_code)
);

CREATE FUNCTION ledger.check_regulatory_mapping() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_account_class text;
    v_line_class text;
BEGIN
    SELECT account_class INTO v_account_class FROM ledger.gl_account
     WHERE tenant_id = NEW.tenant_id AND account_code = NEW.account_code;
    SELECT account_class INTO v_line_class FROM ledger.regulatory_line WHERE line = NEW.line;
    IF v_line_class IS NULL OR v_account_class IS NULL THEN
        RETURN NEW; -- unknown line or account: the foreign keys refuse it
    END IF;
    IF v_account_class IS DISTINCT FROM v_line_class THEN
        RAISE EXCEPTION 'MAPPING_CLASS_MISMATCH: % is a % account; % takes % accounts',
            NEW.account_code, v_account_class, NEW.line, v_line_class USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER regulatory_mapping_class BEFORE INSERT OR UPDATE ON ledger.regulatory_mapping
    FOR EACH ROW EXECUTE FUNCTION ledger.check_regulatory_mapping();

ALTER TABLE ledger.prudential_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.prudential_settings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ledger.prudential_settings
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());
ALTER TABLE ledger.regulatory_mapping ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.regulatory_mapping FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ledger.regulatory_mapping
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());

GRANT SELECT ON ledger.regulatory_line TO mfi_app;
GRANT SELECT, INSERT, UPDATE ON ledger.prudential_settings TO mfi_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ledger.regulatory_mapping TO mfi_app;
