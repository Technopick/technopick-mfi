package ke.co.technopick.mfi.core.member;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.security.core.GrantedAuthority;
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
 * Member self-service (increment 33, ADR-0033) on the small TEST-ONLY book: member i owns savings
 * account i and loan LN-P000000i. Tokens are built by the test the way the identity provider would
 * issue them: role MEMBER and the member's customer number in the customer_number claim.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class MemberSelfServiceIntegrationTest {

    static final String T = "00000000-0000-0000-0000-0000000000c3";
    static final String M = "/api/v1/member";

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
        r.add("mfi.member.requests-per-minute", () -> "20");
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

    record Member(String subject, String customerNumber, String account, String loan, String msisdn) { }

    static Member member(int i) {
        Map<String, Object> r = owner().queryForMap("""
                SELECT c.customer_number, a.account_number, l.loan_number, c.msisdn
                  FROM lending.loan l JOIN customer.customer c ON c.tenant_id = l.tenant_id AND c.customer_id = l.customer_id
                  JOIN savings.deposit_account a ON a.tenant_id = c.tenant_id AND a.customer_id = c.customer_id
                 WHERE l.tenant_id = ?::uuid AND l.loan_number = ?
                """, T, "LN-P%07d".formatted(i));
        return new Member("member-sub-" + i, (String) r.get("customer_number"), (String) r.get("account_number"),
                (String) r.get("loan_number"), (String) r.get("msisdn"));
    }

    static JwtRequestPostProcessor token(Member m, String... roles) {
        return jwt().jwt(j -> j.subject(m.subject()).claim("tenant_id", T).claim("customer_number", m.customerNumber())
                .claim("roles", List.of(roles)))
            .authorities(java.util.Arrays.stream(roles).<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
    }

    static JwtRequestPostProcessor staff(String role) {
        return jwt().jwt(j -> j.subject("staff-1").claim("tenant_id", T)).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    void seed() throws Exception {
        String sql = new String(MemberSelfServiceIntegrationTest.class.getResourceAsStream("/perf/perf-seed-TEST-ONLY.sql").readAllBytes(),
                StandardCharsets.UTF_8).replace("{T}", T).replace("{NS}", "10").replace("{NL}", "10");
        Path file = Files.createTempFile("member-seed", ".sql");
        Files.writeString(file, sql);
        PG.copyFileToContainer(MountableFile.forHostPath(file), "/tmp/seed.sql");
        var r = PG.execInContainer("psql", "-v", "ON_ERROR_STOP=1", "-q", "-U", PG.getUsername(), "-d", PG.getDatabaseName(),
                "-f", "/tmp/seed.sql");
        assertEquals(0, r.getExitCode(), r.getStderr());
    }

    static String pay(String key, String target, String amount) {
        return """
            {"idempotencyKey":"%s","target":"%s","amount":"%s"}
            """.formatted(key, target, amount);
    }

    @Test
    void membersSeeAndPayOnlyTheirOwn() throws Exception {
        // The member API uses the real security chain, so build MockMvc with it and convert tokens the production way.
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        seed();
        Member one = member(1);
        Member two = member(2);
        Member three = member(3);

        // A member sees their own accounts and loans.
        mvc.perform(get(M + "/me").with(token(one, "MEMBER"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.customerNumber").value(one.customerNumber()))
            .andExpect(jsonPath("$.accounts.length()").value(1))
            .andExpect(jsonPath("$.accounts[0].accountNumber").value(one.account()))
            .andExpect(jsonPath("$.loans.length()").value(1))
            .andExpect(jsonPath("$.loans[0].loanNumber").value(one.loan()))
            .andExpect(jsonPath("$.loans[0].principalOutstanding").value("12000.00"))
            .andExpect(jsonPath("$.loans[0].nextDueDate").value("2026-12-01"));
        mvc.perform(get(M + "/accounts/" + one.account() + "/statement").with(token(one, "MEMBER"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.accountNumber").value(one.account()));
        mvc.perform(get(M + "/loans/" + one.loan()).with(token(one, "MEMBER"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.schedule.loanNumber").value(one.loan()))
            .andExpect(jsonPath("$.payoff.principal").value("12000.00"));

        // ... and nothing of anyone else's: another member's account or loan is simply "not found".
        mvc.perform(get(M + "/accounts/" + two.account() + "/statement").with(token(one, "MEMBER"))).andExpect(status().isNotFound());
        mvc.perform(get(M + "/loans/" + two.loan()).with(token(one, "MEMBER"))).andExpect(status().isNotFound());
        mvc.perform(get(M + "/loans/LN-NOPE").with(token(one, "MEMBER"))).andExpect(status().isNotFound());

        // Members reach no staff endpoint, even one guarded only by "authenticated", and even if the token also claims staff roles.
        mvc.perform(get("/api/v1/lending/loans/" + one.loan() + "/settlement").with(token(one, "MEMBER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/end-of-day").with(token(one, "MEMBER", "OPERATIONS"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/accounting/reconciliation").with(token(one, "MEMBER", "FINANCE"))).andExpect(status().isForbidden());
        // Staff do not use the member API, and a member token must name a member of its tenant.
        mvc.perform(get(M + "/me").with(staff("SUPERVISOR"))).andExpect(status().isForbidden());
        mvc.perform(get(M + "/me").with(jwt().jwt(j -> j.subject("x").claim("tenant_id", T))
                .authorities(new SimpleGrantedAuthority("ROLE_MEMBER")))).andExpect(status().isForbidden());
        mvc.perform(get(M + "/me").with(token(new Member("x", "CU26-9999999", null, null, null), "MEMBER")))
            .andExpect(status().isForbidden());
        mvc.perform(get(M + "/me").with(jwt().jwt(j -> j.subject("x").claim("tenant_id", "00000000-0000-0000-0000-0000000000e5")
                .claim("customer_number", one.customerNumber())).authorities(new SimpleGrantedAuthority("ROLE_MEMBER"))))
            .andExpect(status().isForbidden());
        // A blocked member is refused.
        owner().update("UPDATE customer.customer SET status = 'BLOCKED', status_reason = 'test' WHERE tenant_id = ?::uuid AND customer_number = ?",
                T, three.customerNumber());
        mvc.perform(get(M + "/me").with(token(three, "MEMBER"))).andExpect(status().isForbidden());

        // Paying: the prompt goes to the member's registered phone, for their own loan or account only.
        String paid = mvc.perform(post(M + "/payments").with(token(one, "MEMBER")).contentType(MediaType.APPLICATION_JSON)
                .content(pay("pay-1", one.loan(), "500")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.target").value(one.loan())).andExpect(jsonPath("$.amount").value("500.00"))
            .andExpect(jsonPath("$.replayed").value(false))
            .andReturn().getResponse().getContentAsString();
        String requestId = JsonPath.read(paid, "$.requestId");
        Map<String, Object> row = owner().queryForMap(
                "SELECT msisdn, requested_by, member_customer_id IS NOT NULL AS linked FROM payments.stk_request WHERE request_id = ?::uuid",
                requestId);
        assertEquals(one.msisdn(), row.get("msisdn"));
        assertEquals("member:" + one.subject(), row.get("requested_by"));
        assertEquals(Boolean.TRUE, row.get("linked"));
        mvc.perform(post(M + "/payments").with(token(one, "MEMBER")).contentType(MediaType.APPLICATION_JSON)
                .content(pay("pay-1", one.loan(), "500")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.requestId").value(requestId)).andExpect(jsonPath("$.replayed").value(true));
        // Another member using the same key gets their own request, never member one's.
        mvc.perform(post(M + "/payments").with(token(two, "MEMBER")).contentType(MediaType.APPLICATION_JSON)
                .content(pay("pay-1", two.account(), "100")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(false))
            .andExpect(jsonPath("$.requestId").value(org.hamcrest.Matchers.not(requestId)));
        mvc.perform(post(M + "/payments").with(token(one, "MEMBER")).contentType(MediaType.APPLICATION_JSON)
                .content(pay("pay-x", two.account(), "100"))).andExpect(status().isNotFound());
        mvc.perform(post(M + "/payments").with(token(one, "MEMBER")).contentType(MediaType.APPLICATION_JSON)
                .content(pay("pay-y", one.account(), "10.50"))).andExpect(status().isBadRequest());
        mvc.perform(get(M + "/payments/" + requestId).with(token(one, "MEMBER"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.phone").value(startsWith("2547")));
        mvc.perform(get(M + "/payments/" + requestId).with(token(two, "MEMBER"))).andExpect(status().isNotFound());

        // At most three prompts per member in ten minutes (counted in the database, across servers).
        mvc.perform(post(M + "/payments").with(token(one, "MEMBER")).contentType(MediaType.APPLICATION_JSON)
                .content(pay("pay-2", one.account(), "100"))).andExpect(status().isOk());
        mvc.perform(post(M + "/payments").with(token(one, "MEMBER")).contentType(MediaType.APPLICATION_JSON)
                .content(pay("pay-3", one.account(), "100"))).andExpect(status().isOk());
        mvc.perform(post(M + "/payments").with(token(one, "MEMBER")).contentType(MediaType.APPLICATION_JSON)
                .content(pay("pay-4", one.account(), "100")))
            .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "600"));
        // A replay of an earlier prompt is still answered.
        mvc.perform(post(M + "/payments").with(token(one, "MEMBER")).contentType(MediaType.APPLICATION_JSON)
                .content(pay("pay-1", one.loan(), "500"))).andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));

        // Every member request is in the audit trail, under the member's own actor.
        Integer audited = owner().queryForObject("""
                SELECT count(*) FROM audit.staff_action WHERE tenant_id = ?::uuid AND actor = ? AND path LIKE '/api/v1/member/%'
                """, Integer.class, T, "member:" + one.subject());
        assertTrue(audited >= 10, "member requests audited: " + audited);

        // Requests per minute (20 in this test): a burst is cut off with 429.
        Member four = member(4);
        int limited = 0;
        for (int i = 0; i < 50; i++) {
            int s = mvc.perform(get(M + "/me").with(token(four, "MEMBER"))).andReturn().getResponse().getStatus();
            if (s == 429) {
                limited++;
            } else {
                assertEquals(200, s);
            }
        }
        assertTrue(limited > 0, "a burst of 50 requests must be limited");
    }
}
