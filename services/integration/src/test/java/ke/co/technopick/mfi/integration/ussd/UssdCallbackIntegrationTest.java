package ke.co.technopick.mfi.integration.ussd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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
 * USSD aggregator callbacks through the integration runtime (ADR-0034), with a TEST-ONLY stub of
 * core banking and its token endpoint. The database is only needed because the runtime starts its
 * outbox relay datasource.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(properties = {
    "mfi.outbox.enabled=false",
    "mfi.daraja.processor-enabled=false",
    "mfi.daraja.stk-consumer-enabled=false",
    "mfi.daraja.b2c-consumer-enabled=false",
    "mfi.daraja.reconciler-enabled=false",
    "mfi.ussd.allowed-cidrs[0]=127.0.0.1/32",
    "mfi.ussd.services[0].token=test-only-ussd-token-7a2e",
    "mfi.ussd.services[0].tenant-id=00000000-0000-0000-0000-0000000000a1",
    "mfi.ussd.services[0].service-code=*483*1#",
    "mfi.core.client-id=mfi-integration",
    "mfi.core.client-secret=test-only"
})
class UssdCallbackIntegrationTest {

    static final String URL = "/ussd/test-only-ussd-token-7a2e";

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17").withInitScript("db/test-init.sql");

    static HttpServer core;
    static final List<String> calls = new CopyOnWriteArrayList<>();
    /** What the stub core answers next: a status and a JSON body. */
    static final AtomicReference<String[]> next = new AtomicReference<>(new String[] {"200", "{\"response\":\"CON Enter your PIN:\"}"});

    static {
        try {
            core = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        core.createContext("/token", ex -> reply(ex, 200,
                "{\"access_token\":\"stub-token-1\",\"token_type\":\"Bearer\",\"expires_in\":300}"));
        core.createContext("/api/v1/channels/ussd/sessions", ex -> {
            calls.add(ex.getRequestHeaders().getFirst("Authorization") + " "
                    + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String[] answer = next.get();
            reply(ex, Integer.parseInt(answer[0]), answer[1]);
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
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        String url = "http://127.0.0.1:" + core.getAddress().getPort();
        r.add("mfi.core.base-url", () -> url);
        r.add("mfi.core.token-uri", () -> url + "/token");
    }

    @Autowired
    WebApplicationContext context;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        calls.clear();
    }

    @Test
    void callbacksAreAuthenticatedAndForwardedToCore() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("sessionId", "ATUid_1").param("serviceCode", "*483*1#").param("phoneNumber", "+254700000001")
                .param("text", "4826*1").param("networkCode", "63902"))
            .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
            .andExpect(content().string("CON Enter your PIN:"));
        assertEquals(1, calls.size());
        String call = calls.getFirst();
        assertTrue(call.startsWith("Bearer stub-token-1 "), call);
        assertTrue(call.contains("\"tenantId\":\"00000000-0000-0000-0000-0000000000a1\""), call);
        assertTrue(call.contains("\"sessionId\":\"ATUid_1\"") && call.contains("\"text\":\"4826*1\""), call);

        // Unknown token, other source, other service code: nothing reaches core.
        mvc.perform(post("/ussd/wrong-token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("sessionId", "ATUid_2").param("serviceCode", "*483*1#").param("phoneNumber", "+254700000001"))
            .andExpect(status().isNotFound());
        mvc.perform(post(URL).with(r -> { r.setRemoteAddr("203.0.113.9"); return r; }).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("sessionId", "ATUid_2").param("serviceCode", "*483*1#").param("phoneNumber", "+254700000001"))
            .andExpect(status().isForbidden());
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("sessionId", "ATUid_2").param("serviceCode", "*999#").param("phoneNumber", "+254700000001"))
            .andExpect(status().isBadRequest());
        assertEquals(1, calls.size());

        // If core fails or answers nonsense, the member gets a polite END, never an error page or a hang.
        next.set(new String[] {"503", "{}"});
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("sessionId", "ATUid_3").param("serviceCode", "*483*1#").param("phoneNumber", "+254700000001").param("text", ""))
            .andExpect(status().isOk()).andExpect(content().string(UssdCallbackController.UNAVAILABLE));
        next.set(new String[] {"200", "{\"response\":\"<html>\"}"});
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("sessionId", "ATUid_4").param("serviceCode", "*483*1#").param("phoneNumber", "+254700000001").param("text", ""))
            .andExpect(status().isOk()).andExpect(content().string(UssdCallbackController.UNAVAILABLE));
    }
}
