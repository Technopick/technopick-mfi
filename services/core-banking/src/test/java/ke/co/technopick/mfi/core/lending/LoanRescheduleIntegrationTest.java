package ke.co.technopick.mfi.core.lending;

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
 * Restructuring and partial prepayment (increment 37, ADR-0037) on the small TEST-ONLY book:
 * 12,000.00 loans at 3% a month, 1,000.00 principal per instalment. Loan 3 was disbursed on 1 Sep
 * and has paid nothing (instalments of 1 Oct and 1 Nov overdue, interest 360.00 and 330.00); loan 1
 * was disbursed on 1 Nov and is current. Business date 28 Nov 2026.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class LoanRescheduleIntegrationTest {

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

    static final JwtRequestPostProcessor OFFICER = as("officer-1", "LOAN_OFFICER");
    static final JwtRequestPostProcessor MANAGER = as("mgr-1", "BRANCH_MANAGER");

    void seed() throws Exception {
        String sql = new String(LoanRescheduleIntegrationTest.class.getResourceAsStream("/perf/perf-seed-TEST-ONLY.sql").readAllBytes(),
                StandardCharsets.UTF_8).replace("{T}", T).replace("{NS}", "10").replace("{NL}", "10");
        Path file = Files.createTempFile("reschedule-seed", ".sql");
        Files.writeString(file, sql);
        PG.copyFileToContainer(MountableFile.forHostPath(file), "/tmp/seed.sql");
        var r = PG.execInContainer("psql", "-v", "ON_ERROR_STOP=1", "-q", "-U", PG.getUsername(), "-d", PG.getDatabaseName(),
                "-f", "/tmp/seed.sql");
        assertEquals(0, r.getExitCode(), r.getStderr());
    }

    static String loan(int i) {
        return "LN-P%07d".formatted(i);
    }

    String request(String loan, String key, String terms, int expected) throws Exception {
        return mvc.perform(post(L + "/loans/" + loan + "/reschedules").with(OFFICER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\":\"" + key + "\",\"reason\":\"Member's income fell; plan agreed at the branch\"," + terms + "}"))
            .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }

    static List<Map<String, Object>> version(String loan, int v) {
        return owner().queryForList("""
                SELECT s.installment_no, s.due_date::text AS due, s.principal, s.interest FROM lending.schedule_installment s
                  JOIN lending.loan l ON l.tenant_id = s.tenant_id AND l.loan_id = s.loan_id
                 WHERE l.tenant_id = ?::uuid AND l.loan_number = ? AND s.schedule_version = ? ORDER BY s.installment_no
                """, T, loan, v);
    }

    static BigDecimal sum(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> (BigDecimal) r.get("principal")).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void loansAreRestructuredAndPrepaidWithMakerChecker() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        seed();
        mvc.perform(put("/api/v1/notifications/settings").with(as("sup-1", "SUPERVISOR")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"signature\":\"Test Sacco\",\"reminderHour\":8}")).andExpect(status().isOk());

        // Restructure loan 3: the overdue principal and the rest over 12 instalments after one interest-only month.
        String terms = "\"kind\":\"RESTRUCTURE\",\"instalments\":12,\"graceMonths\":1";
        mvc.perform(post(L + "/loans/" + loan(3) + "/reschedules/preview").with(OFFICER).contentType(MediaType.APPLICATION_JSON)
                .content("{" + terms + "}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.outstandingPrincipal").value("12000.00"))
            .andExpect(jsonPath("$.stillDueNow").value("690.00")).andExpect(jsonPath("$.instalments").value(13))
            .andExpect(jsonPath("$.instalmentAmount").value("1205.55")).andExpect(jsonPath("$.firstDue").value("2026-12-01"))
            .andExpect(jsonPath("$.proposed[0].interest").value("300.00")).andExpect(jsonPath("$.proposed[0].principal").value("0.00"));
        String requested = request(loan(3), "rs-3", terms, 200);
        String id3 = JsonPath.read(requested, "$.rescheduleId");
        request(loan(3), "rs-3b", terms, 409);
        mvc.perform(post(L + "/reschedules/" + id3 + "/approve").with(OFFICER).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
        mvc.perform(post(L + "/reschedules/" + id3 + "/approve").with(as("officer-1", "BRANCH_MANAGER"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(get(L + "/reschedules").with(MANAGER)).andExpect(status().isOk())
            .andExpect(jsonPath("$[*].rescheduleId", hasItem(id3)));
        mvc.perform(post(L + "/reschedules/" + id3 + "/approve").with(MANAGER).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED")).andExpect(jsonPath("$.toVersion").value(2));
        List<Map<String, Object>> v1 = version(loan(3), 1);
        List<Map<String, Object>> v2 = version(loan(3), 2);
        assertEquals(15, v2.size());
        assertEquals(0, new BigDecimal("12000").compareTo(sum(v2)));
        for (int i = 0; i < 3; i++) {
            assertEquals(v1.get(i).get("due"), v2.get(i).get("due"));
            assertEquals(0, ((BigDecimal) v1.get(i).get("interest")).compareTo((BigDecimal) v2.get(i).get("interest")));
            assertEquals(0, ((BigDecimal) v2.get(i).get("principal")).signum(), "no principal left on instalment " + (i + 1));
        }
        assertEquals("2027-01-01", v2.get(3).get("due"));
        mvc.perform(get(L + "/loans/" + loan(3) + "/schedule").with(OFFICER)).andExpect(status().isOk())
            .andExpect(jsonPath("$.scheduleVersion").value(2)).andExpect(jsonPath("$.restructured").value(true));
        Map<String, Object> sms = owner().queryForMap("SELECT category, body FROM notify.sms_message WHERE template = 'LOAN_RESCHEDULED'");
        assertEquals("SERVICE", sms.get("category"));
        assertEquals("Test Sacco: Your loan " + loan(3) + " has a new schedule: 13 instalments, usually KES 1,205.55, the first due "
                + "01/12/2026. Ask your branch for details.", sms.get("body"));
        assertEquals(Boolean.TRUE, owner().queryForObject("SELECT lending.restructured_within(?::uuid, l.loan_id, '2026-11-30', 6) "
                + "FROM lending.loan l WHERE l.tenant_id = ?::uuid AND l.loan_number = ?", Boolean.class, T, T, loan(3)));

        // Partial prepayment on loan 1: 3,000.00 paid ahead shortens the term; more than the credit is refused; arrears block it.
        mvc.perform(post("/api/v1/payments/mpesa/c2b").with(jwt().jwt(j -> j.subject("svc"))
                    .authorities(new SimpleGrantedAuthority("ROLE_INTEGRATION")))
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"tenantId":"%s","transId":"TPREPAY001","shortCode":"600993","amount":"3000.00","currency":"KES","billRef":"%s",
                     "msisdnMasked":"2547*****001","transTime":"2026-11-28T10:15:00+03:00"}
                    """.formatted(T, loan(1))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("LOAN_REPAYMENT"));
        String prepay = "\"kind\":\"PREPAYMENT\",\"amount\":\"3000.00\",\"mode\":\"SHORTEN_TERM\"";
        mvc.perform(post(L + "/loans/" + loan(1) + "/reschedules/preview").with(OFFICER).contentType(MediaType.APPLICATION_JSON)
                .content("{" + prepay.replace("3000.00", "5000.00") + "}")).andExpect(status().isConflict());
        mvc.perform(post(L + "/loans/" + loan(4) + "/reschedules/preview").with(OFFICER).contentType(MediaType.APPLICATION_JSON)
                .content("{" + prepay + "}")).andExpect(status().isConflict());
        String id1 = JsonPath.read(request(loan(1), "rs-1", prepay, 200), "$.rescheduleId");
        mvc.perform(post(L + "/reschedules/" + id1 + "/approve").with(MANAGER).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        List<Map<String, Object>> p2 = version(loan(1), 2);
        assertEquals(8, p2.size(), p2.toString());
        assertEquals(0, new BigDecimal("4000.00").compareTo((BigDecimal) p2.get(0).get("principal")));
        assertEquals(0, new BigDecimal("360.00").compareTo((BigDecimal) p2.get(0).get("interest")));
        assertEquals(0, new BigDecimal("12000").compareTo(sum(p2)));
        mvc.perform(get(L + "/loans/" + loan(1) + "/schedule").with(OFFICER))
            .andExpect(jsonPath("$.restructured").value(false));

        // A rejection needs a reason; a request from yesterday cannot be approved today.
        String id4 = JsonPath.read(request(loan(4), "rs-4", terms, 200), "$.rescheduleId");
        mvc.perform(post(L + "/reschedules/" + id4 + "/reject").with(MANAGER).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post(L + "/reschedules/" + id4 + "/reject").with(MANAGER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Needs the credit committee\"}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REJECTED"));
        String id8 = JsonPath.read(request(loan(8), "rs-8", terms, 200), "$.rescheduleId");

        // End of day runs cleanly over the changed schedules, and everything reconciles.
        mvc.perform(post("/api/v1/end-of-day/run").with(as("ops-1", "OPERATIONS"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.reconciliationDifferences").value(0));
        mvc.perform(post(L + "/reschedules/" + id8 + "/approve").with(MANAGER).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isConflict());
        String par = mvc.perform(get("/api/v1/reports/portfolio-at-risk?asOf=2026-11-28").with(MANAGER)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        List<Boolean> flagged = JsonPath.read(par, "$.loansInArrears[?(@.loanNumber == '" + loan(3) + "')].restructured");
        assertEquals(List.of(true), flagged);
        List<Boolean> other = JsonPath.read(par, "$.loansInArrears[?(@.loanNumber == '" + loan(4) + "')].restructured");
        assertEquals(List.of(false), other);
        assertTrue(par.contains(loan(3)));
    }
}
