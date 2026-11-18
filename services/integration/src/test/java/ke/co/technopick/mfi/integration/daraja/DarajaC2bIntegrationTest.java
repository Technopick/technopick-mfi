package ke.co.technopick.mfi.integration.daraja;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Daraja C2B through the integration runtime: real PostgreSQL 17 (core migrations), MockMvc for
 * Safaricom's HTTP calls, and a TEST-ONLY stub of core banking + token endpoint (JDK HttpServer).
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(properties = {
    "mfi.outbox.enabled=false",
    "mfi.daraja.processor-enabled=false",
    "mfi.daraja.retry-backoff=PT0S",
    "mfi.daraja.allowed-cidrs[0]=127.0.0.1/32",
    "mfi.daraja.paybills[0].token=test-only-token-a-6f1c",
    "mfi.daraja.paybills[0].tenant-id=00000000-0000-0000-0000-0000000000a1",
    "mfi.daraja.paybills[0].short-code=600991",
    "mfi.core.client-id=mfi-integration",
    "mfi.core.client-secret=test-only"
})
class DarajaC2bIntegrationTest {

    static final String A = "00000000-0000-0000-0000-0000000000a1";
    static final String BASE = "/daraja/c2b/test-only-token-a-6f1c";

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17").withInitScript("db/test-init.sql");

    /** TEST-ONLY stand-in for core banking and the IdP token endpoint. */
    static HttpServer core;
    static final List<String> applyCalls = new CopyOnWriteArrayList<>();
    static final AtomicInteger applyFailuresToSimulate = new AtomicInteger();

