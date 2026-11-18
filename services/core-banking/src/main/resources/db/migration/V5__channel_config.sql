-- =============================================================================
-- V5: per-tenant channel configuration used by the authorization API.
-- Limits and GL mappings are configuration under maker-checker (approval flow: Phase 2 admin UI);
-- here the database guarantees consistency and tenant isolation.
-- =============================================================================
CREATE SCHEMA IF NOT EXISTS payments;

CREATE TABLE payments.channel_config (
    tenant_id             uuid          NOT NULL REFERENCES tenancy.tenant (tenant_id),
    channel_code          varchar(24)   NOT NULL CHECK (channel_code IN ('AGENT', 'ATM', 'POS', 'USSD', 'MOBILE', 'SWITCH')),
    currency              char(3)       NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    per_txn_max           numeric(19,4) NOT NULL CHECK (per_txn_max > 0),
    daily_max             numeric(19,4) NOT NULL CHECK (daily_max >= per_txn_max),
    gl_settlement_account varchar(40)   NOT NULL,
    gl_fee_income         varchar(40)   NOT NULL,
    hold_ttl_minutes      int           NOT NULL DEFAULT 10080 CHECK (hold_ttl_minutes BETWEEN 1 AND 43200),
    active                boolean       NOT NULL DEFAULT true,
    PRIMARY KEY (tenant_id, channel_code, currency),
    FOREIGN KEY (tenant_id, gl_settlement_account) REFERENCES ledger.gl_account (tenant_id, account_code),
    FOREIGN KEY (tenant_id, gl_fee_income) REFERENCES ledger.gl_account (tenant_id, account_code)
);

ALTER TABLE payments.channel_config ENABLE ROW LEVEL SECURITY;
ALTER TABLE payments.channel_config FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payments.channel_config
    USING (tenant_id = tenancy.current_tenant()) WITH CHECK (tenant_id = tenancy.current_tenant());

REVOKE ALL ON SCHEMA payments FROM PUBLIC;
GRANT USAGE ON SCHEMA payments TO mfi_app;
GRANT SELECT, INSERT, UPDATE ON payments.channel_config TO mfi_app;
