-- =============================================================================
-- TEST-ONLY load-test book (ADR-0028). Placeholders replaced by PerformanceIT:
--   {T}  tenant id    {NS}  savings accounts    {NL}  loans
-- Business date 2026-11-28, so three end-of-day runs cross a month end (interest crediting).
-- Savings: {NS} verified members, one account each, balances 500 .. 151,000 (every 10th below
-- the 1,000 interest minimum), split across two branches.
-- Loans: {NL} loans of 12,000 over 12 months. 60% disbursed 1 Nov (nothing due yet); 40%
-- disbursed 1 Sep with October and November unpaid (in arrears, flat penalty after 3 days).
-- =============================================================================
SELECT set_config('app.tenant_id', '{T}', false);

INSERT INTO tenancy.tenant (tenant_id, code, legal_name, licence_type, deposits_allowed, base_currency)
VALUES ('{T}', 'TEST-PERF', 'TEST ONLY Load-test Sacco', 'DEPOSIT_TAKING_SACCO', true, 'KES');
INSERT INTO tenancy.branch (tenant_id, branch_code, name) VALUES ('{T}', 'NBO01', 'Nairobi (load test)'), ('{T}', 'MSA01', 'Mombasa (load test)');
INSERT INTO ledger.accounting_period (tenant_id, period_id, start_date, end_date) VALUES
  ('{T}', '2026-08', '2026-08-01', '2026-08-31'), ('{T}', '2026-09', '2026-09-01', '2026-09-30'),
  ('{T}', '2026-10', '2026-10-01', '2026-10-31'), ('{T}', '2026-11', '2026-11-01', '2026-11-30'),
  ('{T}', '2026-12', '2026-12-01', '2026-12-31');
INSERT INTO tenancy.business_date (tenant_id, current_date_) VALUES ('{T}', '2026-11-28');

INSERT INTO ledger.gl_account (tenant_id, account_code, name, account_class, currency, requires_subledger) VALUES
  ('{T}', '2110-SAVINGS-CONTROL', 'Member savings', 'LIABILITY', 'KES', true),
  ('{T}', '1000-VAULT-NBO01', 'Vault Nairobi', 'ASSET', 'KES', false),
  ('{T}', '1000-VAULT-MSA01', 'Vault Mombasa', 'ASSET', 'KES', false),
  ('{T}', '1001-TILL-1', 'Till 1', 'ASSET', 'KES', false), ('{T}', '1001-TILL-2', 'Till 2', 'ASSET', 'KES', false),
  ('{T}', '1001-TILL-3', 'Till 3', 'ASSET', 'KES', false), ('{T}', '1001-TILL-4', 'Till 4', 'ASSET', 'KES', false),
  ('{T}', '5200-TELLER-SHORTAGES', 'Teller shortages', 'EXPENSE', 'KES', false),
  ('{T}', '2950-TELLER-OVERAGES', 'Teller overages', 'LIABILITY', 'KES', false),
  ('{T}', '5300-SAVINGS-INTEREST', 'Interest on savings', 'EXPENSE', 'KES', false),
  ('{T}', '2120-INTEREST-PAYABLE', 'Savings interest payable', 'LIABILITY', 'KES', false),
  ('{T}', '2960-WHT-PAYABLE', 'Withholding tax payable', 'LIABILITY', 'KES', false),
  ('{T}', '1120-MPESA-C2B-CLEARING', 'Paybill clearing', 'ASSET', 'KES', false),
  ('{T}', '2900-SUSPENSE', 'Suspense', 'LIABILITY', 'KES', false),
  ('{T}', '1110-MPESA-B2C-CLEARING', 'B2C clearing', 'ASSET', 'KES', false),
  ('{T}', '1200-LOANS-PRINCIPAL', 'Loans', 'ASSET', 'KES', false),
  ('{T}', '1210-INTEREST-RECEIVABLE', 'Interest receivable', 'ASSET', 'KES', false),
  ('{T}', '4000-INTEREST-INCOME', 'Interest income', 'INCOME', 'KES', false),
  ('{T}', '1220-FEE-RECEIVABLE', 'Fee receivable', 'ASSET', 'KES', false),
  ('{T}', '4100-FEE-PROCESSING', 'Fee income', 'INCOME', 'KES', false),
  ('{T}', '1230-PENALTY-RECEIVABLE', 'Penalty receivable', 'ASSET', 'KES', false),
  ('{T}', '4300-PENALTY-INCOME', 'Penalty income', 'INCOME', 'KES', false),
  ('{T}', '2400-LOAN-OVERPAYMENTS', 'Overpayments', 'LIABILITY', 'KES', false),
  ('{T}', '5100-LOAN-WRITE-OFFS', 'Write-offs', 'EXPENSE', 'KES', false),
  ('{T}', '4900-WRITE-OFF-RECOVERIES', 'Recoveries', 'INCOME', 'KES', false);

