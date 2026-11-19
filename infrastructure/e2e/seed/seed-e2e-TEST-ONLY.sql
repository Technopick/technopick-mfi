-- TEST-ONLY demo data for the end-to-end stack. Fictitious institution, no real customers.
-- Business date = today; accounting periods last month .. next month. Runs once after migrations.
\set ON_ERROR_STOP on
\set t '''0e2e0000-0000-0000-0000-000000000001'''

INSERT INTO tenancy.tenant (tenant_id, code, legal_name, licence_type, deposits_allowed, base_currency)
VALUES (:t, 'E2E-TEST', 'TEST ONLY Microfinance Bank', 'MICROFINANCE_BANK', true, 'KES');
INSERT INTO tenancy.branch (tenant_id, branch_code, name) VALUES (:t, 'NBO01', 'Nairobi (test)');
INSERT INTO ledger.gl_account (tenant_id, account_code, name, account_class, currency, requires_subledger) VALUES
  (:t, '2110-SAVINGS-CONTROL', 'Savings control', 'LIABILITY', 'KES', true),
  (:t, '1120-MPESA-C2B-CLEARING', 'M-Pesa paybill clearing', 'ASSET', 'KES', false),
  (:t, '2900-SUSPENSE', 'Unmatched receipts suspense', 'LIABILITY', 'KES', false),
  (:t, '1110-MPESA-B2C-CLEARING', 'M-Pesa B2C clearing', 'ASSET', 'KES', false),
  (:t, '1200-LOANS-PRINCIPAL', 'Loans receivable - principal', 'ASSET', 'KES', false),
  (:t, '1210-INTEREST-RECEIVABLE', 'Interest receivable', 'ASSET', 'KES', false),
  (:t, '4000-INTEREST-INCOME', 'Interest income', 'INCOME', 'KES', false),
  (:t, '1220-FEE-RECEIVABLE', 'Fee receivable', 'ASSET', 'KES', false),
  (:t, '4100-FEE-PROCESSING', 'Processing fees', 'INCOME', 'KES', false),
  (:t, '1230-PENALTY-RECEIVABLE', 'Penalty receivable', 'ASSET', 'KES', false),
  (:t, '4300-PENALTY-INCOME', 'Penalty income', 'INCOME', 'KES', false),
  (:t, '2400-LOAN-OVERPAYMENTS', 'Loan overpayments', 'LIABILITY', 'KES', false),
  (:t, '5100-LOAN-WRITE-OFFS', 'Loan write-offs', 'EXPENSE', 'KES', false),
  (:t, '1000-CASH-NBO01', 'Branch cash - Nairobi (test)', 'ASSET', 'KES', false);
INSERT INTO ledger.accounting_period (tenant_id, period_id, start_date, end_date)
SELECT :t, to_char(m, 'YYYY-MM'), m::date, (m + interval '1 month - 1 day')::date
  FROM generate_series(date_trunc('month', current_date) - interval '1 month',
                       date_trunc('month', current_date) + interval '1 month', interval '1 month') AS m;
INSERT INTO tenancy.business_date (tenant_id, current_date_) VALUES (:t, current_date);
INSERT INTO payments.mpesa_paybill (tenant_id, short_code, branch_code, gl_clearing, gl_suspense, max_amount)
VALUES (:t, '600991', 'NBO01', '1120-MPESA-C2B-CLEARING', '2900-SUSPENSE', 150000);
INSERT INTO payments.mpesa_b2c_account (tenant_id, short_code, branch_code, gl_clearing)
VALUES (:t, '600000', 'NBO01', '1110-MPESA-B2C-CLEARING');
INSERT INTO lending.loan_product (tenant_id, product_code, name, currency) VALUES (:t, 'BIASHARA', 'Biashara (test)', 'KES');
INSERT INTO lending.loan_product_version (tenant_id, version_id, product_code, version, interest_method, annual_rate,
    min_principal, max_principal, min_term_months, max_term_months, upfront_fee_rate, allocation_strategy,
    allocation_order, gl_principal, gl_interest_receivable, gl_interest_income, gl_fee_receivable, gl_fee_income,
    gl_penalty_receivable, gl_penalty_income, gl_overpayment, gl_write_off, created_by)
VALUES (:t, '0e2e0000-0000-0000-0000-0000000000c1', 'BIASHARA', 1, 'REDUCING_BALANCE', 0.24, 5000, 500000, 3, 24,
        0.02, 'COMPONENT_FIRST', ARRAY['PENALTY','FEE','INTEREST','PRINCIPAL'], '1200-LOANS-PRINCIPAL',
        '1210-INTEREST-RECEIVABLE', '4000-INTEREST-INCOME', '1220-FEE-RECEIVABLE', '4100-FEE-PROCESSING',
        '1230-PENALTY-RECEIVABLE', '4300-PENALTY-INCOME', '2400-LOAN-OVERPAYMENTS', '5100-LOAN-WRITE-OFFS', 'seed:maker');
UPDATE lending.loan_product_version SET status = 'ACTIVE', approved_by = 'seed:checker'
 WHERE version_id = '0e2e0000-0000-0000-0000-0000000000c1';
INSERT INTO savings.deposit_product (tenant_id, product_code, name, currency, gl_control_account)
VALUES (:t, 'SAV01', 'Ordinary savings (test)', 'KES', '2110-SAVINGS-CONTROL');
INSERT INTO savings.branch_cash_account (tenant_id, branch_code, currency, gl_cash) VALUES (:t, 'NBO01', 'KES', '1000-CASH-NBO01');
INSERT INTO savings.teller_policy (tenant_id, currency, withdrawal_approval_above, deposit_approval_above)
VALUES (:t, 'KES', 50000, 1000000);
\echo TEST-ONLY seed loaded
