package ke.co.technopick.mfi.core.regulatory;

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
 * SASRA prudential returns (increment 30, ADR-0030) over a small TEST-ONLY book whose figures are
 * worked out by hand: 10 members with 365,855.00 of savings; 10 loans of 12,000.00, six first due
 * on 1 Dec (performing) and four unpaid since 1 Oct (58 days past due on 28 Nov: substandard);
 * members' shares 1,500,000, a statutory reserve 400,000 and a fair value reserve 100,000 (excluded
 * from core capital), bank 1,750,000 with an overdraft of 50,000, treasury bills 300,000 and an
 * allowance for loan loss of 5,000.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class PrudentialReturnIntegrationTest {

    static final String T = "00000000-0000-0000-0000-0000000000c3"; // the seed's tenant id placeholder value
    static final String P = "/api/v1/regulatory";

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

    static final JwtRequestPostProcessor FINANCE = as("finance-1", "FINANCE");

    void seed() throws Exception {
        String sql = new String(PrudentialReturnIntegrationTest.class.getResourceAsStream("/perf/perf-seed-TEST-ONLY.sql").readAllBytes(),
                StandardCharsets.UTF_8).replace("{T}", T).replace("{NS}", "10").replace("{NL}", "10");
        Path file = Files.createTempFile("prudential-seed", ".sql");
        Files.writeString(file, sql);
        PG.copyFileToContainer(MountableFile.forHostPath(file), "/tmp/seed.sql");
        var r = PG.execInContainer("psql", "-v", "ON_ERROR_STOP=1", "-q", "-U", PG.getUsername(), "-d", PG.getDatabaseName(),
                "-f", "/tmp/seed.sql");
        assertEquals(0, r.getExitCode(), r.getStderr());
        owner().execute("""
            INSERT INTO ledger.gl_account (tenant_id, account_code, name, account_class, currency) VALUES
              ('%1$s', '3000-MEMBER-SHARES', 'Members'' shares', 'EQUITY', 'KES'),
              ('%1$s', '3100-STATUTORY-RESERVE', 'Statutory reserve', 'EQUITY', 'KES'),
              ('%1$s', '3200-FAIR-VALUE-RESERVE', 'Fair value reserve', 'EQUITY', 'KES'),
              ('%1$s', '1300-BANK', 'Bank current account', 'ASSET', 'KES'),
              ('%1$s', '1310-TREASURY-BILLS', 'Treasury bills', 'ASSET', 'KES'),
              ('%1$s', '1600-LOAN-LOSS-ALLOWANCE', 'Allowance for loan loss', 'ASSET', 'KES'),
              ('%1$s', '2200-BANK-OVERDRAFT', 'Bank overdraft', 'LIABILITY', 'KES'),
              ('%1$s', '5400-LOAN-LOSS-EXPENSE', 'Loan loss expense', 'EXPENSE', 'KES');
            INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source, correlation_id, actor) VALUES
              ('%1$s', 'aaaaaaaa-0000-0000-0000-000000000001', 'prudential-test-capital', '2026-11-02', 'MIGRATION', 'test', 'test:migration'),
              ('%1$s', 'aaaaaaaa-0000-0000-0000-000000000002', 'prudential-test-tbills', '2026-11-03', 'MIGRATION', 'test', 'test:migration'),
              ('%1$s', 'aaaaaaaa-0000-0000-0000-000000000003', 'prudential-test-allowance', '2026-11-04', 'MIGRATION', 'test', 'test:migration'),
              ('%1$s', 'aaaaaaaa-0000-0000-0000-000000000004', 'prudential-test-overdraft', '2026-11-05', 'MIGRATION', 'test', 'test:migration');
            INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount, currency, branch_code) VALUES
              ('%1$s', 'aaaaaaaa-0000-0000-0000-000000000001', 1, '1300-BANK', 'D', 2000000, 'KES', 'NBO01'),
              ('%1$s', 'aaaaaaaa-0000-0000-0000-000000000001', 2, '3000-MEMBER-SHARES', 'C', 1500000, 'KES', 'NBO01'),
              ('%1$s', 'aaaaaaaa-0000-0000-0000-000000000001', 3, '3100-STATUTORY-RESERVE', 'C', 400000, 'KES', 'NBO01'),
              ('%1$s', 'aaaaaaaa-0000-0000-0000-000000000001', 4, '3200-FAIR-VALUE-RESERVE', 'C', 100000, 'KES', 'NBO01'),
              ('%1$s', 'aaaaaaaa-0000-0000-0000-000000000002', 1, '1310-TREASURY-BILLS', 'D', 300000, 'KES', 'NBO01'),
              ('%1$s', 'aaaaaaaa-0000-0000-0000-000000000002', 2, '1300-BANK', 'C', 300000, 'KES', 'NBO01'),
              ('%1$s', 'aaaaaaaa-0000-0000-0000-000000000003', 1, '5400-LOAN-LOSS-EXPENSE', 'D', 5000, 'KES', 'NBO01'),
              ('%1$s', 'aaaaaaaa-0000-0000-0000-000000000003', 2, '1600-LOAN-LOSS-ALLOWANCE', 'C', 5000, 'KES', 'NBO01'),
              ('%1$s', 'aaaaaaaa-0000-0000-0000-000000000004', 1, '1300-BANK', 'D', 50000, 'KES', 'NBO01'),
              ('%1$s', 'aaaaaaaa-0000-0000-0000-000000000004', 2, '2200-BANK-OVERDRAFT', 'C', 50000, 'KES', 'NBO01');
            """.formatted(T));
    }

    static String mapping(String... pairs) {
        StringBuilder b = new StringBuilder("{\"changes\":[");
        for (int i = 0; i < pairs.length; i += 2) {
            b.append(i == 0 ? "" : ",").append("{\"accountCode\":\"").append(pairs[i]).append("\",\"line\":")
                .append(pairs[i + 1] == null ? "null" : "\"" + pairs[i + 1] + "\"").append('}');
        }
        return b.append("]}").toString();
    }

    static String settings(int watchMax, boolean surplus) {
        return """
            {"minCoreCapital":"10000000","minCoreToAssetsPct":"10","minInstitutionalToAssetsPct":"8","minCoreToDepositsPct":"8",
             "minLiquidityPct":"15","watchMaxDpd":%d,"substandardMaxDpd":180,"doubtfulMaxDpd":360,"provisionPerformingPct":"1",
             "provisionWatchPct":"5","provisionSubstandardPct":"25","provisionDoubtfulPct":"50","provisionLossPct":"100",
             "includeCurrentYearSurplus":%s}
            """.formatted(watchMax, surplus);
    }

    @Test
    void prudentialReturnsFromTheLedgerAndLoanBook() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        seed();

        // Nothing closed yet: there is no return to prepare.
        mvc.perform(get(P + "/prudential").with(FINANCE)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(containsString("no business day has been closed")));
        mvc.perform(post("/api/v1/end-of-day/run").with(as("ops-1", "OPERATIONS"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.closedDate").value("2026-11-28"));
        mvc.perform(get(P + "/prudential?asOf=2026-11-29").with(FINANCE)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(containsString("last closed business day is 2026-11-28")));

        // Who may do what.
        mvc.perform(get(P + "/prudential").with(as("teller-1", "TELLER"))).andExpect(status().isForbidden());
        mvc.perform(get(P + "/prudential").with(as("sup-1", "SUPERVISOR"))).andExpect(status().isOk());
        mvc.perform(put(P + "/settings").with(as("sup-1", "SUPERVISOR")).contentType(MediaType.APPLICATION_JSON)
                .content(settings(30, false))).andExpect(status().isForbidden());
        mvc.perform(put(P + "/mapping").with(as("sup-1", "SUPERVISOR")).contentType(MediaType.APPLICATION_JSON)
                .content(mapping("1300-BANK", "BANK_BALANCES"))).andExpect(status().isForbidden());

        // Before mapping: regulation defaults, zero capital, a note saying why.
        mvc.perform(get(P + "/prudential").with(FINANCE)).andExpect(status().isOk())
            .andExpect(jsonPath("$.asOf").value("2026-11-28"))
            .andExpect(jsonPath("$.settings.regulationDefaults").value(true))
            .andExpect(jsonPath("$.capital.coreCapital").value("0.00"))
            .andExpect(jsonPath("$.notes").value(hasItem(containsString("No ledger accounts are mapped"))));

        // Finance maps the accounts; a mapping that would turn deposits into capital is refused.
        mvc.perform(put(P + "/mapping").with(FINANCE).contentType(MediaType.APPLICATION_JSON)
                .content(mapping("2110-SAVINGS-CONTROL", "SHARE_CAPITAL"))).andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("MAPPING_CLASS_MISMATCH"));
        mvc.perform(put(P + "/mapping").with(FINANCE).contentType(MediaType.APPLICATION_JSON)
                .content(mapping("9999-NOPE", "CASH"))).andExpect(status().isBadRequest());
        mvc.perform(put(P + "/mapping").with(FINANCE).contentType(MediaType.APPLICATION_JSON)
                .content(mapping("3000-MEMBER-SHARES", "SHARE_CAPITAL", "3100-STATUTORY-RESERVE", "DISCLOSED_RESERVES",
                        "3200-FAIR-VALUE-RESERVE", "EXCLUDED_RESERVES", "2110-SAVINGS-CONTROL", "MEMBER_DEPOSITS",
                        "2960-WHT-PAYABLE", "SHORT_TERM_LIABILITIES", "1000-VAULT-NBO01", "CASH", "1000-VAULT-MSA01", "CASH",
                        "1300-BANK", "BANK_BALANCES", "2200-BANK-OVERDRAFT", "BANK_AMOUNTS_OWED",
                        "1310-TREASURY-BILLS", "GOVERNMENT_SECURITIES", "1600-LOAN-LOSS-ALLOWANCE", "LOAN_LOSS_ALLOWANCE",
                        "1120-MPESA-C2B-CLEARING", "CASH")))
            .andExpect(status().isOk());
        // ... and one mapping taken back again.
        mvc.perform(put(P + "/mapping").with(FINANCE).contentType(MediaType.APPLICATION_JSON)
                .content(mapping("1120-MPESA-C2B-CLEARING", null))).andExpect(status().isOk())
            .andExpect(jsonPath("$.accounts[?(@.accountCode == '1120-MPESA-C2B-CLEARING')].line").value(hasItem((Object) null)));

        // Current-year earnings left out so every figure below is exact (interest accrued at end of day varies).
        mvc.perform(put(P + "/settings").with(FINANCE).contentType(MediaType.APPLICATION_JSON).content(settings(30, false)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.regulationDefaults").value(false))
            .andExpect(jsonPath("$.updatedBy").value("user:finance-1"));
        mvc.perform(put(P + "/settings").with(FINANCE).contentType(MediaType.APPLICATION_JSON).content(settings(200, false)))
            .andExpect(status().isBadRequest());

        var r = mvc.perform(get(P + "/prudential?asOf=2026-11-28").with(FINANCE)).andExpect(status().isOk())
            .andExpect(jsonPath("$.periodClosed").value(false))
            .andExpect(jsonPath("$.notes").value(hasItem(containsString("still open"))))
            // Capital adequacy: core = shares 1,500,000 + reserve 400,000; the fair value reserve is not counted.
            .andExpect(jsonPath("$.capital.coreCapital").value("1900000.00"))
            .andExpect(jsonPath("$.capital.institutionalCapital").value("400000.00"))
            .andExpect(jsonPath("$.capital.totalDeposits").value("365855.00"))
            .andExpect(jsonPath("$.capital.lines[?(@.line == 'EXCLUDED_RESERVES')].amount").value(hasItem("100000.00")))
            .andExpect(jsonPath("$.capital.lines[?(@.line == 'SHARE_CAPITAL')].accounts[0].accountCode").value(hasItem("3000-MEMBER-SHARES")))
            .andExpect(jsonPath("$.capital.checks[?(@.code == 'CORE_CAPITAL_MINIMUM')].met").value(hasItem(false)))
            .andExpect(jsonPath("$.capital.checks[?(@.code == 'CORE_TO_DEPOSITS')].valuePct").value(hasItem("519.33")))
            .andExpect(jsonPath("$.capital.checks[?(@.code == 'CORE_TO_DEPOSITS')].met").value(hasItem(true)))
            // Liquidity: vault 365,855 + bank 1,750,000 net of 50,000 owed + treasury bills 300,000, over deposits.
            .andExpect(jsonPath("$.liquidity.liquidAssets").value("2365855.00"))
            .andExpect(jsonPath("$.liquidity.depositsAndShortTermLiabilities").value("365855.00"))
            .andExpect(jsonPath("$.liquidity.checks[0].valuePct").value("646.66"))
            .andExpect(jsonPath("$.liquidity.checks[0].met").value(true))
            // Classification: 6 performing x 12,000 at 1%; 4 substandard x 12,000 at 25%.
            .andExpect(jsonPath("$.classification.totalLoans").value(10))
            .andExpect(jsonPath("$.classification.rows[0].loanClass").value("PERFORMING"))
            .andExpect(jsonPath("$.classification.rows[0].loans").value(6))
            .andExpect(jsonPath("$.classification.rows[0].requiredAllowance").value("720.00"))
            .andExpect(jsonPath("$.classification.rows[2].loanClass").value("SUBSTANDARD"))
            .andExpect(jsonPath("$.classification.rows[2].daysPastDue").value("31-180"))
            .andExpect(jsonPath("$.classification.rows[2].loans").value(4))
            .andExpect(jsonPath("$.classification.rows[2].outstandingPrincipal").value("48000.00"))
            .andExpect(jsonPath("$.classification.rows[2].requiredAllowance").value("12000.00"))
            .andExpect(jsonPath("$.classification.grossLoans").value("120000.00"))
            .andExpect(jsonPath("$.classification.requiredAllowance").value("12720.00"))
            .andExpect(jsonPath("$.classification.allowanceHeld.amount").value("5000.00"))
            .andExpect(jsonPath("$.classification.allowanceShortfall").value("7720.00"))
            .andExpect(jsonPath("$.classification.nonPerformingLoans").value("48000.00"))
            .andExpect(jsonPath("$.classification.nonPerformingRatioPct").value("40.00"))
            .andExpect(jsonPath("$.classification.ledgerLoanPrincipal").value("120000.00"))
            .andExpect(jsonPath("$.classification.unexplainedPrincipalDifference").value("0.00"))
            .andExpect(jsonPath("$.unmappedBalanceSheetAccounts[*].accountCode").value(hasItem("1200-LOANS-PRINCIPAL")))
            .andReturn().getResponse().getContentAsString();

        // Total assets agree with the balance sheet, an independent calculation over the same ledger.
        String sheet = mvc.perform(get("/api/v1/accounting/balance-sheet?asOf=2026-11-28").with(FINANCE)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertEquals(JsonPath.<String>read(sheet, "$.totals[0].totalAssets"), JsonPath.read(r, "$.capital.totalAssets"));
        // Interest still accrued on the four substandard loans equals what was accrued on them (nothing has been paid).
        BigDecimal accrued = owner().queryForObject("""
                SELECT COALESCE(sum(a.amount), 0) FROM lending.loan_accrual a JOIN lending.loan l
                    ON l.tenant_id = a.tenant_id AND l.loan_id = a.loan_id
                 WHERE l.loan_number IN ('LN-P0000003', 'LN-P0000004', 'LN-P0000008', 'LN-P0000009') AND a.business_date <= '2026-11-28'
                """, BigDecimal.class);
        assertEquals(0, accrued.compareTo(new BigDecimal(JsonPath.<String>read(r, "$.classification.interestAccruedOnNonPerforming"))));
        assertEquals(1, accrued.signum(), "the substandard loans have accrued interest");

        // The loans behind the substandard row.
        mvc.perform(get(P + "/prudential/loans?asOf=2026-11-28&loanClass=SUBSTANDARD").with(FINANCE)).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(4))
            .andExpect(jsonPath("$[0].loanNumber").value("LN-P0000003"))
            .andExpect(jsonPath("$[0].daysPastDue").value(58))
            .andExpect(jsonPath("$[0].outstandingPrincipal").value("12000.00"))
            .andExpect(jsonPath("$[0].requiredAllowance").value("3000.00"))
            .andExpect(jsonPath("$[3].loanNumber").value("LN-P0000009"));
        mvc.perform(get(P + "/prudential/loans?loanClass=NONSENSE").with(FINANCE)).andExpect(status().isBadRequest());

        // Wider watch band (a tenant setting): the four loans become "watch" at 5%; no shortfall against 5,000 held.
        mvc.perform(put(P + "/settings").with(FINANCE).contentType(MediaType.APPLICATION_JSON).content(settings(60, false)))
            .andExpect(status().isOk());
        mvc.perform(get(P + "/prudential").with(FINANCE)).andExpect(status().isOk())
            .andExpect(jsonPath("$.classification.rows[1].loanClass").value("WATCH"))
            .andExpect(jsonPath("$.classification.rows[1].daysPastDue").value("1-60"))
            .andExpect(jsonPath("$.classification.rows[1].loans").value(4))
            .andExpect(jsonPath("$.classification.requiredAllowance").value("3120.00"))
            .andExpect(jsonPath("$.classification.allowanceShortfall").value("0.00"))
            .andExpect(jsonPath("$.classification.nonPerformingRatioPct").value("0.00"));

        // Counting current-year earnings adds income less expenses to both capital figures.
        mvc.perform(put(P + "/settings").with(FINANCE).contentType(MediaType.APPLICATION_JSON).content(settings(30, true)))
            .andExpect(status().isOk());
        String withSurplus = mvc.perform(get(P + "/prudential").with(FINANCE)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        BigDecimal surplus = new BigDecimal(JsonPath.<java.util.List<String>>read(withSurplus,
                "$.capital.lines[?(@.line == 'CURRENT_YEAR_SURPLUS')].amount").get(0));
        assertEquals(0, new BigDecimal("1900000.00").add(surplus).compareTo(new BigDecimal(JsonPath.<String>read(withSurplus, "$.capital.coreCapital"))));
        assertEquals(0, new BigDecimal("400000.00").add(surplus)
            .compareTo(new BigDecimal(JsonPath.<String>read(withSurplus, "$.capital.institutionalCapital"))));
    }
}
