package ke.co.technopick.mfi.core.termdeposits;

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
import java.time.LocalDate;
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
 * Fixed deposits (increment 41, ADR-0041) on the small TEST-ONLY book, business date 28 Nov 2026.
 * Product FD-1M: one month, 12%, 6% if withdrawn early, 15% withholding tax, 1,000 to 500,000.
 * Member 1 places A 5,000.00 (paid out) and B 2,000.00 (rolled over with interest); member 2 places
 * C 10,000.00 and withdraws it early on 29 Nov. Every day is then closed by the real end of day
 * until A and B mature at the close of 27 Dec: 30 nights, A earns 49.32 (tax 7.40), B 19.73
 * (tax 2.96) and continues as a 2,016.77 deposit from 28 Dec.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class FixedDepositIntegrationTest {

    static final String T = "00000000-0000-0000-0000-0000000000c3";
    static final String P = "/api/v1/fixed-deposits";

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
    static final JwtRequestPostProcessor SUP = as("sup-1", "SUPERVISOR");
    static final JwtRequestPostProcessor TELLER = as("teller-1", "TELLER");
    static final JwtRequestPostProcessor OFFICER = as("lo-1", "LOAN_OFFICER");
    static final JwtRequestPostProcessor MANAGER = as("bm-1", "BRANCH_MANAGER");
    static final JwtRequestPostProcessor OPS = as("ops-1", "OPERATIONS");

    void seed() throws Exception {
        String sql = new String(FixedDepositIntegrationTest.class.getResourceAsStream("/perf/perf-seed-TEST-ONLY.sql").readAllBytes(),
                StandardCharsets.UTF_8).replace("{T}", T).replace("{NS}", "10").replace("{NL}", "10");
        Path file = Files.createTempFile("fd-seed", ".sql");
        Files.writeString(file, sql);
        PG.copyFileToContainer(MountableFile.forHostPath(file), "/tmp/seed.sql");
        var r = PG.execInContainer("psql", "-v", "ON_ERROR_STOP=1", "-q", "-U", PG.getUsername(), "-d", PG.getDatabaseName(),
                "-f", "/tmp/seed.sql");
        assertEquals(0, r.getExitCode(), r.getStderr());
        owner().update("""
                INSERT INTO ledger.gl_account (tenant_id, account_code, name, account_class, currency) VALUES
                  (?::uuid, '2600-FIXED-DEPOSITS', 'Fixed deposits', 'LIABILITY', 'KES'),
                  (?::uuid, '2610-FD-INTEREST-PAYABLE', 'Interest payable on fixed deposits', 'LIABILITY', 'KES'),
                  (?::uuid, '5600-FD-INTEREST', 'Interest on fixed deposits', 'EXPENSE', 'KES'),
                  (?::uuid, '2620-WHT-FD-INTEREST', 'Withholding tax on deposit interest', 'LIABILITY', 'KES')
                """, T, T, T, T);
        // TEST-ONLY: December is open so the book can be closed day by day to the maturity date.
        if (owner().queryForObject("SELECT count(*) FROM ledger.accounting_period WHERE tenant_id = ?::uuid AND period_id = '2026-12'",
                Integer.class, T) == 0) {
            owner().update("""
                    INSERT INTO ledger.accounting_period (tenant_id, period_id, start_date, end_date)
                    VALUES (?::uuid, '2026-12', date '2026-12-01', date '2026-12-31')
                    """, T);
        }
    }

    Map<String, Object> member(String lastName) {
        return owner().queryForMap("""
                SELECT c.customer_number, a.account_number FROM customer.customer c
                  JOIN savings.deposit_account a ON a.tenant_id = c.tenant_id AND a.customer_id = c.customer_id
                 WHERE c.tenant_id = ?::uuid AND c.last_name = ?
                """, T, lastName);
    }

    BigDecimal balance(String accountNumber) {
        return owner().queryForObject("""
                SELECT b.ledger_balance FROM savings.account_balance b
                  JOIN savings.deposit_account a ON a.tenant_id = b.tenant_id AND a.account_id = b.account_id
                 WHERE a.account_number = ?
                """, BigDecimal.class, accountNumber);
    }

    LocalDate businessDate() {
        return owner().queryForObject("SELECT current_date_ FROM tenancy.business_date WHERE tenant_id = ?::uuid", LocalDate.class, T);
    }

    String closeDay() throws Exception {
        return mvc.perform(post("/api/v1/end-of-day/run").with(OPS)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    @Test
    void placeWithdrawEarlyMatureAndRollOver() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        seed();
        Map<String, Object> m1 = member("Load1");
        Map<String, Object> m2 = member("Load2");
        Map<String, Object> m10 = member("Load10");
        String c1 = (String) m1.get("customer_number");
        String a1 = (String) m1.get("account_number");
        String c2 = (String) m2.get("customer_number");
        String a2 = (String) m2.get("account_number");

        // Products: finance only; account classes checked by the database; early rate at most the rate.
        String product = """
            {"name":"One month fixed","termMonths":1,"ratePct":"12","earlyRatePct":"%s","earlyWithdrawalAllowed":true,
             "minAmount":"1000","maxAmount":"500000","taxRatePct":"15","principalAccount":"%s",
             "interestPayableAccount":"2610-FD-INTEREST-PAYABLE","interestExpenseAccount":"5600-FD-INTEREST",
             "taxAccount":"2620-WHT-FD-INTEREST","status":"ACTIVE"}
            """;
        mvc.perform(put(P + "/products/FD-1M").with(TELLER).contentType(MediaType.APPLICATION_JSON)
                .content(product.formatted("6", "2600-FIXED-DEPOSITS"))).andExpect(status().isForbidden());
        mvc.perform(put(P + "/products/FD-1M").with(FIN).contentType(MediaType.APPLICATION_JSON)
                .content(product.formatted("6", "5600-FD-INTEREST")))
            .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("TERMDEP_PRODUCT_INVALID"));
        mvc.perform(put(P + "/products/FD-1M").with(FIN).contentType(MediaType.APPLICATION_JSON)
                .content(product.formatted("13", "2600-FIXED-DEPOSITS"))).andExpect(status().isBadRequest());
        mvc.perform(put(P + "/products/FD-1M").with(FIN).contentType(MediaType.APPLICATION_JSON)
                .content(product.formatted("6", "2600-FIXED-DEPOSITS")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.ratePct").value("12.00")).andExpect(jsonPath("$.currency").value("KES"));
        mvc.perform(get(P + "/products").with(TELLER)).andExpect(status().isOk()).andExpect(jsonPath("$[0].productCode").value("FD-1M"));

        // Placements: own account, within limits, enough money; a replay places once.
        String place = "{\"idempotencyKey\":\"%s\",\"customerNumber\":\"%s\",\"accountNumber\":\"%s\",\"productCode\":\"FD-1M\","
                + "\"amount\":\"%s\",\"maturityInstruction\":\"%s\"}";
        mvc.perform(post(P + "/deposits").with(FIN).contentType(MediaType.APPLICATION_JSON)
                .content(place.formatted("pa", c1, a1, "5000", "PAYOUT"))).andExpect(status().isForbidden());
        mvc.perform(post(P + "/deposits").with(TELLER).contentType(MediaType.APPLICATION_JSON)
                .content(place.formatted("pa", c1, a1, "500", "PAYOUT"))).andExpect(status().isBadRequest());
        mvc.perform(post(P + "/deposits").with(TELLER).contentType(MediaType.APPLICATION_JSON)
                .content(place.formatted("pa", c1, a2, "5000", "PAYOUT"))).andExpect(status().isBadRequest());
        mvc.perform(post(P + "/deposits").with(TELLER).contentType(MediaType.APPLICATION_JSON)
                .content(place.formatted("px", m10.get("customer_number"), m10.get("account_number"), "1000", "PAYOUT")))
            .andExpect(status().isConflict());
        String a = mvc.perform(post(P + "/deposits").with(TELLER).contentType(MediaType.APPLICATION_JSON)
                .content(place.formatted("pa", c1, a1, "5000", "PAYOUT")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.deposit.status").value("ACTIVE"))
            .andExpect(jsonPath("$.deposit.startDate").value("2026-11-28")).andExpect(jsonPath("$.deposit.maturityDate").value("2026-12-28"))
            .andExpect(jsonPath("$.atMaturity.nights").value(30)).andExpect(jsonPath("$.atMaturity.interest").value("49.32"))
            .andExpect(jsonPath("$.atMaturity.tax").value("7.40")).andExpect(jsonPath("$.atMaturity.total").value("5041.92"))
            .andExpect(jsonPath("$.earlyToday.interest").value("0.00"))
            .andReturn().getResponse().getContentAsString();
        String depositA = JsonPath.read(a, "$.deposit.depositNumber");
        mvc.perform(post(P + "/deposits").with(TELLER).contentType(MediaType.APPLICATION_JSON)
                .content(place.formatted("pa", c1, a1, "5000", "PAYOUT")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.deposit.depositNumber").value(depositA));
        String b = mvc.perform(post(P + "/deposits").with(OFFICER).contentType(MediaType.APPLICATION_JSON)
                .content(place.formatted("pb", c1, a1, "2000", "PAYOUT"))).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String depositB = JsonPath.read(b, "$.deposit.depositNumber");
        mvc.perform(put(P + "/deposits/" + depositB + "/instruction").with(MANAGER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"maturityInstruction\":\"ROLLOVER_ALL\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.deposit.maturityInstruction").value("ROLLOVER_ALL"));
        String c = mvc.perform(post(P + "/deposits").with(MANAGER).contentType(MediaType.APPLICATION_JSON)
                .content(place.formatted("pc", c2, a2, "10000", "PAYOUT"))).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String depositC = JsonPath.read(c, "$.deposit.depositNumber");
        mvc.perform(get(P + "/members/" + c1).with(TELLER)).andExpect(status().isOk())
            .andExpect(jsonPath("$.deposits.length()").value(2)).andExpect(jsonPath("$.activePrincipal").value("7000.00"));

        // Fixed deposits are control accounts: no manual journals.
        mvc.perform(post("/api/v1/accounting/manual-journals").with(FIN).contentType(MediaType.APPLICATION_JSON).content("""
                {"idempotencyKey":"mj-fd","businessDate":"2026-11-28","narrative":"Deposit booked by hand",
                 "lines":[{"accountCode":"5600-FD-INTEREST","side":"DEBIT","amount":"100.00","branchCode":"NBO01"},
                          {"accountCode":"2600-FIXED-DEPOSITS","side":"CREDIT","amount":"100.00","branchCode":"NBO01"}]}
                """)).andExpect(status().isBadRequest());

        // Close 28 Nov: one night accrues (A 1.64, B 0.66, C 3.29).
        String first = closeDay();
        assertEquals(3, (int) JsonPath.read(first, "$.fixedDepositsAccrued"), first);
        assertEquals("5.59", JsonPath.read(first, "$.fixedDepositInterestAccrued"), first);
        mvc.perform(get("/api/v1/accounting/reconciliation").with(FIN)).andExpect(status().isOk())
            .andExpect(jsonPath("$.differences").value(0))
            .andExpect(jsonPath("$.results[?(@.checkCode == 'TERM_DEPOSIT_PRINCIPAL')].ledgerBalance").value(hasItem("17000.00")))
            .andExpect(jsonPath("$.results[?(@.checkCode == 'TERM_DEPOSIT_INTEREST')].ledgerBalance").value(hasItem("5.59")));

        // Early withdrawal of C on 29 Nov: asked by a teller, not decided by them; one night at 6% = 1.64, tax 0.25.
        String asked = mvc.perform(post(P + "/deposits/" + depositC + "/early-withdrawals").with(TELLER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\":\"ec\",\"reason\":\"Member needs the money for school fees\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REQUESTED"))
            .andExpect(jsonPath("$.quoteToday.interest").value("1.64")).andExpect(jsonPath("$.quoteToday.total").value("10001.39"))
            .andReturn().getResponse().getContentAsString();
        String requestId = JsonPath.read(asked, "$.requestId");
        mvc.perform(post(P + "/deposits/" + depositC + "/early-withdrawals").with(OFFICER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\":\"ec2\",\"reason\":\"Asked twice by mistake here\"}")).andExpect(status().isConflict());
        mvc.perform(post(P + "/early-withdrawals/" + requestId + "/approve").with(TELLER).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isForbidden());
        mvc.perform(post(P + "/early-withdrawals/" + requestId + "/reject").with(MANAGER).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest());
        BigDecimal before = balance(a2);
        mvc.perform(post(P + "/early-withdrawals/" + requestId + "/approve").with(MANAGER).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        assertEquals(0, new BigDecimal("10001.39").compareTo(balance(a2).subtract(before)));
        mvc.perform(get(P + "/deposits/" + depositC).with(TELLER)).andExpect(status().isOk())
            .andExpect(jsonPath("$.deposit.status").value("WITHDRAWN_EARLY")).andExpect(jsonPath("$.closure.kind").value("EARLY"))
            .andExpect(jsonPath("$.closure.accruedBefore").value("3.29")).andExpect(jsonPath("$.closure.interestEarned").value("1.64"))
            .andExpect(jsonPath("$.closure.tax").value("0.25")).andExpect(jsonPath("$.closure.toSavings").value("10001.39"));
        mvc.perform(post(P + "/early-withdrawals/" + requestId + "/approve").with(SUP).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isConflict());

        // Close every day until A and B mature at the close of 27 Dec.
        while (businessDate().isBefore(LocalDate.of(2026, 12, 27))) {
            closeDay();
        }
        mvc.perform(get(P + "/portfolio?days=1").with(FIN)).andExpect(status().isOk())
            .andExpect(jsonPath("$.active").value(2)).andExpect(jsonPath("$.principal").value("7000.00"))
            .andExpect(jsonPath("$.maturingSoon.length()").value(2));
        BigDecimal beforeMaturity = balance(a1);
        String last = closeDay();
        assertEquals(2, (int) JsonPath.read(last, "$.fixedDepositsMatured"), last);
        assertEquals(1, (int) JsonPath.read(last, "$.fixedDepositsRolledOver"), last);
        assertEquals(0, new BigDecimal("5041.92").compareTo(balance(a1).subtract(beforeMaturity)));
        mvc.perform(get(P + "/deposits/" + depositA).with(TELLER)).andExpect(status().isOk())
            .andExpect(jsonPath("$.deposit.status").value("MATURED")).andExpect(jsonPath("$.closure.interestEarned").value("49.32"))
            .andExpect(jsonPath("$.closure.accruedBefore").value("49.32")).andExpect(jsonPath("$.closure.tax").value("7.40"));
        String rolled = mvc.perform(get(P + "/deposits/" + depositB).with(TELLER)).andExpect(status().isOk())
            .andExpect(jsonPath("$.deposit.status").value("ROLLED_OVER")).andExpect(jsonPath("$.closure.interestEarned").value("19.73"))
            .andExpect(jsonPath("$.closure.newPrincipal").value("2016.77")).andReturn().getResponse().getContentAsString();
        String depositB2 = JsonPath.read(rolled, "$.closure.newDepositNumber");
        mvc.perform(get(P + "/deposits/" + depositB2).with(TELLER)).andExpect(status().isOk())
            .andExpect(jsonPath("$.deposit.principal").value("2016.77")).andExpect(jsonPath("$.deposit.startDate").value("2026-12-28"))
            .andExpect(jsonPath("$.deposit.maturityDate").value("2027-01-28")).andExpect(jsonPath("$.deposit.rolledFromNumber").value(depositB))
            .andExpect(jsonPath("$.deposit.maturityInstruction").value("ROLLOVER_ALL"));

        // The entry for A: principal and interest payable out, tax withheld, the member's savings in.
        List<Map<String, Object>> lines = owner().queryForList("""
                SELECT l.account_code, l.side, l.amount FROM ledger.journal_line l
                  JOIN termdep.closure c ON c.tenant_id = l.tenant_id AND c.entry_id = l.entry_id
                  JOIN termdep.deposit d ON d.tenant_id = c.tenant_id AND d.deposit_id = c.deposit_id
                 WHERE d.deposit_number = ? ORDER BY l.line_no
                """, depositA);
        assertEquals(4, lines.size(), lines.toString());
        mvc.perform(get("/api/v1/accounting/reconciliation").with(FIN)).andExpect(status().isOk())
            .andExpect(jsonPath("$.differences").value(0))
            .andExpect(jsonPath("$.results[?(@.checkCode == 'TERM_DEPOSIT_PRINCIPAL')].ledgerBalance").value(hasItem("2016.77")))
            .andExpect(jsonPath("$.results[?(@.checkCode == 'TERM_DEPOSIT_INTEREST')].ledgerBalance").value(hasItem("0.00")));
        // Interest expense is what members earned: A 49.32 + B 19.73 + C 1.64.
        BigDecimal expense = owner().queryForObject("""
                SELECT sum(CASE side WHEN 'D' THEN amount ELSE -amount END) FROM ledger.journal_line
                 WHERE tenant_id = ?::uuid AND account_code = '5600-FD-INTEREST'
                """, BigDecimal.class, T);
        assertEquals(0, new BigDecimal("70.69").compareTo(expense), expense.toPlainString());
    }
}
