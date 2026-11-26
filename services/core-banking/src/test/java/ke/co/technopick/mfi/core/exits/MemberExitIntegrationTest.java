package ke.co.technopick.mfi.core.exits;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Member exit (increment 42, ADR-0042) on the small TEST-ONLY book, business date 28 Nov 2026.
 * A TEST-ONLY member with no loans saves 20,000.00 on SAVP (6%, 15% tax), asks to leave with a
 * two-day notice period, earns two nights of interest (6.58) and leaves on 30 Nov: refund
 * 20,000.00 + 6.58 - 0.99 = 20,005.59, paid by bank transfer with a second person's approval.
 * Seed member 1 has a loan and cannot leave.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class MemberExitIntegrationTest {

    static final String T = "00000000-0000-0000-0000-0000000000c3";
    static final String P = "/api/v1/member-exits";
    static final String LEAVER = "CU26-0009801";

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
    static final JwtRequestPostProcessor FIN2 = as("fin-2", "FINANCE");
    static final JwtRequestPostProcessor SUP = as("sup-1", "SUPERVISOR");
    static final JwtRequestPostProcessor TELLER = as("teller-1", "TELLER");
    static final JwtRequestPostProcessor OFFICER = as("lo-1", "LOAN_OFFICER");
    static final JwtRequestPostProcessor MANAGER = as("bm-1", "BRANCH_MANAGER");
    static final JwtRequestPostProcessor OPS = as("ops-1", "OPERATIONS");

    void seed() throws Exception {
        String sql = new String(MemberExitIntegrationTest.class.getResourceAsStream("/perf/perf-seed-TEST-ONLY.sql").readAllBytes(),
                StandardCharsets.UTF_8).replace("{T}", T).replace("{NS}", "10").replace("{NL}", "10");
        Path file = Files.createTempFile("exit-seed", ".sql");
        Files.writeString(file, sql);
        PG.copyFileToContainer(MountableFile.forHostPath(file), "/tmp/seed.sql");
        var r = PG.execInContainer("psql", "-v", "ON_ERROR_STOP=1", "-q", "-U", PG.getUsername(), "-d", PG.getDatabaseName(),
                "-f", "/tmp/seed.sql");
        assertEquals(0, r.getExitCode(), r.getStderr());
        // TEST-ONLY: refunds and bank accounts, and a member with savings and no loans, funded from the bank.
        owner().execute("""
                SELECT set_config('app.tenant_id', '%1$s', false);
                INSERT INTO ledger.gl_account (tenant_id, account_code, name, account_class, currency) VALUES
                  ('%1$s', '2450-MEMBER-REFUNDS', 'Member refunds payable', 'LIABILITY', 'KES'),
                  ('%1$s', '1020-BANK-TEST', 'Bank (test)', 'ASSET', 'KES');
                INSERT INTO customer.customer (tenant_id, customer_id, customer_number, idempotency_key, first_name, last_name,
                    date_of_birth, id_type, id_number, msisdn, branch_code, consent_version, consent_at, created_by)
                VALUES ('%1$s', 'c0000000-0000-0000-0000-000000009801', '%2$s', 'exit-leaver', 'Member', 'Leaver', date '1979-02-02',
                        'NATIONAL_ID', '29999801', '254799999801', 'NBO01', 'DPA-2026-01', now(), 'perf:officer');
                UPDATE customer.customer SET status = 'ACTIVE', verified_by = 'perf:manager', verification_note = 'Load-test member, TEST ONLY'
                 WHERE customer_number = '%2$s';
                INSERT INTO savings.deposit_account (tenant_id, account_id, account_number, customer_ref, customer_id, product_code, currency,
                    branch_code, gl_control_account, opened_on, open_idempotency_key)
                SELECT '%1$s', 'ab000000-0000-0000-0000-000000009801', savings.next_account_number(), '%2$s',
                       'c0000000-0000-0000-0000-000000009801', 'SAVP', 'KES', 'NBO01', '2110-SAVINGS-CONTROL', current_date_, 'exit-a'
                  FROM tenancy.business_date WHERE tenant_id = '%1$s';
                INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor)
                SELECT '%1$s', 'e0000000-0000-0000-0000-000000009801', 'exit-test-funding', current_date_, 'TELLER', 'c', 'perf:teller'
                  FROM tenancy.business_date WHERE tenant_id = '%1$s';
                INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code,
                    subledger_account_id) VALUES
                  ('%1$s', 'e0000000-0000-0000-0000-000000009801', 1, '1020-BANK-TEST', 'D', 20000, 'KES', 'NBO01', NULL),
                  ('%1$s', 'e0000000-0000-0000-0000-000000009801', 2, '2110-SAVINGS-CONTROL', 'C', 20000, 'KES', 'NBO01',
                   'ab000000-0000-0000-0000-000000009801');
                """.formatted(T, LEAVER));
    }

    String accountNumber() {
        return owner().queryForObject("SELECT account_number FROM savings.deposit_account WHERE account_id = 'ab000000-0000-0000-0000-000000009801'",
                String.class);
    }

    @Test
    void memberLeavesAfterNoticeAndIsRefunded() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        seed();
        String member1 = owner().queryForObject("SELECT customer_number FROM customer.customer WHERE tenant_id = ?::uuid AND last_name = 'Load1'",
                String.class, T);

        // Settings: finance only, refunds payable must be a liability.
        mvc.perform(post(P).with(OFFICER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\":\"x0\",\"customerNumber\":\"" + LEAVER + "\",\"reason\":\"Relocating abroad for work\"}"))
            .andExpect(status().isConflict());
        mvc.perform(put(P + "/settings").with(TELLER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"noticeDays\":2,\"refundsPayableAccount\":\"2450-MEMBER-REFUNDS\"}")).andExpect(status().isForbidden());
        mvc.perform(put(P + "/settings").with(FIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"noticeDays\":2,\"refundsPayableAccount\":\"1020-BANK-TEST\"}"))
            .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("EXIT_SETTINGS_INVALID"));
        mvc.perform(put(P + "/settings").with(FIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"noticeDays\":2,\"refundsPayableAccount\":\"2450-MEMBER-REFUNDS\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.noticeDays").value(2));

        // A member with a loan is told what stops them; the leaver has nothing outstanding.
        mvc.perform(get(P + "/members/" + member1).with(TELLER)).andExpect(status().isOk())
            .andExpect(jsonPath("$.blockers").value(hasItem("1 loan(s) not yet repaid")));
        mvc.perform(get(P + "/members/" + LEAVER).with(TELLER)).andExpect(status().isOk())
            .andExpect(jsonPath("$.blockers.length()").value(0)).andExpect(jsonPath("$.refundToday").value("20000.00"));

        // The request: notice copied; one at a time; another person approves, after the notice.
        String asked = mvc.perform(post(P).with(OFFICER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\":\"x1\",\"customerNumber\":\"" + LEAVER + "\",\"reason\":\"Relocating abroad for work\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REQUESTED"))
            .andExpect(jsonPath("$.earliestExitDate").value("2026-11-30")).andReturn().getResponse().getContentAsString();
        String exitId = JsonPath.read(asked, "$.exitId");
        mvc.perform(post(P).with(TELLER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\":\"x2\",\"customerNumber\":\"" + LEAVER + "\",\"reason\":\"Asked a second time today\"}"))
            .andExpect(status().isConflict());
        mvc.perform(post(P + "/" + exitId + "/approve").with(OFFICER)).andExpect(status().isForbidden());
        mvc.perform(post(P + "/" + exitId + "/approve").with(MANAGER))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(containsString("2026-11-30")));

        // Two closes pass; savings interest accrues each night (20,000 at 6%: 3.29 + 3.29).
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/end-of-day/run").with(OPS)).andExpect(status().isOk());
        }
        mvc.perform(get(P + "/members/" + LEAVER).with(MANAGER)).andExpect(status().isOk())
            .andExpect(jsonPath("$.accounts[0].interestGross").value("6.58")).andExpect(jsonPath("$.accounts[0].interestTax").value("0.99"))
            .andExpect(jsonPath("$.refundToday").value("20005.59"));
        mvc.perform(post(P + "/" + exitId + "/approve").with(MANAGER)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED")).andExpect(jsonPath("$.refundTotal").value("20005.59"))
            .andExpect(jsonPath("$.accounts[0].refund").value("20005.59")).andExpect(jsonPath("$.businessDate").value("2026-11-30"));
        assertEquals("EXITED", owner().queryForObject("SELECT status FROM customer.customer WHERE customer_number = ?", String.class, LEAVER));
        assertEquals("CLOSED", owner().queryForObject("SELECT status FROM savings.deposit_account WHERE account_number = ?", String.class,
                accountNumber()));
        mvc.perform(post(P + "/" + exitId + "/approve").with(SUP)).andExpect(status().isConflict());
        mvc.perform(post(P).with(OFFICER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\":\"x3\",\"customerNumber\":\"" + LEAVER + "\",\"reason\":\"Leaving a second time\"}"))
            .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/accounting/reconciliation").with(FIN)).andExpect(status().isOk())
            .andExpect(jsonPath("$.differences").value(0))
            .andExpect(jsonPath("$.results[?(@.checkCode == 'MEMBER_REFUNDS_PAYABLE')].ledgerBalance").value(hasItem("20005.59")));
        mvc.perform(post("/api/v1/accounting/manual-journals").with(FIN).contentType(MediaType.APPLICATION_JSON).content("""
                {"idempotencyKey":"mj-refund","businessDate":"2026-11-30","narrative":"Refund paid by hand",
                 "lines":[{"accountCode":"2450-MEMBER-REFUNDS","side":"DEBIT","amount":"100.00","branchCode":"NBO01"},
                          {"accountCode":"1020-BANK-TEST","side":"CREDIT","amount":"100.00","branchCode":"NBO01"}]}
                """)).andExpect(status().isBadRequest());

        // The refund: recorded by finance from a bank account, approved by someone else.
        String refund = "{\"idempotencyKey\":\"%s\",\"method\":\"BANK_TRANSFER\",\"paidFromAccount\":\"%s\",\"reference\":\"EFT-0001\"}";
        mvc.perform(post(P + "/" + exitId + "/refund-payments").with(TELLER).contentType(MediaType.APPLICATION_JSON)
                .content(refund.formatted("r1", "1020-BANK-TEST"))).andExpect(status().isForbidden());
        mvc.perform(post(P + "/" + exitId + "/refund-payments").with(FIN).contentType(MediaType.APPLICATION_JSON)
                .content(refund.formatted("r1", "2110-SAVINGS-CONTROL"))).andExpect(status().isBadRequest());
        String recorded = mvc.perform(post(P + "/" + exitId + "/refund-payments").with(FIN).contentType(MediaType.APPLICATION_JSON)
                .content(refund.formatted("r1", "1020-BANK-TEST")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.payment.status").value("REQUESTED"))
            .andExpect(jsonPath("$.payment.amount").value("20005.59")).andReturn().getResponse().getContentAsString();
        String paymentId = JsonPath.read(recorded, "$.payment.paymentId");
        mvc.perform(post(P + "/" + exitId + "/refund-payments").with(FIN2).contentType(MediaType.APPLICATION_JSON)
                .content(refund.formatted("r2", "1020-BANK-TEST"))).andExpect(status().isConflict());
        mvc.perform(post(P + "/refund-payments/" + paymentId + "/approve").with(FIN)).andExpect(status().isForbidden());
        mvc.perform(post(P + "/refund-payments/" + paymentId + "/approve").with(SUP)).andExpect(status().isOk())
            .andExpect(jsonPath("$.payment.status").value("APPROVED")).andExpect(jsonPath("$.payment.decidedBy").value("user:sup-1"));
        mvc.perform(get("/api/v1/accounting/reconciliation").with(FIN)).andExpect(status().isOk())
            .andExpect(jsonPath("$.differences").value(0))
            .andExpect(jsonPath("$.results[?(@.checkCode == 'MEMBER_REFUNDS_PAYABLE')].ledgerBalance").value(hasItem("0.00")));
        mvc.perform(get(P + "?status=APPROVED").with(FIN)).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].customerNumber").value(LEAVER)).andExpect(jsonPath("$[0].payment.reference").value("EFT-0001"));
        // A closing day after the exit runs cleanly with the account closed.
        mvc.perform(post("/api/v1/end-of-day/run").with(OPS)).andExpect(status().isOk())
            .andExpect(jsonPath("$.reconciliationDifferences").value(0));
    }
}
