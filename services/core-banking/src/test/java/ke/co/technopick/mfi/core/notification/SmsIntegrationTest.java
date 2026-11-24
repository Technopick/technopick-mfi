package ke.co.technopick.mfi.core.notification;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ke.co.technopick.mfi.core.notification.application.NotificationService;
import ke.co.technopick.mfi.core.tenancy.persistence.TenantSession;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * SMS notifications (increment 35, ADR-0035) on the small TEST-ONLY book: member i owns savings
 * account i and loan LN-P000000i; business date 28 Nov 2026.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class SmsIntegrationTest {

    static final String T = "00000000-0000-0000-0000-0000000000c3";

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
    @Autowired
    NotificationService notifications;
    @Autowired
    TenantSession session;
    @Autowired
    PlatformTransactionManager transactions;

    MockMvc mvc;

    static JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
    }

    static JwtRequestPostProcessor as(String subject, String role) {
        return jwt().jwt(j -> j.subject(subject).claim("tenant_id", T)).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    static final JwtRequestPostProcessor INTEGRATION = jwt().jwt(j -> j.subject("svc").claim("azp", "mfi-integration"))
        .authorities(new SimpleGrantedAuthority("ROLE_INTEGRATION"));

    void seed() throws Exception {
        String sql = new String(SmsIntegrationTest.class.getResourceAsStream("/perf/perf-seed-TEST-ONLY.sql").readAllBytes(),
                StandardCharsets.UTF_8).replace("{T}", T).replace("{NS}", "10").replace("{NL}", "10");
        Path file = Files.createTempFile("sms-seed", ".sql");
        Files.writeString(file, sql);
        PG.copyFileToContainer(MountableFile.forHostPath(file), "/tmp/seed.sql");
        var r = PG.execInContainer("psql", "-v", "ON_ERROR_STOP=1", "-q", "-U", PG.getUsername(), "-d", PG.getDatabaseName(),
                "-f", "/tmp/seed.sql");
        assertEquals(0, r.getExitCode(), r.getStderr());
    }

    record Member(String customerNumber, String account, String loan, String msisdn) { }

    static Member member(int i) {
        Map<String, Object> r = owner().queryForMap("""
                SELECT c.customer_number, a.account_number, l.loan_number, c.msisdn
                  FROM lending.loan l JOIN customer.customer c ON c.tenant_id = l.tenant_id AND c.customer_id = l.customer_id
                  JOIN savings.deposit_account a ON a.tenant_id = c.tenant_id AND a.customer_id = c.customer_id
                 WHERE l.tenant_id = ?::uuid AND l.loan_number = ?
                """, T, "LN-P%07d".formatted(i));
        return new Member((String) r.get("customer_number"), (String) r.get("account_number"), (String) r.get("loan_number"),
                (String) r.get("msisdn"));
    }

    void pay(String transId, String billRef, String amount, String outcome) throws Exception {
        mvc.perform(post("/api/v1/payments/mpesa/c2b").with(INTEGRATION).contentType(MediaType.APPLICATION_JSON).content("""
                    {"tenantId":"%s","transId":"%s","shortCode":"600993","amount":"%s","currency":"KES","billRef":"%s",
                     "msisdnMasked":"2547*****000","transTime":"2026-11-28T10:15:00+03:00"}
                    """.formatted(T, transId, amount, billRef)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value(outcome));
    }

    static Map<String, Object> sms(String dedupeKey) {
        return owner().queryForMap("SELECT * FROM notify.sms_message WHERE tenant_id = ?::uuid AND dedupe_key = ?", T, dedupeKey);
    }

    List<Map<String, Object>> claim() throws Exception {
        String json = mvc.perform(post("/api/v1/notifications/sms/claim").with(INTEGRATION).contentType(MediaType.APPLICATION_JSON)
                .content("{\"limit\":10}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$");
    }

    String report(UUID id, String kind, String body) throws Exception {
        return mvc.perform(post("/api/v1/notifications/sms/" + id + "/" + kind).with(INTEGRATION).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn().getResponse().getContentAsString();
    }

    @Test
    void membersGetSmsForTheirPaymentsRemindersAndPinChanges() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        seed();
        Member one = member(1);
        Member two = member(2);
        Member three = member(3);
        Member four = member(4);

        // Off until a supervisor switches it on: nothing is queued.
        pay("TSMS000000", one.account(), "50.00", "APPLIED");
        assertEquals(0, owner().queryForObject("SELECT count(*) FROM notify.sms_message", Integer.class));
        String settings = """
            {"enabled":true,"signature":"Test Sacco","senderId":"TESTSACCO","ussdCode":"*483*1#","reminderHour":8}
            """;
        mvc.perform(put("/api/v1/notifications/settings").with(as("ops-1", "OPERATIONS")).contentType(MediaType.APPLICATION_JSON)
                .content(settings)).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/notifications/settings").with(as("sup-1", "SUPERVISOR")).contentType(MediaType.APPLICATION_JSON)
                .content(settings.replace("Test Sacco", "Bad\\nName"))).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/notifications/settings").with(as("sup-1", "SUPERVISOR")).contentType(MediaType.APPLICATION_JSON)
                .content(settings)).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(get("/api/v1/notifications/settings").with(as("teller-1", "TELLER"))).andExpect(status().isForbidden());

        // Members' choices, recorded by staff: member 2 in Swahili, member 3 no payment alerts, member 4 no reminders.
        for (var p : List.of(List.of(two, "sw", true, true), List.of(three, "en", false, true), List.of(four, "en", true, false))) {
            mvc.perform(put("/api/v1/customers/" + ((Member) p.get(0)).customerNumber() + "/sms-preferences").with(as("teller-1", "TELLER"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"language\":\"%s\",\"paymentAlerts\":%s,\"reminders\":%s}".formatted(p.get(1), p.get(2), p.get(3))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.preferences.updatedBy").value("user:teller-1"));
        }

        // Payments: an SMS in the member's language, one per receipt; opted-out members' messages are recorded, not sent.
        pay("TSMS000001", one.account(), "1000.00", "APPLIED");
        pay("TSMS000001", one.account(), "1000.00", "APPLIED");
        pay("TSMS000002", two.loan(), "500.00", "LOAN_REPAYMENT");
        pay("TSMS000003", three.account(), "200.00", "APPLIED");
        Map<String, Object> m1 = sms("pay:TSMS000001");
        assertEquals("Test Sacco: KES 1,000.00 was deposited to your savings account *" + one.account().substring(one.account().length() - 4)
                + " on 28/11/2026. M-Pesa ref TSMS000001.", m1.get("body"));
        assertEquals("QUEUED", m1.get("status"));
        assertEquals(one.msisdn(), m1.get("msisdn"));
        Map<String, Object> m2 = sms("pay:TSMS000002");
        assertEquals("Test Sacco: Tumepokea KES 500.00 kwa mkopo " + two.loan() + " tarehe 28/11/2026. Kumbukumbu ya M-Pesa TSMS000002. Asante.",
                m2.get("body"));
        assertEquals("SUPPRESSED", sms("pay:TSMS000003").get("status"));
        assertEquals("MEMBER_OPTED_OUT", sms("pay:TSMS000003").get("suppressed_reason"));
        assertEquals(1, owner().queryForObject("SELECT count(*) FROM notify.sms_message WHERE dedupe_key = 'pay:TSMS000001'", Integer.class));

        // The sender claims what is due, once.
        List<Map<String, Object>> claimed = claim();
        assertEquals(2, claimed.size(), claimed.toString());
        assertTrue(claimed.stream().allMatch(c -> T.equals(c.get("tenantId")) && "TESTSACCO".equals(c.get("senderId"))));
        assertEquals(0, claim().size());
        UUID id1 = (UUID) m1.get("message_id");
        UUID id2 = (UUID) m2.get("message_id");

        // Sent, then delivered; repeated reports are harmless, a conflicting one is refused.
        assertTrue(report(id1, "sent", "{\"tenantId\":\"" + T + "\",\"providerMessageId\":\"prov-1\"}").contains("\"status\":\"SENT\""));
        mvc.perform(post("/api/v1/notifications/sms/" + id1 + "/sent").with(INTEGRATION).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":\"" + T + "\",\"providerMessageId\":\"prov-other\"}")).andExpect(status().isConflict());
        String delivered = "{\"tenantId\":\"" + T + "\",\"providerMessageId\":\"prov-1\",\"delivered\":true}";
        assertTrue(report(id1, "delivery", delivered).contains("\"status\":\"DELIVERED\""));
        assertTrue(report(id1, "delivery", delivered).contains("\"status\":\"DELIVERED\""));

        // A temporary failure is retried after a back-off; a permanent one fails.
        String retry = report(id2, "failed", "{\"tenantId\":\"" + T + "\",\"code\":\"503\",\"description\":\"busy\",\"retryable\":true}");
        assertTrue(retry.contains("\"status\":\"QUEUED\"") && retry.contains("\"attempts\":1"), retry);
        assertEquals(0, claim().size(), "not due before the back-off");
        owner().update("UPDATE notify.sms_message SET not_before = now() - interval '1 second' WHERE message_id = ?", id2);
        assertEquals(1, claim().size());
        String failed = report(id2, "failed", "{\"tenantId\":\"" + T + "\",\"code\":\"INVALID\",\"description\":\"bad number\",\"retryable\":false}");
        assertTrue(failed.contains("\"status\":\"FAILED\"") && failed.contains("\"attempts\":2"), failed);
        mvc.perform(get("/api/v1/notifications/sms?status=FAILED").with(as("ops-1", "OPERATIONS"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.messages[*].messageId", hasItem(id2.toString())))
            .andExpect(jsonPath("$.lastSevenDays.FAILED").value(1));

        // PIN setup approval tells the member how to choose a PIN. A claim never reported is sent again.
        String setup = JsonPath.read(mvc.perform(post("/api/v1/customers/" + one.customerNumber() + "/member-pin/setups")
                .with(as("teller-1", "TELLER")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\":\"sms-pin-1\",\"reason\":\"ID sighted at the counter\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.setupId");
        mvc.perform(post("/api/v1/member-pin-setups/" + setup + "/approve").with(as("sup-1", "SUPERVISOR"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());
        Map<String, Object> ready = sms("pin:ready:" + setup);
        assertEquals("Test Sacco: Your mobile banking PIN setup is ready. Dial *483*1# from this phone within 24 hours to choose "
                + "your PIN. Never share your PIN.", ready.get("body"));
        assertEquals("SECURITY", ready.get("category"));
        assertEquals(1, claim().size());
        owner().update("UPDATE notify.sms_message SET claimed_at = now() - interval '10 minutes' WHERE message_id = ?", ready.get("message_id"));
        assertEquals(1, claim().size());
        Map<String, Object> again = sms("pin:ready:" + setup);
        assertEquals(2, again.get("attempts"));
        assertEquals("no report from the sender", again.get("last_error"));

        // End of day: reminders for instalments due in three days, sent the next morning at 08:00.
        mvc.perform(post("/api/v1/end-of-day/run").with(as("ops-1", "OPERATIONS"))).andExpect(status().isOk());
        Map<String, Object> due = sms("due:" + one.loan() + ":2026-12-01");
        assertEquals("LOAN_DUE_SOON", due.get("template"));
        assertTrue(((String) due.get("body")).startsWith("Test Sacco: Your loan " + one.loan() + " instalment of KES "), (String) due.get("body"));
        assertTrue(((String) due.get("body")).endsWith(" is due on 01/12/2026. Pay by M-Pesa paybill 600993, account " + one.loan() + "."));
        assertEquals(Boolean.TRUE, owner().queryForObject("""
                SELECT m.not_before = ('2026-11-29 08:00'::timestamp AT TIME ZONE t.time_zone)
                  FROM notify.sms_message m JOIN tenancy.tenant t ON t.tenant_id = m.tenant_id WHERE m.dedupe_key = ?
                """, Boolean.class, "due:" + one.loan() + ":2026-12-01"));
        assertEquals("SUPPRESSED", sms("due:" + four.loan() + ":2026-12-01").get("status"));
        assertTrue(((String) sms("due:" + two.loan() + ":2026-12-01").get("body")).contains("inadaiwa tarehe 01/12/2026"));

        // A missed instalment (as the close of 1 November would have found it for the older loans).
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            session.bind(UUID.fromString(T));
            notifications.loanReminders(LocalDate.of(2026, 11, 1), "test");
        });
        Map<String, Object> missed = sms("missed:" + three.loan() + ":2026-11-01");
        assertEquals("LOAN_MISSED", missed.get("template"));
        assertTrue(((String) missed.get("body")).contains("instalment due 01/11/2026 is unpaid; KES "), (String) missed.get("body"));

        // Staff see a member's messages and choices; only the integration runtime claims.
        mvc.perform(get("/api/v1/customers/" + one.customerNumber() + "/sms").with(as("teller-1", "TELLER"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.smsEnabled").value(true)).andExpect(jsonPath("$.phone").value(startsWith("2547")))
            .andExpect(jsonPath("$.messages[*].template", hasItem("PAYMENT_SAVINGS")))
            .andExpect(jsonPath("$.messages[*].status", hasItem("DELIVERED")));
        mvc.perform(post("/api/v1/notifications/sms/claim").with(as("ops-1", "OPERATIONS")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"limit\":10}")).andExpect(status().isForbidden());
    }
}
