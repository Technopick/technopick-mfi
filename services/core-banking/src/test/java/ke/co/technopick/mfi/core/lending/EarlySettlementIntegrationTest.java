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
 * Early settlement and refunds of loan credit (increment 32, ADR-0032) on the small TEST-ONLY book:
 * 10 members, 10 loans of 12,000.00 at 3% a month (scheduled interest 2,340.00 each); loan i
 * belongs to member i. LN-P0000001 is performing; LN-P0000003 is 58 days past due on 28 Nov.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class EarlySettlementIntegrationTest {

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

    static final JwtRequestPostProcessor OPS = as("ops-1", "OPERATIONS");
    static final JwtRequestPostProcessor SUPERVISOR = as("sup-1", "SUPERVISOR");

    static BigDecimal q(String sql, Object... args) {
        return owner().queryForObject(sql, BigDecimal.class, args);
    }

    static BigDecimal credit(String account) {
        return q("SELECT COALESCE(sum(CASE side WHEN 'C' THEN amount ELSE -amount END), 0) FROM ledger.journal_line"
                + " WHERE tenant_id = ?::uuid AND account_code = ?", T, account);
    }

    static BigDecimal loanFn(String fn, String loanNumber) {
        return q("SELECT lending." + fn + "(tenant_id, loan_id) FROM lending.loan WHERE tenant_id = ?::uuid AND loan_number = ?",
                T, loanNumber);
    }

    static BigDecimal savings(String accountNumber) {
        return q("""
                SELECT COALESCE(sum(CASE l.side WHEN 'C' THEN l.amount ELSE -l.amount END), 0)
                  FROM savings.deposit_account a LEFT JOIN ledger.journal_line l
                    ON l.tenant_id = a.tenant_id AND l.subledger_account_id = a.account_id
                 WHERE a.tenant_id = ?::uuid AND a.account_number = ?
                """, T, accountNumber);
    }

    static String accountOfMember(int i) {
        return owner().queryForObject("""
                SELECT a.account_number FROM savings.deposit_account a JOIN lending.loan l
                    ON l.tenant_id = a.tenant_id AND l.customer_id = a.customer_id
                 WHERE l.tenant_id = ?::uuid AND l.loan_number = ?
                """, String.class, T, "LN-P%07d".formatted(i));
    }

    void pay(String transId, String loanNumber, BigDecimal amount) throws Exception {
        mvc.perform(post("/api/v1/payments/mpesa/c2b").with(jwt().jwt(j -> j.subject("svc-integration"))
                    .authorities(new SimpleGrantedAuthority("ROLE_INTEGRATION")))
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"tenantId":"%s","transId":"%s","shortCode":"600993","amount":"%s","currency":"KES","billRef":"%s",
                     "msisdnMasked":"2547*****000","transTime":"2026-11-29T10:15:00+03:00"}
                    """.formatted(T, transId, amount.toPlainString(), loanNumber)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("LOAN_REPAYMENT"));
    }

    void reconciled() throws Exception {
        mvc.perform(get("/api/v1/accounting/reconciliation").with(as("fin-1", "FINANCE"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.differences").value(0));
    }

    void seed() throws Exception {
        String sql = new String(EarlySettlementIntegrationTest.class.getResourceAsStream("/perf/perf-seed-TEST-ONLY.sql").readAllBytes(),
                StandardCharsets.UTF_8).replace("{T}", T).replace("{NS}", "10").replace("{NL}", "10");
        Path file = Files.createTempFile("settlement-seed", ".sql");
        Files.writeString(file, sql);
        PG.copyFileToContainer(MountableFile.forHostPath(file), "/tmp/seed.sql");
        var r = PG.execInContainer("psql", "-v", "ON_ERROR_STOP=1", "-q", "-U", PG.getUsername(), "-d", PG.getDatabaseName(),
                "-f", "/tmp/seed.sql");
        assertEquals(0, r.getExitCode(), r.getStderr());
        owner().update("INSERT INTO ledger.gl_account (tenant_id, account_code, name, account_class, currency)"
                + " VALUES (?::uuid, '1650-INTEREST-IN-SUSPENSE', 'Interest in suspense', 'ASSET', 'KES')", T);
    }

    @Test
    void earlySettlementAndCreditRefund() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        seed();
        // Interest suspension on, so settling a suspended loan exercises the release at closure.
        mvc.perform(put("/api/v1/regulatory/settings").with(as("fin-1", "FINANCE")).contentType(MediaType.APPLICATION_JSON).content("""
                {"minCoreCapital":"10000000","minCoreToAssetsPct":"10","minInstitutionalToAssetsPct":"8","minCoreToDepositsPct":"8",
                 "minLiquidityPct":"15","watchMaxDpd":30,"substandardMaxDpd":180,"doubtfulMaxDpd":360,"provisionPerformingPct":"1",
                 "provisionWatchPct":"5","provisionSubstandardPct":"25","provisionDoubtfulPct":"50","provisionLossPct":"100",
                 "includeCurrentYearSurplus":true,"interestSuspensionEnabled":true,"interestSuspenseAccount":"1650-INTEREST-IN-SUSPENSE"}
                """)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/end-of-day/run").with(OPS)).andExpect(status().isOk())
            .andExpect(jsonPath("$.closedDate").value("2026-11-28"));

        // ---- a performing loan: quote, pay, settle, refund what is left
        String one = "LN-P0000001";
        BigDecimal accrued = loanFn("loan_interest_receivable", one);
        assertEquals(1, accrued.signum(), "interest has accrued since 1 Nov");
        BigDecimal payoff = new BigDecimal("12000.00").add(accrued);
        mvc.perform(get(L + "/loans/" + one + "/settlement").with(as("teller-1", "TELLER"))).andExpect(status().isForbidden());
        mvc.perform(get(L + "/loans/" + one + "/settlement").with(as("officer-1", "LOAN_OFFICER"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.businessDate").value("2026-11-29"))
            .andExpect(jsonPath("$.principal").value("12000.00"))
            .andExpect(jsonPath("$.interest").value(accrued.setScale(2).toPlainString()))
            .andExpect(jsonPath("$.penalty").value("0.00"))
            .andExpect(jsonPath("$.payoff").value(payoff.setScale(2).toPlainString()))
            .andExpect(jsonPath("$.interestWaived").value(new BigDecimal("2340.00").subtract(accrued).setScale(2).toPlainString()))
            .andExpect(jsonPath("$.creditHeld").value("0.00"))
            .andExpect(jsonPath("$.stillToPay").value(payoff.setScale(2).toPlainString()))
            .andExpect(jsonPath("$.canSettle").value(false));
        mvc.perform(post(L + "/loans/" + one + "/settlement").with(OPS).contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\":\"settle-1\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(containsString("more is needed")));

        // The member pays the payoff and 500.00 more. Nothing is due yet, so it is all held as credit.
        pay("TSETTLE001", one, payoff.add(new BigDecimal("500.00")));
        mvc.perform(get(L + "/loans/" + one + "/settlement").with(OPS)).andExpect(status().isOk())
            .andExpect(jsonPath("$.stillToPay").value("0.00")).andExpect(jsonPath("$.canSettle").value(true));
        BigDecimal incomeBefore = credit("4000-INTEREST-INCOME");
        mvc.perform(post(L + "/loans/" + one + "/settlement").with(as("officer-1", "LOAN_OFFICER")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\":\"settle-1\"}")).andExpect(status().isForbidden());
        String settled = mvc.perform(post(L + "/loans/" + one + "/settlement").with(OPS).contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\":\"settle-1\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.payoff").value(payoff.setScale(2).toPlainString()))
            .andExpect(jsonPath("$.creditLeft").value("500.00"))
            .andExpect(jsonPath("$.scheduleVersion").value(2))
            .andExpect(jsonPath("$.settledBy").value("user:ops-1"))
            .andReturn().getResponse().getContentAsString();
        // The same request again is the same settlement.
        mvc.perform(post(L + "/loans/" + one + "/settlement").with(OPS).contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\":\"settle-1\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.settlementId").value(JsonPath.<String>read(settled, "$.settlementId")));
        mvc.perform(get(L + "/loans/" + one + "/settlement").with(OPS)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CLOSED"))
            .andExpect(jsonPath("$.creditHeld").value("500.00"))
            .andExpect(jsonPath("$.settlement.interestWaived").value(new BigDecimal("2340.00").subtract(accrued).setScale(2).toPlainString()));
        assertEquals(0, loanFn("loan_unpaid", one).signum());
        assertEquals(0, incomeBefore.compareTo(credit("4000-INTEREST-INCOME")), "no interest that never accrued becomes income");
        reconciled();

        // Refund the 500.00 left to the member's savings account: requested by one person, approved by another.
        String own = accountOfMember(1);
        String other = accountOfMember(2);
        BigDecimal before = savings(own);
        mvc.perform(post(L + "/loans/" + one + "/credit-refunds").with(OPS).contentType(MediaType.APPLICATION_JSON).content("""
                {"idempotencyKey":"refund-x","accountNumber":"%s","amount":"600.00","reason":"Member settled early and asked for the balance"}
                """.formatted(own))).andExpect(status().isConflict());
        mvc.perform(post(L + "/loans/" + one + "/credit-refunds").with(OPS).contentType(MediaType.APPLICATION_JSON).content("""
                {"idempotencyKey":"refund-y","accountNumber":"%s","amount":"500.00","reason":"Member settled early and asked for the balance"}
                """.formatted(other))).andExpect(status().is(422)).andExpect(jsonPath("$.code").value("REFUND_ACCOUNT_INVALID"));
        mvc.perform(post(L + "/loans/LN-P0000002/credit-refunds").with(OPS).contentType(MediaType.APPLICATION_JSON).content("""
                {"idempotencyKey":"refund-z","accountNumber":"%s","amount":"1.00","reason":"Member asked for a refund of credit"}
                """.formatted(other))).andExpect(status().isConflict());
        String refund = mvc.perform(post(L + "/loans/" + one + "/credit-refunds").with(OPS).contentType(MediaType.APPLICATION_JSON).content("""
                {"idempotencyKey":"refund-1","accountNumber":"%s","amount":"500.00","reason":"Member settled early and asked for the balance"}
                """.formatted(own)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REQUESTED"))
            .andReturn().getResponse().getContentAsString();
        String refundId = JsonPath.read(refund, "$.refundId");
        assertEquals(0, before.compareTo(savings(own)), "nothing moves on request");
        mvc.perform(post(L + "/credit-refunds/" + refundId + "/approve").with(as("ops-1", "SUPERVISOR"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(get(L + "/credit-refunds?status=REQUESTED").with(SUPERVISOR)).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].refundId").value(refundId)).andExpect(jsonPath("$[0].accountNumber").value(own));
        mvc.perform(post(L + "/credit-refunds/" + refundId + "/approve").with(SUPERVISOR).contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.decidedBy").value("user:sup-1")).andExpect(jsonPath("$.creditRemaining").value("0.00"));
        assertEquals(0, before.add(new BigDecimal("500.00")).compareTo(savings(own)));
        assertEquals(0, loanFn("loan_credit", one).signum());
        mvc.perform(post(L + "/credit-refunds/" + refundId + "/approve").with(SUPERVISOR).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isConflict());
        mvc.perform(post(L + "/loans/" + one + "/credit-refunds").with(OPS).contentType(MediaType.APPLICATION_JSON).content("""
                {"idempotencyKey":"refund-2","accountNumber":"%s","amount":"1.00","reason":"Member asked for a second refund"}
                """.formatted(own))).andExpect(status().isConflict());
        reconciled();

        // ---- a suspended loan settled early: its interest in suspense becomes income at closure
        String three = "LN-P0000003";
        BigDecimal held = loanFn("interest_in_suspense", three);
        assertEquals(1, held.signum(), "the substandard loan's interest is in suspense");
        String quote = mvc.perform(get(L + "/loans/" + three + "/settlement").with(OPS)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        BigDecimal payoff3 = new BigDecimal(JsonPath.<String>read(quote, "$.payoff"));
        assertEquals(1, new BigDecimal(JsonPath.<String>read(quote, "$.penalty")).signum(), "penalties are part of the payoff");
        pay("TSETTLE003", three, payoff3);
        BigDecimal incomeBefore3 = credit("4000-INTEREST-INCOME");
        mvc.perform(post(L + "/loans/" + three + "/settlement").with(OPS).contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\":\"settle-3\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.creditLeft").value("0.00"));
        assertEquals(0, loanFn("interest_in_suspense", three).signum());
        assertEquals(0, incomeBefore3.add(held).compareTo(credit("4000-INTEREST-INCOME")),
                "the suspended interest, now paid, is income");
        assertEquals("CLOSURE", owner().queryForObject("""
                SELECT s.reason FROM lending.interest_suspense s JOIN lending.loan l ON l.tenant_id = s.tenant_id AND l.loan_id = s.loan_id
                 WHERE l.tenant_id = ?::uuid AND l.loan_number = ? ORDER BY s.movement_id DESC LIMIT 1
                """, String.class, T, three));
        reconciled();

        // The next close skips both closed loans and still reconciles.
        mvc.perform(post("/api/v1/end-of-day/run").with(OPS)).andExpect(status().isOk())
            .andExpect(jsonPath("$.closedDate").value("2026-11-29"))
            .andExpect(jsonPath("$.reconciliationDifferences").value(0));
    }
}
