package ke.co.technopick.mfi.core.member;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Member PIN and USSD (increment 34, ADR-0034) on the small TEST-ONLY book. Member i has phone
 * 2547000000ii, ID number 2000000i, born 1985, savings account i and loan LN-P000000i.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class UssdIntegrationTest {

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

    MockMvc mvc;

    static JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
    }

    static JwtRequestPostProcessor staff(String subject, String role) {
        return jwt().jwt(j -> j.subject(subject).claim("tenant_id", T)).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    static JwtRequestPostProcessor integration() {
        return jwt().jwt(j -> j.subject("svc").claim("azp", "mfi-integration")).authorities(new SimpleGrantedAuthority("ROLE_INTEGRATION"));
    }

    void seed() throws Exception {
        String sql = new String(UssdIntegrationTest.class.getResourceAsStream("/perf/perf-seed-TEST-ONLY.sql").readAllBytes(),
                StandardCharsets.UTF_8).replace("{T}", T).replace("{NS}", "10").replace("{NL}", "10");
        Path file = Files.createTempFile("ussd-seed", ".sql");
        Files.writeString(file, sql);
        PG.copyFileToContainer(MountableFile.forHostPath(file), "/tmp/seed.sql");
        var r = PG.execInContainer("psql", "-v", "ON_ERROR_STOP=1", "-q", "-U", PG.getUsername(), "-d", PG.getDatabaseName(),
                "-f", "/tmp/seed.sql");
        assertEquals(0, r.getExitCode(), r.getStderr());
    }

    /** One USSD request as the aggregator would send it; every screen must fit the 182-character limit. */
    String ussd(String session, String phone, String text) throws Exception {
        String body = """
            {"tenantId":"%s","sessionId":"%s","serviceCode":"*483*1#","phoneNumber":"%s","text":"%s"}
            """.formatted(T, session, phone, text);
        String json = mvc.perform(post("/api/v1/channels/ussd/sessions").with(integration()).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String response = JsonPath.read(json, "$.response");
        assertTrue(response.length() <= 182, "screen too long: " + response.length());
        assertTrue(response.startsWith("CON ") || response.startsWith("END "), response);
        return response;
    }

    static void starts(String expected, String actual) {
        assertTrue(actual.startsWith(expected), () -> "expected «" + expected + "» but was «" + actual + "»");
    }

    static void has(String expected, String actual) {
        assertTrue(actual.contains(expected), () -> "expected «" + expected + "» in «" + actual + "»");
    }

    String requestSetup(String customer, String key, String who) throws Exception {
        String json = mvc.perform(post("/api/v1/customers/" + customer + "/member-pin/setups").with(staff(who, "TELLER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\":\"" + key + "\",\"reason\":\"Member enrolled at the counter, ID seen\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REQUESTED"))
            .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.setupId");
    }

    @Test
    void membersSetAPinAndBankByUssd() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        seed();
        Map<String, Object> m = owner().queryForMap("""
                SELECT c.customer_number, c.customer_id::text AS id, a.account_number FROM customer.customer c
                  JOIN savings.deposit_account a ON a.tenant_id = c.tenant_id AND a.customer_id = c.customer_id
                 WHERE c.tenant_id = ?::uuid AND c.msisdn = '254700000001'
                """, T);
        String cn = (String) m.get("customer_number");
        String phone = "+254700000001";

        // Before any setup: the member is told to visit the branch.
        starts("END Mobile banking is not set up", ussd("s0", phone, ""));
        starts("END This phone number is not registered", ussd("s0b", "0711111111", ""));

        // Staff: a teller asks, the same teller cannot approve, a supervisor approves.
        mvc.perform(get("/api/v1/customers/" + cn + "/member-pin").with(staff("teller-1", "TELLER")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NONE"));
        String setup = requestSetup(cn, "pin-1", "teller-1");
        mvc.perform(post("/api/v1/customers/" + cn + "/member-pin/setups").with(staff("teller-2", "TELLER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\":\"pin-2\",\"reason\":\"A second request while one is open\"}"))
            .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/member-pin-setups/" + setup + "/approve").with(staff("teller-1", "SUPERVISOR"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/member-pin-setups/" + setup + "/approve").with(staff("teller-2", "TELLER"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/member-pin-setups").with(staff("sup-1", "SUPERVISOR"))).andExpect(status().isOk())
            .andExpect(jsonPath("$[*].setupId", hasItem(setup)));
        mvc.perform(post("/api/v1/member-pin-setups/" + setup + "/approve").with(staff("sup-1", "SUPERVISOR"))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(get("/api/v1/customers/" + cn + "/member-pin").with(staff("teller-1", "TELLER")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SETUP_OPEN"))
            .andExpect(jsonPath("$.setups[0].kind").value("FIRST"));

        // The member chooses a PIN: ID number first, easy PINs refused, PIN entered twice.
        starts("CON Welcome to mobile banking.\nTo set your PIN, enter your ID number:", ussd("s1", phone, ""));
        starts("CON Choose a 4-digit PIN", ussd("s1", phone, "20000001"));
        starts("END That PIN is too easy to guess.", ussd("s1", phone, "20000001*1234"));
        starts("END Do not use your birth year", ussd("s2", phone, "20000001*1985"));
        starts("END The PINs do not match", ussd("s3", phone, "20000001*4826*4862"));
        starts("CON Enter the PIN again", ussd("s4", phone, "20000001*4826"));
        starts("END Your PIN is set.", ussd("s4", phone, "20000001*4826*4826"));
        String hash = owner().queryForObject("SELECT pin_hash FROM member.pin WHERE customer_id = ?::uuid", String.class, m.get("id"));
        assertTrue(hash.startsWith("$2a$10$") && !hash.contains("4826"));

        // Banking: PIN once per session, then the menu.
        starts("CON Welcome Member.\nEnter your PIN:", ussd("s5", phone, ""));
        starts("CON 1. Balances", ussd("s5", phone, "4826"));
        String balances = ussd("s5", phone, "4826*1");
        starts("END Available balances:", balances);
        has("KES", balances);
        String statement = ussd("s5", phone, "4826*2");
        starts("END Last transactions", statement);
        has("Balance KES", statement);
        String loans = ussd("s5", phone, "4826*3");
        has("LN-P0000001", loans);
        has("Next: KES", loans);
        has("To clear today: KES 12,", loans);

        // Pay the loan by M-Pesa prompt to the member's own phone.
        starts("CON Enter amount to pay to LN-P0000001", ussd("s5", phone, "4826*4"));
        starts("END Enter a whole amount", ussd("s5", phone, "4826*4*10.50"));
        starts("CON Pay KES 500.00 to LN-P0000001 from M-Pesa?", ussd("s5", phone, "4826*4*500"));
        starts("END Enter your M-Pesa PIN on the prompt", ussd("s5", phone, "4826*4*500*1"));
        starts("END Enter your M-Pesa PIN on the prompt", ussd("s5", phone, "4826*4*500*1"));
        Map<String, Object> prompt = owner().queryForMap("""
                SELECT count(*) AS n, min(msisdn) AS msisdn, min(requested_by) AS requester, min(target_reference) AS target,
                       min(amount) AS amount, bool_and(member_customer_id = ?::uuid) AS linked
                  FROM payments.stk_request WHERE tenant_id = ?::uuid AND idempotency_key LIKE '%:ussd:s5'
                """, m.get("id"), T);
        assertEquals(1L, ((Number) prompt.get("n")).longValue(), "a repeated confirmation is the same prompt");
        assertEquals("254700000001", prompt.get("msisdn"));
        assertEquals("ussd:" + cn, prompt.get("requester"));
        assertEquals("LN-P0000001", prompt.get("target"));
        assertEquals(0, new java.math.BigDecimal("500.00").compareTo((java.math.BigDecimal) prompt.get("amount")));
        assertEquals(Boolean.TRUE, prompt.get("linked"));
        starts("END Payment cancelled", ussd("s6", phone, "4826*5*100*2"));

        // A session keeps its phone.
        starts("END Session error", ussd("s5", "+254700000002", "4826*1"));

        // Change PIN; the old one stops working.
        starts("END Your PIN has been changed", ussd("s7", phone, "4826*6*7391*7391"));
        starts("END Wrong PIN. 2 attempts left", ussd("s8", phone, "4826"));
        starts("CON 1. Balances", ussd("s9", phone, "7391"));

        // Wrong PINs lock it (the earlier wrong attempt was cleared by the correct PIN).
        starts("END Wrong PIN. 2 attempts left", ussd("s10", phone, "0000"));
        starts("END Wrong PIN. 1 attempt left", ussd("s11", phone, "1111"));
        starts("END Wrong PIN. Your PIN is now locked.", ussd("s12", phone, "2222"));
        starts("END Your PIN is locked", ussd("s13", phone, "7391"));
        mvc.perform(get("/api/v1/customers/" + cn + "/member-pin").with(staff("teller-1", "TELLER")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("LOCKED"))
            .andExpect(jsonPath("$.recentEvents[*].event", hasItem("PIN_LOCKED")))
            .andExpect(jsonPath("$.recentEvents[*].event", hasItem("PAYMENT_PROMPT")));

        // Reset: a new approved setup opens a new window; a wrong ID number counts against the limit.
        String reset = requestSetup(cn, "pin-3", "teller-2");
        mvc.perform(post("/api/v1/member-pin-setups/" + reset + "/reject").with(staff("sup-1", "SUPERVISOR"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/member-pin-setups/" + reset + "/approve").with(staff("mgr-1", "BRANCH_MANAGER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Member present with ID\"}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/customers/" + cn + "/member-pin").with(staff("teller-1", "TELLER")))
            .andExpect(jsonPath("$.status").value("SETUP_OPEN")).andExpect(jsonPath("$.setups[0].kind").value("RESET"));
        starts("END The ID number does not match our records. 2 attempts left", ussd("s14", phone, "99999999"));
        starts("END Your PIN is set.", ussd("s15", phone, "20000001*2580*2580"));
        starts("CON 1. Balances", ussd("s16", phone, "2580"));

        // A blocked member is refused, whatever their PIN.
        owner().update("UPDATE customer.customer SET status = 'BLOCKED', status_reason = 'test' WHERE tenant_id = ?::uuid AND customer_number = ?",
                T, cn);
        starts("END Your membership is not active", ussd("s17", phone, "2580"));

        // Only the integration runtime calls the USSD endpoint; staff and members cannot.
        mvc.perform(post("/api/v1/channels/ussd/sessions").with(staff("sup-1", "SUPERVISOR")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":\"" + T + "\",\"sessionId\":\"x\",\"serviceCode\":\"*483#\",\"phoneNumber\":\"254700000002\",\"text\":\"\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/channels/ussd/sessions").with(jwt().jwt(j -> j.subject("m").claim("tenant_id", T)
                    .claim("customer_number", cn)).authorities(new SimpleGrantedAuthority("ROLE_MEMBER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":\"" + T + "\",\"sessionId\":\"x\",\"serviceCode\":\"*483#\",\"phoneNumber\":\"254700000002\",\"text\":\"\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/channels/ussd/sessions").with(integration()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":\"" + T + "\",\"sessionId\":\"x\",\"serviceCode\":\"*483#\",\"phoneNumber\":\"12345\",\"text\":\"\"}"))
            .andExpect(status().isBadRequest());

        // The channel trail never holds the PIN or ID number.
        Integer leaks = owner().queryForObject("""
                SELECT count(*) FROM member.ussd_event WHERE detail LIKE '%4826%' OR detail LIKE '%7391%' OR detail LIKE '%2580%'
                    OR detail LIKE '%20000001%'
                """, Integer.class);
        assertEquals(0, leaks);
    }
}