INSERT INTO savings.deposit_product (tenant_id, product_code, name, currency, gl_control_account, interest_rate,
    interest_min_balance, withholding_tax_rate, gl_interest_expense, gl_interest_payable, gl_withholding_tax)
VALUES ('{T}', 'SAVP', 'Member savings (load test)', 'KES', '2110-SAVINGS-CONTROL', 6, 1000, 15,
        '5300-SAVINGS-INTEREST', '2120-INTEREST-PAYABLE', '2960-WHT-PAYABLE');
INSERT INTO savings.branch_cash_account (tenant_id, branch_code, currency, gl_cash, gl_shortage, gl_overage) VALUES
  ('{T}', 'NBO01', 'KES', '1000-VAULT-NBO01', '5200-TELLER-SHORTAGES', '2950-TELLER-OVERAGES'),
  ('{T}', 'MSA01', 'KES', '1000-VAULT-MSA01', '5200-TELLER-SHORTAGES', '2950-TELLER-OVERAGES');
INSERT INTO savings.till (tenant_id, till_id, till_code, branch_code, currency, gl_cash)
SELECT '{T}', gen_random_uuid(), 'P-T' || n, 'NBO01', 'KES', '1001-TILL-' || n FROM generate_series(1, 4) n;
INSERT INTO savings.teller_policy (tenant_id, currency, withdrawal_approval_above, deposit_approval_above)
VALUES ('{T}', 'KES', 50000, 1000000);
INSERT INTO payments.mpesa_paybill (tenant_id, short_code, branch_code, gl_clearing, gl_suspense, max_amount)
VALUES ('{T}', '600993', 'NBO01', '1120-MPESA-C2B-CLEARING', '2900-SUSPENSE', 150000);

-- Members and their savings accounts ------------------------------------------------------
CREATE TEMP TABLE perf_member AS
SELECT i, gen_random_uuid() AS customer_id, gen_random_uuid() AS account_id,
       CASE WHEN (i / 500) % 2 = 0 THEN 'NBO01' ELSE 'MSA01' END AS branch,
       CASE WHEN i % 10 = 0 THEN 500 ELSE 1000 + (i * 7919) % 150000 END::numeric AS balance
  FROM generate_series(1, {NS}) i;

INSERT INTO customer.customer (tenant_id, customer_id, customer_number, idempotency_key, first_name, last_name, date_of_birth,
    id_type, id_number, msisdn, branch_code, consent_version, consent_at, created_by)
SELECT '{T}', customer_id, customer.next_customer_number(2026), 'perf-c-' || i, 'Member', 'Load' || i, date '1985-06-01',
       'NATIONAL_ID', (20000000 + i)::text, '2547' || lpad(i::text, 8, '0'), branch, 'DPA-2026-01', now(), 'perf:officer'
  FROM perf_member ORDER BY i;
UPDATE customer.customer SET status = 'ACTIVE', verified_by = 'perf:manager', verification_note = 'Load-test member, TEST ONLY'
 WHERE tenant_id = '{T}';

INSERT INTO savings.deposit_account (tenant_id, account_id, account_number, customer_ref, customer_id, product_code, currency,
    branch_code, gl_control_account, opened_on, open_idempotency_key)
SELECT '{T}', m.account_id, savings.next_account_number(), c.customer_number, m.customer_id, 'SAVP', 'KES', m.branch,
       '2110-SAVINGS-CONTROL', date '2026-11-01', 'perf-a-' || m.i
  FROM perf_member m JOIN customer.customer c ON c.tenant_id = '{T}' AND c.customer_id = m.customer_id ORDER BY m.i;

-- Opening balances: one entry per 500 members (one branch each), vault debit and member credits.
-- Each journal section is one transaction: the ledger's balance checks run at commit.
ANALYZE;
BEGIN;
CREATE TEMP TABLE perf_batch AS
SELECT i / 500 AS b, gen_random_uuid() AS entry_id, min(branch) AS branch, sum(balance) AS total
  FROM perf_member GROUP BY i / 500;
INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
SELECT '{T}', entry_id, 'perf-opening-' || b, date '2026-11-01', 'MIGRATION', 'perf-seed', 'perf:migration' FROM perf_batch;
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code)
SELECT '{T}', entry_id, 1, '1000-VAULT-' || branch, 'D', total, 'KES', branch FROM perf_batch;
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code, subledger_account_id)
SELECT '{T}', p.entry_id, 1 + row_number() OVER (PARTITION BY p.b ORDER BY m.i), '2110-SAVINGS-CONTROL', 'C', m.balance, 'KES',
       m.branch, m.account_id
  FROM perf_member m JOIN perf_batch p ON p.b = m.i / 500;
COMMIT;

-- Loans -----------------------------------------------------------------------------------
INSERT INTO lending.loan_product (tenant_id, product_code, name, currency) VALUES ('{T}', 'BIASHARA', 'Business loan (load test)', 'KES');
INSERT INTO lending.loan_product_version (tenant_id, version_id, product_code, version, interest_method, annual_rate,
    min_principal, max_principal, min_term_months, max_term_months, upfront_fee_rate, allocation_strategy, allocation_order,
    gl_principal, gl_interest_receivable, gl_interest_income, gl_fee_receivable, gl_fee_income, gl_penalty_receivable,
    gl_penalty_income, gl_overpayment, gl_write_off, created_by, penalty_method, penalty_grace_days, penalty_flat,
    arrears_tolerance, gl_write_off_recovery)
VALUES ('{T}', 'cccccccc-0000-0000-0000-0000000000f1', 'BIASHARA', 1, 'REDUCING_BALANCE', 0.36, 1000, 1000000, 1, 24, 0,
        'COMPONENT_FIRST', ARRAY['PENALTY','FEE','INTEREST','PRINCIPAL'], '1200-LOANS-PRINCIPAL', '1210-INTEREST-RECEIVABLE',
        '4000-INTEREST-INCOME', '1220-FEE-RECEIVABLE', '4100-FEE-PROCESSING', '1230-PENALTY-RECEIVABLE', '4300-PENALTY-INCOME',
        '2400-LOAN-OVERPAYMENTS', '5100-LOAN-WRITE-OFFS', 'perf:maker', 'FLAT_ONCE', 3, 200, 0, '4900-WRITE-OFF-RECOVERIES');
UPDATE lending.loan_product_version SET status = 'ACTIVE', approved_by = 'perf:checker'
 WHERE version_id = 'cccccccc-0000-0000-0000-0000000000f1';

BEGIN;
CREATE TEMP TABLE perf_loan AS
SELECT i, gen_random_uuid() AS loan_id, gen_random_uuid() AS entry_id,
       CASE WHEN i % 5 < 3 THEN date '2026-11-01' ELSE date '2026-09-01' END AS disbursed,
       CASE WHEN (i / 500) % 2 = 0 THEN 'NBO01' ELSE 'MSA01' END AS branch
  FROM generate_series(1, {NL}) i;
INSERT INTO lending.loan (tenant_id, loan_id, loan_number, customer_ref, product_version_id, branch_code, currency, principal,
    term_months, created_by, approved_by)
SELECT '{T}', loan_id, 'LN-P' || lpad(i::text, 7, '0'), 'PERF-' || i, 'cccccccc-0000-0000-0000-0000000000f1', branch, 'KES',
       12000, 12, 'perf:officer', 'perf:manager'
  FROM perf_loan;
INSERT INTO lending.schedule_installment (tenant_id, loan_id, schedule_version, installment_no, due_date, principal, interest)
SELECT '{T}', l.loan_id, 1, k, (l.disbursed + make_interval(months => k))::date, 1000, round((12000 - 1000 * (k - 1)) * 0.03, 2)
  FROM perf_loan l CROSS JOIN generate_series(1, 12) k;
INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
SELECT '{T}', entry_id, 'loan:' || loan_id || ':DISBURSE:SEED', disbursed, 'LOAN', 'perf-seed', 'perf:migration' FROM perf_loan;
INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code)
SELECT '{T}'::uuid, entry_id, 1, '1200-LOANS-PRINCIPAL', 'D', 12000, 'KES', branch FROM perf_loan
UNION ALL
SELECT '{T}'::uuid, entry_id, 2, '1110-MPESA-B2C-CLEARING', 'C', 12000, 'KES', branch FROM perf_loan;
UPDATE lending.loan l SET status = 'ACTIVE', disbursement_entry_id = p.entry_id
  FROM perf_loan p WHERE l.tenant_id = '{T}' AND l.loan_id = p.loan_id;

COMMIT;
ANALYZE;
