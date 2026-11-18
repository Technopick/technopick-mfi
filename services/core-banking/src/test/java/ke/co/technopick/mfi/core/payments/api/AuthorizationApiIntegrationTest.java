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
              ('%1$s', '4200-FEE-WITHDRAWAL', 'Withdrawal fees', 'INCOME', 'KES', false);
            INSERT INTO ledger.accounting_period (tenant_id, period_id, start_date, end_date) VALUES ('%1$s', '2026-11', '2026-11-01', '2026-11-30');
            INSERT INTO tenancy.business_date (tenant_id, current_date_) VALUES ('%1$s', '2026-11-18');
            INSERT INTO savings.deposit_product (tenant_id, product_code, name, currency, gl_control_account)
              VALUES ('%1$s', 'SAV01', 'Savings', 'KES', '2110-SAVINGS-CONTROL');
            INSERT INTO savings.deposit_account (tenant_id, account_id, account_number, customer_ref, product_code, currency,
                branch_code, gl_control_account, opened_on) VALUES
              ('%1$s', 'aaaaaaaa-0000-0000-0000-000000000001', '0100000001', 'C1', 'SAV01', 'KES', 'NBO01', '2110-SAVINGS-CONTROL', '2026-11-01'),
              ('%1$s', 'aaaaaaaa-0000-0000-0000-000000000002', '0100000002', 'C2', 'SAV01', 'KES', 'NBO01', '2110-SAVINGS-CONTROL', '2026-11-01');
            INSERT INTO payments.channel_config (tenant_id, channel_code, currency, per_txn_max, daily_max,
                gl_settlement_account, gl_fee_income) VALUES
              ('%1$s', 'AGENT', 'KES', 70000, 150000, '2300-AGENT-FLOAT-PAYABLE', '4200-FEE-WITHDRAWAL');
            BEGIN;
            INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
              VALUES ('%1$s', '44444444-0000-0000-0000-000000000001', 'seed-deposits', '2026-11-18', 'TELLER', 'seed', 'test');
            INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code, subledger_account_id) VALUES
              ('%1$s', '44444444-0000-0000-0000-000000000001', 1, '1000-VAULT-CASH', 'D', 11000, 'KES', 'NBO01', NULL),
              ('%1$s', '44444444-0000-0000-0000-000000000001', 2, '2110-SAVINGS-CONTROL', 'C', 10000, 'KES', 'NBO01', 'aaaaaaaa-0000-0000-0000-000000000001'),
              ('%1$s', '44444444-0000-0000-0000-000000000001', 3, '2110-SAVINGS-CONTROL', 'C', 1000, 'KES', 'NBO01', 'aaaaaaaa-0000-0000-0000-000000000002');
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
}
