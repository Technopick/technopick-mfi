package ke.co.technopick.mfi.core.provisioning;

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
 * IFRS 9 provisioning (increment 36, ADR-0036) on the small TEST-ONLY book: ten loans of 12,000.00;
 * loans 3, 4, 8 and 9 were disbursed on 1 Sep and have paid nothing, so at the 30 Nov close they
 * are 60 days past due (stage 2); the other six were disbursed on 1 Nov and are current (stage 1).
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class EclIntegrationTest {

    static final String T = "00000000-0000-0000-0000-0000000000c3";
    static final String P = "/api/v1/provisioning";

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
    static final JwtRequestPostProcessor FIN2 = as("fin-2", "FINANCE");

    void seed() throws Exception {
        String sql = new String(EclIntegrationTest.class.getResourceAsStream("/perf/perf-seed-TEST-ONLY.sql").readAllBytes(),
                StandardCharsets.UTF_8).replace("{T}", T).replace("{NS}", "10").replace("{NL}", "10");
        Path file = Files.createTempFile("ecl-seed", ".sql");
        Files.writeString(file, sql);
        PG.copyFileToContainer(MountableFile.forHostPath(file), "/tmp/seed.sql");
        var r = PG.execInContainer("psql", "-v", "ON_ERROR_STOP=1", "-q", "-U", PG.getUsername(), "-d", PG.getDatabaseName(),
                "-f", "/tmp/seed.sql");
        assertEquals(0, r.getExitCode(), r.getStderr());
        owner().update("""
                INSERT INTO ledger.gl_account (tenant_id, account_code, name, account_class, currency) VALUES
                  (?::uuid, '1290-LOAN-LOSS-ALLOWANCE', 'Loan loss allowance (IFRS 9)', 'ASSET', 'KES'),
                  (?::uuid, '1291-SECOND-ALLOWANCE', 'Another allowance account', 'ASSET', 'KES'),
                  (?::uuid, '5200-IMPAIRMENT', 'Loan impairment expense', 'EXPENSE', 'KES')
                """, T, T, T);
    }

    String requestRun(String key, String asOf, int expected) throws Exception {
        return mvc.perform(post(P + "/runs").with(FIN1).contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\":\"%s\",\"asOf\":\"%s\"}".formatted(key, asOf)))
            .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }

    @Test
    void financeProvidesForExpectedLossesWithMakerChecker() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        seed();
        String product = owner().queryForObject("SELECT min(product_code) FROM lending.loan_product WHERE tenant_id = ?::uuid",
                String.class, T);

        // Finance only; nothing can run before the accounts are set.
        mvc.perform(get(P + "/settings").with(as("ops-1", "OPERATIONS"))).andExpect(status().isForbidden());
        mvc.perform(get(P + "/settings").with(FIN1)).andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(false));
        mvc.perform(get(P + "/preview?asOf=2026-11-27").with(FIN1)).andExpect(status().isConflict());
        mvc.perform(put(P + "/settings").with(FIN1).contentType(MediaType.APPLICATION_JSON)
                .content("{\"allowanceAccount\":\"5200-IMPAIRMENT\",\"expenseAccount\":\"5200-IMPAIRMENT\"}"))
            .andExpect(status().is(422));
        mvc.perform(put(P + "/settings").with(FIN1).contentType(MediaType.APPLICATION_JSON)
                .content("{\"allowanceAccount\":\"1200-LOANS-PRINCIPAL\",\"expenseAccount\":\"5200-IMPAIRMENT\"}"))
            .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("ECL_ACCOUNT_INVALID"));
        mvc.perform(put(P + "/settings").with(FIN1).contentType(MediaType.APPLICATION_JSON)
                .content("{\"allowanceAccount\":\"1290-LOAN-LOSS-ALLOWANCE\",\"expenseAccount\":\"5200-IMPAIRMENT\",\"stage2Dpd\":30,\"stage3Dpd\":90}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(true)).andExpect(jsonPath("$.updatedBy").value("user:fin-1"));

        // Rates: the product's own, and defaults; out-of-range and unknown products are refused.
        String rates = """
            {"rows":[{"productCode":"%s","stage1Pct":"1","stage2Pct":"10","stage3Pct":"50"},
                     {"productCode":"*","stage1Pct":"2","stage2Pct":"20","stage3Pct":"100"}]}
            """.formatted(product);
        mvc.perform(put(P + "/rates").with(FIN1).contentType(MediaType.APPLICATION_JSON).content(rates.replace("\"50\"", "\"150\"")))
            .andExpect(status().isBadRequest());
        mvc.perform(put(P + "/rates").with(FIN1).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rows\":[{\"productCode\":\"NOPE\",\"stage1Pct\":\"1\"}]}")).andExpect(status().isBadRequest());
        mvc.perform(put(P + "/rates").with(FIN1).contentType(MediaType.APPLICATION_JSON).content(rates)).andExpect(status().isOk())
            .andExpect(jsonPath("$.rows[0].stage2Pct").value("10")).andExpect(jsonPath("$.rows[-1:].productCode").value(hasItem("*")));

        // Close 28, 29 and 30 November.
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/v1/end-of-day/run").with(as("ops-1", "OPERATIONS"))).andExpect(status().isOk());
        }

        // A preview works for any closed day; runs only for month ends.
        mvc.perform(get(P + "/preview?asOf=2026-11-29").with(FIN1)).andExpect(status().isOk())
            .andExpect(jsonPath("$.monthEnd").value(false)).andExpect(jsonPath("$.loans").value(10));
        requestRun("ecl-29", "2026-11-29", 400);
        mvc.perform(get(P + "/preview?asOf=2026-11-30").with(FIN1)).andExpect(status().isOk())
            .andExpect(jsonPath("$.stages[0].loans").value(6)).andExpect(jsonPath("$.stages[0].ecl").value("720.00"))
            .andExpect(jsonPath("$.stages[1].loans").value(4)).andExpect(jsonPath("$.stages[1].ecl").value("4800.00"))
            .andExpect(jsonPath("$.stages[1].coveragePct").value("10.00"))
            .andExpect(jsonPath("$.requiredTotal").value("5520.00")).andExpect(jsonPath("$.currentAllowance").value("0.00"))
            .andExpect(jsonPath("$.change").value("5520.00")).andExpect(jsonPath("$.productsWithoutRates.length()").value(0));

        // Request; the requester cannot decide; a rejection needs a reason; one open run at a time.
        String first = requestRun("ecl-30a", "2026-11-30", 200);
        String firstId = JsonPath.read(first, "$.runId");
        assertEquals("REQUESTED", JsonPath.read(first, "$.status"));
        assertEquals(Boolean.TRUE, JsonPath.read(requestRun("ecl-30a", "2026-11-30", 200), "$.replayed"));
        requestRun("ecl-30b", "2026-11-30", 409);
        mvc.perform(put(P + "/rates").with(FIN2).contentType(MediaType.APPLICATION_JSON).content(rates)).andExpect(status().isConflict());
        mvc.perform(post(P + "/runs/" + firstId + "/approve").with(FIN1).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
        mvc.perform(post(P + "/runs/" + firstId + "/reject").with(FIN2).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post(P + "/runs/" + firstId + "/reject").with(FIN2).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Use the board-approved rates\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));

        // Request again and approve: the change is posted, dated the month end, and the allowance reconciles.
        String run = requestRun("ecl-30c", "2026-11-30", 200);
        String runId = JsonPath.read(run, "$.runId");
        String posted = mvc.perform(post(P + "/runs/" + runId + "/approve").with(FIN2).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("POSTED")).andExpect(jsonPath("$.change").value("5520.00"))
            .andExpect(jsonPath("$.decidedBy").value("user:fin-2"))
            .andReturn().getResponse().getContentAsString();
        String entryId = JsonPath.read(posted, "$.entryId");
        List<Map<String, Object>> lines = owner().queryForList("""
                SELECT l.account_code, l.side, l.amount, e.business_date FROM ledger.journal_line l
                  JOIN ledger.journal_entry e ON e.tenant_id = l.tenant_id AND e.entry_id = l.entry_id
                 WHERE l.entry_id = ?::uuid ORDER BY l.account_code
                """, entryId);
        assertEquals(2, lines.size(), lines.toString());
        assertEquals("1290-LOAN-LOSS-ALLOWANCE", lines.get(0).get("account_code"));
        assertEquals("C", lines.get(0).get("side"));
        assertEquals(0, new BigDecimal("5520.00").compareTo((BigDecimal) lines.get(0).get("amount")));
        assertEquals("5200-IMPAIRMENT", lines.get(1).get("account_code"));
        assertEquals("D", lines.get(1).get("side"));
        assertEquals("2026-11-30", lines.get(0).get("business_date").toString());
        mvc.perform(get("/api/v1/accounting/reconciliation").with(FIN1)).andExpect(status().isOk())
            .andExpect(jsonPath("$.differences").value(0))
            .andExpect(jsonPath("$.results[?(@.checkCode == 'LOAN_LOSS_ALLOWANCE')].ledgerBalance").value(hasItem("-5520.00")));

        // Every loan is in the run's lines, for the auditors.
        mvc.perform(get(P + "/runs/" + runId + "/lines").with(FIN1)).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(10))
            .andExpect(jsonPath("$[0].stage").value(2)).andExpect(jsonPath("$[0].daysPastDue").value(60))
            .andExpect(jsonPath("$[0].exposure").value("12000.00")).andExpect(jsonPath("$[0].ratePct").value("10"))
            .andExpect(jsonPath("$[0].ecl").value("1200.00"))
            .andExpect(jsonPath("$[*].loanNumber", hasItem("LN-P0000003")));

        // Nothing else posts to the allowance; it cannot be swapped; and a month already posted is not run again.
        String control = mvc.perform(post("/api/v1/accounting/manual-journals").with(FIN1).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"idempotencyKey":"mj-ecl","businessDate":"2026-12-01","narrative":"Top up the allowance by hand",
                     "lines":[{"accountCode":"5200-IMPAIRMENT","side":"DEBIT","amount":"100.00","branchCode":"NBO01"},
                              {"accountCode":"1290-LOAN-LOSS-ALLOWANCE","side":"CREDIT","amount":"100.00","branchCode":"NBO01"}]}
                    """))
            .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertTrue(control.contains("control account"), control);
        mvc.perform(put(P + "/settings").with(FIN1).contentType(MediaType.APPLICATION_JSON)
                .content("{\"allowanceAccount\":\"1291-SECOND-ALLOWANCE\",\"expenseAccount\":\"5200-IMPAIRMENT\"}"))
            .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("ECL_ACCOUNT_LOCKED"));
        requestRun("ecl-30d", "2026-11-30", 409);
        mvc.perform(get(P + "/runs").with(FIN1)).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].status").value("POSTED")).andExpect(jsonPath("$[1].status").value("REJECTED"))
            .andExpect(jsonPath("$[0].stages[1].ecl").value("4800.00"));
    }
}
