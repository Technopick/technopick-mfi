package ke.co.technopick.mfi.core.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
 * M-Pesa statement reconciliation (increment 38, ADR-0038) on the TEST-ONLY seed: paybill 600993
 * with clearing account 1120-MPESA-C2B-CLEARING, business date 28 Nov. Three receipts arrive
 * through C2B and finance books the day's charges by manual journal; the uploaded statement shows
 * one receipt as recorded, one for a different amount, two charges, a failed line, and does not
 * show the third receipt.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class MpesaStatementIntegrationTest {

    static final String T = "00000000-0000-0000-0000-0000000000c3";
    static final String S = "/api/v1/payments/mpesa/statements";

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

    /** Minimal JSON for flat maps of strings and lists of integers. */
    static String json(Map<String, Object> body) {
        return body.entrySet().stream().map(e -> str(e.getKey()) + ":" + (e.getValue() instanceof List<?> l
                ? l.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]")) : str(String.valueOf(e.getValue()))))
            .collect(Collectors.joining(",", "{", "}"));
    }

    static String str(String v) {
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    static JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
    }

    static JwtRequestPostProcessor as(String subject, String role) {
        return jwt().jwt(j -> j.subject(subject).claim("tenant_id", T)).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    static final JwtRequestPostProcessor FIN1 = as("fin-1", "FINANCE");
    static final JwtRequestPostProcessor FIN2 = as("fin-2", "FINANCE");
    static final JwtRequestPostProcessor SUP = as("sup-1", "SUPERVISOR");
    static final JwtRequestPostProcessor SWITCH = jwt().jwt(j -> j.subject("svc"))
        .authorities(new SimpleGrantedAuthority("ROLE_INTEGRATION"));

    void seed() throws Exception {
        String sql = new String(MpesaStatementIntegrationTest.class.getResourceAsStream("/perf/perf-seed-TEST-ONLY.sql").readAllBytes(),
                StandardCharsets.UTF_8).replace("{T}", T).replace("{NS}", "10").replace("{NL}", "10");
        Path file = Files.createTempFile("recon-seed", ".sql");
        Files.writeString(file, sql);
        PG.copyFileToContainer(MountableFile.forHostPath(file), "/tmp/seed.sql");
        var r = PG.execInContainer("psql", "-v", "ON_ERROR_STOP=1", "-q", "-U", PG.getUsername(), "-d", PG.getDatabaseName(),
                "-f", "/tmp/seed.sql");
        assertEquals(0, r.getExitCode(), r.getStderr());
        owner().update("INSERT INTO ledger.gl_account (tenant_id, account_code, name, account_class, currency) VALUES "
                + "(?::uuid, '5310-MPESA-CHARGES', 'M-Pesa charges', 'EXPENSE', 'KES')", T);
    }

    void receipt(String transId, String amount, String time) throws Exception {
        mvc.perform(post("/api/v1/payments/mpesa/c2b").with(SWITCH).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.<String, Object>of("tenantId", T, "transId", transId, "shortCode", "600993",
                        "amount", amount, "currency", "KES", "billRef", "NO-SUCH-ACCOUNT", "msisdnMasked", "2547****001",
                        "transTime", time))))
            .andExpect(status().isOk());
    }

    ResultActions upload(JwtRequestPostProcessor who, String date, String fileName, String csv) throws Exception {
        return mvc.perform(post(S).with(who).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.<String, Object>of("accountKind", "PAYBILL", "shortCode", "600993", "statementDate", date,
                        "fileName", fileName, "csv", csv))));
    }

    ResultActions act(JwtRequestPostProcessor who, String id, String action, Map<String, Object> body) throws Exception {
        return mvc.perform(post(S + "/" + id + "/" + action).with(who).contentType(MediaType.APPLICATION_JSON)
                .content(json(body)));
    }

    static final String HEADER = "Receipt No.,Completion Time,Initiation Time,Details,Transaction Status,Paid In,Withdrawn,Balance,"
            + "Balance Confirmed,Reason Type,Other Party Info,Linked Transaction ID,A/C No.\n";

    static final String DAY_28 = "Short Code:,600993\n\n" + HEADER
            + "QAR0000001,2026-11-28 09:00:00,,Pay Bill from 254700000001 - TEST ONLY Acc. NO-SUCH-ACCOUNT,Completed,1000.00,,1000.00,true,Pay Bill,254700000001 - TEST ONLY,,\n"
            + "QAR0000001,2026-11-28 09:00:00,,Pay Bill Charge,Completed,,-15.00,985.00,true,Pay Bill Charge,,,\n"
            + "QAR0000002,2026-11-28 10:00:00,,Pay Bill from 254700000002,Completed,550.00,,1535.00,true,Pay Bill,,,\n"
            + "QAR0000002,2026-11-28 10:00:00,,Pay Bill Charge,Completed,,-18.00,1517.00,true,Pay Bill Charge,,,\n"
            + "QAR0000009,2026-11-28 11:00:00,,Pay Bill from 254700000009,Failed,300.00,,,false,Pay Bill,,,\n";

    @Test
    void financeReconcilesTheDayAndASecondPersonSignsItOff() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        seed();
        receipt("QAR0000001", "1000.00", "2026-11-28T09:00:00+03:00");
        receipt("QAR0000002", "500.00", "2026-11-28T10:00:00+03:00");
        receipt("QAR0000003", "200.00", "2026-11-28T12:00:00+03:00");
        // Finance books the day's M-Pesa charges; another person posts the journal.
        String journal = JsonPath.read(mvc.perform(post("/api/v1/accounting/manual-journals").with(FIN1)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"idempotencyKey":"charges-28","businessDate":"2026-11-28","narrative":"M-Pesa paybill charges 28 Nov",
                         "lines":[{"accountCode":"5310-MPESA-CHARGES","side":"DEBIT","amount":"33.00","branchCode":"NBO01"},
                                  {"accountCode":"1120-MPESA-C2B-CLEARING","side":"CREDIT","amount":"33.00","branchCode":"NBO01"}]}
                        """)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.journalId");
        String chargesEntry = JsonPath.read(mvc.perform(post("/api/v1/accounting/manual-journals/" + journal + "/post").with(FIN2)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.entryId");

        // Who may do what; what cannot be uploaded.
        mvc.perform(get(S).with(as("ops-1", "OPERATIONS"))).andExpect(status().isForbidden());
        upload(SUP, "2026-11-28", "day.csv", DAY_28).andExpect(status().isForbidden());
        mvc.perform(get(S + "/accounts").with(SUP)).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].shortCode").value("600993")).andExpect(jsonPath("$[0].clearingAccount").value("1120-MPESA-C2B-CLEARING"));
        upload(FIN1, "2026-11-28", "x.csv", "not,a,statement\n").andExpect(status().isBadRequest());
        upload(FIN1, "2026-11-27", "day.csv", DAY_28).andExpect(status().isBadRequest());
        upload(FIN1, "2026-11-29", "day.csv", DAY_28.replace("2026-11-28", "2026-11-29")).andExpect(status().is(422))
            .andExpect(jsonPath("$.code").value("STATEMENT_INVALID"));

        // Upload: one receipt matches, one differs in amount, two charges are not on the ledger yet.
        String body = upload(FIN1, "2026-11-28", "paybill-2026-11-28.csv", DAY_28).andExpect(status().isOk())
            .andExpect(jsonPath("$.upload.status").value("OPEN"))
            .andExpect(jsonPath("$.upload.lineCount").value(5))
            .andExpect(jsonPath("$.upload.openingBalance").value("0.00"))
            .andExpect(jsonPath("$.upload.closingBalance").value("1517.00"))
            .andExpect(jsonPath("$.upload.balanceChainOk").value(true))
            .andExpect(jsonPath("$.upload.matched").value(1))
            .andExpect(jsonPath("$.upload.exceptions").value(3))
            .andExpect(jsonPath("$.upload.ignored").value(1))
            .andExpect(jsonPath("$.check.ledgerBalance").value("1667.00"))
            .andExpect(jsonPath("$.check.difference").value("-150.00"))
            .andExpect(jsonPath("$.check.ledgerOnlyCount").value(3))
            .andExpect(jsonPath("$.check.ledgerOnlyAmount").value("667.00"))
            .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.upload.uploadId");
        upload(FIN1, "2026-11-28", "paybill-2026-11-28.csv", DAY_28).andExpect(status().isConflict());
        upload(FIN1, "2026-11-28", "other.csv", DAY_28 + "\n").andExpect(status().isConflict());

        mvc.perform(get(S + "/" + id + "/lines?status=EXCEPTION").with(SUP)).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(3))
            .andExpect(jsonPath("$[0].lineNo").value(2)).andExpect(jsonPath("$[0].category").value("CHARGE"))
            .andExpect(jsonPath("$[1].exceptionCode").value("AMOUNT_MISMATCH"));
        mvc.perform(get(S + "/" + id + "/lines?status=MATCHED").with(FIN1)).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].receiptNo").value("QAR0000001"))
            .andExpect(jsonPath("$[0].details").value("Pay Bill from 2547****001 Acc. NO-SUCH-ACCOUNT"))
            .andExpect(jsonPath("$[0].otherParty").value("2547****001"));

        // Resolve: the charges journal must account for both charge lines together.
        act(FIN1, id, "link", Map.of("lineNos", List.of(2), "journalEntryId", chargesEntry, "note", "Charges booked by journal"))
            .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("STATEMENT_LINK_INVALID"));
        act(FIN1, id, "link", Map.of("lineNos", List.of(2, 4), "journalEntryId", chargesEntry, "note", "Charges booked by journal"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.upload.exceptions").value(1))
            .andExpect(jsonPath("$.check.ledgerOnlyCount").value(2));
        act(FIN1, id, "explain", Map.of("lineNos", List.of(2), "note", "Already linked to the charges"))
            .andExpect(status().isConflict());

        // Sign-off waits for end of day and for every exception.
        act(SUP, id, "sign-off", Map.of("note", "Reviewed")).andExpect(status().is(422))
            .andExpect(jsonPath("$.code").value("RECON_DAY_OPEN"));
        mvc.perform(post("/api/v1/end-of-day/run").with(as("ops-1", "OPERATIONS"))).andExpect(status().isOk());
        act(SUP, id, "sign-off", Map.of("note", "Reviewed")).andExpect(status().is(422))
            .andExpect(jsonPath("$.code").value("RECON_EXCEPTIONS_OPEN"));
        act(FIN1, id, "explain", Map.of("lineNos", List.of(3), "note", "Member paid 550; we recorded 500; raised with Safaricom"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.upload.exceptions").value(0));
        act(FIN1, id, "sign-off", Map.of("note", "Reviewed")).andExpect(status().isConflict());
        act(SUP, id, "sign-off", Map.of()).andExpect(status().is(422)).andExpect(jsonPath("$.code").value("RECON_NOTE_REQUIRED"));
        act(SUP, id, "sign-off", Map.of("note", "QAR0000003 expected on the 29 Nov statement; QAR0000002 query open"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.upload.status").value("SIGNED_OFF"))
            .andExpect(jsonPath("$.upload.signedOffBy").value("user:sup-1"))
            .andExpect(jsonPath("$.upload.ledgerOnlyCount").value(2))
            .andExpect(jsonPath("$.upload.ledgerOnlyAmount").value("700.00"))
            .andExpect(jsonPath("$.check.live").value(false))
            .andExpect(jsonPath("$.check.difference").value("-150.00"));
        act(FIN1, id, "withdraw", Map.of("reason", "Wrong statement uploaded")).andExpect(status().isConflict());

        // Next day: a receipt on the statement that the platform has not applied yet; re-matching finds it later.
        String day29 = HEADER + "QAR0000005,2026-11-29 08:00:00,,Pay Bill from 254700000005,Completed,700.00,,2217.00,true,Pay Bill,,,\n";
        String next = JsonPath.read(upload(FIN2, "2026-11-29", "paybill-2026-11-29.csv", day29).andExpect(status().isOk())
            .andExpect(jsonPath("$.upload.exceptions").value(1)).andReturn().getResponse().getContentAsString(), "$.upload.uploadId");
        receipt("QAR0000005", "700.00", "2026-11-29T08:00:00+03:00");
        mvc.perform(post(S + "/" + next + "/rematch").with(FIN2)).andExpect(status().isOk())
            .andExpect(jsonPath("$.matched").value(1)).andExpect(jsonPath("$.stillOpen").value(0));
        act(FIN2, next, "withdraw", Map.of("reason", "Uploaded before the day was complete")).andExpect(status().isOk())
            .andExpect(jsonPath("$.upload.status").value("WITHDRAWN"));
        upload(FIN2, "2026-11-29", "paybill-2026-11-29.csv", day29).andExpect(status().isOk())
            .andExpect(jsonPath("$.upload.matched").value(1));
        mvc.perform(get(S + "?status=SIGNED_OFF").with(SUP)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }
}
