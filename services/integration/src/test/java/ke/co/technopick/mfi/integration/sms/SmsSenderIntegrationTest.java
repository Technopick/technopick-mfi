package ke.co.technopick.mfi.integration.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * SMS sending through the integration runtime (ADR-0035) with TEST-ONLY stubs of core banking and
 * of an SMS provider: claim, send, report; delivery reports forwarded to core.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(properties = {
    "mfi.outbox.enabled=false",
    "mfi.daraja.processor-enabled=false",
    "mfi.daraja.stk-consumer-enabled=false",
    "mfi.daraja.b2c-consumer-enabled=false",
    "mfi.daraja.reconciler-enabled=false",
    "mfi.sms.enabled=false",
    "mfi.sms.api-key=test-only-sms-key",
    "mfi.sms.callback-base-url=https://mfi.example.test",
    "mfi.sms.delivery-token=test-only-dlr-token",
    "mfi.sms.allowed-cidrs[0]=127.0.0.1/32",
    "mfi.core.client-id=mfi-integration",
    "mfi.core.client-secret=test-only"
})
class SmsSenderIntegrationTest {

    static final String T = "00000000-0000-0000-0000-0000000000a1";
    static final String M1 = "11111111-1111-1111-1111-111111111111";

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17").withInitScript("db/test-init.sql");

    static HttpServer stubs;
    static final List<String> coreCalls = new CopyOnWriteArrayList<>();
    static final List<String> providerCalls = new CopyOnWriteArrayList<>();

    static {
        try {
            stubs = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        stubs.createContext("/token", ex -> reply(ex, 200, "{\"access_token\":\"stub-token\",\"token_type\":\"Bearer\",\"expires_in\":300}"));
        stubs.createContext("/api/v1/notifications/sms/", ex -> {
            String path = ex.getRequestURI().getPath();
            coreCalls.add(path + " " + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (path.endsWith("/claim")) {
                reply(ex, 200, """
                    [{"tenantId":"%s","messageId":"%s","msisdn":"254700000001","body":"Sacco: KES 1.00 received.","senderId":"SACCO"},
                     {"tenantId":"%s","messageId":"22222222-2222-2222-2222-222222222222","msisdn":"254700000002","body":"two","senderId":null},
                     {"tenantId":"%s","messageId":"33333333-3333-3333-3333-333333333333","msisdn":"254700000003","body":"three","senderId":null}]
                    """.formatted(T, M1, T, T));
            } else {
                reply(ex, 200, "{\"status\":\"OK\"}");
            }
        });
        stubs.createContext("/provider/messages", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            providerCalls.add(ex.getRequestHeaders().getFirst("Authorization") + " " + body);
            if (body.contains("+254700000001")) {
                reply(ex, 201, "{\"id\":\"prov-1\",\"status\":\"Sent\"}");
            } else if (body.contains("+254700000002")) {
                reply(ex, 503, "{\"error\":\"busy\"}");
            } else {
                reply(ex, 400, "{\"error\":\"invalid number\"}");
            }
        });
        stubs.start();
    }

    static void reply(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @AfterAll
    static void stop() {
        stubs.stop(0);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        String url = "http://127.0.0.1:" + stubs.getAddress().getPort();
        r.add("mfi.core.base-url", () -> url);
        r.add("mfi.core.token-uri", () -> url + "/token");
        r.add("mfi.sms.provider-url", () -> url + "/provider");
    }

    @Autowired
    SmsSender sender;
    @Autowired
    WebApplicationContext context;

    @Test
    void claimsSendsAndReportsAndForwardsDeliveryReports() throws Exception {
        assertEquals(1, sender.run());
        assertEquals(3, providerCalls.size());
        String first = providerCalls.getFirst();
        assertTrue(first.startsWith("Bearer test-only-sms-key "), first);
        assertTrue(first.contains("\"to\":\"+254700000001\"") && first.contains("\"from\":\"SACCO\"")
                && first.contains("\"callbackUrl\":\"https://mfi.example.test/sms/delivery/test-only-dlr-token/" + T + "/" + M1 + "\""), first);
        assertTrue(!providerCalls.get(1).contains("\"from\""), "no sender ID: the provider's default is used");
        assertTrue(coreCalls.stream().anyMatch(c -> c.startsWith("/api/v1/notifications/sms/" + M1 + "/sent ")
                && c.contains("\"providerMessageId\":\"prov-1\"")), coreCalls.toString());
        assertTrue(coreCalls.stream().anyMatch(c -> c.startsWith("/api/v1/notifications/sms/22222222-2222-2222-2222-222222222222/failed ")
                && c.contains("\"retryable\":true") && c.contains("HTTP 503")), coreCalls.toString());
        assertTrue(coreCalls.stream().anyMatch(c -> c.startsWith("/api/v1/notifications/sms/33333333-3333-3333-3333-333333333333/failed ")
                && c.contains("\"retryable\":false")), coreCalls.toString());

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        String url = "/sms/delivery/test-only-dlr-token/" + T + "/" + M1;
        coreCalls.clear();
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{\"id\":\"prov-1\",\"status\":\"Delivered\"}"))
            .andExpect(status().isOk());
        assertEquals(1, coreCalls.size());
        assertTrue(coreCalls.getFirst().startsWith("/api/v1/notifications/sms/" + M1 + "/delivery ")
                && coreCalls.getFirst().contains("\"delivered\":true") && coreCalls.getFirst().contains("\"providerMessageId\":\"prov-1\""));
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{\"id\":\"prov-1\",\"status\":\"Queued\"}"))
            .andExpect(status().isOk());
        mvc.perform(post("/sms/delivery/wrong/" + T + "/" + M1).contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"prov-1\",\"status\":\"Delivered\"}")).andExpect(status().isNotFound());
        mvc.perform(post(url).with(r -> { r.setRemoteAddr("203.0.113.7"); return r; }).contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"prov-1\",\"status\":\"Delivered\"}")).andExpect(status().isForbidden());
        assertEquals(1, coreCalls.size(), "only the first report reached core");
    }
}
