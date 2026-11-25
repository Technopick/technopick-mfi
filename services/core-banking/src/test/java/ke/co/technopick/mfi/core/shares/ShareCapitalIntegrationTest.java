package ke.co.technopick.mfi.core.shares;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
 * Member share capital (increment 40, ADR-0040) on the small TEST-ONLY book. Par 100.00, minimum
 * holding 1,000.00. Member 1 (8,919.00 saved) buys 5,000.00 and transfers 2,000.00 to a member with
 * no savings account; member 2 buys 1,000.00. A 10% dividend with 5% tax at the 28 Nov close pays
 * 285.00 and 95.00 into savings and holds 190.00 as dividends payable.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class ShareCapitalIntegrationTest {

    static final String T = "00000000-0000-0000-0000-0000000000c3";
    static final String P = "/api/v1/shares";
    static final String NO_ACCOUNT = "CU26-0009901";

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

    static final JwtRequestPostProcessor FIN1 = as("fin-1", "FINANCE");
    static final JwtRequestPostProcessor SUP = as("sup-1", "SUPERVISOR");
    static final JwtRequestPostProcessor TELLER = as("teller-1", "TELLER");
    static final JwtRequestPostProcessor OFFICER = as("lo-1", "LOAN_OFFICER");
    static final JwtRequestPostProcessor MANAGER = as("bm-1", "BRANCH_MANAGER");

    void seed() throws Exception {
        String sql = new String(ShareCapitalIntegrationTest.class.getResourceAsStream("/perf/perf-seed-TEST-ONLY.sql").readAllBytes(),
                StandardCharsets.UTF_8).replace("{T}", T).replace("{NS}", "10").replace("{NL}", "10");
        Path file = Files.createTempFile("shares-seed", ".sql");
        Files.writeString(file, sql);
        PG.copyFileToContainer(MountableFile.forHostPath(file), "/tmp/seed.sql");
        var r = PG.execInContainer("psql", "-v", "ON_ERROR_STOP=1", "-q", "-U", PG.getUsername(), "-d", PG.getDatabaseName(),
                "-f", "/tmp/seed.sql");
        assertEquals(0, r.getExitCode(), r.getStderr());
        owner().update("""
                INSERT INTO ledger.gl_account (tenant_id, account_code, name, account_class, currency) VALUES
                  (?::uuid, '3100-SHARE-CAPITAL', 'Member share capital', 'EQUITY', 'KES'),
                  (?::uuid, '3300-RETAINED-EARNINGS', 'Retained earnings', 'EQUITY', 'KES'),
                  (?::uuid, '2400-DIVIDENDS-PAYABLE', 'Dividends payable', 'LIABILITY', 'KES'),
                  (?::uuid, '2410-WHT-DIVIDENDS', 'Withholding tax on dividends', 'LIABILITY', 'KES')
                """, T, T, T, T);
        // TEST-ONLY: an active member with no savings account, whose dividend is held as payable.
        owner().update("""
                INSERT INTO customer.customer (tenant_id, customer_id, customer_number, idempotency_key, first_name, last_name,
                    date_of_birth, id_type, id_number, msisdn, branch_code, consent_version, consent_at, created_by)
                VALUES (?::uuid, gen_random_uuid(), ?, 'shares-no-account', 'Member', 'NoAccount', date '1980-01-01', 'NATIONAL_ID',
                        '29999901', '254799999901', 'NBO01', 'DPA-2026-01', now(), 'perf:officer')
                """, T, NO_ACCOUNT);
        owner().update("""
                UPDATE customer.customer SET status = 'ACTIVE', verified_by = 'perf:manager',
                       verification_note = 'Load-test member, TEST ONLY' WHERE tenant_id = ?::uuid AND customer_number = ?
                """, T, NO_ACCOUNT);
    }

    Map<String, Object> member(String lastName) {
        return owner().queryForMap("""
                SELECT c.customer_number, a.account_number FROM customer.customer c
                  JOIN savings.deposit_account a ON a.tenant_id = c.tenant_id AND a.customer_id = c.customer_id
                 WHERE c.tenant_id = ?::uuid AND c.last_name = ?
                """, T, lastName);
    }

    String body(String json, Object... args) {
        return json.formatted(args);
    }

    @Test
    void membersBuyTransferAndEarnDividends() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        seed();
        Map<String, Object> m1 = member("Load1");
        Map<String, Object> m2 = member("Load2");
        Map<String, Object> m10 = member("Load10");
        String c1 = (String) m1.get("customer_number");
        String a1 = (String) m1.get("account_number");
        String c2 = (String) m2.get("customer_number");
        String a2 = (String) m2.get("account_number");

        // Nothing works before finance sets the shares up; only finance can; the accounts must fit.
        mvc.perform(get(P + "/settings").with(FIN1)).andExpect(status().isNotFound());
        mvc.perform(post(P + "/purchases").with(TELLER).contentType(MediaType.APPLICATION_JSON)
                .content(body("{\"idempotencyKey\":\"p0\",\"customerNumber\":\"%s\",\"accountNumber\":\"%s\",\"amount\":\"1000\"}", c1, a1)))
            .andExpect(status().isConflict());
        String settings = """
            {"parValue":"100","minimumHolding":"1000","shareCapitalAccount":"%s","dividendSourceAccount":"3300-RETAINED-EARNINGS",
             "dividendsPayableAccount":"2400-DIVIDENDS-PAYABLE","dividendTaxAccount":"2410-WHT-DIVIDENDS","dividendTaxRate":"5"}
            """;
        mvc.perform(put(P + "/settings").with(TELLER).contentType(MediaType.APPLICATION_JSON)
                .content(settings.formatted("3100-SHARE-CAPITAL"))).andExpect(status().isForbidden());
        mvc.perform(put(P + "/settings").with(FIN1).contentType(MediaType.APPLICATION_JSON)
                .content(settings.formatted("2400-DIVIDENDS-PAYABLE")))
            .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("SHARE_SETTINGS_INVALID"));
        mvc.perform(put(P + "/settings").with(FIN1).contentType(MediaType.APPLICATION_JSON)
                .content(settings.formatted("3100-SHARE-CAPITAL").replace("\"1000\"", "\"1050\""))).andExpect(status().isBadRequest());
        mvc.perform(put(P + "/settings").with(FIN1).contentType(MediaType.APPLICATION_JSON)
                .content(settings.formatted("3100-SHARE-CAPITAL")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.parValue").value("100.00"))
            .andExpect(jsonPath("$.dividendTaxRate").value("5.00")).andExpect(jsonPath("$.sharesIssued").value(false));

        // Purchases: whole shares, the member's own account, enough money, at least the minimum.
        String purchase = "{\"idempotencyKey\":\"%s\",\"customerNumber\":\"%s\",\"accountNumber\":\"%s\",\"amount\":\"%s\"}";
        mvc.perform(post(P + "/purchases").with(FIN1).contentType(MediaType.APPLICATION_JSON)
                .content(body(purchase, "p1", c1, a1, "5000"))).andExpect(status().isForbidden());
        mvc.perform(post(P + "/purchases").with(TELLER).contentType(MediaType.APPLICATION_JSON)
                .content(body(purchase, "p1", c1, a1, "5050"))).andExpect(status().isBadRequest());
        mvc.perform(post(P + "/purchases").with(TELLER).contentType(MediaType.APPLICATION_JSON)
                .content(body(purchase, "p1", c1, a2, "5000"))).andExpect(status().isBadRequest());
        mvc.perform(post(P + "/purchases").with(TELLER).contentType(MediaType.APPLICATION_JSON)
                .content(body(purchase, "p1", c1, a1, "500"))).andExpect(status().isConflict());
        mvc.perform(post(P + "/purchases").with(TELLER).contentType(MediaType.APPLICATION_JSON)
                .content(body(purchase, "p10", m10.get("customer_number"), m10.get("account_number"), "1000")))
            .andExpect(status().isConflict());
        mvc.perform(post(P + "/purchases").with(TELLER).contentType(MediaType.APPLICATION_JSON)
                .content(body(purchase, "p1", c1, a1, "5000")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.holding").value("5000.00")).andExpect(jsonPath("$.shares").value(50))
            .andExpect(jsonPath("$.transactions[0].kind").value("PURCHASE"))
            .andExpect(jsonPath("$.transactions[0].accountNumber").value(a1));
        // A replay does not buy twice.
        mvc.perform(post(P + "/purchases").with(TELLER).contentType(MediaType.APPLICATION_JSON)
                .content(body(purchase, "p1", c1, a1, "5000")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.holding").value("5000.00"));
        mvc.perform(post(P + "/purchases").with(OFFICER).contentType(MediaType.APPLICATION_JSON)
                .content(body(purchase, "p2", c2, a2, "1000"))).andExpect(status().isOk());
        assertEquals(0, new BigDecimal("3919.00").compareTo(balance(a1)));
        // The par value and capital account are fixed once shares exist.
        mvc.perform(put(P + "/settings").with(FIN1).contentType(MediaType.APPLICATION_JSON)
                .content(settings.formatted("3100-SHARE-CAPITAL").replace("\"parValue\":\"100\"", "\"parValue\":\"50\"")))
            .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("SHARE_SETTINGS_LOCKED"));

        // Transfers: asked by one person, decided by another; the seller keeps the minimum.
        String transfer = "{\"idempotencyKey\":\"%s\",\"fromCustomerNumber\":\"%s\",\"toCustomerNumber\":\"%s\",\"amount\":\"%s\","
                + "\"reason\":\"Gift to a family member\"}";
        mvc.perform(post(P + "/transfers").with(OFFICER).contentType(MediaType.APPLICATION_JSON)
                .content(body(transfer, "t0", c2, c1, "100"))).andExpect(status().isConflict());
        mvc.perform(post(P + "/transfers").with(OFFICER).contentType(MediaType.APPLICATION_JSON)
                .content(body(transfer, "t0", c1, c1, "100"))).andExpect(status().isBadRequest());
        String rejected = mvc.perform(post(P + "/transfers").with(OFFICER).contentType(MediaType.APPLICATION_JSON)
                .content(body(transfer, "t1", c1, NO_ACCOUNT, "1000")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REQUESTED")).andExpect(jsonPath("$.shares").value(10))
            .andReturn().getResponse().getContentAsString();
        String rejectedId = JsonPath.read(rejected, "$.transferId");
        mvc.perform(post(P + "/transfers/" + rejectedId + "/reject").with(MANAGER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Wrong amount; the form says 2,000\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        String asked = mvc.perform(post(P + "/transfers").with(MANAGER).contentType(MediaType.APPLICATION_JSON)
                .content(body(transfer, "t2", c1, NO_ACCOUNT, "2000")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String transferId = JsonPath.read(asked, "$.transferId");
        mvc.perform(post(P + "/transfers/" + transferId + "/approve").with(MANAGER).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
        mvc.perform(post(P + "/transfers/" + transferId + "/approve").with(OFFICER).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
        mvc.perform(post(P + "/transfers/" + transferId + "/approve").with(SUP).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.decidedBy").value("user:sup-1")).andExpect(jsonPath("$.sellerHolding").value("3000.00"));
        mvc.perform(post(P + "/transfers/" + transferId + "/approve").with(SUP).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isConflict());
        mvc.perform(get(P + "/members/" + NO_ACCOUNT).with(TELLER)).andExpect(status().isOk())
            .andExpect(jsonPath("$.holding").value("2000.00")).andExpect(jsonPath("$.transactions[0].kind").value("TRANSFER_IN"));
        mvc.perform(get(P + "/register").with(FIN1)).andExpect(status().isOk())
            .andExpect(jsonPath("$.members").value(3)).andExpect(jsonPath("$.totalHolding").value("6000.00"))
            .andExpect(jsonPath("$.totalShares").value(60));

        // The register and the ledger agree; nothing else posts to share capital.
        mvc.perform(get("/api/v1/accounting/reconciliation").with(FIN1)).andExpect(status().isOk())
            .andExpect(jsonPath("$.differences").value(0))
            .andExpect(jsonPath("$.results[?(@.checkCode == 'SHARE_CAPITAL')].ledgerBalance").value(hasItem("6000.00")));
        String manual = mvc.perform(post("/api/v1/accounting/manual-journals").with(FIN1).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"idempotencyKey":"mj-shares","businessDate":"2026-11-28","narrative":"Shares bought for cash",
                     "lines":[{"accountCode":"3300-RETAINED-EARNINGS","side":"DEBIT","amount":"100.00","branchCode":"NBO01"},
                              {"accountCode":"3100-SHARE-CAPITAL","side":"CREDIT","amount":"100.00","branchCode":"NBO01"}]}
                    """))
            .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertTrue(manual.contains("control account"), manual);

        // Dividends need a closed record date; finance asks, a different person approves.
        String dividend = "{\"idempotencyKey\":\"%s\",\"financialYear\":2026,\"recordDate\":\"2026-11-28\",\"ratePct\":\"%s\"}";
        mvc.perform(post(P + "/dividends").with(FIN1).contentType(MediaType.APPLICATION_JSON).content(body(dividend, "d0", "10")))
            .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/end-of-day/run").with(as("ops-1", "OPERATIONS"))).andExpect(status().isOk());
        mvc.perform(post(P + "/dividends/preview").with(FIN1).contentType(MediaType.APPLICATION_JSON)
                .content("{\"financialYear\":2026,\"recordDate\":\"2026-11-28\",\"ratePct\":\"150\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(P + "/dividends/preview").with(FIN1).contentType(MediaType.APPLICATION_JSON)
                .content("{\"financialYear\":2026,\"recordDate\":\"2026-11-28\",\"ratePct\":\"10\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.members").value(3)).andExpect(jsonPath("$.grossTotal").value("600.00"))
            .andExpect(jsonPath("$.taxTotal").value("30.00")).andExpect(jsonPath("$.netTotal").value("570.00"))
            .andExpect(jsonPath("$.toSavingsTotal").value("380.00")).andExpect(jsonPath("$.toPayableTotal").value("190.00"));
        String run = mvc.perform(post(P + "/dividends").with(FIN1).contentType(MediaType.APPLICATION_JSON).content(body(dividend, "d1", "10")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REQUESTED")).andReturn().getResponse().getContentAsString();
        String runId = JsonPath.read(run, "$.runId");
        mvc.perform(post(P + "/dividends").with(FIN1).contentType(MediaType.APPLICATION_JSON).content(body(dividend, "d2", "12")))
            .andExpect(status().isConflict());
        mvc.perform(post(P + "/dividends/" + runId + "/approve").with(FIN1).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
        mvc.perform(post(P + "/dividends/" + runId + "/reject").with(SUP).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
        String posted = mvc.perform(post(P + "/dividends/" + runId + "/approve").with(SUP).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Approved at the AGM\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("POSTED"))
            .andExpect(jsonPath("$.decidedBy").value("user:sup-1")).andReturn().getResponse().getContentAsString();
        String entryId = JsonPath.read(posted, "$.entryId");

        // The entry: retained earnings pays gross; tax and payable credited; members' net into their savings.
        List<Map<String, Object>> lines = owner().queryForList("""
                SELECT account_code, side, sum(amount) AS amount FROM ledger.journal_line WHERE entry_id = ?::uuid
                 GROUP BY account_code, side ORDER BY account_code
                """, entryId);
        assertEquals(List.of("2110-SAVINGS-CONTROL", "2400-DIVIDENDS-PAYABLE", "2410-WHT-DIVIDENDS", "3300-RETAINED-EARNINGS"),
                lines.stream().map(l -> l.get("account_code")).toList(), lines.toString());
        assertEquals(0, new BigDecimal("380.00").compareTo((BigDecimal) lines.get(0).get("amount")));
        assertEquals(0, new BigDecimal("190.00").compareTo((BigDecimal) lines.get(1).get("amount")));
        assertEquals(0, new BigDecimal("30.00").compareTo((BigDecimal) lines.get(2).get("amount")));
        assertEquals("D", lines.get(3).get("side"));
        assertEquals(0, new BigDecimal("600.00").compareTo((BigDecimal) lines.get(3).get("amount")));
        assertEquals(0, new BigDecimal("4204.00").compareTo(balance(a1)));
        mvc.perform(get(P + "/dividends/" + runId + "/lines").with(SUP)).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(3))
            .andExpect(jsonPath("$[?(@.customerNumber == '" + NO_ACCOUNT + "')].paidTo").value(hasItem("Dividends payable (no savings account)")))
            .andExpect(jsonPath("$[?(@.customerNumber == '" + c1 + "')].net").value(hasItem("285.00")));
        mvc.perform(post(P + "/dividends").with(FIN1).contentType(MediaType.APPLICATION_JSON).content(body(dividend, "d3", "10")))
            .andExpect(status().isConflict());
        mvc.perform(get(P + "/dividends").with(FIN1)).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].status").value("POSTED")).andExpect(jsonPath("$[0].netTotal").value("570.00"));
        mvc.perform(get("/api/v1/accounting/reconciliation").with(FIN1)).andExpect(status().isOk())
            .andExpect(jsonPath("$.differences").value(0));
    }

    BigDecimal balance(String accountNumber) {
        return owner().queryForObject("""
                SELECT b.ledger_balance FROM savings.account_balance b
                  JOIN savings.deposit_account a ON a.tenant_id = b.tenant_id AND a.account_id = b.account_id
                 WHERE a.account_number = ?
                """, BigDecimal.class, accountNumber);
    }
}
