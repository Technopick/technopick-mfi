package ke.co.technopick.mfi.core.payments.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * End-to-end through HTTP, Spring Security, the application service, JDBC and the real
 * PostgreSQL schema (V1-V5, applied by Flyway as owner; the app connects as mfi_app under RLS).
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class AuthorizationApiIntegrationTest {

    static final String TENANT_A = "00000000-0000-0000-0000-0000000000a1";
    static final String TENANT_B = "00000000-0000-0000-0000-0000000000b2";

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17").withInitScript("db/test-init.sql");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", () -> "mfi_app");
        r.add("spring.datasource.password", () -> "it-only-not-a-secret");
        r.add("spring.flyway.url", PG::getJdbcUrl);
        r.add("spring.flyway.user", PG::getUsername);
        r.add("spring.flyway.password", PG::getPassword);
        r.add("mfi.jobs.enabled", () -> "false");          // jobs are driven directly in tests
        r.add("springdoc.api-docs.enabled", () -> "true");  // for the contract drift check
    }

    /** Real tokens are never decoded in tests: requests carry a mock JWT via jwt(). */
    @TestConfiguration
    static class NoRealTokens {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new BadJwtException("test decoder accepts no tokens");
            };
        }
    }

    @Autowired
    WebApplicationContext context;

    @Autowired
    ke.co.technopick.mfi.core.savings.application.HoldExpiryService holdExpiry;

    MockMvc mvc;
    static boolean seeded;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        if (!seeded) {
            seed();
            seeded = true;
        }
    }

    static JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
    }

    /** Reference data as platform administration would create it (owner connection, TEST ONLY data). */
    static void seed() {
        JdbcTemplate db = owner();
        db.execute("""
            INSERT INTO tenancy.tenant (tenant_id, code, legal_name, licence_type, deposits_allowed, base_currency) VALUES
              ('%1$s', 'TEST-A', 'TEST ONLY Bank A', 'MICROFINANCE_BANK', true, 'KES'),
              ('%2$s', 'TEST-B', 'TEST ONLY Sacco B', 'DEPOSIT_TAKING_SACCO', true, 'KES');
            INSERT INTO tenancy.branch (tenant_id, branch_code, name) VALUES ('%1$s', 'NBO01', 'Nairobi');
            INSERT INTO ledger.gl_account (tenant_id, account_code, name, account_class, currency, requires_subledger) VALUES
              ('%1$s', '2110-SAVINGS-CONTROL', 'Savings control', 'LIABILITY', 'KES', true),
              ('%1$s', '1000-VAULT-CASH', 'Vault cash', 'ASSET', 'KES', false),
              ('%1$s', '2300-AGENT-FLOAT-PAYABLE', 'Agent float payable', 'LIABILITY', 'KES', false),
              ('%1$s', '4200-FEE-WITHDRAWAL', 'Withdrawal fees', 'INCOME', 'KES', false),
              ('%1$s', '1120-MPESA-C2B-CLEARING', 'M-Pesa paybill clearing', 'ASSET', 'KES', false),
              ('%1$s', '2900-SUSPENSE', 'Unmatched receipts suspense', 'LIABILITY', 'KES', false),
              ('%1$s', '1200-LOANS-PRINCIPAL', 'Loans receivable - principal', 'ASSET', 'KES', false),
              ('%1$s', '1210-INTEREST-RECEIVABLE', 'Interest receivable', 'ASSET', 'KES', false),
              ('%1$s', '4000-INTEREST-INCOME', 'Interest income', 'INCOME', 'KES', false),
              ('%1$s', '1220-FEE-RECEIVABLE', 'Fee receivable', 'ASSET', 'KES', false),
              ('%1$s', '4100-FEE-PROCESSING', 'Processing fees', 'INCOME', 'KES', false),
              ('%1$s', '1230-PENALTY-RECEIVABLE', 'Penalty receivable', 'ASSET', 'KES', false),
              ('%1$s', '4300-PENALTY-INCOME', 'Penalty income', 'INCOME', 'KES', false),
              ('%1$s', '2400-LOAN-OVERPAYMENTS', 'Loan overpayments', 'LIABILITY', 'KES', false),
              ('%1$s', '5100-LOAN-WRITE-OFFS', 'Loan write-offs', 'EXPENSE', 'KES', false),
              ('%1$s', '1110-MPESA-B2C-CLEARING', 'M-Pesa B2C clearing', 'ASSET', 'KES', false),
              ('%1$s', '1001-TILL-NBO01-01', 'Till NBO01-T1', 'ASSET', 'KES', false),
              ('%1$s', '1002-TILL-NBO01-02', 'Till NBO01-T2', 'ASSET', 'KES', false),
              ('%1$s', '5200-TELLER-SHORTAGES', 'Teller shortages', 'EXPENSE', 'KES', false),
              ('%1$s', '2950-TELLER-OVERAGES', 'Teller overages', 'LIABILITY', 'KES', false);
            INSERT INTO ledger.accounting_period (tenant_id, period_id, start_date, end_date) VALUES
              ('%1$s', '2026-10', '2026-10-01', '2026-10-31'),
              ('%1$s', '2026-11', '2026-11-01', '2026-11-30');
            INSERT INTO tenancy.business_date (tenant_id, current_date_) VALUES ('%1$s', '2026-11-18');
            INSERT INTO savings.deposit_product (tenant_id, product_code, name, currency, gl_control_account)
              VALUES ('%1$s', 'SAV01', 'Savings', 'KES', '2110-SAVINGS-CONTROL');
            INSERT INTO savings.deposit_account (tenant_id, account_id, account_number, customer_ref, product_code, currency,
                branch_code, gl_control_account, opened_on) VALUES
              ('%1$s', 'aaaaaaaa-0000-0000-0000-000000000001', '0100000001', 'C1', 'SAV01', 'KES', 'NBO01', '2110-SAVINGS-CONTROL', '2026-11-01'),
              ('%1$s', 'aaaaaaaa-0000-0000-0000-000000000002', '0100000002', 'C2', 'SAV01', 'KES', 'NBO01', '2110-SAVINGS-CONTROL', '2026-11-01'),
              ('%1$s', 'aaaaaaaa-0000-0000-0000-000000000003', '0100000003', 'C3', 'SAV01', 'KES', 'NBO01', '2110-SAVINGS-CONTROL', '2026-11-01'),
              ('%1$s', 'aaaaaaaa-0000-0000-0000-000000000004', '0100000004', 'C4', 'SAV01', 'KES', 'NBO01', '2110-SAVINGS-CONTROL', '2026-11-01'),
              ('%1$s', 'aaaaaaaa-0000-0000-0000-000000000005', '0100000005', 'C5', 'SAV01', 'KES', 'NBO01', '2110-SAVINGS-CONTROL', '2026-11-01'),
              ('%1$s', 'aaaaaaaa-0000-0000-0000-000000000006', '0100000006', 'C6', 'SAV01', 'KES', 'NBO01', '2110-SAVINGS-CONTROL', '2026-11-01'),
              ('%1$s', 'aaaaaaaa-0000-0000-0000-000000000007', '0100000007', 'C7', 'SAV01', 'KES', 'NBO01', '2110-SAVINGS-CONTROL', '2026-11-01'),
              ('%1$s', 'aaaaaaaa-0000-0000-0000-000000000008', '0100000008', 'C8', 'SAV01', 'KES', 'NBO01', '2110-SAVINGS-CONTROL', '2026-11-01');
            INSERT INTO payments.mpesa_paybill (tenant_id, short_code, branch_code, gl_clearing, gl_suspense, max_amount)
              VALUES ('%1$s', '600991', 'NBO01', '1120-MPESA-C2B-CLEARING', '2900-SUSPENSE', 150000);
            INSERT INTO payments.channel_config (tenant_id, channel_code, currency, per_txn_max, daily_max,
                gl_settlement_account, gl_fee_income) VALUES
              ('%1$s', 'AGENT', 'KES', 70000, 150000, '2300-AGENT-FLOAT-PAYABLE', '4200-FEE-WITHDRAWAL');
            BEGIN;
            INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
              VALUES ('%1$s', '44444444-0000-0000-0000-000000000001', 'seed-deposits', '2026-11-18', 'TELLER', 'seed', 'test');
            INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code, subledger_account_id) VALUES
              ('%1$s', '44444444-0000-0000-0000-000000000001', 1, '1000-VAULT-CASH', 'D', 11500, 'KES', 'NBO01', NULL),
              ('%1$s', '44444444-0000-0000-0000-000000000001', 2, '2110-SAVINGS-CONTROL', 'C', 10000, 'KES', 'NBO01', 'aaaaaaaa-0000-0000-0000-000000000001'),
              ('%1$s', '44444444-0000-0000-0000-000000000001', 3, '2110-SAVINGS-CONTROL', 'C', 1000, 'KES', 'NBO01', 'aaaaaaaa-0000-0000-0000-000000000002'),
              ('%1$s', '44444444-0000-0000-0000-000000000001', 4, '2110-SAVINGS-CONTROL', 'C', 500, 'KES', 'NBO01', 'aaaaaaaa-0000-0000-0000-000000000003');
            COMMIT;
            INSERT INTO lending.loan_product (tenant_id, product_code, name, currency) VALUES ('%1$s', 'BIASHARA', 'Biashara', 'KES');
            INSERT INTO lending.loan_product_version (tenant_id, version_id, product_code, version, interest_method, annual_rate,
                min_principal, max_principal, min_term_months, max_term_months, upfront_fee_rate, allocation_strategy,
                allocation_order, gl_principal, gl_interest_receivable, gl_interest_income, gl_fee_receivable, gl_fee_income,
                gl_penalty_receivable, gl_penalty_income, gl_overpayment, gl_write_off, created_by)
              VALUES ('%1$s', 'cccccccc-0000-0000-0000-000000000001', 'BIASHARA', 1, 'REDUCING_BALANCE', 0.24, 5000, 500000, 3, 24,
                0.02, 'COMPONENT_FIRST', ARRAY['PENALTY','FEE','INTEREST','PRINCIPAL'], '1200-LOANS-PRINCIPAL',
                '1210-INTEREST-RECEIVABLE', '4000-INTEREST-INCOME', '1220-FEE-RECEIVABLE', '4100-FEE-PROCESSING',
                '1230-PENALTY-RECEIVABLE', '4300-PENALTY-INCOME', '2400-LOAN-OVERPAYMENTS', '5100-LOAN-WRITE-OFFS', 'test:maker');
            UPDATE lending.loan_product_version SET status = 'ACTIVE', approved_by = 'test:checker'
             WHERE version_id = 'cccccccc-0000-0000-0000-000000000001';
            BEGIN;
            INSERT INTO lending.loan (tenant_id, loan_id, loan_number, customer_ref, product_version_id, branch_code, currency,
                principal, term_months, created_by, approved_by) VALUES
              ('%1$s', 'bbbbbbbb-0000-0000-0000-000000000001', 'LN-000001', 'C1', 'cccccccc-0000-0000-0000-000000000001', 'NBO01', 'KES', 100000, 12, 'test:officer', 'test:manager'),
              ('%1$s', 'bbbbbbbb-0000-0000-0000-000000000002', 'LN-000002', 'C2', 'cccccccc-0000-0000-0000-000000000001', 'NBO01', 'KES', 100000, 12, 'test:officer', 'test:manager');
            INSERT INTO lending.schedule_installment (tenant_id, loan_id, schedule_version, installment_no, due_date, principal, interest) VALUES
              ('%1$s', 'bbbbbbbb-0000-0000-0000-000000000001', 1, 1, '2026-11-18', 7455.96, 2000.00),
              ('%1$s', 'bbbbbbbb-0000-0000-0000-000000000001', 1, 2, '2026-12-18', 7605.08, 1850.88),
              ('%1$s', 'bbbbbbbb-0000-0000-0000-000000000001', 1, 3, '2027-01-18', 7757.18, 1698.78),
              ('%1$s', 'bbbbbbbb-0000-0000-0000-000000000001', 1, 4, '2027-02-18', 7912.32, 1543.64),
              ('%1$s', 'bbbbbbbb-0000-0000-0000-000000000001', 1, 5, '2027-03-18', 8070.57, 1385.39),
              ('%1$s', 'bbbbbbbb-0000-0000-0000-000000000001', 1, 6, '2027-04-18', 8231.98, 1223.98),
              ('%1$s', 'bbbbbbbb-0000-0000-0000-000000000001', 1, 7, '2027-05-18', 8396.62, 1059.34),
              ('%1$s', 'bbbbbbbb-0000-0000-0000-000000000001', 1, 8, '2027-06-18', 8564.55, 891.41),
              ('%1$s', 'bbbbbbbb-0000-0000-0000-000000000001', 1, 9, '2027-07-18', 8735.85, 720.11),
              ('%1$s', 'bbbbbbbb-0000-0000-0000-000000000001', 1, 10, '2027-08-18', 8910.56, 545.40),
              ('%1$s', 'bbbbbbbb-0000-0000-0000-000000000001', 1, 11, '2027-09-18', 9088.77, 367.19),
              ('%1$s', 'bbbbbbbb-0000-0000-0000-000000000001', 1, 12, '2027-10-18', 9270.56, 185.41),
              ('%1$s', 'bbbbbbbb-0000-0000-0000-000000000002', 1, 1, '2026-11-18', 7455.96, 2000.00),
              ('%1$s', 'bbbbbbbb-0000-0000-0000-000000000002', 1, 2, '2026-12-18', 7605.08, 1850.88),
              ('%1$s', 'bbbbbbbb-0000-0000-0000-000000000002', 1, 3, '2027-01-18', 7757.18, 1698.78),
              ('%1$s', 'bbbbbbbb-0000-0000-0000-000000000002', 1, 4, '2027-02-18', 7912.32, 1543.64),
              ('%1$s', 'bbbbbbbb-0000-0000-0000-000000000002', 1, 5, '2027-03-18', 8070.57, 1385.39),
              ('%1$s', 'bbbbbbbb-0000-0000-0000-000000000002', 1, 6, '2027-04-18', 8231.98, 1223.98),
              ('%1$s', 'bbbbbbbb-0000-0000-0000-000000000002', 1, 7, '2027-05-18', 8396.62, 1059.34),
              ('%1$s', 'bbbbbbbb-0000-0000-0000-000000000002', 1, 8, '2027-06-18', 8564.55, 891.41),
              ('%1$s', 'bbbbbbbb-0000-0000-0000-000000000002', 1, 9, '2027-07-18', 8735.85, 720.11),
              ('%1$s', 'bbbbbbbb-0000-0000-0000-000000000002', 1, 10, '2027-08-18', 8910.56, 545.40),
              ('%1$s', 'bbbbbbbb-0000-0000-0000-000000000002', 1, 11, '2027-09-18', 9088.77, 367.19),
              ('%1$s', 'bbbbbbbb-0000-0000-0000-000000000002', 1, 12, '2027-10-18', 9270.56, 185.41);
            INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor) VALUES
              ('%1$s', '77777777-0000-0000-0000-000000000001', 'loan:bbbbbbbb-0000-0000-0000-000000000001:DISBURSE:SEED', '2026-10-18', 'LOAN', 'seed', 'test'),
              ('%1$s', '77777777-0000-0000-0000-000000000002', 'loan:bbbbbbbb-0000-0000-0000-000000000002:DISBURSE:SEED', '2026-10-18', 'LOAN', 'seed', 'test');
            INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
              ('%1$s', '77777777-0000-0000-0000-000000000001', 1, '1200-LOANS-PRINCIPAL', 'D', 100000, 'KES', 'NBO01'),
              ('%1$s', '77777777-0000-0000-0000-000000000001', 2, '1110-MPESA-B2C-CLEARING', 'C', 98000, 'KES', 'NBO01'),
              ('%1$s', '77777777-0000-0000-0000-000000000001', 3, '4100-FEE-PROCESSING', 'C', 2000, 'KES', 'NBO01'),
              ('%1$s', '77777777-0000-0000-0000-000000000002', 1, '1200-LOANS-PRINCIPAL', 'D', 100000, 'KES', 'NBO01'),
              ('%1$s', '77777777-0000-0000-0000-000000000002', 2, '1110-MPESA-B2C-CLEARING', 'C', 98000, 'KES', 'NBO01'),
              ('%1$s', '77777777-0000-0000-0000-000000000002', 3, '4100-FEE-PROCESSING', 'C', 2000, 'KES', 'NBO01');
            UPDATE lending.loan SET status = 'ACTIVE', disbursement_entry_id = '77777777-0000-0000-0000-000000000001'
             WHERE loan_id = 'bbbbbbbb-0000-0000-0000-000000000001';
            UPDATE lending.loan SET status = 'ACTIVE', disbursement_entry_id = '77777777-0000-0000-0000-000000000002'
             WHERE loan_id = 'bbbbbbbb-0000-0000-0000-000000000002';
            COMMIT;
            BEGIN;
            INSERT INTO payments.mpesa_b2c_account (tenant_id, short_code, branch_code, gl_clearing)
              VALUES ('%1$s', '600000', 'NBO01', '1110-MPESA-B2C-CLEARING');
            INSERT INTO savings.branch_cash_account (tenant_id, branch_code, currency, gl_cash, gl_shortage, gl_overage)
              VALUES ('%1$s', 'NBO01', 'KES', '1000-VAULT-CASH', '5200-TELLER-SHORTAGES', '2950-TELLER-OVERAGES');
            INSERT INTO savings.till (tenant_id, till_id, till_code, branch_code, currency, gl_cash) VALUES
              ('%1$s', '7e000000-0000-0000-0000-000000000001', 'NBO01-T1', 'NBO01', 'KES', '1001-TILL-NBO01-01'),
              ('%1$s', '7e000000-0000-0000-0000-000000000002', 'NBO01-T2', 'NBO01', 'KES', '1002-TILL-NBO01-02');
            INSERT INTO savings.teller_policy (tenant_id, currency, withdrawal_approval_above, deposit_approval_above)
              VALUES ('%1$s', 'KES', 50000, 1000000);
            INSERT INTO lending.loan (tenant_id, loan_id, loan_number, customer_ref, product_version_id, branch_code, currency,
                principal, term_months, created_by, approved_by) VALUES
              ('%1$s', 'bbbbbbbb-0000-0000-0000-000000000003', 'LN-000003', 'C3', 'cccccccc-0000-0000-0000-000000000001', 'NBO01', 'KES', 100000, 12, 'test:officer', 'test:manager'),
              ('%1$s', 'bbbbbbbb-0000-0000-0000-000000000004', 'LN-000004', 'C4', 'cccccccc-0000-0000-0000-000000000001', 'NBO01', 'KES', 100000, 12, 'test:officer', 'test:manager'),
              ('%1$s', 'bbbbbbbb-0000-0000-0000-000000000005', 'LN-000005', 'C5', 'cccccccc-0000-0000-0000-000000000001', 'NBO01', 'KES', 100000, 12, 'test:officer', 'test:manager'),
              ('%1$s', 'bbbbbbbb-0000-0000-0000-000000000006', 'LN-000006', 'C6', 'cccccccc-0000-0000-0000-000000000001', 'NBO01', 'KES', 100000, 12, 'test:officer', 'test:manager');
            INSERT INTO lending.schedule_installment (tenant_id, loan_id, schedule_version, installment_no, due_date, principal, interest) VALUES
              ('%1$s', 'bbbbbbbb-0000-0000-0000-000000000003', 1, 1, '2026-12-18', 100000, 2000),
              ('%1$s', 'bbbbbbbb-0000-0000-0000-000000000004', 1, 1, '2026-12-18', 100000, 2000),
              ('%1$s', 'bbbbbbbb-0000-0000-0000-000000000005', 1, 1, '2026-12-18', 100000, 2000),
              ('%1$s', 'bbbbbbbb-0000-0000-0000-000000000006', 1, 1, '2026-12-18', 100000, 2000);
            COMMIT;
            """.formatted(TENANT_A, TENANT_B));
    }

    static JwtRequestPostProcessor switchToken() {
        return jwt().jwt(j -> j.subject("svc-switch").claim("azp", "switch")).authorities(new SimpleGrantedAuthority("ROLE_SWITCH"));
    }

    static JwtRequestPostProcessor teller(String tenant) {
        return jwt().jwt(j -> j.claim("tenant_id", tenant)).authorities(new SimpleGrantedAuthority("ROLE_TELLER"));
    }

    static String authorizeBody(String tenant, String account, String amount, String fee, String stan) {
        return """
            {"tenantId":"%s","channel":"AGENT","accountNumber":"%s","type":"CASH_WITHDRAWAL","amount":"%s","fee":"%s",
             "currency":"KES","acquirerId":"123456","terminalId":"AGT00001","stan":"%s",
             "transmissionDateTime":"2026-11-18T07:00:00Z","rrn":"632207%s"}
            """.formatted(tenant, account, amount, fee, stan, stan);
    }

    String authorize(String body) throws Exception {
        return mvc.perform(post("/api/v1/authorizations").with(switchToken())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    void expectBalance(String account, String ledger, String holds, String available) throws Exception {
        mvc.perform(get("/api/v1/accounts/" + account + "/balance").with(teller(TENANT_A)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ledgerBalance").value(ledger))
            .andExpect(jsonPath("$.activeHolds").value(holds))
            .andExpect(jsonPath("$.availableBalance").value(available));
    }

    @Test
    void healthIsPublicApiIsNot() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/authorizations").contentType(MediaType.APPLICATION_JSON)
                .content(authorizeBody(TENANT_A, "0100000001", "1", "0", "000900"))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/authorizations").with(teller(TENANT_A)).contentType(MediaType.APPLICATION_JSON)
                .content(authorizeBody(TENANT_A, "0100000001", "1", "0", "000901"))).andExpect(status().isForbidden());
    }

    /** Authorize -> replay -> decline -> complete -> replay -> reverse -> replay, checking balances each step. */
    @Test
    void withdrawalLifecycle() throws Exception {
        String first = authorize(authorizeBody(TENANT_A, "0100000001", "5000", "50", "000101"));
        assertEquals(Boolean.TRUE, JsonPath.read(first, "$.approved"));
        assertEquals("5050.00", JsonPath.read(first, "$.holdAmount"));
        assertEquals("4950.00", JsonPath.read(first, "$.availableBalance"));
        String holdId = JsonPath.read(first, "$.holdId");

        // Same STAN/terminal/time again: the same hold comes back, nothing new is held.
        String replay = authorize(authorizeBody(TENANT_A, "0100000001", "5000", "50", "000101"));
        assertEquals(holdId, JsonPath.read(replay, "$.holdId"));
        assertEquals(Boolean.TRUE, JsonPath.read(replay, "$.replayed"));
        expectBalance("0100000001", "10000.00", "5050.00", "4950.00");

        String declined = authorize(authorizeBody(TENANT_A, "0100000001", "5000", "50", "000102"));
        assertEquals("INSUFFICIENT_FUNDS", JsonPath.read(declined, "$.reason"));

        // Another tenant can never reach this account.
        String crossTenant = authorize(authorizeBody(TENANT_B, "0100000001", "10", "0", "000103"));
        assertEquals("ACCOUNT_NOT_FOUND", JsonPath.read(crossTenant, "$.reason"));

        // Completion amounts must add up to the hold.
        mvc.perform(post("/api/v1/authorizations/" + holdId + "/completion").with(switchToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":\"" + TENANT_A + "\",\"channel\":\"AGENT\",\"amount\":\"5000\",\"fee\":\"40\",\"currency\":\"KES\"}"))
            .andExpect(status().isBadRequest());

        String completeBody = "{\"tenantId\":\"" + TENANT_A + "\",\"channel\":\"AGENT\",\"amount\":\"5000\",\"fee\":\"50\",\"currency\":\"KES\"}";
        String done = mvc.perform(post("/api/v1/authorizations/" + holdId + "/completion").with(switchToken())
                .contentType(MediaType.APPLICATION_JSON).content(completeBody))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String entryId = JsonPath.read(done, "$.entryId");
        mvc.perform(post("/api/v1/authorizations/" + holdId + "/completion").with(switchToken())
                .contentType(MediaType.APPLICATION_JSON).content(completeBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entryId").value(entryId))
            .andExpect(jsonPath("$.replayed").value(true));
        expectBalance("0100000001", "4950.00", "0.00", "4950.00");

        String reverseBody = "{\"tenantId\":\"" + TENANT_A + "\"}";
        mvc.perform(post("/api/v1/authorizations/" + holdId + "/reversal").with(switchToken())
                .contentType(MediaType.APPLICATION_JSON).content(reverseBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("REVERSED"))
            .andExpect(jsonPath("$.replayed").value(false));
        mvc.perform(post("/api/v1/authorizations/" + holdId + "/reversal").with(switchToken())
                .contentType(MediaType.APPLICATION_JSON).content(reverseBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.replayed").value(true));
        expectBalance("0100000001", "10000.00", "0.00", "10000.00");

        // The ledger agrees: completion and reversal net to zero on every account, entries balanced.
        BigDecimal net = owner().queryForObject("""
                SELECT COALESCE(sum(CASE side WHEN 'D' THEN amount ELSE -amount END), 0) FROM ledger.journal_line
                 WHERE entry_id IN (SELECT entry_id FROM ledger.journal_entry WHERE idempotency_key LIKE ?)
                """, BigDecimal.class, "%" + holdId);
        assertEquals(0, net.signum());
        // Exactly one event per business change, written in the same transaction (replays added none).
        assertEquals(java.util.List.of("AuthorizationCompleted", "AuthorizationReversed"), owner().queryForList(
                "SELECT event_type FROM messaging.outbox_event WHERE aggregate_id = ? ORDER BY occurred_at",
                String.class, holdId));
        // Tenant B's teller cannot see the account at all.
        mvc.perform(get("/api/v1/accounts/0100000001/balance").with(teller(TENANT_B))).andExpect(status().isNotFound());
    }

    /** Ten concurrent requests for 700 against 1,000: exactly one may be approved. */
    @Test
    void concurrentAuthorizationsCannotOverspend() throws Exception {
        List<Callable<String>> calls = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String stan = String.format("%06d", 500 + i);
            calls.add(() -> authorize(authorizeBody(TENANT_A, "0100000002", "700", "0", stan)));
        }
        int approved = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
            for (Future<String> f : pool.invokeAll(calls)) {
                if (Boolean.TRUE.equals(JsonPath.read(f.get(), "$.approved"))) {
                    approved++;
                }
            }
        }
        assertEquals(1, approved);
        BigDecimal held = owner().queryForObject(
                "SELECT sum(amount) FROM savings.account_hold WHERE account_id = 'aaaaaaaa-0000-0000-0000-000000000002' AND status = 'ACTIVE'",
                BigDecimal.class);
        assertEquals(0, new BigDecimal("700").compareTo(held));
    }

    /** Due holds expire exactly once; holds not yet due are untouched; funds come back. */
    @Test
    void holdExpiryReleasesOnlyDueHolds() throws Exception {
        owner().execute("""
            INSERT INTO savings.account_hold (tenant_id, hold_id, account_id, kind, amount, currency, idempotency_key, expires_at) VALUES
              ('%1$s', 'eeeeeeee-0000-0000-0000-000000000001', 'aaaaaaaa-0000-0000-0000-000000000003', 'AUTHORIZATION', 100, 'KES', 'exp-due', now() - interval '1 minute'),
              ('%1$s', 'eeeeeeee-0000-0000-0000-000000000002', 'aaaaaaaa-0000-0000-0000-000000000003', 'AUTHORIZATION', 50, 'KES', 'exp-later', now() + interval '1 day');
            """.formatted(TENANT_A));
        expectBalance("0100000003", "500.00", "150.00", "350.00");
        assertEquals(true, holdExpiry.activeTenants().contains(java.util.UUID.fromString(TENANT_A)));
        assertEquals(1, holdExpiry.expireDue(java.util.UUID.fromString(TENANT_A)));
        assertEquals(0, holdExpiry.expireDue(java.util.UUID.fromString(TENANT_A)));
        expectBalance("0100000003", "500.00", "50.00", "450.00");
        assertEquals("EXPIRED", owner().queryForObject(
                "SELECT status FROM savings.account_hold WHERE hold_id = 'eeeeeeee-0000-0000-0000-000000000001'", String.class));
        assertEquals("ACTIVE", owner().queryForObject(
                "SELECT status FROM savings.account_hold WHERE hold_id = 'eeeeeeee-0000-0000-0000-000000000002'", String.class));
    }

    /**
     * The live OpenAPI document must equal the reviewed contract in shared/contracts. The
     * generated document is always written to target/openapi so a change can be reviewed and
     * committed deliberately.
     */
    @Test
    void openApiMatchesCommittedContract() throws Exception {
        mvc.perform(get("/v3/api-docs").with(teller(TENANT_A))).andExpect(status().isForbidden());
        String live = mvc.perform(get("/v3/api-docs").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        java.nio.file.Path out = java.nio.file.Path.of("target", "openapi", "core-banking-v1.json");
        java.nio.file.Files.createDirectories(out.getParent());
        java.nio.file.Files.writeString(out, live, java.nio.charset.StandardCharsets.UTF_8);
        java.util.regex.Matcher numbered = java.util.regex.Pattern.compile("\"operationId\":\"([^\"]*_\\d+)\"").matcher(live);
        boolean autoNumbered = numbered.find();
        org.junit.jupiter.api.Assertions.assertFalse(autoNumbered,
                "auto-numbered operationId (unstable for generated clients): " + (autoNumbered ? numbered.group(1) : ""));
        java.nio.file.Path contract = java.nio.file.Path.of("..", "..", "shared", "contracts", "openapi", "core-banking-v1.json");
        org.junit.jupiter.api.Assertions.assertTrue(java.nio.file.Files.exists(contract),
                "No committed contract yet: review " + out.toAbsolutePath() + " and copy it to " + contract);
        // Compared as parsed JSON, so the committed file can be pretty-printed for review.
        org.springframework.boot.json.JsonParser parser = org.springframework.boot.json.JsonParserFactory.getJsonParser();
        assertEquals(parser.parseMap(java.nio.file.Files.readString(contract, java.nio.charset.StandardCharsets.UTF_8)),
                parser.parseMap(live),
                "API changed: review target/openapi/core-banking-v1.json and update the committed contract");
    }

    static JwtRequestPostProcessor integrationToken() {
        return jwt().jwt(j -> j.subject("svc-integration")).authorities(new SimpleGrantedAuthority("ROLE_INTEGRATION"));
    }

    String c2b(String path, String body, org.springframework.test.web.servlet.request.RequestPostProcessor auth,
               org.springframework.test.web.servlet.ResultMatcher expected) throws Exception {
        return mvc.perform(post("/api/v1/payments/mpesa/c2b" + path).with(auth).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(expected).andReturn().getResponse().getContentAsString();
    }

    static String validation(String shortCode, String billRef, String amount) {
        return """
            {"tenantId":"%s","shortCode":"%s","billRef":"%s","amount":"%s","currency":"KES"}
            """.formatted(TENANT_A, shortCode, billRef, amount);
    }

    static String confirmation(String transId, String billRef, String amount) {
        return """
            {"tenantId":"%s","transId":"%s","shortCode":"600991","amount":"%s","currency":"KES","billRef":"%s",
             "msisdnMasked":"2547*****678","transTime":"2026-11-18T10:15:00+03:00"}
            """.formatted(TENANT_A, transId, amount, billRef);
    }

    /** Daraja C2B in core: validation codes, exactly-once application, unmatched receipts to suspense. */
    @Test
    void mpesaC2bPayments() throws Exception {
        var ok = status().isOk();
        assertEquals("0", JsonPath.read(c2b("/validation", validation("600991", "0100000004", "1500.00"), integrationToken(), ok), "$.resultCode"));
        assertEquals("C2B00012", JsonPath.read(c2b("/validation", validation("600991", "NO-SUCH-ACC", "1500.00"), integrationToken(), ok), "$.resultCode"));
        assertEquals("C2B00015", JsonPath.read(c2b("/validation", validation("999999", "0100000004", "1500.00"), integrationToken(), ok), "$.resultCode"));
        assertEquals("C2B00013", JsonPath.read(c2b("/validation", validation("600991", "0100000004", "150000.01"), integrationToken(), ok), "$.resultCode"));
        c2b("/validation", validation("600991", "0100000004", "1"), switchToken(), status().isForbidden());

        String applied = c2b("", confirmation("TA57ZBCAM3", "0100000004", "1500.00"), integrationToken(), ok);
        assertEquals("APPLIED", JsonPath.read(applied, "$.outcome"));
        assertEquals(Boolean.FALSE, JsonPath.read(applied, "$.replayed"));
        String replay = c2b("", confirmation("TA57ZBCAM3", "0100000004", "1500.00"), integrationToken(), ok);
        assertEquals(Boolean.TRUE, JsonPath.read(replay, "$.replayed"));
        assertEquals((String) JsonPath.read(applied, "$.paymentId"), JsonPath.read(replay, "$.paymentId"));
        expectBalance("0100000004", "1500.00", "0.00", "1500.00");

        String unmatched = c2b("", confirmation("TA57ZBCAM4", "WRONG-REF", "200.00"), integrationToken(), ok);
        assertEquals("UNMATCHED", JsonPath.read(unmatched, "$.outcome"));
        BigDecimal suspense = owner().queryForObject("""
                SELECT sum(CASE side WHEN 'C' THEN amount ELSE -amount END) FROM ledger.journal_line
                 WHERE tenant_id = ?::uuid AND account_code = '2900-SUSPENSE'
                """, BigDecimal.class, TENANT_A);
        assertEquals(0, new BigDecimal("200").compareTo(suspense));
        assertEquals(2, owner().queryForObject("""
                SELECT count(*) FROM messaging.outbox_event
                 WHERE event_type = 'MpesaPaymentReceived' AND payload->>'transId' IN ('TA57ZBCAM3', 'TA57ZBCAM4')
                """, Integer.class));
    }

    static JwtRequestPostProcessor staff(String subject, String role) {
        return jwt().jwt(j -> j.subject(subject).claim("tenant_id", TENANT_A)).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    /** Paybill receipt quoting a loan number repays the loan with proper allocation and postings. */
    @Test
    void mpesaReceiptRepaysLoanByLoanNumber() throws Exception {
        var ok = status().isOk();
        assertEquals("0", JsonPath.read(c2b("/validation", validation("600991", "LN-000001", "10000.00"), integrationToken(), ok), "$.resultCode"));
        String applied = c2b("", confirmation("TA57ZBCAL1", "ln-000001", "10000.00"), integrationToken(), ok);
        assertEquals("LOAN_REPAYMENT", JsonPath.read(applied, "$.outcome"));
        assertEquals("LN-000001", JsonPath.read(applied, "$.accountNumber"));
        String entryId = JsonPath.read(applied, "$.journalEntryId");

        // Installment 1 (due 18 Nov): interest 2,000.00 + principal 7,455.96; remainder 544.04 is an overpayment.
        assertEquals(0, new BigDecimal("544.04").compareTo(owner().queryForObject(
                "SELECT unallocated FROM lending.repayment WHERE provider_ref = 'TA57ZBCAL1'", BigDecimal.class)));
        assertEquals(java.util.List.of("1|INTEREST|2000.00", "1|PRINCIPAL|7455.96"), owner().queryForList("""
                SELECT a.installment_no || '|' || a.component || '|' || round(a.amount, 2) FROM lending.repayment_allocation a
                  JOIN lending.repayment r USING (tenant_id, repayment_id) WHERE r.provider_ref = 'TA57ZBCAL1' ORDER BY a.line_no
                """, String.class));
        // No interest had been accrued by EOD yet, so the interest goes straight to income.
        assertEquals(java.util.List.of("C 1200-LOANS-PRINCIPAL 7455.96", "C 2400-LOAN-OVERPAYMENTS 544.04",
                "C 4000-INTEREST-INCOME 2000.00", "D 1120-MPESA-C2B-CLEARING 10000.00"), owner().queryForList("""
                SELECT side || ' ' || account_code || ' ' || round(amount, 2) FROM ledger.journal_line
                 WHERE entry_id = ?::uuid ORDER BY side, account_code
                """, String.class, entryId));
        String replay = c2b("", confirmation("TA57ZBCAL1", "ln-000001", "10000.00"), integrationToken(), ok);
        assertEquals(Boolean.TRUE, JsonPath.read(replay, "$.replayed"));
        assertEquals(1, owner().queryForObject("SELECT count(*) FROM lending.repayment WHERE provider_ref = 'TA57ZBCAL1'", Integer.class));
    }

    /** Unmatched receipts leave suspense only with two different people: to a savings account or to a loan. */
    @Test
    void suspenseClearanceNeedsMakerAndChecker() throws Exception {
        var ok = status().isOk();
        String toAccount = JsonPath.read(c2b("", confirmation("TA57ZBCAS1", "TYPO-REF", "300.00"), integrationToken(), ok), "$.paymentId");
        String toLoan = JsonPath.read(c2b("", confirmation("TA57ZBCAS2", "TYPO-2", "9455.96"), integrationToken(), ok), "$.paymentId");

        String items = mvc.perform(get("/api/v1/payments/suspense/items").with(staff("ops-1", "OPERATIONS")))
            .andExpect(ok).andReturn().getResponse().getContentAsString();
        assertEquals(Boolean.TRUE, items.contains(toAccount) && items.contains(toLoan));
        mvc.perform(get("/api/v1/payments/suspense/items").with(teller(TENANT_A))).andExpect(status().isForbidden());

        String r1 = JsonPath.read(mvc.perform(post("/api/v1/payments/suspense/items/" + toAccount + "/resolutions")
                .with(staff("ops-1", "OPERATIONS")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetType\":\"DEPOSIT_ACCOUNT\",\"targetReference\":\"0100000005\",\"reason\":\"Customer called: typo in account number\"}"))
            .andExpect(ok).andReturn().getResponse().getContentAsString(), "$.resolutionId");
        // A second live resolution for the same receipt is refused.
        mvc.perform(post("/api/v1/payments/suspense/items/" + toAccount + "/resolutions")
                .with(staff("ops-2", "OPERATIONS")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetType\":\"DEPOSIT_ACCOUNT\",\"targetReference\":\"0100000004\",\"reason\":\"duplicate\"}"))
            .andExpect(status().isConflict());
        // The requester cannot approve, even holding the supervisor role as well.
        mvc.perform(post("/api/v1/payments/suspense/resolutions/" + r1 + "/approve")
                .with(jwt().jwt(j -> j.subject("ops-1").claim("tenant_id", TENANT_A))
                    .authorities(new SimpleGrantedAuthority("ROLE_SUPERVISOR"))))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/payments/suspense/resolutions/" + r1 + "/approve").with(staff("sup-1", "SUPERVISOR")))
            .andExpect(ok).andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(post("/api/v1/payments/suspense/resolutions/" + r1 + "/approve").with(staff("sup-1", "SUPERVISOR")))
            .andExpect(status().isConflict());
        expectBalance("0100000005", "300.00", "0.00", "300.00");

        String r2 = JsonPath.read(mvc.perform(post("/api/v1/payments/suspense/items/" + toLoan + "/resolutions")
                .with(staff("ops-1", "OPERATIONS")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetType\":\"LOAN\",\"targetReference\":\"LN-000002\",\"reason\":\"Loan number mistyped\"}"))
            .andExpect(ok).andReturn().getResponse().getContentAsString(), "$.resolutionId");
        mvc.perform(post("/api/v1/payments/suspense/resolutions/" + r2 + "/approve").with(staff("sup-1", "SUPERVISOR")))
            .andExpect(ok);
        assertEquals("SUSPENSE", owner().queryForObject(
                "SELECT channel FROM lending.repayment WHERE provider_ref = ?", String.class, r2));
        assertEquals(0, owner().queryForObject(
                "SELECT unallocated FROM lending.repayment WHERE provider_ref = ?", BigDecimal.class, r2).signum());

        String after = mvc.perform(get("/api/v1/payments/suspense/items").with(staff("sup-1", "SUPERVISOR")))
            .andExpect(ok).andReturn().getResponse().getContentAsString();
        assertEquals(Boolean.FALSE, after.contains(toAccount) || after.contains(toLoan));
    }

    String stk(String path, String body, org.springframework.test.web.servlet.request.RequestPostProcessor auth,
               org.springframework.test.web.servlet.ResultMatcher expected) throws Exception {
        return mvc.perform(post("/api/v1/payments/mpesa/stk-requests" + path).with(auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(expected).andReturn().getResponse().getContentAsString();
    }

    static String stkCreate(String key, String phone, String amount, String target) {
        return """
            {"idempotencyKey":"%s","phoneNumber":"%s","amount":"%s","targetReference":"%s"}
            """.formatted(key, phone, amount, target);
    }

    static String stkResult(String checkout, String code, String receipt, String amount) {
        return """
            {"tenantId":"%s","checkoutRequestId":"%s","merchantRequestId":"m-%s","resultCode":"%s","resultDesc":"test",
             "receipt":%s,"amount":%s,"transactionTime":"2026-11-18T11:00:00+03:00"}
            """.formatted(TENANT_A, checkout, checkout, code, receipt == null ? "null" : "\"" + receipt + "\"",
                amount == null ? "null" : "\"" + amount + "\"");
    }

    /** STK Push: staff request, single dispatch, provider result applied once across STK and C2B. */
    @Test
    void mpesaStkPushLifecycle() throws Exception {
        var ok = status().isOk();
        var bad = status().isBadRequest();
        stk("", stkCreate("stk-bad-1", "0712345678", "500.50", "0100000006"), teller(TENANT_A), bad);
        stk("", stkCreate("stk-bad-2", "12345", "500", "0100000006"), teller(TENANT_A), bad);
        stk("", stkCreate("stk-bad-3", "0712345678", "500", "NOPE"), teller(TENANT_A), bad);
        stk("", stkCreate("stk-bad-4", "0712345678", "500", "0100000006"), integrationToken(), status().isForbidden());

        String created = stk("", stkCreate("stk-1", "+254 712 345 678", "500", "0100000006"), teller(TENANT_A), ok);
        String id = JsonPath.read(created, "$.requestId");
        assertEquals("PENDING", JsonPath.read(created, "$.status"));
        String replay = stk("", stkCreate("stk-1", "0712345678", "500.00", "0100000006"), teller(TENANT_A), ok);
        assertEquals(id, JsonPath.read(replay, "$.requestId"));
        assertEquals(Boolean.TRUE, JsonPath.read(replay, "$.replayed"));
        stk("", stkCreate("stk-1", "0712345678", "600", "0100000006"), teller(TENANT_A), status().isConflict());
        // The event carries no phone number.
        String event = owner().queryForObject("""
                SELECT payload::text FROM messaging.outbox_event WHERE event_type = 'MpesaStkPushRequested' AND aggregate_id = ?
                """, String.class, id);
        assertEquals(Boolean.FALSE, event.contains("712345678") || event.contains("phone"), event);

        String tenantBody = "{\"tenantId\":\"" + TENANT_A + "\"}";
        stk("/" + id + "/dispatch", tenantBody, teller(TENANT_A), status().isForbidden());
        String dispatch = stk("/" + id + "/dispatch", tenantBody, integrationToken(), ok);
        assertEquals("254712345678", JsonPath.read(dispatch, "$.msisdn"));
        assertEquals("500", JsonPath.read(dispatch, "$.amount"));
        assertEquals("0100000006", JsonPath.read(dispatch, "$.accountReference"));
        stk("/" + id + "/dispatch", tenantBody, integrationToken(), status().isConflict()); // sent at most once

        String submitted = "{\"tenantId\":\"" + TENANT_A + "\",\"merchantRequestId\":\"m-ws_CO_1\",\"checkoutRequestId\":\"ws_CO_1\"}";
        assertEquals("SUBMITTED", JsonPath.read(stk("/" + id + "/submitted", submitted, integrationToken(), ok), "$.status"));
        stk("/" + id + "/submitted", submitted, integrationToken(), ok); // repeat is harmless
        stk("/" + id + "/result", stkResult("ws_CO_other", "0", "SIMSTK0001", "500.00"), integrationToken(), status().isConflict());

        String done = stk("/" + id + "/result", stkResult("ws_CO_1", "0", "SIMSTK0001", "500.00"), integrationToken(), ok);
        assertEquals("COMPLETED", JsonPath.read(done, "$.status"));
        assertEquals("2547*****678", JsonPath.read(done, "$.msisdnMasked"));
        expectBalance("0100000006", "500.00", "0.00", "500.00");
        stk("/" + id + "/result", stkResult("ws_CO_1", "0", "SIMSTK0001", "500.00"), integrationToken(), ok);
        // Safaricom may also report the same receipt as a C2B confirmation: replayed, not credited twice.
        String c2bSame = c2b("", confirmation("SIMSTK0001", "0100000006", "500.00"), integrationToken(), ok);
        assertEquals(Boolean.TRUE, JsonPath.read(c2bSame, "$.replayed"));
        expectBalance("0100000006", "500.00", "0.00", "500.00");
        assertEquals(null, owner().queryForObject("SELECT msisdn FROM payments.stk_request WHERE request_id = ?::uuid",
                String.class, id));
        assertEquals("MPESA_STK", owner().queryForObject(
                "SELECT provider FROM payments.provider_payment WHERE provider_ref = 'SIMSTK0001'", String.class));

        // Cancelled by the customer: no money, request FAILED with the provider's code.
        String id2 = JsonPath.read(stk("", stkCreate("stk-2", "0112345678", "1000", "LN-000001"), teller(TENANT_A), ok), "$.requestId");
        stk("/" + id2 + "/dispatch", tenantBody, integrationToken(), ok);
        // Result arrives before the integration runtime reported the submission (callback race).
        String failed = stk("/" + id2 + "/result", stkResult("ws_CO_2", "1032", null, null), integrationToken(), ok);
        assertEquals("FAILED", JsonPath.read(failed, "$.status"));
        assertEquals("1032", JsonPath.read(failed, "$.resultCode"));
        assertEquals("ws_CO_2", JsonPath.read(failed, "$.checkoutRequestId"));
        stk("/" + id2 + "/submitted", "{\"tenantId\":\"" + TENANT_A + "\",\"checkoutRequestId\":\"ws_CO_2\"}", integrationToken(), ok);
        assertEquals("FAILED", JsonPath.read(mvc.perform(get("/api/v1/payments/mpesa/stk-requests/" + id2).with(teller(TENANT_A)))
            .andExpect(ok).andReturn().getResponse().getContentAsString(), "$.status"));
        // Another tenant's staff cannot see it.
        mvc.perform(get("/api/v1/payments/mpesa/stk-requests/" + id2).with(teller(TENANT_B))).andExpect(status().isNotFound());
    }

    @Autowired
    ke.co.technopick.mfi.core.payments.application.MpesaStkService stkService;

    String stkSubmitted(String key, String checkout) throws Exception {
        String id = JsonPath.read(stk("", stkCreate(key, "0712345678", "50", "0100000007"), teller(TENANT_A), status().isOk()),
                "$.requestId");
        stk("/" + id + "/dispatch", "{\"tenantId\":\"" + TENANT_A + "\"}", integrationToken(), status().isOk());
        if (checkout != null) {
            stk("/" + id + "/submitted", "{\"tenantId\":\"" + TENANT_A + "\",\"checkoutRequestId\":\"" + checkout + "\"}",
                    integrationToken(), status().isOk());
        }
        return id;
    }

    String queryResult(String id, String checkout, String code) throws Exception {
        return stk("/" + id + "/query-result", "{\"tenantId\":\"" + TENANT_A + "\",\"checkoutRequestId\":\"" + checkout + "\""
                + (code == null ? "" : ",\"resultCode\":\"" + code + "\",\"resultDesc\":\"query\"") + "}",
                integrationToken(), status().isOk());
    }

    /** Reconciliation: expiry of undispatched requests, unknown sends flagged, status queries applied safely. */
    @Test
    void mpesaStkReconciliation() throws Exception {
        var ok = status().isOk();
        String neverSent = JsonPath.read(stk("", stkCreate("rec-1", "0712345678", "50", "0100000007"), teller(TENANT_A), ok),
                "$.requestId");
        String unknownSend = stkSubmitted("rec-2", null);
        String paidNoReceipt = stkSubmitted("rec-3", "ws_CO_R3");
        String stuck = stkSubmitted("rec-4", "ws_CO_R4");
        String cancelled = stkSubmitted("rec-5", "ws_CO_R5");

        stkService.housekeep(java.util.UUID.fromString(TENANT_A), java.time.Duration.ZERO, java.time.Duration.ZERO);
        String expired = mvc.perform(get("/api/v1/payments/mpesa/stk-requests/" + neverSent).with(teller(TENANT_A)))
            .andExpect(ok).andReturn().getResponse().getContentAsString();
        assertEquals("FAILED", JsonPath.read(expired, "$.status"));
        assertEquals("EXPIRED", JsonPath.read(expired, "$.resultCode"));
        stk("/" + neverSent + "/dispatch", "{\"tenantId\":\"" + TENANT_A + "\"}", integrationToken(), status().isConflict());

        String stale = stk("/reconciliation/stale", "{\"tenantId\":\"" + TENANT_A + "\",\"olderThanSeconds\":0,\"limit\":100}",
                integrationToken(), ok);
        java.util.List<String> staleIds = JsonPath.read(stale, "$.items[*].requestId");
        assertEquals(Boolean.TRUE, staleIds.containsAll(java.util.List.of(paidNoReceipt, stuck, cancelled)), stale);
        assertEquals(Boolean.FALSE, staleIds.contains(unknownSend), "no CheckoutRequestID: cannot be queried");
        stk("/reconciliation/stale", "{\"tenantId\":\"" + TENANT_A + "\",\"olderThanSeconds\":0,\"limit\":100}",
                teller(TENANT_A), status().isForbidden());

        // Paid per status query: flagged, not credited (no receipt number); a late callback completes it once.
        String paid = queryResult(paidNoReceipt, "ws_CO_R3", "0");
        assertEquals("SUBMITTED", JsonPath.read(paid, "$.status"));
        assertEquals(0, owner().queryForObject("SELECT count(*) FROM payments.provider_payment WHERE provider_ref = 'SIMREC0003'",
                Integer.class));
        // Final failure per status query.
        assertEquals("FAILED", JsonPath.read(queryResult(cancelled, "ws_CO_R5", "1032"), "$.status"));
        // Still processing: counted; after the limit it goes to operations.
        for (int i = 0; i < ke.co.technopick.mfi.core.payments.application.MpesaStkService.MAX_CHECKS; i++) {
            queryResult(stuck, "ws_CO_R4", null);
        }

        String attention = mvc.perform(get("/api/v1/payments/mpesa/stk-requests/attention").with(staff("ops-1", "OPERATIONS")))
            .andExpect(ok).andReturn().getResponse().getContentAsString();
        assertEquals("SEND_OUTCOME_UNKNOWN", ((java.util.List<?>) JsonPath.read(attention,
                "$[?(@.requestId == '" + unknownSend + "')].attention")).get(0));
        assertEquals("PAID_AWAITING_RECEIPT", ((java.util.List<?>) JsonPath.read(attention,
                "$[?(@.requestId == '" + paidNoReceipt + "')].attention")).get(0));
        assertEquals("RECONCILE_EXHAUSTED", ((java.util.List<?>) JsonPath.read(attention,
                "$[?(@.requestId == '" + stuck + "')].attention")).get(0));
        mvc.perform(get("/api/v1/payments/mpesa/stk-requests/attention").with(teller(TENANT_A))).andExpect(status().isForbidden());
        java.util.List<String> staleAfter = JsonPath.read(stk("/reconciliation/stale",
                "{\"tenantId\":\"" + TENANT_A + "\",\"olderThanSeconds\":0,\"limit\":100}", integrationToken(), ok), "$.items[*].requestId");
        assertEquals(Boolean.FALSE, staleAfter.contains(paidNoReceipt) || staleAfter.contains(stuck) || staleAfter.contains(cancelled));

        String late = stk("/" + paidNoReceipt + "/result", stkResult("ws_CO_R3", "0", "SIMREC0003", "50.00"), integrationToken(), ok);
        assertEquals("COMPLETED", JsonPath.read(late, "$.status"));
        expectBalance("0100000007", "50.00", "0.00", "50.00");
        String after = mvc.perform(get("/api/v1/payments/mpesa/stk-requests/attention").with(staff("ops-1", "OPERATIONS")))
            .andExpect(ok).andReturn().getResponse().getContentAsString();
        assertEquals(Boolean.FALSE, after.contains(paidNoReceipt));
    }

    String b2c(String path, String body, org.springframework.test.web.servlet.request.RequestPostProcessor auth,
               org.springframework.test.web.servlet.ResultMatcher expected) throws Exception {
        var builder = post("/api/v1/payments/mpesa/b2c/disbursements" + path).with(auth);
        if (body != null) {
            builder = builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(builder).andExpect(expected).andReturn().getResponse().getContentAsString();
    }

    static String b2cCreate(String key, String loan) {
        return """
            {"idempotencyKey":"%s","loanNumber":"%s","phoneNumber":"0712345678"}
            """.formatted(key, loan);
    }

    static String b2cResult(String conversation, String code, String txn, String amount) {
        return """
            {"tenantId":"%s","conversationId":"%s","resultCode":"%s","resultDesc":"test","transactionId":%s,"amount":%s}
            """.formatted(TENANT_A, conversation, code, txn == null ? "null" : "\"" + txn + "\"",
                amount == null ? "null" : "\"" + amount + "\"");
    }

    String approvedAndDispatched(String key, String loan) throws Exception {
        String id = JsonPath.read(b2c("", b2cCreate(key, loan), staff("officer-1", "LOAN_OFFICER"), status().isOk()),
                "$.disbursementId");
        b2c("/" + id + "/approve", null, staff("sup-1", "SUPERVISOR"), status().isOk());
        b2c("/" + id + "/dispatch", "{\"tenantId\":\"" + TENANT_A + "\"}", integrationToken(), status().isOk());
        return id;
    }

    /** B2C disbursement: maker-checker, single dispatch, post + activate only on a matching success. */
    @Test
    void mpesaB2cDisbursement() throws Exception {
        var ok = status().isOk();
        String tenantBody = "{\"tenantId\":\"" + TENANT_A + "\"}";
        b2c("", b2cCreate("b2c-0", "LN-000003"), teller(TENANT_A), status().isForbidden());
        b2c("", b2cCreate("b2c-x", "LN-000001"), staff("officer-1", "LOAN_OFFICER"), status().isBadRequest()); // already active
        String created = b2c("", b2cCreate("b2c-1", "LN-000003"), staff("officer-1", "LOAN_OFFICER"), ok);
        String id = JsonPath.read(created, "$.disbursementId");
        assertEquals("98000.00", JsonPath.read(created, "$.amount")); // 100,000 less the 2% upfront fee
        assertEquals(id, JsonPath.read(b2c("", b2cCreate("b2c-1", "LN-000003"), staff("officer-1", "LOAN_OFFICER"), ok),
                "$.disbursementId"));
        b2c("", b2cCreate("b2c-2", "LN-000003"), staff("officer-2", "LOAN_OFFICER"), status().isConflict()); // one live per loan
        b2c("/" + id + "/dispatch", tenantBody, integrationToken(), status().isConflict()); // not approved yet
        mvc.perform(post("/api/v1/payments/mpesa/b2c/disbursements/" + id + "/approve")
                .with(jwt().jwt(j -> j.subject("officer-1").claim("tenant_id", TENANT_A))
                    .authorities(new SimpleGrantedAuthority("ROLE_SUPERVISOR"))))
            .andExpect(status().isForbidden()); // requester cannot approve, whatever roles they hold
        b2c("/" + id + "/approve", null, staff("officer-2", "LOAN_OFFICER"), status().isForbidden());
        String waiting = mvc.perform(get("/api/v1/payments/mpesa/b2c/disbursements").with(staff("sup-1", "SUPERVISOR")))
            .andExpect(ok).andReturn().getResponse().getContentAsString();
        assertEquals(Boolean.TRUE, waiting.contains(id), waiting);
        assertEquals("APPROVED", JsonPath.read(b2c("/" + id + "/approve", null, staff("sup-1", "SUPERVISOR"), ok), "$.status"));
        String event = owner().queryForObject("""
                SELECT payload::text FROM messaging.outbox_event WHERE event_type = 'MpesaDisbursementApproved' AND aggregate_id = ?
                """, String.class, id);
        assertEquals(Boolean.FALSE, event.contains("712345678"), event);

        String dispatch = b2c("/" + id + "/dispatch", tenantBody, integrationToken(), ok);
        assertEquals("254712345678", JsonPath.read(dispatch, "$.msisdn"));
        assertEquals("98000", JsonPath.read(dispatch, "$.amount"));
        b2c("/" + id + "/dispatch", tenantBody, integrationToken(), status().isConflict()); // sent at most once
        b2c("/" + id + "/submitted", "{\"tenantId\":\"" + TENANT_A + "\",\"conversationId\":\"AG_1\"}", integrationToken(), ok);
        b2c("/" + id + "/result", b2cResult("AG_2", "0", "SIMB2C0001", "98000.00"), integrationToken(), status().isConflict());

        String done = b2c("/" + id + "/result", b2cResult("AG_1", "0", "SIMB2C0001", "98000.00"), integrationToken(), ok);
        assertEquals("COMPLETED", JsonPath.read(done, "$.status"));
        String entry = JsonPath.read(done, "$.journalEntryId");
        assertEquals(List.of("C 1110-MPESA-B2C-CLEARING 98000.00", "C 4100-FEE-PROCESSING 2000.00", "D 1200-LOANS-PRINCIPAL 100000.00"),
                owner().queryForList("""
                    SELECT side || ' ' || account_code || ' ' || round(amount, 2) FROM ledger.journal_line
                     WHERE entry_id = ?::uuid ORDER BY side, account_code
                    """, String.class, entry));
        assertEquals("ACTIVE", owner().queryForObject("SELECT status FROM lending.loan WHERE loan_number = 'LN-000003'", String.class));
        assertEquals(null, owner().queryForObject("SELECT msisdn FROM payments.b2c_disbursement WHERE disbursement_id = ?::uuid",
                String.class, id));
        b2c("/" + id + "/result", b2cResult("AG_1", "0", "SIMB2C0001", "98000.00"), integrationToken(), ok); // replay
        b2c("/" + id + "/result", b2cResult("AG_1", "0", "SIMB2C9999", "98000.00"), integrationToken(), status().isConflict());

        // Definitive failure: loan stays APPROVED, a new attempt may be requested; a later success is an alarm.
        String failed = approvedAndDispatched("b2c-3", "LN-000004");
        assertEquals("FAILED", JsonPath.read(b2c("/" + failed + "/result", b2cResult("AG_3", "1", null, null), integrationToken(), ok),
                "$.status"));
        assertEquals("APPROVED", owner().queryForObject("SELECT status FROM lending.loan WHERE loan_number = 'LN-000004'", String.class));
        String retry = JsonPath.read(b2c("", b2cCreate("b2c-4", "LN-000004"), staff("officer-1", "LOAN_OFFICER"), ok), "$.disbursementId");
        assertEquals("REJECTED", JsonPath.read(b2c("/" + retry + "/reject", null, staff("sup-1", "SUPERVISOR"), ok), "$.status"));
        String alarm = b2c("/" + failed + "/result", b2cResult("AG_3", "0", "SIMB2C0003", "98000.00"), integrationToken(), ok);
        assertEquals("FAILED", JsonPath.read(alarm, "$.status"));
        assertEquals("SUCCESS_AFTER_FAILURE", JsonPath.read(alarm, "$.attention"));
        assertEquals("APPROVED", owner().queryForObject("SELECT status FROM lending.loan WHERE loan_number = 'LN-000004'", String.class));

        // Timeout: unknown outcome, flagged; a mismatched amount is never posted.
        String timedOut = approvedAndDispatched("b2c-5", "LN-000005");
        b2c("/" + timedOut + "/submitted", "{\"tenantId\":\"" + TENANT_A + "\",\"conversationId\":\"AG_5\"}", integrationToken(), ok);
        assertEquals("TIMEOUT_UNKNOWN", JsonPath.read(b2c("/" + timedOut + "/timeout", tenantBody, integrationToken(), ok), "$.attention"));
        String mismatch = b2c("/" + timedOut + "/result", b2cResult("AG_5", "0", "SIMB2C0005", "90000.00"), integrationToken(), ok);
        assertEquals("SUBMITTED", JsonPath.read(mismatch, "$.status"));
        assertEquals("AMOUNT_MISMATCH", JsonPath.read(mismatch, "$.attention"));
        assertEquals("APPROVED", owner().queryForObject("SELECT status FROM lending.loan WHERE loan_number = 'LN-000005'", String.class));

        String attention = mvc.perform(get("/api/v1/payments/mpesa/b2c/disbursements/attention").with(staff("ops-1", "OPERATIONS")))
            .andExpect(ok).andReturn().getResponse().getContentAsString();
        assertEquals(Boolean.TRUE, attention.contains(failed) && attention.contains(timedOut) && !attention.contains(id), attention);
        mvc.perform(get("/api/v1/payments/mpesa/b2c/disbursements/attention").with(teller(TENANT_A))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/payments/mpesa/b2c/disbursements/" + id).with(jwt().jwt(j -> j.subject("x").claim("tenant_id", TENANT_B))
                .authorities(new SimpleGrantedAuthority("ROLE_SUPERVISOR"))))
            .andExpect(status().isNotFound());
    }

    String propose(String type, String item, String outcome, String ref, String amount, String evidence,
                   org.springframework.test.web.servlet.ResultMatcher expected) throws Exception {
        return mvc.perform(post("/api/v1/payments/mpesa/resolutions").with(staff("ops-1", "OPERATIONS"))
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"itemType":"%s","itemId":"%s","outcome":"%s","providerRef":%s,"amount":%s,"evidence":"%s"}
                    """.formatted(type, item, outcome, ref == null ? "null" : "\"" + ref + "\"",
                        amount == null ? "null" : "\"" + amount + "\"", evidence)))
            .andExpect(expected).andReturn().getResponse().getContentAsString();
    }

    String decideResolution(String id, String action, org.springframework.test.web.servlet.request.RequestPostProcessor who,
                            org.springframework.test.web.servlet.ResultMatcher expected) throws Exception {
        return mvc.perform(post("/api/v1/payments/mpesa/resolutions/" + id + "/" + action).with(who))
            .andExpect(expected).andReturn().getResponse().getContentAsString();
    }

    /** Operations close-out: proposed by one person, approved by another, applied through the normal paths. */
    @Test
    void mpesaManualResolution() throws Exception {
        var ok = status().isOk();
        String tenantBody = "{\"tenantId\":\"" + TENANT_A + "\"}";
        // STK request paid according to the status query, receipt never delivered.
        String paid = JsonPath.read(stk("", stkCreate("res-1", "0712345678", "50", "0100000008"), teller(TENANT_A), ok), "$.requestId");
        stk("/" + paid + "/dispatch", tenantBody, integrationToken(), ok);
        stk("/" + paid + "/submitted", "{\"tenantId\":\"" + TENANT_A + "\",\"checkoutRequestId\":\"ws_CO_RES1\"}", integrationToken(), ok);
        queryResult(paid, "ws_CO_RES1", "0");

        propose("STK_REQUEST", paid, "PAID", null, null, "Portal statement 18/11 14:02", status().isBadRequest()); // no receipt
        propose("STK_REQUEST", paid, "PAID", "SIMRES0001", "50.00", "short", status().isBadRequest());           // weak evidence
        mvc.perform(get("/api/v1/payments/mpesa/resolutions").with(teller(TENANT_A))).andExpect(status().isForbidden());
        String r1 = JsonPath.read(propose("STK_REQUEST", paid, "PAID", "SIMRES0001", "50.00",
                "M-Pesa org portal statement 18/11 14:02", ok), "$.resolutionId");
        propose("STK_REQUEST", paid, "NOT_PAID", null, null, "Customer says it failed", status().isConflict()); // one live
        decideResolution(r1, "approve", jwt().jwt(j -> j.subject("ops-1").claim("tenant_id", TENANT_A))
                .authorities(new SimpleGrantedAuthority("ROLE_SUPERVISOR")), status().isForbidden());
        decideResolution(r1, "approve", staff("ops-2", "OPERATIONS"), status().isForbidden());
        String approved = decideResolution(r1, "approve", staff("sup-1", "SUPERVISOR"), ok);
        assertEquals("APPROVED", JsonPath.read(approved, "$.status"));
        assertEquals("COMPLETED", JsonPath.read(approved, "$.resultStatus"));
        expectBalance("0100000008", "50.00", "0.00", "50.00");
        decideResolution(r1, "reject", staff("sup-1", "SUPERVISOR"), status().isConflict());
        propose("STK_REQUEST", paid, "PAID", "SIMRES0001", "50.00", "Second attempt at the same item", status().isConflict());

        // Not paid: first proposal rejected, second approved; the request is closed without money.
        String unpaid = JsonPath.read(stk("", stkCreate("res-2", "0712345678", "50", "0100000008"), teller(TENANT_A), ok), "$.requestId");
        stk("/" + unpaid + "/dispatch", tenantBody, integrationToken(), ok);
        String r2 = JsonPath.read(propose("STK_REQUEST", unpaid, "NOT_PAID", null, null, "Customer confirms no prompt", ok), "$.resolutionId");
        assertEquals("REJECTED", JsonPath.read(decideResolution(r2, "reject", staff("sup-1", "SUPERVISOR"), ok), "$.status"));
        String r3 = JsonPath.read(propose("STK_REQUEST", unpaid, "NOT_PAID", null, null, "Portal shows no transaction", ok), "$.resolutionId");
        assertEquals("FAILED", JsonPath.read(decideResolution(r3, "approve", staff("sup-1", "SUPERVISOR"), ok), "$.resultStatus"));
        expectBalance("0100000008", "50.00", "0.00", "50.00");

        // B2C disbursement with a queue timeout: confirmed paid from the portal, so posted and activated.
        String timedOut = approvedAndDispatched("res-b1", "LN-000006");
        b2c("/" + timedOut + "/submitted", "{\"tenantId\":\"" + TENANT_A + "\",\"conversationId\":\"AG_RES1\"}", integrationToken(), ok);
        propose("B2C_DISBURSEMENT", timedOut, "PAID", "SIMRESB001", "98000.00", "Portal shows payment", status().isConflict()); // not flagged
        b2c("/" + timedOut + "/timeout", tenantBody, integrationToken(), ok);
        String r4 = JsonPath.read(propose("B2C_DISBURSEMENT", timedOut, "PAID", "SIMRESB001", "98000.00",
                "Portal: B2C payment completed 14:05", ok), "$.resolutionId");
        assertEquals("COMPLETED", JsonPath.read(decideResolution(r4, "approve", staff("sup-1", "SUPERVISOR"), ok), "$.resultStatus"));
        assertEquals("ACTIVE", owner().queryForObject("SELECT status FROM lending.loan WHERE loan_number = 'LN-000006'", String.class));
        propose("B2C_DISBURSEMENT", timedOut, "NOT_PAID", null, null, "Too late, already completed", status().isConflict());

        String pending = mvc.perform(get("/api/v1/payments/mpesa/resolutions").with(staff("sup-1", "SUPERVISOR")))
            .andExpect(ok).andReturn().getResponse().getContentAsString();
        assertEquals(Boolean.FALSE, pending.contains(r1) || pending.contains(r3) || pending.contains(r4), pending);
    }

    static String journeyCustomer = "none";

    static String application(String key, String principal, int term, String firstDue) {
        return """
            {"idempotencyKey":"%s","customerRef":"%s","productCode":"biashara","branchCode":"NBO01",
             "principal":"%s","termMonths":%d,%s"purpose":"Restock the shop"}
            """.formatted(key, journeyCustomer, principal, term,
                firstDue == null ? "" : "\"firstDueDate\":\"" + firstDue + "\",");
    }

    static String customerJson(String key, String idNumber, String dob, boolean consent) {
        return """
            {"idempotencyKey":"%s","firstName":"Achieng","lastName":"Otieno","dateOfBirth":"%s","idType":"NATIONAL_ID",
             "idNumber":"%s","phoneNumber":"0712345678","branchCode":"NBO01","consentGiven":%s}
            """.formatted(key, dob, idNumber, consent);
    }

    String registerAndVerify(String key, String idNumber) throws Exception {
        String number = JsonPath.read(mvc.perform(post("/api/v1/customers").with(staff("officer-1", "LOAN_OFFICER"))
                .contentType(MediaType.APPLICATION_JSON).content(customerJson(key, idNumber, "1990-05-01", true)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.customerNumber");
        mvc.perform(post("/api/v1/customers/" + number + "/verify").with(staff("manager-1", "BRANCH_MANAGER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"National ID sighted, IPRS ref T-1\"}"))
            .andExpect(status().isOk());
        return number;
    }

    /** Customers and KYC: consent, adults only, one per document, four-eyes verification, masked search. */
    @Test
    void customerRegistrationAndKyc() throws Exception {
        var ok = status().isOk();
        var officer = staff("officer-1", "LOAN_OFFICER");
        java.util.function.BiFunction<String, org.springframework.test.web.servlet.ResultMatcher, String> register = (body, expected) -> {
            try {
                return mvc.perform(post("/api/v1/customers").with(officer).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(expected).andReturn().getResponse().getContentAsString();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        register.apply(customerJson("cu-0", "22334455", "1990-05-01", false), status().isBadRequest()); // no consent
        assertEquals(Boolean.TRUE, register.apply(customerJson("cu-0b", "22334455", "1990-05-01", true).replace(",\"consentGiven\":true", ""),
                status().isBadRequest()).contains("consent"), "omitted consent is refused with a clear reason");
        register.apply(customerJson("cu-1", "22334456", "2015-01-01", true), status().isBadRequest());  // minor
        register.apply(customerJson("cu-2", "12AB", "1990-05-01", true), status().isBadRequest()); // malformed ID
        mvc.perform(post("/api/v1/customers").with(teller(TENANT_A)).contentType(MediaType.APPLICATION_JSON)
                .content(customerJson("cu-t", "22334499", "1990-05-01", true))).andExpect(ok); // tellers may register
        String created = register.apply(customerJson("cu-3", "22334457", "1990-05-01", true), ok);
        String number = JsonPath.read(created, "$.customerNumber");
        assertEquals(Boolean.TRUE, number.matches("^CU26-\\d{7}$"), number);
        assertEquals("PENDING_VERIFICATION", JsonPath.read(created, "$.status"));
        assertEquals(number, JsonPath.read(register.apply(customerJson("cu-3", "22334457", "1990-05-01", true), ok), "$.customerNumber"));
        register.apply(customerJson("cu-4", "22334457", "1990-05-01", true), status().isConflict()); // same ID again

        String verifyBody = "{\"note\":\"National ID sighted at branch\"}";
        mvc.perform(post("/api/v1/customers/" + number + "/verify")
                .with(jwt().jwt(j -> j.subject("officer-1").claim("tenant_id", TENANT_A))
                    .authorities(new SimpleGrantedAuthority("ROLE_BRANCH_MANAGER")))
                .contentType(MediaType.APPLICATION_JSON).content(verifyBody))
            .andExpect(status().isForbidden()); // the registrar cannot verify, whatever roles they hold
        mvc.perform(post("/api/v1/customers/" + number + "/verify").with(officer)
                .contentType(MediaType.APPLICATION_JSON).content(verifyBody)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/customers/" + number + "/verify").with(staff("manager-1", "BRANCH_MANAGER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"ok\"}")).andExpect(status().isBadRequest());
        String verified = mvc.perform(post("/api/v1/customers/" + number + "/verify").with(staff("manager-1", "BRANCH_MANAGER"))
                .contentType(MediaType.APPLICATION_JSON).content(verifyBody))
            .andExpect(ok).andReturn().getResponse().getContentAsString();
        assertEquals("ACTIVE", JsonPath.read(verified, "$.status"));
        assertEquals("user:manager-1", JsonPath.read(verified, "$.verifiedBy"));
        assertEquals("DPA-2026-01", JsonPath.read(verified, "$.consentVersion"));

        String found = mvc.perform(get("/api/v1/customers").param("q", "22334457").with(officer))
            .andExpect(ok).andReturn().getResponse().getContentAsString();
        assertEquals("*****457", JsonPath.read(found, "$[0].idNumberMasked"));
        assertEquals("2547*****678", JsonPath.read(found, "$[0].phoneMasked"));
        assertEquals(Boolean.FALSE, found.contains("22334457") || found.contains("254712345678"), found);
        assertEquals(1, ((List<?>) JsonPath.read(mvc.perform(get("/api/v1/customers").param("q", "otieno achieng").with(officer))
            .andReturn().getResponse().getContentAsString(), "$[?(@.customerNumber == '" + number + "')]")).size());
        mvc.perform(get("/api/v1/customers").param("q", "ab").with(officer)).andExpect(status().isBadRequest());
        // Tenant B numbers its own customers, possibly with the same number, but never sees tenant A's customer.
        var crossTenant = mvc.perform(get("/api/v1/customers/" + number).with(jwt().jwt(j -> j.subject("x").claim("tenant_id", TENANT_B))
                .authorities(new SimpleGrantedAuthority("ROLE_BRANCH_MANAGER")))).andReturn().getResponse();
        assertEquals(Boolean.TRUE, crossTenant.getStatus() == 404 || !crossTenant.getContentAsString().contains("*****457"),
                crossTenant.getContentAsString());
    }

    String apply(String body, org.springframework.test.web.servlet.request.RequestPostProcessor who,
                 org.springframework.test.web.servlet.ResultMatcher expected) throws Exception {
        return mvc.perform(post("/api/v1/lending/applications").with(who).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(expected).andReturn().getResponse().getContentAsString();
    }

    String decideApplication(String id, String action, String body, org.springframework.test.web.servlet.request.RequestPostProcessor who,
                             org.springframework.test.web.servlet.ResultMatcher expected) throws Exception {
        var builder = post("/api/v1/lending/applications/" + id + "/" + action).with(who);
        if (body != null) {
            builder = builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(builder).andExpect(expected).andReturn().getResponse().getContentAsString();
    }

    /** Origination to repayment with no seeded loan: apply, approve, disburse by B2C, repay by paybill. */
    @Test
    void loanOriginationJourney() throws Exception {
        var ok = status().isOk();
        var bad = status().isBadRequest();
        var officer = staff("officer-1", "LOAN_OFFICER");
        var manager = staff("manager-1", "BRANCH_MANAGER");
        // The applicant must be a verified customer.
        String unverified = JsonPath.read(mvc.perform(post("/api/v1/customers").with(officer).contentType(MediaType.APPLICATION_JSON)
                .content(customerJson("journey-u", "33445566", "1988-02-02", true)))
            .andExpect(ok).andReturn().getResponse().getContentAsString(), "$.customerNumber");
        journeyCustomer = unverified;
        apply(application("app-u", "100000", 12, null), officer, bad);
        journeyCustomer = registerAndVerify("journey-c", "33445567");
        apply(application("app-0", "100000", 12, null), teller(TENANT_A), status().isForbidden());
        apply(application("app-x1", "1000", 12, null), officer, bad);                // below the product minimum
        apply(application("app-x2", "100000", 30, null), officer, bad);              // above the maximum term
        apply(application("app-x3", "100000", 12, "2026-11-18"), officer, bad);      // first due date not after today
        apply(application("app-x4", "100000.005", 12, null), officer, bad);          // sub-cent amount

        String submitted = apply(application("app-1", "100000", 12, "2026-12-18"), officer, ok);
        String id = JsonPath.read(submitted, "$.applicationId");
        assertEquals("SUBMITTED", JsonPath.read(submitted, "$.status"));
        assertEquals("9455.96", JsonPath.read(submitted, "$.quote.installments[0].total"));
        assertEquals("2000.00", JsonPath.read(submitted, "$.quote.upfrontFee"));
        assertEquals("98000.00", JsonPath.read(submitted, "$.quote.netDisbursement"));
        assertEquals(12, ((List<?>) JsonPath.read(submitted, "$.quote.installments")).size());
        assertEquals("0.00", JsonPath.read(submitted, "$.quote.installments[11].closingBalance"));
        assertEquals(id, JsonPath.read(apply(application("app-1", "100000", 12, "2026-12-18"), officer, ok), "$.applicationId"));
        apply(application("app-1", "90000", 12, "2026-12-18"), officer, status().isConflict());

        decideApplication(id, "approve", null, jwt().jwt(j -> j.subject("officer-1").claim("tenant_id", TENANT_A))
                .authorities(new SimpleGrantedAuthority("ROLE_BRANCH_MANAGER")), status().isForbidden()); // submitter
        decideApplication(id, "approve", null, staff("officer-2", "LOAN_OFFICER"), status().isForbidden());
        decideApplication(id, "decline", "{\"reason\":\"\"}", manager, bad);
        String approved = decideApplication(id, "approve", null, manager, ok);
        assertEquals("APPROVED", JsonPath.read(approved, "$.status"));
        String loanNumber = JsonPath.read(approved, "$.loanNumber");
        assertEquals(Boolean.TRUE, loanNumber.matches("^LN26-\\d{7}$"), loanNumber);
        decideApplication(id, "approve", null, manager, status().isConflict());

        String schedule = mvc.perform(get("/api/v1/lending/loans/" + loanNumber + "/schedule").with(teller(TENANT_A)))
            .andExpect(ok).andReturn().getResponse().getContentAsString();
        assertEquals(Integer.valueOf(1), JsonPath.<Integer>read(schedule, "$.scheduleVersion"));
        assertEquals("2026-12-18", JsonPath.read(schedule, "$.installments[0].dueDate"));
        assertEquals("2027-11-18", JsonPath.read(schedule, "$.installments[11].dueDate"));
        assertEquals("0.00", JsonPath.read(schedule, "$.installments[11].closingBalance"));
        assertEquals("APPROVED", JsonPath.read(schedule, "$.status"));
        assertEquals(List.of("user:officer-1|user:manager-1"), owner().queryForList(
                "SELECT created_by || '|' || approved_by FROM lending.loan WHERE loan_number = ?", String.class, loanNumber));
        assertEquals(journeyCustomer, owner().queryForObject(
                "SELECT c.customer_number FROM lending.loan l JOIN customer.customer c USING (tenant_id, customer_id) WHERE l.loan_number = ?",
                String.class, loanNumber));

        // Declined application: reason kept, no loan.
        String other = JsonPath.read(apply(application("app-2", "50000", 6, null), officer, ok), "$.applicationId");
        String declined = decideApplication(other, "decline", "{\"reason\":\"Existing arrears with another lender\"}", manager, ok);
        assertEquals("DECLINED", JsonPath.read(declined, "$.status"));
        org.junit.jupiter.api.Assertions.assertNull(JsonPath.read(declined, "$.loanNumber"));

        // Disburse the new loan by B2C, then the customer pays through the paybill with the loan number.
        // Payouts go only to the customer's registered number (0712345678).
        mvc.perform(post("/api/v1/payments/mpesa/b2c/disbursements").with(officer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\":\"orig-wrong\",\"loanNumber\":\"" + loanNumber + "\",\"phoneNumber\":\"0799999999\"}"))
            .andExpect(bad);
        String disbursement = approvedAndDispatched("orig-b2c", loanNumber);
        assertEquals("COMPLETED", JsonPath.read(b2c("/" + disbursement + "/result",
                b2cResult("AG_ORIG", "0", "SIMORIG001", "98000.00"), integrationToken(), ok), "$.status"));
        String paid = c2b("", confirmation("SIMORIGP01", loanNumber, "9455.96"), integrationToken(), ok);
        assertEquals("LOAN_REPAYMENT", JsonPath.read(paid, "$.outcome"));
        // Nothing is due before 18 Dec, so today's payment is held as an overpayment (policy pending, ADR-0010).
        assertEquals(0, new BigDecimal("9455.96").compareTo(owner().queryForObject(
                "SELECT unallocated FROM lending.repayment WHERE provider_ref = 'SIMORIGP01'", BigDecimal.class)));
    }

    String cashTxn(String body, org.springframework.test.web.servlet.request.RequestPostProcessor who,
                  org.springframework.test.web.servlet.ResultMatcher expected) throws Exception {
        var result = mvc.perform(post("/api/v1/savings/teller/transactions").with(who).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn();
        String response = result.getResponse().getContentAsString();
        try {
            expected.match(result);
        } catch (AssertionError e) {
            throw new AssertionError(e.getMessage() + " - response: " + response, e);
        }
        return response;
    }

    static String cash(String key, String account, String kind, String amount) {
        return """
            {"idempotencyKey":"%s","accountNumber":"%s","kind":"%s","amount":"%s","narrative":"%s at counter"}
            """.formatted(key, account, kind, amount, kind.toLowerCase());
    }

    /** Savings for a verified customer: open, teller cash in and out, large withdrawal approved by another person, statement. */
    @Test
    void savingsAccountAndTeller() throws Exception {
        var ok = status().isOk();
        var tellerUser = staff("teller-1", "TELLER");
        String customer = registerAndVerify("sav-c", "44556677");
        String opened = mvc.perform(post("/api/v1/savings/accounts").with(tellerUser).contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\":\"sav-open-1\",\"customerNumber\":\"" + customer + "\",\"productCode\":\"sav01\",\"branchCode\":\"NBO01\"}"))
            .andExpect(ok).andReturn().getResponse().getContentAsString();
        String account = JsonPath.read(opened, "$.accountNumber");
        assertEquals(Boolean.TRUE, account.matches("^2\\d{9}$"), account);
        // An unverified customer cannot open an account.
        String pendingCustomer = JsonPath.read(mvc.perform(post("/api/v1/customers").with(staff("officer-1", "LOAN_OFFICER"))
                .contentType(MediaType.APPLICATION_JSON).content(customerJson("sav-p", "44556678", "1990-01-01", true)))
            .andReturn().getResponse().getContentAsString(), "$.customerNumber");
        mvc.perform(post("/api/v1/savings/accounts").with(tellerUser).contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\":\"sav-open-2\",\"customerNumber\":\"" + pendingCustomer + "\",\"productCode\":\"SAV01\",\"branchCode\":\"NBO01\"}"))
            .andExpect(status().isBadRequest());

        // No cash without an open till.
        assertEquals(Boolean.TRUE, cashTxn(cash("t-dep-0", account, "DEPOSIT", "100"), tellerUser, status().isBadRequest())
                .contains("open your till"));
        mvc.perform(post("/api/v1/savings/till-sessions").with(tellerUser).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tillCode\":\"NBO01-T1\"}")).andExpect(ok);

        assertEquals("POSTED", JsonPath.read(cashTxn(cash("t-dep-1", account, "DEPOSIT", "100000"), tellerUser, ok), "$.status"));
        assertEquals(Boolean.TRUE, JsonPath.read(cashTxn(cash("t-dep-1", account, "DEPOSIT", "100000"), tellerUser, ok), "$.replayed"));
        cashTxn(cash("t-dep-1", account, "DEPOSIT", "5"), tellerUser, status().isConflict());
        assertEquals("POSTED", JsonPath.read(cashTxn(cash("t-wd-1", account, "WITHDRAWAL", "20000.50"), tellerUser, ok), "$.status"));
        cashTxn(cash("t-wd-x", account, "WITHDRAWAL", "90000"), tellerUser, status().isBadRequest()); // more than available
        cashTxn(cash("t-wd-y", account, "WITHDRAWAL", "10"), staff("ops-1", "OPERATIONS"), status().isForbidden());

        // 60,000 is above the 50,000 policy: waits for a different person.
        String big = cashTxn(cash("t-wd-2", account, "WITHDRAWAL", "60000"), tellerUser, ok);
        assertEquals("PENDING_APPROVAL", JsonPath.read(big, "$.status"));
        String bigId = JsonPath.read(big, "$.txnId");
        expectBalance(account, "79999.50", "0.00", "79999.50");
        mvc.perform(post("/api/v1/savings/teller/transactions/" + bigId + "/approve")
                .with(jwt().jwt(j -> j.subject("teller-1").claim("tenant_id", TENANT_A))
                    .authorities(new SimpleGrantedAuthority("ROLE_SUPERVISOR"))))
            .andExpect(status().isForbidden()); // the capturing teller cannot approve
        String pending = mvc.perform(get("/api/v1/savings/teller/transactions").with(staff("sup-1", "SUPERVISOR")))
            .andExpect(ok).andReturn().getResponse().getContentAsString();
        assertEquals(Boolean.TRUE, pending.contains(bigId));
        assertEquals("POSTED", JsonPath.read(mvc.perform(post("/api/v1/savings/teller/transactions/" + bigId + "/approve")
                .with(staff("sup-1", "SUPERVISOR"))).andExpect(ok).andReturn().getResponse().getContentAsString(), "$.status"));
        mvc.perform(post("/api/v1/savings/teller/transactions/" + bigId + "/approve").with(staff("sup-1", "SUPERVISOR")))
            .andExpect(status().isConflict());
        expectBalance(account, "19999.50", "0.00", "19999.50");

        // A requested-approval deposit, rejected with a reason: no money moves.
        String asked = cashTxn("""
            {"idempotencyKey":"t-dep-2","accountNumber":"%s","kind":"DEPOSIT","amount":"500","requestApproval":true}
            """.formatted(account), tellerUser, ok);
        String askedId = JsonPath.read(asked, "$.txnId");
        mvc.perform(post("/api/v1/savings/teller/transactions/" + askedId + "/reject").with(staff("sup-1", "SUPERVISOR"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"\"}")).andExpect(status().isBadRequest());
        assertEquals("REJECTED", JsonPath.read(mvc.perform(post("/api/v1/savings/teller/transactions/" + askedId + "/reject")
                .with(staff("sup-1", "SUPERVISOR")).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Notes look counterfeit\"}"))
            .andExpect(ok).andReturn().getResponse().getContentAsString(), "$.status"));

        String statement = mvc.perform(get("/api/v1/savings/accounts/" + account + "/statement").with(tellerUser))
            .andExpect(ok).andReturn().getResponse().getContentAsString();
        assertEquals("0.00", JsonPath.read(statement, "$.openingBalance"));
        assertEquals("100000.00", JsonPath.read(statement, "$.totalCredits"));
        assertEquals("80000.50", JsonPath.read(statement, "$.totalDebits"));
        assertEquals("19999.50", JsonPath.read(statement, "$.closingBalance"));
        assertEquals(List.of("deposit at counter", "withdrawal at counter", "withdrawal at counter"),
                JsonPath.read(statement, "$.lines[*].description"));
        assertEquals(List.of("100000.00", "79999.50", "19999.50"), JsonPath.read(statement, "$.lines[*].balance"));
        String mine = mvc.perform(get("/api/v1/savings/accounts").param("customerNumber", customer).with(tellerUser))
            .andExpect(ok).andReturn().getResponse().getContentAsString();
        assertEquals(account, JsonPath.read(mine, "$[0].accountNumber"));
        assertEquals("Achieng Otieno", JsonPath.read(mine, "$[0].customerName"));
        // The savings account receives paybill payments like any other account.
        assertEquals("APPLIED", JsonPath.read(c2b("", confirmation("SIMSAV0001", account, "250.00"), integrationToken(), ok), "$.outcome"));
        expectBalance(account, "20249.50", "0.00", "20249.50");
    }

    String tillPost(String path, String body, org.springframework.test.web.servlet.request.RequestPostProcessor who,
                    org.springframework.test.web.servlet.ResultMatcher expected) throws Exception {
        var result = mvc.perform(post("/api/v1/savings" + path).with(who).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn();
        String response = result.getResponse().getContentAsString();
        try {
            expected.match(result);
        } catch (AssertionError e) {
            throw new AssertionError(e.getMessage() + " - response: " + response, e);
        }
        return response;
    }

    /** A till day: open, vault cash in (approved by another person), serve customers, count, approve a shortage. */
    @Test
    void tellerTillDay() throws Exception {
        var ok = status().isOk();
        var teller = staff("teller-2", "TELLER");
        var sup = staff("sup-2", "SUPERVISOR");
        String customer = registerAndVerify("till-c", "55667788");
        String account = JsonPath.read(mvc.perform(post("/api/v1/savings/accounts").with(teller).contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\":\"till-open-1\",\"customerNumber\":\"" + customer + "\",\"productCode\":\"SAV01\",\"branchCode\":\"NBO01\"}"))
            .andExpect(ok).andReturn().getResponse().getContentAsString(), "$.accountNumber");

        tillPost("/till-sessions", "{\"tillCode\":\"NBO01-T9\"}", teller, status().isBadRequest());
        String opened = tillPost("/till-sessions", "{\"tillCode\":\"NBO01-T2\"}", teller, ok);
        String sessionId = JsonPath.read(opened, "$.sessionId");
        assertEquals("OPEN", JsonPath.read(opened, "$.status"));
        assertEquals("0.00", JsonPath.read(opened, "$.expectedCash"));
        tillPost("/till-sessions", "{\"tillCode\":\"NBO01-T1\"}", teller, status().isConflict()); // one till per teller
        tillPost("/till-sessions", "{\"tillCode\":\"NBO01-T2\"}", staff("teller-3", "TELLER"), status().isConflict());
        tillPost("/till-sessions", "{\"tillCode\":\"NBO01-T2\"}", sup, status().isForbidden());

        // Paying out needs cash in the till.
        cashTxn(cash("till-d-1", account, "DEPOSIT", "30000"), teller, ok);
        assertEquals(Boolean.TRUE, cashTxn(cash("till-w-0", account, "WITHDRAWAL", "100"), teller, ok).contains("POSTED"));

        String tr = "{\"idempotencyKey\":\"till-x-1\",\"direction\":\"VAULT_TO_TILL\",\"amount\":\"50000\"}";
        String transfer = tillPost("/till-sessions/" + sessionId + "/transfers", tr, teller, ok);
        String transferId = JsonPath.read(transfer, "$.transferId");
        assertEquals("PENDING", JsonPath.read(transfer, "$.status"));
        assertEquals(Boolean.TRUE, JsonPath.read(tillPost("/till-sessions/" + sessionId + "/transfers", tr, teller, ok), "$.replayed"));
        tillPost("/till-sessions/" + sessionId + "/transfers", tr.replace("50000", "1"), teller, status().isConflict());
        tillPost("/till-sessions/" + sessionId + "/transfers", tr.replace("till-x-1", "till-x-2"), staff("teller-3", "TELLER"),
                status().isForbidden()); // not their till
        tillPost("/cash-transfers/" + transferId + "/approve", "{}", teller, status().isForbidden());
        // Cannot close while the transfer waits.
        tillPost("/till-sessions/" + sessionId + "/close", "{\"countedAmount\":\"0\"}", teller, status().isUnprocessableContent());
        assertEquals(Boolean.TRUE, mvc.perform(get("/api/v1/savings/cash-transfers").with(sup)).andExpect(ok).andReturn()
                .getResponse().getContentAsString().contains(transferId));
        assertEquals("POSTED", JsonPath.read(tillPost("/cash-transfers/" + transferId + "/approve", "{}", sup, ok), "$.status"));
        tillPost("/cash-transfers/" + transferId + "/approve", "{}", sup, status().isConflict());

        // Returning more than the till holds is refused.
        tillPost("/till-sessions/" + sessionId + "/transfers",
                "{\"idempotencyKey\":\"till-x-3\",\"direction\":\"TILL_TO_VAULT\",\"amount\":\"999999\"}", teller,
                status().isBadRequest());

        String mine = mvc.perform(get("/api/v1/savings/till-sessions/mine").with(teller)).andExpect(ok).andReturn()
            .getResponse().getContentAsString();
        assertEquals("79900.00", JsonPath.read(mine, "$.expectedCash"));

        // Count 79,800: a 100 shortage waits for the supervisor.
        String closing = tillPost("/till-sessions/" + sessionId + "/close", "{\"countedAmount\":\"79800\"}", teller, ok);
        assertEquals("CLOSING", JsonPath.read(closing, "$.status"));
        assertEquals("-100.00", JsonPath.read(closing, "$.variance"));
        cashTxn(cash("till-d-2", account, "DEPOSIT", "10"), teller, status().isBadRequest()); // no cash while closing
        // The supervisor sends it back for a recount; the teller recounts and finds 79,850.
        tillPost("/till-sessions/" + sessionId + "/recount", "{\"reason\":\"Count the 50s again\"}", sup, ok);
        assertEquals("CLOSING", JsonPath.read(tillPost("/till-sessions/" + sessionId + "/close",
                "{\"countedAmount\":\"79850\"}", teller, ok), "$.status"));
        tillPost("/till-sessions/" + sessionId + "/approve-variance", "{\"reason\":\"x\"}", sup, status().isBadRequest());
        tillPost("/till-sessions/" + sessionId + "/approve-variance", "{\"reason\":\"Shortage accepted\"}",
                staff("teller-2", "SUPERVISOR"), status().isForbidden()); // the teller cannot approve their own count
        assertEquals(Boolean.TRUE, mvc.perform(get("/api/v1/savings/till-sessions/closing").with(sup)).andExpect(ok).andReturn()
                .getResponse().getContentAsString().contains(sessionId));
        String closed = tillPost("/till-sessions/" + sessionId + "/approve-variance",
                "{\"reason\":\"Shortage of 50 accepted; teller counselled\"}", sup, ok);
        assertEquals("CLOSED", JsonPath.read(closed, "$.status"));
        assertEquals("-50.00", JsonPath.read(closed, "$.variance"));
        assertEquals("79850.00", JsonPath.read(closed, "$.expectedCash")); // ledger now matches the count
        mvc.perform(get("/api/v1/savings/till-sessions/mine").with(teller)).andExpect(status().isNotFound());
        Long shortages = owner().queryForObject("""
                SELECT sum(CASE side WHEN 'D' THEN amount ELSE -amount END) FROM ledger.journal_line
                 WHERE tenant_id = ? AND account_code = '5200-TELLER-SHORTAGES'
                """, Long.class, java.util.UUID.fromString(TENANT_A));
        assertEquals(50L, shortages);

        // Next day: a balanced count closes at once.
        String again = tillPost("/till-sessions", "{\"tillCode\":\"NBO01-T2\"}", teller, ok);
        assertEquals("79850.00", JsonPath.read(again, "$.expectedCash"));
        assertEquals("CLOSED", JsonPath.read(tillPost("/till-sessions/" + JsonPath.read(again, "$.sessionId") + "/close",
                "{\"countedAmount\":\"79850.00\"}", teller, ok), "$.status"));
    }

    static JwtRequestPostProcessor staffB(String subject, String role) {
        return jwt().jwt(j -> j.subject(subject).claim("tenant_id", TENANT_B)).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    String eodRun(JwtRequestPostProcessor who, org.springframework.test.web.servlet.ResultMatcher expected) throws Exception {
        var result = mvc.perform(post("/api/v1/end-of-day/run").with(who)).andReturn();
        String response = result.getResponse().getContentAsString();
        try {
            expected.match(result);
        } catch (AssertionError e) {
            throw new AssertionError(e.getMessage() + " - response: " + response, e);
        }
        return response;
    }

    /**
     * End of day with savings interest, in tenant B so moving its business date cannot disturb other
     * tests: tills must be closed, three days accrue (cumulative rounding), month end credits interest
     * less 15% withholding tax.
     */
    @Test
    void endOfDayLoansSavingsWriteOffAndRecovery() throws Exception {
        var ok = status().isOk();
        owner().execute("""
            INSERT INTO tenancy.branch (tenant_id, branch_code, name) VALUES ('%1$s', 'NBO01', 'Nairobi (B)');
            INSERT INTO ledger.gl_account (tenant_id, account_code, name, account_class, currency, requires_subledger) VALUES
              ('%1$s', '2110-SAVINGS-CONTROL', 'Savings control', 'LIABILITY', 'KES', true),
              ('%1$s', '1000-VAULT-CASH', 'Vault cash', 'ASSET', 'KES', false),
              ('%1$s', '1001-TILL-NBO01-01', 'Till 1', 'ASSET', 'KES', false),
              ('%1$s', '5300-SAVINGS-INTEREST', 'Interest on savings', 'EXPENSE', 'KES', false),
              ('%1$s', '2120-INTEREST-PAYABLE', 'Savings interest payable', 'LIABILITY', 'KES', false),
              ('%1$s', '2960-WHT-PAYABLE', 'Withholding tax payable', 'LIABILITY', 'KES', false);
            INSERT INTO ledger.accounting_period (tenant_id, period_id, start_date, end_date) VALUES
              ('%1$s', '2026-10', '2026-10-01', '2026-10-31'), ('%1$s', '2026-11', '2026-11-01', '2026-11-30'),
              ('%1$s', '2026-12', '2026-12-01', '2026-12-31');
            INSERT INTO tenancy.business_date (tenant_id, current_date_) VALUES ('%1$s', '2026-11-28');
            INSERT INTO savings.deposit_product (tenant_id, product_code, name, currency, gl_control_account, interest_rate,
                interest_min_balance, withholding_tax_rate, gl_interest_expense, gl_interest_payable, gl_withholding_tax)
              VALUES ('%1$s', 'SAVB', 'Member savings', 'KES', '2110-SAVINGS-CONTROL', 7.3, 1000, 15,
                      '5300-SAVINGS-INTEREST', '2120-INTEREST-PAYABLE', '2960-WHT-PAYABLE');
            INSERT INTO savings.branch_cash_account (tenant_id, branch_code, currency, gl_cash) VALUES ('%1$s', 'NBO01', 'KES', '1000-VAULT-CASH');
            INSERT INTO savings.till (tenant_id, till_id, till_code, branch_code, currency, gl_cash)
              VALUES ('%1$s', '7e000000-0000-0000-0000-0000000000b1', 'B-T1', 'NBO01', 'KES', '1001-TILL-NBO01-01');
            INSERT INTO savings.teller_policy (tenant_id, currency, withdrawal_approval_above, deposit_approval_above)
              VALUES ('%1$s', 'KES', 50000, 1000000);
            INSERT INTO ledger.gl_account (tenant_id, account_code, name, account_class, currency, requires_subledger) VALUES
              ('%1$s', '1200-LOANS-PRINCIPAL', 'Loans', 'ASSET', 'KES', false),
              ('%1$s', '1210-INTEREST-RECEIVABLE', 'Interest receivable', 'ASSET', 'KES', false),
              ('%1$s', '4000-INTEREST-INCOME', 'Interest income', 'INCOME', 'KES', false),
              ('%1$s', '1220-FEE-RECEIVABLE', 'Fee receivable', 'ASSET', 'KES', false),
              ('%1$s', '4100-FEE-PROCESSING', 'Fee income', 'INCOME', 'KES', false),
              ('%1$s', '1230-PENALTY-RECEIVABLE', 'Penalty receivable', 'ASSET', 'KES', false),
              ('%1$s', '4300-PENALTY-INCOME', 'Penalty income', 'INCOME', 'KES', false),
              ('%1$s', '2400-LOAN-OVERPAYMENTS', 'Overpayments', 'LIABILITY', 'KES', false),
              ('%1$s', '5100-LOAN-WRITE-OFFS', 'Write-offs', 'EXPENSE', 'KES', false),
              ('%1$s', '1110-MPESA-B2C-CLEARING', 'B2C clearing', 'ASSET', 'KES', false),
              ('%1$s', '4900-WRITE-OFF-RECOVERIES', 'Recoveries of written-off loans', 'INCOME', 'KES', false);
            INSERT INTO lending.loan_product (tenant_id, product_code, name, currency) VALUES ('%1$s', 'MKOPO', 'Member loan', 'KES');
            INSERT INTO lending.loan_product_version (tenant_id, version_id, product_code, version, interest_method, annual_rate,
                min_principal, max_principal, min_term_months, max_term_months, upfront_fee_rate, allocation_strategy,
                allocation_order, gl_principal, gl_interest_receivable, gl_interest_income, gl_fee_receivable, gl_fee_income,
                gl_penalty_receivable, gl_penalty_income, gl_overpayment, gl_write_off, created_by,
                penalty_method, penalty_grace_days, penalty_flat, arrears_tolerance, gl_write_off_recovery)
              VALUES ('%1$s', 'cccccccc-0000-0000-0000-0000000000b1', 'MKOPO', 1, 'REDUCING_BALANCE', 0.36, 1000, 500000, 1, 24,
                0, 'COMPONENT_FIRST', ARRAY['PENALTY','FEE','INTEREST','PRINCIPAL'], '1200-LOANS-PRINCIPAL',
                '1210-INTEREST-RECEIVABLE', '4000-INTEREST-INCOME', '1220-FEE-RECEIVABLE', '4100-FEE-PROCESSING',
                '1230-PENALTY-RECEIVABLE', '4300-PENALTY-INCOME', '2400-LOAN-OVERPAYMENTS', '5100-LOAN-WRITE-OFFS', 'test:maker',
                'FLAT_ONCE', 3, 200, 0, '4900-WRITE-OFF-RECOVERIES');
            UPDATE lending.loan_product_version SET status = 'ACTIVE', approved_by = 'test:checker'
             WHERE version_id = 'cccccccc-0000-0000-0000-0000000000b1';
            BEGIN;
            INSERT INTO lending.loan (tenant_id, loan_id, loan_number, customer_ref, product_version_id, branch_code, currency,
                principal, term_months, created_by, approved_by) VALUES
              ('%1$s', 'bbbbbbbb-0000-0000-0000-0000000000b1', 'LN-B00001', 'CB1', 'cccccccc-0000-0000-0000-0000000000b1',
               'NBO01', 'KES', 10000, 2, 'test:officer', 'test:manager');
            INSERT INTO lending.schedule_installment (tenant_id, loan_id, schedule_version, installment_no, due_date, principal, interest) VALUES
              ('%1$s', 'bbbbbbbb-0000-0000-0000-0000000000b1', 1, 1, '2026-11-24', 5000, 300),
              ('%1$s', 'bbbbbbbb-0000-0000-0000-0000000000b1', 1, 2, '2026-12-24', 5000, 150);
            INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor) VALUES
              ('%1$s', '77777777-0000-0000-0000-0000000000b1', 'loan:bbbbbbbb-0000-0000-0000-0000000000b1:DISBURSE:SEED', '2026-10-24', 'LOAN', 'seed', 'test');
            INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
              ('%1$s', '77777777-0000-0000-0000-0000000000b1', 1, '1200-LOANS-PRINCIPAL', 'D', 10000, 'KES', 'NBO01'),
              ('%1$s', '77777777-0000-0000-0000-0000000000b1', 2, '1110-MPESA-B2C-CLEARING', 'C', 10000, 'KES', 'NBO01');
            UPDATE lending.loan SET status = 'ACTIVE', disbursement_entry_id = '77777777-0000-0000-0000-0000000000b1'
             WHERE loan_id = 'bbbbbbbb-0000-0000-0000-0000000000b1';
            INSERT INTO lending.loan (tenant_id, loan_id, loan_number, customer_ref, product_version_id, branch_code, currency,
                principal, term_months, created_by, approved_by) VALUES
              ('%1$s', 'bbbbbbbb-0000-0000-0000-0000000000b2', 'LN-B00002', 'CB2', 'cccccccc-0000-0000-0000-0000000000b1',
               'NBO01', 'KES', 2000, 1, 'test:officer', 'test:manager');
            INSERT INTO lending.schedule_installment (tenant_id, loan_id, schedule_version, installment_no, due_date, principal, interest) VALUES
              ('%1$s', 'bbbbbbbb-0000-0000-0000-0000000000b2', 1, 1, '2026-11-29', 2000, 60);
            INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor) VALUES
              ('%1$s', '77777777-0000-0000-0000-0000000000b2', 'loan:bbbbbbbb-0000-0000-0000-0000000000b2:DISBURSE:SEED', '2026-10-30', 'LOAN', 'seed', 'test'),
              ('%1$s', '77777777-0000-0000-0000-0000000000b3', 'loan:bbbbbbbb-0000-0000-0000-0000000000b2:REPAY:B2-RCPT', '2026-11-28', 'LOAN', 'seed', 'test');
            INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
              ('%1$s', '77777777-0000-0000-0000-0000000000b2', 1, '1200-LOANS-PRINCIPAL', 'D', 2000, 'KES', 'NBO01'),
              ('%1$s', '77777777-0000-0000-0000-0000000000b2', 2, '1110-MPESA-B2C-CLEARING', 'C', 2000, 'KES', 'NBO01'),
              ('%1$s', '77777777-0000-0000-0000-0000000000b3', 1, '1110-MPESA-B2C-CLEARING', 'D', 2100, 'KES', 'NBO01'),
              ('%1$s', '77777777-0000-0000-0000-0000000000b3', 2, '2400-LOAN-OVERPAYMENTS', 'C', 2100, 'KES', 'NBO01');
            UPDATE lending.loan SET status = 'ACTIVE', disbursement_entry_id = '77777777-0000-0000-0000-0000000000b2'
             WHERE loan_id = 'bbbbbbbb-0000-0000-0000-0000000000b2';
            -- Paid 2,100 the day before its only installment (2,060) falls due: all held as credit.
            INSERT INTO lending.repayment (tenant_id, repayment_id, loan_id, amount, unallocated, currency, channel, provider_ref,
                journal_entry_id, received_at)
              VALUES ('%1$s', 'dddddddd-0000-0000-0000-0000000000b2', 'bbbbbbbb-0000-0000-0000-0000000000b2', 2100, 2100, 'KES',
                      'MPESA_C2B', 'B2-RCPT', '77777777-0000-0000-0000-0000000000b3', now());
            COMMIT;
            """.formatted(TENANT_B));
        var teller = staffB("teller-b", "TELLER");
        var ops = staffB("ops-b", "OPERATIONS");
        String[] accounts = new String[3];
        String[] deposits = {"40000", "1234.56", "500"};
        for (int i = 0; i < 3; i++) {
            String number = JsonPath.read(mvc.perform(post("/api/v1/customers").with(staffB("officer-b", "LOAN_OFFICER"))
                    .contentType(MediaType.APPLICATION_JSON).content(customerJson("eod-c" + i, "6677889" + i, "1985-03-01", true)))
                .andExpect(ok).andReturn().getResponse().getContentAsString(), "$.customerNumber");
            mvc.perform(post("/api/v1/customers/" + number + "/verify").with(staffB("manager-b", "BRANCH_MANAGER"))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"National ID sighted\"}")).andExpect(ok);
            accounts[i] = JsonPath.read(mvc.perform(post("/api/v1/savings/accounts").with(teller).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"idempotencyKey\":\"eod-a" + i + "\",\"customerNumber\":\"" + number + "\",\"productCode\":\"SAVB\",\"branchCode\":\"NBO01\"}"))
                .andExpect(ok).andReturn().getResponse().getContentAsString(), "$.accountNumber");
            if (i == 0) {
                tillPost("/till-sessions", "{\"tillCode\":\"B-T1\"}", teller, ok);
            }
            cashTxn(cash("eod-d" + i, accounts[i], "DEPOSIT", deposits[i]), teller, ok);
        }

        // An open till blocks the day.
        String status = mvc.perform(get("/api/v1/end-of-day").with(ops)).andExpect(ok).andReturn().getResponse().getContentAsString();
        assertEquals("2026-11-28", JsonPath.read(status, "$.businessDate"));
        assertEquals(List.of("B-T1"), JsonPath.read(status, "$.openTills[*].tillCode"));
        assertEquals(Boolean.TRUE, eodRun(ops, status().isConflict()).contains("B-T1"));
        eodRun(teller, status().isForbidden());
        String session = JsonPath.read(mvc.perform(get("/api/v1/savings/till-sessions/mine").with(teller)).andExpect(ok)
            .andReturn().getResponse().getContentAsString(), "$.sessionId");
        assertEquals("CLOSED", JsonPath.read(tillPost("/till-sessions/" + session + "/close",
                "{\"countedAmount\":\"41734.56\"}", teller, ok), "$.status"));

        // 40,000 earns exactly 8.00 a day; 1,234.56 earns 0.246912 (posted 0.25, 0.24, 0.25); 500 is below the minimum.
        String day1 = eodRun(ops, ok);
        assertEquals("2026-11-29", JsonPath.read(day1, "$.newBusinessDate"));
        assertEquals(2, (Integer) JsonPath.read(day1, "$.accountsAccrued"));
        assertEquals("8.25", JsonPath.read(day1, "$.interestAccrued"));
        assertEquals(0, (Integer) JsonPath.read(day1, "$.accountsCredited"));
        // Loan LN-B00001: installment 1 (due 24 Nov, interest 300) catches up in full; installment 2
        // accrues 4/30 of 150 = 20.00; 4 days late is past the 3 grace days, so the flat 200 penalty.
        // LN-B00002 (2,060 due 29 Nov, 2,100 paid ahead) accrues 29/30 of 60 = 58.00.
        assertEquals(2, (Integer) JsonPath.read(day1, "$.loansProcessed"));
        assertEquals("378.00", JsonPath.read(day1, "$.loanInterestAccrued"));
        assertEquals("200.00", JsonPath.read(day1, "$.penaltiesCharged"));
        assertEquals(1, (Integer) JsonPath.read(day1, "$.loansInArrears"));
        assertEquals(0, (Integer) JsonPath.read(day1, "$.reconciliationDifferences"));
        String day2 = eodRun(ops, ok);
        assertEquals("8.24", JsonPath.read(day2, "$.interestAccrued"));
        assertEquals("5.00", JsonPath.read(day2, "$.loanInterestAccrued"));
        assertEquals("0.00", JsonPath.read(day2, "$.penaltiesCharged")); // the flat penalty is charged once
        // 29 Nov: LN-B00002 falls due; its credit pays the 2,060 and the loan closes holding 40.00.
        assertEquals("2060.00", JsonPath.read(day2, "$.creditApplied"));
        assertEquals(1, (Integer) JsonPath.read(day2, "$.loansClosed"));
        assertEquals(0, (Integer) JsonPath.read(day2, "$.reconciliationDifferences"));
        assertEquals(1, (Integer) JsonPath.read(day2, "$.loansProcessed"));
        assertEquals("CLOSED", owner().queryForObject("SELECT status FROM lending.loan WHERE loan_number = 'LN-B00002'", String.class));
        java.math.BigDecimal income = owner().queryForObject("""
                SELECT sum(CASE side WHEN 'C' THEN amount ELSE -amount END) FROM ledger.journal_line l
                  JOIN ledger.journal_entry e ON e.tenant_id = l.tenant_id AND e.entry_id = l.entry_id
                 WHERE l.tenant_id = ? AND l.account_code = '4000-INTEREST-INCOME'
                   AND e.idempotency_key LIKE 'loan:bbbbbbbb-0000-0000-0000-0000000000b2:%'
                """, java.math.BigDecimal.class, java.util.UUID.fromString(TENANT_B));
        assertEquals(0, new java.math.BigDecimal("60.00").compareTo(income)); // 58 accrued + 2 at repayment, once
        String monthEnd = eodRun(ops, ok);
        assertEquals("2026-12-01", JsonPath.read(monthEnd, "$.newBusinessDate"));
        assertEquals("8.25", JsonPath.read(monthEnd, "$.interestAccrued"));
        assertEquals(2, (Integer) JsonPath.read(monthEnd, "$.accountsCredited"));
        assertEquals("21.03", JsonPath.read(monthEnd, "$.interestCredited")); // 20.40 + 0.63
        assertEquals("3.71", JsonPath.read(monthEnd, "$.taxWithheld"));     // 3.60 + 0.11
        assertEquals(0, (Integer) JsonPath.read(monthEnd, "$.reconciliationDifferences"));

        String statement = mvc.perform(get("/api/v1/savings/accounts/" + accounts[0] + "/statement").param("from", "2026-11-01")
                .param("to", "2026-12-01").with(teller)).andExpect(ok).andReturn().getResponse().getContentAsString();
        assertEquals("40020.40", JsonPath.read(statement, "$.closingBalance"));
        assertEquals(Boolean.TRUE, statement.contains("Interest 28 Nov 2026 to 30 Nov 2026 (gross 24.00, tax 3.60)"), statement);
        Long tax = owner().queryForObject("""
                SELECT sum(CASE side WHEN 'C' THEN amount ELSE -amount END) FROM ledger.journal_line
                 WHERE tenant_id = ? AND account_code = '2960-WHT-PAYABLE'
                """, java.math.BigDecimal.class, java.util.UUID.fromString(TENANT_B)).multiply(java.math.BigDecimal.valueOf(100)).longValueExact();
        assertEquals(371L, tax);
        Long payable = owner().queryForObject("""
                SELECT sum(CASE side WHEN 'C' THEN amount ELSE -amount END) FROM ledger.journal_line
                 WHERE tenant_id = ? AND account_code = '2120-INTEREST-PAYABLE'
                """, java.math.BigDecimal.class, java.util.UUID.fromString(TENANT_B)).longValueExact();
        assertEquals(0L, payable); // everything accrued has been paid out
        var snapshot = owner().queryForMap("""
                SELECT days_past_due, bucket, arrears_amount, outstanding_principal FROM lending.arrears_snapshot
                 WHERE tenant_id = ? AND as_of = '2026-11-30'
                """, java.util.UUID.fromString(TENANT_B));
        assertEquals(6, ((Number) snapshot.get("days_past_due")).intValue());
        assertEquals("DPD_1_30", snapshot.get("bucket"));
        assertEquals(0, new java.math.BigDecimal("5300").compareTo((java.math.BigDecimal) snapshot.get("arrears_amount")));
        assertEquals(0, new java.math.BigDecimal("10000").compareTo((java.math.BigDecimal) snapshot.get("outstanding_principal")));
        java.math.BigDecimal receivable = owner().queryForObject("""
                SELECT sum(CASE side WHEN 'D' THEN amount ELSE -amount END) FROM ledger.journal_line
                 WHERE tenant_id = ? AND account_code = '1210-INTEREST-RECEIVABLE'
                """, java.math.BigDecimal.class, java.util.UUID.fromString(TENANT_B));
        assertEquals(0, new java.math.BigDecimal("330.00").compareTo(receivable)); // 320 + 5 + 5
        assertEquals(1, owner().queryForObject(
                "SELECT loans_processed FROM ledger.eod_run WHERE tenant_id = ? AND business_date = '2026-11-30'",
                Integer.class, java.util.UUID.fromString(TENANT_B)));
        String par = mvc.perform(get("/api/v1/reports/portfolio-at-risk").with(staffB("manager-b", "BRANCH_MANAGER")))
            .andExpect(ok).andReturn().getResponse().getContentAsString();
        assertEquals("2026-11-30", JsonPath.read(par, "$.asOf"));
        assertEquals(1, (Integer) JsonPath.read(par, "$.branches[0].loans"));
        assertEquals("10000.00", JsonPath.read(par, "$.branches[0].portfolio"));
        assertEquals("100.00", JsonPath.read(par, "$.branches[0].par1"));
        assertEquals("0.00", JsonPath.read(par, "$.branches[0].par30"));
        assertEquals(List.of("LN-B00001"), JsonPath.read(par, "$.loansInArrears[*].loanNumber"));
        assertEquals(List.of("40.00"), JsonPath.read(par, "$.closedWithCredit[?(@.loanNumber == 'LN-B00002')].creditBalance"));
        mvc.perform(get("/api/v1/reports/portfolio-at-risk").with(teller)).andExpect(status().isForbidden());
        String after = mvc.perform(get("/api/v1/end-of-day").with(ops)).andExpect(ok).andReturn().getResponse().getContentAsString();
        assertEquals("2026-11-30", JsonPath.read(after, "$.lastClosedDate"));
        // Write-off: LN-B00001 is 6 days late; this TEST tenant's policy allows write-off from 5 days.
        owner().execute("""
            INSERT INTO lending.write_off_policy (tenant_id, min_days_past_due) VALUES ('%1$s', 5);
            INSERT INTO ledger.gl_account (tenant_id, account_code, name, account_class, currency, requires_subledger) VALUES
              ('%1$s', '1120-MPESA-C2B-CLEARING', 'Paybill clearing', 'ASSET', 'KES', false),
              ('%1$s', '2900-SUSPENSE', 'Suspense', 'LIABILITY', 'KES', false);
            INSERT INTO payments.mpesa_paybill (tenant_id, short_code, branch_code, gl_clearing, gl_suspense, max_amount)
              VALUES ('%1$s', '600992', 'NBO01', '1120-MPESA-C2B-CLEARING', '2900-SUSPENSE', 150000);
            """.formatted(TENANT_B));
        var managerB = staffB("manager-b", "BRANCH_MANAGER");
        var supB = staffB("sup-b", "SUPERVISOR");
        String woBody = "{\"idempotencyKey\":\"wo-b1\",\"reason\":\"Member relocated; no contact for months\"}";
        String wo = mvc.perform(post("/api/v1/lending/loans/LN-B00001/write-off").with(managerB).contentType(MediaType.APPLICATION_JSON)
                .content(woBody)).andExpect(ok).andReturn().getResponse().getContentAsString();
        String woId = JsonPath.read(wo, "$.writeOffId");
        assertEquals("PENDING", JsonPath.read(wo, "$.status"));
        assertEquals("10000.00", JsonPath.read(wo, "$.principalOutstanding"));
        assertEquals("330.00", JsonPath.read(wo, "$.interestOutstanding"));
        assertEquals("200.00", JsonPath.read(wo, "$.penaltyOutstanding"));
        assertEquals(Boolean.TRUE, JsonPath.read(mvc.perform(post("/api/v1/lending/loans/LN-B00001/write-off").with(managerB)
                .contentType(MediaType.APPLICATION_JSON).content(woBody)).andExpect(ok).andReturn().getResponse()
                .getContentAsString(), "$.replayed"));
        mvc.perform(post("/api/v1/lending/loans/LN-B00001/write-off").with(teller).contentType(MediaType.APPLICATION_JSON)
                .content(woBody)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/lending/write-offs/" + woId + "/approve").with(managerB)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/lending/write-offs/" + woId + "/approve").with(staffB("manager-b", "SUPERVISOR")))
            .andExpect(status().isForbidden()); // the requester cannot approve, whatever role they hold
        assertEquals(List.of(woId), JsonPath.read(mvc.perform(get("/api/v1/lending/write-offs").param("status", "PENDING").with(supB))
                .andExpect(ok).andReturn().getResponse().getContentAsString(), "$[*].writeOffId"));
        String approved = mvc.perform(post("/api/v1/lending/write-offs/" + woId + "/approve").with(supB)).andExpect(ok)
            .andReturn().getResponse().getContentAsString();
        assertEquals("APPROVED", JsonPath.read(approved, "$.status"));
        assertEquals("10530.00", JsonPath.read(approved, "$.writtenOff"));
        assertEquals("WRITTEN_OFF", owner().queryForObject("SELECT status FROM lending.loan WHERE loan_number = 'LN-B00001'", String.class));
        for (String gl : List.of("1200-LOANS-PRINCIPAL", "1210-INTEREST-RECEIVABLE", "1230-PENALTY-RECEIVABLE")) {
            java.math.BigDecimal left = owner().queryForObject("""
                    SELECT sum(CASE side WHEN 'D' THEN amount ELSE -amount END) FROM ledger.journal_line l
                      JOIN ledger.journal_entry e ON e.tenant_id = l.tenant_id AND e.entry_id = l.entry_id
                     WHERE l.tenant_id = ? AND l.account_code = ? AND e.idempotency_key LIKE 'loan:bbbbbbbb-0000-0000-0000-0000000000b1:%'
                    """, java.math.BigDecimal.class, java.util.UUID.fromString(TENANT_B), gl);
            assertEquals(0, left.signum(), gl + " left " + left); // nothing remains receivable on the loan
        }

        // After write-off, M-Pesa receipts for the loan are recoveries, up to what was written off.
        String recoveryCheck = """
            {"tenantId":"%s","shortCode":"600992","billRef":"LN-B00001","amount":"%s","currency":"KES"}
            """;
        assertEquals("0", JsonPath.read(c2b("/validation", recoveryCheck.formatted(TENANT_B, "2000.00"), integrationToken(), ok),
                "$.resultCode"));
        assertEquals("C2B00012", JsonPath.read(c2b("/validation", recoveryCheck.formatted(TENANT_B, "10530.01"), integrationToken(),
                ok), "$.resultCode"));
        String recovered = c2b("", """
            {"tenantId":"%s","transId":"RCVB000001","shortCode":"600992","amount":"2000.00","currency":"KES","billRef":"LN-B00001",
             "msisdnMasked":"2547*****678","transTime":"2026-12-01T10:15:00+03:00"}
            """.formatted(TENANT_B), integrationToken(), ok);
        assertEquals("LOAN_RECOVERY", JsonPath.read(recovered, "$.outcome"));
        String afterRecovery = mvc.perform(get("/api/v1/lending/loans/LN-B00001/write-off").with(supB)).andExpect(ok)
            .andReturn().getResponse().getContentAsString();
        assertEquals("2000.00", JsonPath.read(afterRecovery, "$.recovered"));
        assertEquals(List.of("RCVB000001"), JsonPath.read(afterRecovery, "$.recoveries[*].providerRef"));
        // The general ledger of the closed days, as finance sees it.
        var finance = staffB("finance-b", "FINANCE");
        String tb = mvc.perform(get("/api/v1/accounting/trial-balance").param("asOf", "2026-12-01").with(finance)).andExpect(ok)
            .andReturn().getResponse().getContentAsString();
        assertEquals(List.of(Boolean.TRUE), JsonPath.read(tb, "$.totals[*].balanced"));
        assertEquals(List.of("3.71"), JsonPath.read(tb, "$.accounts[?(@.accountCode == '2960-WHT-PAYABLE')].balance"));
        assertEquals(List.of("10530.00"), JsonPath.read(tb, "$.accounts[?(@.accountCode == '5100-LOAN-WRITE-OFFS')].balance"));
        assertEquals(List.of("2000.00"), JsonPath.read(tb, "$.accounts[?(@.accountCode == '4900-WRITE-OFF-RECOVERIES')].balance"));
        assertEquals(List.of(), JsonPath.read(tb, "$.accounts[?(@.accountCode == '2120-INTEREST-PAYABLE')]")); // nets to zero
        String activity = mvc.perform(get("/api/v1/accounting/gl-accounts/2120-INTEREST-PAYABLE/activity")
                .param("from", "2026-11-28").param("to", "2026-11-30").with(finance)).andExpect(ok)
            .andReturn().getResponse().getContentAsString();
        assertEquals("0.00", JsonPath.read(activity, "$.openingBalance"));
        assertEquals("24.74", JsonPath.read(activity, "$.totalCredit"));  // 8.25 + 8.24 + 8.25 accrued
        assertEquals("24.74", JsonPath.read(activity, "$.totalDebit"));   // paid out at month end
        assertEquals("0.00", JsonPath.read(activity, "$.closingBalance"));
        assertEquals("8.25", JsonPath.read(activity, "$.lines[0].balance"));
        String firstEntry = JsonPath.read(activity, "$.lines[0].entryId");
        String entry = mvc.perform(get("/api/v1/accounting/journal-entries/" + firstEntry).with(finance)).andExpect(ok)
            .andReturn().getResponse().getContentAsString();
        assertEquals("EOD", JsonPath.read(entry, "$.source"));
        assertEquals(List.of("DEBIT", "CREDIT"), JsonPath.read(entry, "$.lines[*].side"));
        mvc.perform(get("/api/v1/accounting/trial-balance").with(teller)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/accounting/gl-accounts/2120-INTEREST-PAYABLE/activity").param("from", "2026-01-01")
                .param("to", "2027-12-31").with(finance)).andExpect(status().isBadRequest());
        // Tenant B's finance user cannot open tenant A's entries.
        mvc.perform(get("/api/v1/accounting/journal-entries/44444444-0000-0000-0000-000000000001").with(finance))
            .andExpect(status().isNotFound());
        // Financial statements: November income and expenses, and the balance sheet that balances.
        String is = mvc.perform(get("/api/v1/accounting/income-statement").param("from", "2026-11-01").param("to", "2026-11-30")
                .with(finance)).andExpect(ok).andReturn().getResponse().getContentAsString();
        // Income: loan interest 330 + 60 (LN-B00002), penalty 200. Expenses: savings interest 24.74.
        assertEquals(List.of("390.00"), JsonPath.read(is, "$.income[?(@.accountCode == '4000-INTEREST-INCOME')].amount"));
        assertEquals(List.of("200.00"), JsonPath.read(is, "$.income[?(@.accountCode == '4300-PENALTY-INCOME')].amount"));
        assertEquals(List.of("24.74"), JsonPath.read(is, "$.expenses[?(@.accountCode == '5300-SAVINGS-INTEREST')].amount"));
        assertEquals(List.of("565.26"), JsonPath.read(is, "$.totals[?(@.currency == 'KES')].netProfit"));
        String bs = mvc.perform(get("/api/v1/accounting/balance-sheet").with(finance)).andExpect(ok).andReturn().getResponse()
            .getContentAsString();
        assertEquals(List.of(Boolean.TRUE), JsonPath.read(bs, "$.totals[*].balanced"));
        mvc.perform(get("/api/v1/accounting/income-statement").param("from", "2025-01-01").param("to", "2026-11-30").with(finance))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/accounting/balance-sheet").with(teller)).andExpect(status().isForbidden());
        // Reconciliation: every closed day matched, and a posting without its business record is caught.
        String recorded = mvc.perform(get("/api/v1/accounting/reconciliation").param("businessDate", "2026-11-30").with(finance))
            .andExpect(ok).andReturn().getResponse().getContentAsString();
        assertEquals(Boolean.TRUE, JsonPath.read(recorded, "$.recorded"));
        assertEquals(0, (Integer) JsonPath.read(recorded, "$.differences"));
        assertEquals(List.of("10000.00"), JsonPath.read(recorded, "$.results[?(@.checkCode == 'LOAN_PRINCIPAL')].subledgerBalance"));
        assertEquals(List.of("0.00"), JsonPath.read(recorded, "$.results[?(@.checkCode == 'SAVINGS_INTEREST_PAYABLE')].ledgerBalance")); // all credited at month end
        assertEquals(0, (Integer) JsonPath.read(mvc.perform(get("/api/v1/accounting/reconciliation").with(finance)).andExpect(ok)
                .andReturn().getResponse().getContentAsString(), "$.differences"));
        owner().execute("""
            BEGIN;
            INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
              VALUES ('%1$s', '77777777-0000-0000-0000-0000000000bf', 'stray-principal', '2026-12-01', 'MANUAL', 'test', 'test');
            INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
              ('%1$s', '77777777-0000-0000-0000-0000000000bf', 1, '1200-LOANS-PRINCIPAL', 'D', 500, 'KES', 'NBO01'),
              ('%1$s', '77777777-0000-0000-0000-0000000000bf', 2, '1110-MPESA-B2C-CLEARING', 'C', 500, 'KES', 'NBO01');
            COMMIT;
            """.formatted(TENANT_B));
        String live = mvc.perform(get("/api/v1/accounting/reconciliation").with(finance)).andExpect(ok).andReturn().getResponse()
            .getContentAsString();
        assertEquals(1, (Integer) JsonPath.read(live, "$.differences"));
        assertEquals(List.of("500.00"), JsonPath.read(live, "$.results[?(@.checkCode == 'LOAN_PRINCIPAL')].difference"));
        // Manual journals and period close.
        var finance2 = staffB("finance2-b", "FINANCE");
        String mjTemplate = """
            {"idempotencyKey":"%s","businessDate":"%s","narrative":"Reclassify part of the recovery as write-off reversal",
             "lines":[{"accountCode":"%s","side":"DEBIT","amount":"100.00","branchCode":"NBO01"},
                      {"accountCode":"4900-WRITE-OFF-RECOVERIES","side":"CREDIT","amount":"%s","branchCode":"NBO01"}]}
            """;
        String control = mvc.perform(post("/api/v1/accounting/manual-journals").with(finance).contentType(MediaType.APPLICATION_JSON)
                .content(mjTemplate.formatted("mj-c", "2026-12-01", "1200-LOANS-PRINCIPAL", "100.00")))
            .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertEquals(Boolean.TRUE, control.contains("control account"), control);
        mvc.perform(post("/api/v1/accounting/manual-journals").with(finance).contentType(MediaType.APPLICATION_JSON)
                .content(mjTemplate.formatted("mj-u", "2026-12-01", "5100-LOAN-WRITE-OFFS", "90.00")))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/accounting/manual-journals").with(teller).contentType(MediaType.APPLICATION_JSON)
                .content(mjTemplate.formatted("mj-t", "2026-12-01", "5100-LOAN-WRITE-OFFS", "100.00")))
            .andExpect(status().isForbidden());
        String mj = mvc.perform(post("/api/v1/accounting/manual-journals").with(finance).contentType(MediaType.APPLICATION_JSON)
                .content(mjTemplate.formatted("mj-1", "2026-12-01", "5100-LOAN-WRITE-OFFS", "100.00")))
            .andExpect(ok).andReturn().getResponse().getContentAsString();
        String mjId = JsonPath.read(mj, "$.journalId");
        assertEquals("PENDING", JsonPath.read(mj, "$.status"));
        mvc.perform(post("/api/v1/accounting/manual-journals/" + mjId + "/post").with(finance)).andExpect(status().isForbidden());
        String posted = mvc.perform(post("/api/v1/accounting/manual-journals/" + mjId + "/post").with(finance2)).andExpect(ok)
            .andReturn().getResponse().getContentAsString();
        assertEquals("POSTED", JsonPath.read(posted, "$.status"));
        assertEquals("MANUAL", JsonPath.read(mvc.perform(get("/api/v1/accounting/journal-entries/" + JsonPath.read(posted, "$.entryId"))
                .with(finance)).andExpect(ok).andReturn().getResponse().getContentAsString(), "$.source"));

        // November: a journal dated in it is pending, so it cannot close until that is decided.
        String late = mvc.perform(post("/api/v1/accounting/manual-journals").with(finance).contentType(MediaType.APPLICATION_JSON)
                .content(mjTemplate.formatted("mj-2", "2026-11-30", "5100-LOAN-WRITE-OFFS", "100.00")))
            .andExpect(ok).andReturn().getResponse().getContentAsString();
        String periodsBefore = mvc.perform(get("/api/v1/accounting/periods").with(finance)).andExpect(ok).andReturn()
            .getResponse().getContentAsString();
        assertEquals(List.of(1), JsonPath.read(periodsBefore, "$[?(@.periodId == '2026-11')].pendingJournals"));
        assertEquals(List.of(0), JsonPath.read(periodsBefore, "$[?(@.periodId == '2026-11')].reconciliationDifferences"));
        String blocked = mvc.perform(post("/api/v1/accounting/periods/2026-11/close").with(finance)).andExpect(status().isBadRequest())
            .andReturn().getResponse().getContentAsString();
        assertEquals(Boolean.TRUE, blocked.contains("still pending"), blocked);
        mvc.perform(post("/api/v1/accounting/manual-journals/" + JsonPath.read(late, "$.journalId") + "/reject").with(finance2)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Belongs in December\"}")).andExpect(ok);
        mvc.perform(post("/api/v1/accounting/periods/2026-12/close").with(finance)).andExpect(status().isBadRequest()); // not over yet
        mvc.perform(post("/api/v1/accounting/periods/2026-11/close").with(teller)).andExpect(status().isForbidden());
        assertEquals("CLOSED", JsonPath.read(mvc.perform(post("/api/v1/accounting/periods/2026-11/close").with(finance)).andExpect(ok)
                .andReturn().getResponse().getContentAsString(), "$.status"));
        String intoClosed = mvc.perform(post("/api/v1/accounting/manual-journals").with(finance).contentType(MediaType.APPLICATION_JSON)
                .content(mjTemplate.formatted("mj-3", "2026-11-29", "5100-LOAN-WRITE-OFFS", "100.00")))
            .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertEquals(Boolean.TRUE, intoClosed.contains("not in an open accounting period"), intoClosed);
        assertEquals("2027-01", JsonPath.read(mvc.perform(post("/api/v1/accounting/periods/next").with(finance)).andExpect(ok)
                .andReturn().getResponse().getContentAsString(), "$.periodId"));
    }

    /** Staff actions land in a tamper-evident audit trail, refused ones included, without bodies or identifying query values. */
    @Test
    void staffActionsAreAudited() throws Exception {
        var ok = status().isOk();
        var officer = staff("audit-officer", "LOAN_OFFICER");
        var sup = staff("audit-sup", "SUPERVISOR");
        String number = JsonPath.read(mvc.perform(post("/api/v1/customers").with(officer).contentType(MediaType.APPLICATION_JSON)
                .content(customerJson("audit-c1", "77665544", "1990-01-01", true))).andExpect(ok).andReturn().getResponse()
                .getContentAsString(), "$.customerNumber");
        mvc.perform(get("/api/v1/customers/" + number).with(officer)).andExpect(ok);
        mvc.perform(get("/api/v1/customers").param("q", "77665544").with(officer)).andExpect(ok);
        mvc.perform(post("/api/v1/lending/write-offs/" + java.util.UUID.randomUUID() + "/approve").with(officer))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/end-of-day").with(officer)); // routine read: not audited

        String rows = mvc.perform(get("/api/v1/audit/staff-actions").param("actor", "user:audit-officer").with(sup))
            .andExpect(ok).andReturn().getResponse().getContentAsString();
        // Four audited requests, newest first: refused approval, search, customer view, registration.
        assertEquals(List.of("POST", "GET", "GET", "POST"), JsonPath.read(rows, "$[*].method"));
        assertEquals(List.of(403), JsonPath.read(rows, "$[?(@.route == '/api/v1/lending/write-offs/{writeOffId}/approve')].status"));
        assertEquals(List.of("/api/v1/customers/" + number),
                JsonPath.read(rows, "$[?(@.route == '/api/v1/customers/{customerNumber}')].path"));
        assertEquals(List.of("q=***"), JsonPath.read(rows, "$[?(@.route == '/api/v1/customers' && @.method == 'GET')].query"));
        assertEquals(Boolean.FALSE, rows.contains("77665544"), "no identity number in the audit trail");
        assertEquals(List.of("LOAN_OFFICER"), JsonPath.read(rows, "$[?(@.method == 'POST' && @.status == 200)].roles"));
        assertEquals(List.of(), JsonPath.read(rows, "$[?(@.route == '/api/v1/end-of-day')]"));
        assertEquals(1, ((List<?>) JsonPath.read(mvc.perform(get("/api/v1/audit/staff-actions").param("actor", "user:audit-officer")
                .param("refusedOnly", "true").with(sup)).andExpect(ok).andReturn().getResponse().getContentAsString(), "$")).size());

        String verify = mvc.perform(get("/api/v1/audit/staff-actions/verify").with(sup)).andExpect(ok).andReturn().getResponse()
            .getContentAsString();
        assertEquals(Boolean.TRUE, JsonPath.read(verify, "$.intact"));
        mvc.perform(get("/api/v1/audit/staff-actions").with(officer)).andExpect(status().isForbidden());
        // Tenant B's supervisor sees none of tenant A's trail.
        assertEquals(List.of(), JsonPath.read(mvc.perform(get("/api/v1/audit/staff-actions").param("actor", "user:audit-officer")
                .with(staffB("audit-sup-b", "SUPERVISOR"))).andExpect(ok).andReturn().getResponse().getContentAsString(), "$"));
    }
}
