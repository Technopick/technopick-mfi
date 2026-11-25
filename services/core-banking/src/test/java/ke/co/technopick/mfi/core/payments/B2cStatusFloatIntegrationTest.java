package ke.co.technopick.mfi.core.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.hamcrest.Matchers;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * B2C transaction status queries and float checks (increment 39, ADR-0039), on the TEST-ONLY seed
 * plus B2C short code 600000 and three approved loans of 20,000.00. Also matches a B2C statement
 * against a disbursement completed through the API.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class B2cStatusFloatIntegrationTest {

    static final String T = "00000000-0000-0000-0000-0000000000c3";
    static final String B = "/api/v1/payments/mpesa/b2c";
    static final String TENANT = "{\"tenantId\":\"" + T + "\"}";

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
    static final JwtRequestPostProcessor MANAGER = as("manager-1", "BRANCH_MANAGER");
    static final JwtRequestPostProcessor OPS = as("ops-1", "OPERATIONS");
    static final JwtRequestPostProcessor FIN = as("fin-1", "FINANCE");
    static final JwtRequestPostProcessor SWITCH = jwt().jwt(j -> j.subject("svc"))
        .authorities(new SimpleGrantedAuthority("ROLE_INTEGRATION"));

    void seed() throws Exception {
        String sql = new String(B2cStatusFloatIntegrationTest.class.getResourceAsStream("/perf/perf-seed-TEST-ONLY.sql").readAllBytes(),
                StandardCharsets.UTF_8).replace("{T}", T).replace("{NS}", "10").replace("{NL}", "10");
        Path file = Files.createTempFile("b2c-seed", ".sql");
        Files.writeString(file, sql);
        PG.copyFileToContainer(MountableFile.forHostPath(file), "/tmp/seed.sql");
        var r = PG.execInContainer("psql", "-v", "ON_ERROR_STOP=1", "-q", "-U", PG.getUsername(), "-d", PG.getDatabaseName(),
                "-f", "/tmp/seed.sql");
        assertEquals(0, r.getExitCode(), r.getStderr());
        JdbcTemplate db = owner();
        db.update("INSERT INTO payments.mpesa_b2c_account (tenant_id, short_code, branch_code, gl_clearing) "
                + "VALUES (?::uuid, '600000', 'NBO01', '1110-MPESA-B2C-CLEARING')", T);
        for (int i = 1; i <= 3; i++) {
            db.update("""
                    INSERT INTO lending.loan (tenant_id, loan_id, loan_number, customer_ref, product_version_id, branch_code,
                        currency, principal, term_months, created_by, approved_by)
                    VALUES (?::uuid, ?::uuid, ?, 'TEST-ONLY', 'cccccccc-0000-0000-0000-0000000000f1', 'NBO01', 'KES', 20000, 12,
                            'test:officer', 'test:manager')
                    """, T, "b2c00000-0000-0000-0000-00000000000" + i, "LN-B2C-000" + i);
            db.update("""
                    INSERT INTO lending.schedule_installment (tenant_id, loan_id, schedule_version, installment_no, due_date,
                        principal, interest) VALUES (?::uuid, ?::uuid, 1, 1, '2026-12-28', 20000, 600)
                    """, T, "b2c00000-0000-0000-0000-00000000000" + i);
        }
    }

    ResultActions call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b,
                       JwtRequestPostProcessor who, String body) throws Exception {
        b = b.with(who);
        if (body != null) {
            b = b.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(b);
    }

    String body(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }

    /** Requested by an officer, approved by a manager, claimed by the integration runtime. */
    String dispatched(int n) throws Exception {
        String id = JsonPath.read(body(call(post(B + "/disbursements"), OFFICER,
                "{\"idempotencyKey\":\"b2c-" + n + "\",\"loanNumber\":\"LN-B2C-000" + n + "\",\"phoneNumber\":\"0712345678\"}")
                .andExpect(status().isOk())), "$.disbursementId");
        call(post(B + "/disbursements/" + id + "/approve"), MANAGER, null).andExpect(status().isOk());
        call(post(B + "/disbursements/" + id + "/dispatch"), SWITCH, TENANT).andExpect(status().isOk());
        return id;
    }

    static String answer(String code, String status, String receipt, String amount) {
        return "{\"tenantId\":\"" + T + "\",\"resultCode\":\"" + code + "\",\"resultDesc\":\"TEST-ONLY answer\","
                + "\"transactionStatus\":" + (status == null ? "null" : "\"" + status + "\"")
                + ",\"receiptNo\":" + (receipt == null ? "null" : "\"" + receipt + "\"")
                + ",\"amount\":" + (amount == null ? "null" : "\"" + amount + "\"") + "}";
    }

    @Test
    void unknownOutcomesAreAskedAndOnlyAConfirmedPaymentCompletesTheLoan() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        seed();

        // 1. M-Pesa's queue timed out: the next pass asks about it once; a confirmed payment activates the loan.
        String d1 = dispatched(1);
        call(post(B + "/disbursements/" + d1 + "/timeout"), SWITCH, TENANT).andExpect(status().isOk())
            .andExpect(jsonPath("$.attention").value("TIMEOUT_UNKNOWN"));
        String due = body(call(post(B + "/status-queries/due"), SWITCH, TENANT).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].disbursementId").value(d1))
            .andExpect(jsonPath("$[0].shortCode").value("600000")));
        String q1 = JsonPath.read(due, "$[0].queryId");
        call(post(B + "/status-queries/due"), SWITCH, TENANT).andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].queryId").value(q1));
        call(post(B + "/status-queries/due"), OPS, TENANT).andExpect(status().isForbidden());
        call(post(B + "/status-queries/" + q1 + "/sent"), SWITCH, TENANT).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SENT"));
        call(post(B + "/status-queries/" + q1 + "/sent"), SWITCH, TENANT).andExpect(status().isConflict());
        call(post(B + "/status-queries/" + q1 + "/result"), SWITCH, answer("0", "Completed", "SIMQ00001A", "20000.00"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ANSWERED"))
            .andExpect(jsonPath("$.outcome").value("COMPLETED_DISBURSEMENT"))
            .andExpect(jsonPath("$.reportedReceipt").value("SIMQ00001A"));
        call(get(B + "/disbursements/" + d1), OFFICER, null).andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.transactionId").value("SIMQ00001A")).andExpect(jsonPath("$.attention").doesNotExist());
        assertEquals("ACTIVE", owner().queryForObject("SELECT status FROM lending.loan WHERE loan_number = 'LN-B2C-0001'",
                String.class));
        // a repeated answer changes nothing
        call(post(B + "/status-queries/" + q1 + "/result"), SWITCH, answer("0", "Completed", "SIMQ00001A", "20000.00"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("COMPLETED_DISBURSEMENT"));
        call(post(B + "/disbursements/" + d1 + "/status-queries"), OPS, null).andExpect(status().isConflict());

        // 2. Submitted, result not yet overdue: a person asks now; "not found" is evidence, not a failure.
        String d2 = dispatched(2);
        call(post(B + "/disbursements/" + d2 + "/submitted"), SWITCH,
                "{\"tenantId\":\"" + T + "\",\"conversationId\":\"AG_TEST_0002\"}").andExpect(status().isOk());
        call(post(B + "/status-queries/due"), SWITCH, TENANT).andExpect(jsonPath("$.length()").value(0));
        call(post(B + "/disbursements/" + d2 + "/status-queries"), OFFICER, null).andExpect(status().isForbidden());
        String q2 = JsonPath.read(body(call(post(B + "/disbursements/" + d2 + "/status-queries"), OPS, null)
            .andExpect(status().isOk()).andExpect(jsonPath("$.reason").value("MANUAL"))
            .andExpect(jsonPath("$.requestedBy").value("user:ops-1"))), "$.queryId");
        call(post(B + "/disbursements/" + d2 + "/status-queries"), OPS, null).andExpect(status().isConflict());
        call(post(B + "/status-queries/due"), SWITCH, TENANT).andExpect(jsonPath("$[0].queryId").value(q2))
            .andExpect(jsonPath("$[0].conversationId").value("AG_TEST_0002"));
        call(post(B + "/status-queries/" + q2 + "/sent"), SWITCH, TENANT).andExpect(status().isOk());
        call(post(B + "/status-queries/" + q2 + "/result"), SWITCH, answer("1", null, null, null))
            .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("NOT_CONFIRMED"));
        call(get(B + "/disbursements/" + d2), OFFICER, null).andExpect(jsonPath("$.status").value("SUBMITTED"));
        call(get(B + "/disbursements/" + d2 + "/status-queries"), OFFICER, null).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].resultCode").value("1"));

        // 3. A confirmed payment for another amount is not posted; the disbursement is flagged.
        String d3 = dispatched(3);
        call(post(B + "/disbursements/" + d3 + "/timeout"), SWITCH, TENANT).andExpect(status().isOk());
        String q3 = JsonPath.read(body(call(post(B + "/status-queries/due"), SWITCH, TENANT)
            .andExpect(jsonPath("$.length()").value(1))), "$[0].queryId");
        call(post(B + "/status-queries/" + q3 + "/no-answer"), SWITCH, TENANT).andExpect(jsonPath("$.status").value("NO_ANSWER"));
        call(post(B + "/disbursements/" + d3 + "/status-queries"), OPS, null).andExpect(status().isOk());
        String q3b = JsonPath.read(body(call(post(B + "/status-queries/due"), SWITCH, TENANT)), "$[0].queryId");
        call(post(B + "/status-queries/" + q3b + "/result"), SWITCH, answer("0", "Completed", "SIMQ00003C", "19999.00"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("AMOUNT_MISMATCH"));
        call(get(B + "/disbursements/" + d3), OFFICER, null).andExpect(jsonPath("$.status").value("DISPATCHED"))
            .andExpect(jsonPath("$.attention").value("AMOUNT_MISMATCH"));
        assertEquals("APPROVED", owner().queryForObject("SELECT status FROM lending.loan WHERE loan_number = 'LN-B2C-0003'",
                String.class));

        // 4. The B2C statement line of the confirmed payment matches the completed disbursement.
        String csv = "Receipt No.,Completion Time,Details,Transaction Status,Paid In,Withdrawn,Balance,Reason Type\\n"
                + "SIMQ00001A,2026-11-28 09:00:00,Business Payment to 254712345678 - TEST ONLY,Completed,,20000.00,30000.00,"
                + "Business Payment to Customer via API\\n";
        call(post("/api/v1/payments/mpesa/statements"), FIN, "{\"accountKind\":\"B2C\",\"shortCode\":\"600000\","
                + "\"statementDate\":\"2026-11-28\",\"fileName\":\"b2c.csv\",\"csv\":\"" + csv + "\"}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.upload.matched").value(1))
            .andExpect(jsonPath("$.upload.exceptions").value(0))
            .andExpect(jsonPath("$.check.ledgerOnlyCount").value(0));

        // 5. Float: settings by finance only; a check asked on demand, then once a day.
        call(get(B + "/float"), FIN, null).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].shortCode").value("600000"))
            .andExpect(jsonPath("$[0].alerts", Matchers.hasItem("NO_BALANCE_YET")));
        String settings = "{\"floatAccountName\":\"Utility Account\",\"lowFloatThreshold\":\"50000.00\",\"dailyCheckHour\":0}";
        call(put(B + "/float/600000/settings"), OPS, settings).andExpect(status().isForbidden());
        call(put(B + "/float/600000/settings"), FIN, settings).andExpect(status().isOk())
            .andExpect(jsonPath("$.lowFloatThreshold").value("50000.00")).andExpect(jsonPath("$.updatedBy").value("user:fin-1"));
        call(put(B + "/float/600000/settings"), FIN, settings.replace("\"dailyCheckHour\":0", "\"dailyCheckHour\":24"))
            .andExpect(status().isBadRequest());
        String c1 = JsonPath.read(body(call(post(B + "/float/600000/checks"), OPS, null).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING"))), "$.checkId");
        call(post(B + "/float/600000/checks"), FIN, null).andExpect(status().isConflict());
        call(post(B + "/float/999999/checks"), FIN, null).andExpect(status().isNotFound());
        call(post(B + "/balance-checks/due"), SWITCH, TENANT).andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].checkId").value(c1));
        call(post(B + "/balance-checks/" + c1 + "/sent"), SWITCH, TENANT).andExpect(status().isOk());
        call(post(B + "/balance-checks/" + c1 + "/result"), SWITCH, "{\"tenantId\":\"" + T + "\",\"resultCode\":\"0\","
                + "\"resultDesc\":\"TEST-ONLY\",\"accountBalance\":\"Working Account|KES|0.00|0.00|0.00|0.00"
                + "&Utility Account|KES|30000.00|30000.00|0.00|0.00\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.floatAvailable").value("30000.00"))
            .andExpect(jsonPath("$.ledgerBalance").value("-20000.00"))
            .andExpect(jsonPath("$.difference").value("50000.00"))
            .andExpect(jsonPath("$.committed").value("40000.00"))
            .andExpect(jsonPath("$.accounts.length()").value(2))
            .andExpect(jsonPath("$.accounts[1].name").value("Utility Account"));
        call(get(B + "/float"), OPS, null).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].latest.checkId").value(c1))
            .andExpect(jsonPath("$[0].alerts").value(Matchers.contains("APPROVED_EXCEEDS_FLOAT", "LOW_FLOAT", "LEDGER_DIFFERENCE")));

        // the daily check (hour 0) opens once; a failed send is reported on the float view
        String daily = JsonPath.read(body(call(post(B + "/balance-checks/due"), SWITCH, TENANT)
            .andExpect(jsonPath("$.length()").value(1))), "$[0].checkId");
        call(post(B + "/balance-checks/" + daily + "/send-failure"), SWITCH,
                "{\"tenantId\":\"" + T + "\",\"description\":\"Daraja unreachable\"}").andExpect(status().isOk());
        call(post(B + "/balance-checks/due"), SWITCH, TENANT).andExpect(jsonPath("$.length()").value(0));
        call(get(B + "/float"), FIN, null).andExpect(jsonPath("$[0].alerts", Matchers.hasItem("LAST_CHECK_FAILED")))
            .andExpect(jsonPath("$[0].latest.checkId").value(c1));

        // an answer without the configured float account is recorded as unreadable
        String c2 = JsonPath.read(body(call(post(B + "/float/600000/checks"), FIN, null)), "$.checkId");
        call(post(B + "/balance-checks/" + c2 + "/result"), SWITCH, "{\"tenantId\":\"" + T + "\",\"resultCode\":\"0\","
                + "\"accountBalance\":\"Working Account|KES|5.00|5.00|0.00|0.00\"}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.resultCode").value("UNREADABLE"))
            .andExpect(jsonPath("$.floatAvailable").doesNotExist());
        List<String> reasons = owner().queryForList("SELECT reason FROM payments.b2c_balance_check ORDER BY requested_at",
                String.class);
        assertEquals(List.of("MANUAL", "DAILY", "MANUAL"), reasons);
    }
}