    static {
        try {
            core = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        core.createContext("/token", ex -> reply(ex, 200,
                "{\"access_token\":\"stub-token-1\",\"token_type\":\"Bearer\",\"expires_in\":300}"));
        core.createContext("/api/v1/payments/mpesa/c2b/validation", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("BOOM")) {
                reply(ex, 503, "{}");
            } else if (body.contains("\"0100000004\"")) {
                reply(ex, 200, "{\"resultCode\":\"0\",\"resultDesc\":\"Accepted\"}");
            } else {
                reply(ex, 200, "{\"resultCode\":\"C2B00012\",\"resultDesc\":\"Invalid account number\"}");
            }
        });
        core.createContext("/api/v1/payments/mpesa/c2b", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (applyFailuresToSimulate.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                reply(ex, 503, "{}");
                return;
            }
            applyCalls.add(ex.getRequestHeaders().getFirst("Authorization") + " " + body);
            reply(ex, 200, "{\"outcome\":\"APPLIED\",\"replayed\":false}");
        });
        core.start();
    }

    static void reply(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @AfterAll
    static void stopCore() {
        core.stop(0);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", () -> "mfi_relay");
        r.add("spring.datasource.password", () -> "it-only-relay");
        String url = "http://127.0.0.1:" + core.getAddress().getPort();
        r.add("mfi.core.base-url", () -> url);
        r.add("mfi.core.token-uri", () -> url + "/token");
    }

    @BeforeAll
    static void schema() {
        Flyway.configure().dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
            .locations("classpath:db/migration").load().migrate();
        owner().execute("""
            INSERT INTO tenancy.tenant (tenant_id, code, legal_name, licence_type, deposits_allowed, base_currency)
            VALUES ('%s', 'TEST-A', 'TEST ONLY A', 'MICROFINANCE_BANK', true, 'KES')
            """.formatted(A));
    }

    static JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
    }

    @Autowired
    WebApplicationContext context;
    @Autowired
    C2bCallbackProcessor processor;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    static String payload(String transId, String billRef, String amount, String shortCode) {
        return """
            {"TransactionType":"Pay Bill","TransID":"%s","TransTime":"20261118101500","TransAmount":"%s",
             "BusinessShortCode":"%s","BillRefNumber":"%s","InvoiceNumber":"","OrgAccountBalance":"",
             "ThirdPartyTransID":"","MSISDN":"254712345678","FirstName":"TEST"}
            """.formatted(transId, amount, shortCode, billRef);
    }

    @Test
    void callbacksAreAuthenticated() throws Exception {
        mvc.perform(post("/daraja/c2b/wrong-token/confirmation").contentType(MediaType.APPLICATION_JSON)
                .content(payload("TA57ZBCAM1", "0100000004", "10.00", "600991"))).andExpect(status().isNotFound());
        mvc.perform(post(BASE + "/confirmation").with(r -> { r.setRemoteAddr("203.0.113.9"); return r; })
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload("TA57ZBCAM1", "0100000004", "10.00", "600991"))).andExpect(status().isForbidden());
        // Right token, but a different paybill's short code: not accepted.
        mvc.perform(post(BASE + "/confirmation").contentType(MediaType.APPLICATION_JSON)
                .content(payload("TA57ZBCAM1", "0100000004", "10.00", "600992"))).andExpect(status().isBadRequest());
        assertEquals(0, owner().queryForObject("SELECT count(*) FROM integration.provider_callback WHERE provider_ref = 'TA57ZBCAM1'", Integer.class));
    }

    @Test
    void validationAsksCoreAndFailsSafe() throws Exception {
        mvc.perform(post(BASE + "/validation").contentType(MediaType.APPLICATION_JSON)
                .content(payload("TA57ZBCAV1", "0100000004", "10.00", "600991")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.ResultCode").value("0"));
        mvc.perform(post(BASE + "/validation").contentType(MediaType.APPLICATION_JSON)
                .content(payload("TA57ZBCAV2", "UNKNOWN", "10.00", "600991")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.ResultCode").value("C2B00012"));
        mvc.perform(post(BASE + "/validation").contentType(MediaType.APPLICATION_JSON)
                .content(payload("TA57ZBCAV3", "0100000004", "10.00", "600992")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.ResultCode").value("C2B00015"));
        // Core unavailable: circuit-breaker fallback rejects (configured default).
        mvc.perform(post(BASE + "/validation").contentType(MediaType.APPLICATION_JSON)
                .content(payload("TA57ZBCAV4", "BOOM", "10.00", "600991")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.ResultCode").value("C2B00016"));
    }

    @Test
    void confirmationIsStoredOnceAppliedWithRetries() throws Exception {
        for (int i = 0; i < 2; i++) { // Safaricom may deliver the same confirmation twice
            mvc.perform(post(BASE + "/confirmation").contentType(MediaType.APPLICATION_JSON)
                    .content(payload("TA57ZBCAM3", "0100000004", "1500.00", "600991")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ResultCode").value("0"));
        }
        assertEquals(1, owner().queryForObject(
                "SELECT count(*) FROM integration.provider_callback WHERE provider_ref = 'TA57ZBCAM3'", Integer.class));

        applyFailuresToSimulate.set(1);      // core briefly unavailable
        assertEquals(0, processor.processPending());
        assertEquals("FAILED", owner().queryForObject(
                "SELECT status FROM integration.provider_callback WHERE provider_ref = 'TA57ZBCAM3'", String.class));
        assertEquals(1, processor.processPending()); // retried after back-off (0 s in this test)
        assertEquals(0, processor.processPending()); // nothing left
        assertEquals("APPLIED", owner().queryForObject(
                "SELECT status FROM integration.provider_callback WHERE provider_ref = 'TA57ZBCAM3'", String.class));
        assertEquals(2, owner().queryForObject(
                "SELECT attempts FROM integration.provider_callback WHERE provider_ref = 'TA57ZBCAM3'", Integer.class));

        List<String> calls = applyCalls.stream().filter(c -> c.contains("TA57ZBCAM3")).toList();
        assertEquals(1, calls.size());
        String call = calls.get(0);
        assertTrue(call.startsWith("Bearer stub-token-1 "), call);
        assertTrue(call.contains("\"tenantId\":\"" + A + "\""), call);
        assertTrue(call.contains("\"amount\":\"1500.00\""), call);
        assertTrue(call.contains("2547*****678"), call);
        assertTrue(!call.contains("254712345678"), "full MSISDN must not be forwarded");
    }
}
