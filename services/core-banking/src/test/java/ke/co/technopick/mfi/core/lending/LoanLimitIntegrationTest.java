package ke.co.technopick.mfi.core.lending;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Loan limits and guarantors (increment 43, ADR-0043) on the small TEST-ONLY book. BIASHARA lends
 * 3 x own cover less other loans outstanding, with guarantees for the part above own cover. Member 1
 * (8,919.00 saved, one 12,000.00 loan) borrows 10,000.00: 1,081.00 must be guaranteed; member 2
 * pledges 1,000.00 (not enough), withdraws it and pledges 1,200.00, which is locked on their savings.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class LoanLimitIntegrationTest {

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
    static final JwtRequestPostProcessor TELLER = as("teller-1", "TELLER");

    void seed() throws Exception {
        String sql = new String(LoanLimitIntegrationTest.class.getResourceAsStream("/perf/perf-seed-TEST-ONLY.sql").readAllBytes(),
                StandardCharsets.UTF_8).replace("{T}", T).replace("{NS}", "10").replace("{NL}", "10");
        Path file = Files.createTempFile("limits-seed", ".sql");
        Files.writeString(file, sql);
        PG.copyFileToContainer(MountableFile.forHostPath(file), "/tmp/seed.sql");
        var r = PG.execInContainer("psql", "-v", "ON_ERROR_STOP=1", "-q", "-U", PG.getUsername(), "-d", PG.getDatabaseName(),
                "-f", "/tmp/seed.sql");
        assertEquals(0, r.getExitCode(), r.getStderr());
    }

    Map<String, Object> member(String lastName) {
        return owner().queryForMap("""
                SELECT c.customer_number, a.account_number FROM customer.customer c
                  JOIN savings.deposit_account a ON a.tenant_id = c.tenant_id AND a.customer_id = c.customer_id
                 WHERE c.tenant_id = ?::uuid AND c.last_name = ?
                """, T, lastName);
    }

    String submit(String key, String customer, String principal) throws Exception {
        return mvc.perform(post(L + "/applications").with(OFFICER).contentType(MediaType.APPLICATION_JSON).content("""
                {"idempotencyKey":"%s","customerRef":"%s","productCode":"BIASHARA","branchCode":"NBO01",
                 "principal":"%s","termMonths":12,"purpose":"Restock the shop"}
                """.formatted(key, customer, principal))).andReturn().getResponse().getContentAsString();
    }

    String pledge(String application, String key, Map<String, Object> guarantor, String amount, int expected) throws Exception {
        return mvc.perform(post(L + "/applications/" + application + "/guarantees").with(OFFICER).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"idempotencyKey":"%s","guarantorNumber":"%s","accountNumber":"%s","amount":"%s","consentReference":"Form G-%s signed"}
                    """.formatted(key, guarantor.get("customer_number"), guarantor.get("account_number"), amount, key)))
            .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }

    @Test
    void limitsAndGuarantees() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        seed();
        Map<String, Object> m1 = member("Load1");
        Map<String, Object> m2 = member("Load2");
        Map<String, Object> m3 = member("Load3");
        Map<String, Object> m10 = member("Load10");
        String c1 = (String) m1.get("customer_number");

        // Without a rule there is no limit.
        mvc.perform(get(L + "/limits?customerNumber=" + c1 + "&productCode=BIASHARA").with(OFFICER)).andExpect(status().isOk())
            .andExpect(jsonPath("$.ruleApplies").value(false));
        String rule = "{\"savingsMultiple\":\"3\",\"includeShares\":true,\"deductOutstanding\":true,\"guaranteesRequired\":true}";
        mvc.perform(put(L + "/limit-rules/BIASHARA").with(OFFICER).contentType(MediaType.APPLICATION_JSON).content(rule))
            .andExpect(status().isForbidden());
        mvc.perform(put(L + "/limit-rules/BIASHARA").with(FIN).contentType(MediaType.APPLICATION_JSON).content(rule))
            .andExpect(status().isOk()).andExpect(jsonPath("$.savingsMultiple").value("3.00"))
            .andExpect(jsonPath("$.guaranteesRequired").value(true));
        String limit = mvc.perform(get(L + "/limits?customerNumber=" + c1 + "&productCode=BIASHARA").with(OFFICER))
            .andExpect(status().isOk()).andExpect(jsonPath("$.savings").value("8919.00")).andExpect(jsonPath("$.ownCover").value("8919.00"))
            .andReturn().getResponse().getContentAsString();
        BigDecimal outstanding = new BigDecimal((String) JsonPath.read(limit, "$.outstanding"));
        BigDecimal max = new BigDecimal("26757.00").subtract(outstanding);
        assertEquals(0, max.compareTo(new BigDecimal((String) JsonPath.read(limit, "$.limit"))), limit);

        // Above the limit is refused at submission; within it is taken.
        mvc.perform(post(L + "/applications").with(OFFICER).contentType(MediaType.APPLICATION_JSON).content("""
                {"idempotencyKey":"a0","customerRef":"%s","productCode":"BIASHARA","branchCode":"NBO01",
                 "principal":"%s","termMonths":12,"purpose":"Restock the shop"}
                """.formatted(c1, max.add(BigDecimal.ONE).toPlainString())))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(containsString("may borrow up to")));
        String a = submit("a1", c1, "10000");
        String applicationA = JsonPath.read(a, "$.applicationId");
        mvc.perform(get(L + "/applications/" + applicationA + "/cover").with(MANAGER)).andExpect(status().isOk())
            .andExpect(jsonPath("$.guaranteeNeeded").value("1081.00")).andExpect(jsonPath("$.covered").value(false))
            .andExpect(jsonPath("$.withinLimit").value(true));

        // Guarantees: not the borrower, own account, money available; one pledge per guarantor.
        pledge(applicationA, "g0", m1, "100", 400);
        pledge(applicationA, "g0", Map.of("customer_number", m2.get("customer_number"), "account_number", m3.get("account_number")), "100", 400);
        pledge(applicationA, "g0", m10, "1000", 409);
        String first = pledge(applicationA, "g1", m2, "1000", 200);
        assertEquals("1000.00", JsonPath.read(first, "$.pledged"));
        pledge(applicationA, "g2", m2, "500", 409);
        mvc.perform(post(L + "/applications/" + applicationA + "/approve").with(MANAGER).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(containsString("1081.00")));
        String firstId = JsonPath.read(first, "$.guarantees[0].guaranteeId");
        mvc.perform(post(L + "/guarantees/" + firstId + "/withdraw").with(OFFICER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Guarantor will pledge more\"}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.pledged").value("0.00"));
        pledge(applicationA, "g3", m2, "1200", 200);
        BigDecimal availableBefore = owner().queryForObject("SELECT available_balance FROM savings.account_balance WHERE account_number = ?",
                BigDecimal.class, m2.get("account_number"));
        assertEquals(0, new BigDecimal("1200").compareTo(owner().queryForObject(
                "SELECT ledger_balance FROM savings.account_balance WHERE account_number = ?", BigDecimal.class, m2.get("account_number"))
                .subtract(availableBefore)), "the pledge is locked on the guarantor's savings");
        mvc.perform(post(L + "/applications/" + applicationA + "/approve").with(MANAGER).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        String g3 = mvc.perform(get(L + "/members/" + m2.get("customer_number") + "/guarantees-given").with(TELLER)).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].status").value("PLEDGED")).andExpect(jsonPath("$[0].amount").value("1200.00"))
            .andExpect(jsonPath("$[0].borrowerNumber").value(c1)).andReturn().getResponse().getContentAsString();
        String g3Id = JsonPath.read(g3, "$[0].guaranteeId");
        assertEquals(2, (int) JsonPath.read(g3, "$.length()"));
        mvc.perform(post(L + "/guarantees/" + g3Id + "/withdraw").with(OFFICER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Guarantor changed their mind\"}")).andExpect(status().isConflict());
        mvc.perform(get(L + "/limits?customerNumber=" + m2.get("customer_number") + "&productCode=BIASHARA").with(OFFICER))
            .andExpect(status().isOk()).andExpect(jsonPath("$.pledgedForOthers").value("1200.00"));
        mvc.perform(get("/api/v1/member-exits/members/" + m2.get("customer_number")).with(TELLER)).andExpect(status().isOk())
            .andExpect(jsonPath("$.blockers").value(hasItem("1 guarantee(s) for other members' loans (1200.00)")));

        // The new loan counts against the next one; a declined application releases its pledges.
        mvc.perform(get(L + "/limits?customerNumber=" + c1 + "&productCode=BIASHARA").with(OFFICER)).andExpect(status().isOk())
            .andExpect(jsonPath("$.outstanding").value(outstanding.add(new BigDecimal("10000")).setScale(2).toPlainString()));
        String b = submit("b1", c1, "1000");
        String applicationB = JsonPath.read(b, "$.applicationId");
        pledge(applicationB, "g4", m3, "500", 200);
        mvc.perform(post(L + "/applications/" + applicationB + "/decline").with(MANAGER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Too soon after the last loan\"}")).andExpect(status().isOk());
        mvc.perform(get(L + "/members/" + m3.get("customer_number") + "/guarantees-given").with(TELLER)).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].status").value("RELEASED")).andExpect(jsonPath("$[0].endReason").value("Application declined"));
        assertEquals(0, owner().queryForObject("""
                SELECT count(*) FROM savings.account_hold h JOIN savings.deposit_account a ON a.tenant_id = h.tenant_id AND a.account_id = h.account_id
                 WHERE a.account_number = ? AND h.status = 'ACTIVE'
                """, Integer.class, m3.get("account_number")));
    }
}
