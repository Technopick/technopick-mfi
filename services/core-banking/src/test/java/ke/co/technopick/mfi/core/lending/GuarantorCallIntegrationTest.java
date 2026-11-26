package ke.co.technopick.mfi.core.lending;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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
import org.testcontainers.utility.MountableFile;

/**
 * Calling on guarantors (increment 44, ADR-0044) on the small TEST-ONLY book. Member 3's 12,000.00
 * loan (disbursed 1 Sep, 58 days past due after the 28 Nov close) is guaranteed 4,000.00 by member 2
 * and 2,000.00 by member 6. A call of 3,000.00 takes 2,000.00 and 1,000.00 from their savings into
 * the loan; the next close lowers the remaining locks to each pledge's share of the principal still
 * outstanding; a second call takes what is left and the guarantees are CALLED.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class GuarantorCallIntegrationTest {

    static final String T = "00000000-0000-0000-0000-0000000000c3";
    static final String L = "/api/v1/lending";

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
        r.add("mfi.jobs.enabled", () -> "false");
    }

    @TestConfiguration
    static class Config {
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

    static JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
    }

    static JwtRequestPostProcessor as(String subject, String role) {
        return jwt().jwt(j -> j.subject(subject).claim("tenant_id", T)).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    static final JwtRequestPostProcessor FIN = as("fin-1", "FINANCE");
    static final JwtRequestPostProcessor OFFICER = as("lo-1", "LOAN_OFFICER");
    static final JwtRequestPostProcessor MANAGER = as("bm-1", "BRANCH_MANAGER");
    static final JwtRequestPostProcessor OPS = as("ops-1", "OPERATIONS");
    static final JwtRequestPostProcessor TELLER = as("teller-1", "TELLER");

    void seed() throws Exception {
        String sql = new String(GuarantorCallIntegrationTest.class.getResourceAsStream("/perf/perf-seed-TEST-ONLY.sql").readAllBytes(),
                StandardCharsets.UTF_8).replace("{T}", T).replace("{NS}", "10").replace("{NL}", "10");
        Path file = Files.createTempFile("calls-seed", ".sql");
        Files.writeString(file, sql);
        PG.copyFileToContainer(MountableFile.forHostPath(file), "/tmp/seed.sql");
        var r = PG.execInContainer("psql", "-v", "ON_ERROR_STOP=1", "-q", "-U", PG.getUsername(), "-d", PG.getDatabaseName(),
                "-f", "/tmp/seed.sql");
        assertEquals(0, r.getExitCode(), r.getStderr());
        // TEST-ONLY: the clearing account, and member 3's seed loan linked to an application guaranteed by members 2 and 6.
        owner().execute("""
                SELECT set_config('app.tenant_id', '%1$s', false);
                INSERT INTO ledger.gl_account (tenant_id, account_code, name, account_class, currency)
                VALUES ('%1$s', '1190-GUARANTOR-RECOVERIES', 'Guarantor recoveries clearing', 'ASSET', 'KES');
                INSERT INTO lending.loan_application (tenant_id, application_id, idempotency_key, customer_ref, customer_id,
                    product_version_id, branch_code, currency, principal, term_months, first_due_date, purpose, submitted_by)
                SELECT l.tenant_id, 'f5000000-0000-0000-0000-000000000003', 'calls-app-3', l.customer_ref, l.customer_id,
                       l.product_version_id, l.branch_code, l.currency, l.principal, l.term_months, date '2026-10-01', 'Seed loan (TEST ONLY)',
                       l.created_by
                  FROM lending.loan l JOIN customer.customer c ON c.tenant_id = l.tenant_id AND c.customer_id = l.customer_id
                 WHERE l.tenant_id = '%1$s' AND c.last_name = 'Load3';
                INSERT INTO savings.account_hold (tenant_id, hold_id, account_id, kind, amount, currency, reference, idempotency_key)
                SELECT a.tenant_id, x.id, a.account_id, 'EARMARK', x.amount, 'KES', 'guarantee', 'calls-hold-' || x.last
                  FROM (VALUES ('f6000000-0000-0000-0000-000000000002'::uuid, 'Load2', 4000),
                               ('f6000000-0000-0000-0000-000000000006'::uuid, 'Load6', 2000)) x(id, last, amount)
                  JOIN customer.customer c ON c.tenant_id = '%1$s' AND c.last_name = x.last
                  JOIN savings.deposit_account a ON a.tenant_id = c.tenant_id AND a.customer_id = c.customer_id;
                INSERT INTO lending.loan_guarantee (tenant_id, guarantee_id, idempotency_key, application_id, guarantor_customer_id,
                    savings_account_id, amount, consent_reference, hold_id, pledged_by)
                SELECT h.tenant_id, h.hold_id, 'calls-g-' || h.hold_id, 'f5000000-0000-0000-0000-000000000003', a.customer_id,
                       a.account_id, h.amount, 'Form G (TEST ONLY)', h.hold_id, 'perf:officer'
                  FROM savings.account_hold h JOIN savings.deposit_account a ON a.tenant_id = h.tenant_id AND a.account_id = h.account_id
                 WHERE h.hold_id IN ('f6000000-0000-0000-0000-000000000002', 'f6000000-0000-0000-0000-000000000006');
                UPDATE lending.loan_application a SET status = 'APPROVED', decided_by = l.approved_by, loan_id = l.loan_id
                  FROM lending.loan l JOIN customer.customer c ON c.tenant_id = l.tenant_id AND c.customer_id = l.customer_id
                 WHERE a.application_id = 'f5000000-0000-0000-0000-000000000003' AND l.tenant_id = '%1$s' AND c.last_name = 'Load3';
                """.formatted(T));
    }

    Map<String, Object> guarantee(String id) {
        return owner().queryForMap("SELECT status, locked_amount, recovered FROM lending.loan_guarantee WHERE guarantee_id = ?::uuid", id);
    }

    BigDecimal balance(String lastName) {
        return owner().queryForObject("""
                SELECT b.ledger_balance FROM savings.account_balance b
                  JOIN savings.deposit_account a ON a.tenant_id = b.tenant_id AND a.account_id = b.account_id
                  JOIN customer.customer c ON c.tenant_id = a.tenant_id AND c.customer_id = a.customer_id
                 WHERE c.tenant_id = ?::uuid AND c.last_name = ?
                """, BigDecimal.class, T, lastName);
    }

    String call(String loan, String key, String amount, int expected) throws Exception {
        return mvc.perform(post(L + "/loans/" + loan + "/guarantor-calls").with(OFFICER).contentType(MediaType.APPLICATION_JSON)
                .content(amount == null ? "{\"idempotencyKey\":\"" + key + "\",\"reason\":\"Borrower has stopped paying\"}"
                        : "{\"idempotencyKey\":\"" + key + "\",\"amount\":\"" + amount + "\",\"reason\":\"Borrower has stopped paying\"}"))
            .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }

    @Test
    void guarantorsAreCalledAndLocksFollowRepayment() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        seed();
        String loan = owner().queryForObject("""
                SELECT l.loan_number FROM lending.loan l JOIN customer.customer c ON c.tenant_id = l.tenant_id AND c.customer_id = l.customer_id
                 WHERE l.tenant_id = ?::uuid AND c.last_name = 'Load3'
                """, String.class, T);
        String g2 = "f6000000-0000-0000-0000-000000000002";
        String g6 = "f6000000-0000-0000-0000-000000000006";

        // Settings: finance only; the clearing account must be an asset.
        call(loan, "c0", null, 409);
        String settings = "{\"recoveryClearingAccount\":\"%s\",\"minDaysPastDue\":30,\"releaseAsRepaid\":true}";
        mvc.perform(put(L + "/guarantee-settings").with(OFFICER).contentType(MediaType.APPLICATION_JSON)
                .content(settings.formatted("1190-GUARANTOR-RECOVERIES"))).andExpect(status().isForbidden());
        mvc.perform(put(L + "/guarantee-settings").with(FIN).contentType(MediaType.APPLICATION_JSON)
                .content(settings.formatted("2400-LOAN-OVERPAYMENTS")))
            .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("GUARANTEE_SETTINGS_INVALID"));
        mvc.perform(put(L + "/guarantee-settings").with(FIN).contentType(MediaType.APPLICATION_JSON)
                .content(settings.formatted("1190-GUARANTOR-RECOVERIES"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.minDaysPastDue").value(30));

        // Not yet past due as far as the last close knows.
        call(loan, "c1", null, 409);
        mvc.perform(post("/api/v1/end-of-day/run").with(OPS)).andExpect(status().isOk());
        String position = mvc.perform(get(L + "/loans/" + loan + "/guarantees").with(OFFICER)).andExpect(status().isOk())
            .andExpect(jsonPath("$.daysPastDue").value(58)).andExpect(jsonPath("$.locked").value("6000.00"))
            .andExpect(jsonPath("$.callable").value("6000.00")).andExpect(jsonPath("$.callAllowed").value(true))
            .andReturn().getResponse().getContentAsString();
        assertEquals(2, (int) JsonPath.read(position, "$.guarantees.length()"));

        // A call above what is locked is refused; one is rejected (with a reason); one of 3,000 is approved.
        call(loan, "c2", "6000.01", 400);
        String first = call(loan, "c3", "1500", 200);
        String firstId = JsonPath.read(first, "$.callId");
        call(loan, "c4", "1000", 409);
        mvc.perform(post(L + "/guarantor-calls/" + firstId + "/reject").with(MANAGER).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post(L + "/guarantor-calls/" + firstId + "/reject").with(MANAGER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Call the full arrears instead\"}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REJECTED"));
        String second = call(loan, "c5", "3000", 200);
        String secondId = JsonPath.read(second, "$.callId");
        java.util.List<String> share2 = JsonPath.read(second, "$.lines[?(@.guaranteeId == '" + g2 + "')].amount");
        assertEquals(java.util.List.of("2000.00"), share2);
        BigDecimal saved2 = balance("Load2");
        BigDecimal saved6 = balance("Load6");
        mvc.perform(post(L + "/guarantor-calls/" + secondId + "/approve").with(OFFICER)).andExpect(status().isForbidden());
        mvc.perform(post(L + "/guarantor-calls/" + secondId + "/approve").with(MANAGER)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED")).andExpect(jsonPath("$.lines[0].applied").value(true));
        assertEquals(0, saved2.subtract(new BigDecimal("2000")).compareTo(balance("Load2")));
        assertEquals(0, saved6.subtract(new BigDecimal("1000")).compareTo(balance("Load6")));
        assertEquals("PLEDGED", guarantee(g2).get("status"));
        assertEquals(0, new BigDecimal("2000").compareTo((BigDecimal) guarantee(g2).get("locked_amount")));
        assertEquals(0, new BigDecimal("2000").compareTo((BigDecimal) guarantee(g2).get("recovered")));
        assertEquals(0, new BigDecimal("3000").compareTo(owner().queryForObject(
                "SELECT sum(amount) FROM lending.repayment WHERE provider_ref LIKE 'GUARANTOR:' || ? || ':%'", BigDecimal.class, secondId)));
        assertEquals(0, BigDecimal.ZERO.compareTo(owner().queryForObject("""
                SELECT COALESCE(sum(CASE side WHEN 'D' THEN amount ELSE -amount END), 0) FROM ledger.journal_line
                 WHERE tenant_id = ?::uuid AND account_code = '1190-GUARANTOR-RECOVERIES'
                """, BigDecimal.class, T)), "the clearing account nets to zero");
        mvc.perform(get("/api/v1/accounting/reconciliation").with(FIN)).andExpect(status().isOk())
            .andExpect(jsonPath("$.differences").value(0));
        mvc.perform(post("/api/v1/accounting/manual-journals").with(FIN).contentType(MediaType.APPLICATION_JSON).content("""
                {"idempotencyKey":"mj-clearing","businessDate":"2026-11-29","narrative":"Clearing by hand",
                 "lines":[{"accountCode":"1190-GUARANTOR-RECOVERIES","side":"DEBIT","amount":"100.00","branchCode":"NBO01"},
                          {"accountCode":"1001-TILL-1","side":"CREDIT","amount":"100.00","branchCode":"NBO01"}]}
                """)).andExpect(status().isBadRequest());

        // The next close lowers the remaining locks to each pledge's share of the principal still outstanding.
        mvc.perform(post("/api/v1/end-of-day/run").with(OPS)).andExpect(status().isOk());
        BigDecimal outstanding = owner().queryForObject("""
                SELECT lending.loan_principal_outstanding(l.tenant_id, l.loan_id) FROM lending.loan l WHERE l.loan_number = ?
                """, BigDecimal.class, loan);
        BigDecimal expected2 = new BigDecimal("4000").multiply(outstanding).divide(new BigDecimal("12000"), 2, RoundingMode.HALF_UP)
            .subtract(new BigDecimal("2000")).max(BigDecimal.ZERO).min(new BigDecimal("2000"));
        assertEquals(0, expected2.compareTo((BigDecimal) guarantee(g2).get("locked_amount")),
                "lock " + guarantee(g2) + " with " + outstanding + " outstanding");
        assertEquals(1, owner().queryForObject("""
                SELECT count(*) FROM savings.account_hold h JOIN lending.loan_guarantee g ON g.current_hold_id = h.hold_id
                 WHERE g.guarantee_id = ?::uuid AND h.status = 'ACTIVE' AND h.amount = g.locked_amount
                """, Integer.class, g2), "one active hold carries the lock");

        // A second call takes what is left; both guarantees are used up.
        String rest = call(loan, "c6", null, 200);
        String restId = JsonPath.read(rest, "$.callId");
        mvc.perform(post(L + "/guarantor-calls/" + restId + "/approve").with(MANAGER)).andExpect(status().isOk());
        assertEquals("CALLED", guarantee(g2).get("status"));
        assertEquals("CALLED", guarantee(g6).get("status"));
        mvc.perform(get(L + "/loans/" + loan + "/guarantees").with(OFFICER)).andExpect(status().isOk())
            .andExpect(jsonPath("$.locked").value("0.00")).andExpect(jsonPath("$.callAllowed").value(false));
        call(loan, "c7", null, 409);
        mvc.perform(get(L + "/guarantor-calls?status=APPROVED").with(FIN)).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].loanNumber").value(loan));
        mvc.perform(get("/api/v1/accounting/reconciliation").with(FIN)).andExpect(status().isOk())
            .andExpect(jsonPath("$.differences").value(0));
        mvc.perform(get("/api/v1/member-exits/members/" + owner().queryForObject(
                "SELECT customer_number FROM customer.customer WHERE tenant_id = ?::uuid AND last_name = 'Load2'", String.class, T)).with(TELLER))
            .andExpect(status().isOk()).andExpect(jsonPath("$.blockers").value(org.hamcrest.Matchers.not(
                    org.hamcrest.Matchers.hasItem(containsString("guarantee")))));
    }
}
