package ke.co.technopick.mfi.integration.daraja;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import ke.co.technopick.mfi.simulators.daraja.DarajaSimulator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * STK Push and B2C end to end through the integration runtime, all on real transports: outbox rows in
 * PostgreSQL 17 -> relay -> RabbitMQ 4 -> dispatcher -> TEST-ONLY Daraja simulator over HTTP ->
 * simulator calls back into this runtime's real HTTP port -> stored -> processor -> core.
 * Core banking and the IdP are a TEST-ONLY stub (core's own STK behaviour is covered by the core
 * module's end-to-end test).
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
    "mfi.outbox.poll-interval-ms=200",
    "mfi.daraja.process-interval-ms=200",
    "mfi.daraja.retry-backoff=PT0S",
    "mfi.daraja.allowed-cidrs[0]=127.0.0.1/32",
    "mfi.daraja.paybills[0].token=test-only-stk-token-9d2e",
    "mfi.daraja.paybills[0].tenant-id=00000000-0000-0000-0000-0000000000a1",
    "mfi.daraja.paybills[0].short-code=174379",
    "mfi.daraja.paybills[0].passkey=test-only-passkey",
    "mfi.daraja.consumer-key=test-only-key",
    "mfi.daraja.consumer-secret=test-only-secret",
    "mfi.daraja.allow-insecure-test-endpoint=true",
    "mfi.daraja.reconciler-enabled=false",
    "mfi.daraja.b2c-accounts[0].token=test-only-b2c-token-41aa",
    "mfi.daraja.b2c-accounts[0].tenant-id=00000000-0000-0000-0000-0000000000a1",
    "mfi.daraja.b2c-accounts[0].short-code=600000",
    "mfi.daraja.b2c-accounts[0].initiator-name=testapi",
    "mfi.daraja.b2c-accounts[0].initiator-password=test-only-initiator-pw",
    "mfi.daraja.reconcile-after=PT0S",
    "mfi.core.client-id=mfi-integration",
    "mfi.core.client-secret=test-only"
})
class DarajaStkIntegrationTest {

    static final String A = "00000000-0000-0000-0000-0000000000a1";

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17").withInitScript("db/test-init.sql");

    @Container
    static final RabbitMQContainer MQ = new RabbitMQContainer("rabbitmq:4.1");

    static final int PORT = freePort();
    static final DarajaSimulator SIM;
    static final HttpServer CORE;
    /** request id -> phone number the stub core hands out on dispatch. */
    static final Map<String, String> PHONES = new ConcurrentHashMap<>();
    static final Map<String, Integer> DISPATCHES = new ConcurrentHashMap<>();
    static final List<String> CALLS = new CopyOnWriteArrayList<>();
    /** request id -> CheckoutRequestID reported as submitted; only ids in RECONCILE are offered as stale. */
    static final Map<String, String> SUBMITTED = new ConcurrentHashMap<>();
    static final java.util.Set<String> RECONCILE = ConcurrentHashMap.newKeySet();

    static {
        try {
            SIM = DarajaSimulator.start("test-only-key", "test-only-secret", Map.of("174379", "test-only-passkey"),
                    Map.of("600000", new DarajaSimulator.Initiator("testapi", "test-only-initiator-pw")), 150, 0);
            CORE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        CORE.createContext("/token", ex -> reply(ex, 200, "{\"access_token\":\"stub\",\"expires_in\":300}"));
        CORE.createContext("/api/v1/payments/mpesa/stk-requests/", DarajaStkIntegrationTest::core);
        CORE.createContext("/api/v1/payments/mpesa/b2c/disbursements/", DarajaStkIntegrationTest::b2cCore);
        CORE.start();
    }

    static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** TEST-ONLY stub of core's STK endpoints: dispatch is claimable once per request. */
    static void core(HttpExchange ex) throws IOException {
        String[] parts = ex.getRequestURI().getPath().split("/");
        String id = parts[parts.length - 2];
        String action = parts[parts.length - 1];
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        CALLS.add(action + " " + id + " " + body);
        if ("dispatch".equals(action)) {
            if (DISPATCHES.merge(id, 1, Integer::sum) > 1) {
                reply(ex, 409, "{\"title\":\"conflict\"}");
                return;
            }
            String reference = id.substring(0, 8).toUpperCase();
            reply(ex, 200, """
                {"requestId":"%s","shortCode":"174379","msisdn":"%s","amount":"500","accountReference":"%s",
                 "description":"Deposit"}""".formatted(id, PHONES.get(id), reference));
            return;
        }
        if ("submitted".equals(action)) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"checkoutRequestId\":\"([^\"]+)\"").matcher(body);
            if (m.find()) {
                SUBMITTED.put(id, m.group(1));
            }
        }
        if ("stale".equals(action)) {
            StringBuilder items = new StringBuilder();
            for (String rid : RECONCILE) {
                if (SUBMITTED.containsKey(rid)) {
                    items.append(items.isEmpty() ? "" : ",").append("""
                        {"requestId":"%s","shortCode":"174379","checkoutRequestId":"%s","status":"SUBMITTED","checkCount":0}
                        """.formatted(rid, SUBMITTED.get(rid)));
                }
            }
            reply(ex, 200, "{\"items\":[" + items + "]}");
            return;
        }
        reply(ex, 200, "{\"status\":\"OK\"}");
    }

    /** TEST-ONLY stub of core's B2C endpoints: dispatch is claimable once per disbursement. */
    static void b2cCore(HttpExchange ex) throws IOException {
        String[] parts = ex.getRequestURI().getPath().split("/");
        String id = parts[parts.length - 2];
        String action = parts[parts.length - 1];
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        CALLS.add("b2c-" + action + " " + id + " " + body);
        if ("dispatch".equals(action)) {
            if (DISPATCHES.merge(id, 1, Integer::sum) > 1) {
                reply(ex, 409, "{\"title\":\"conflict\"}");
                return;
            }
            reply(ex, 200, """
                {"disbursementId":"%s","shortCode":"600000","msisdn":"%s","amount":"98000","remarks":"Loan LN-T",
                 "occasion":"LN-T"}""".formatted(id, PHONES.get(id)));
            return;
        }
        reply(ex, 200, "{\"status\":\"OK\"}");
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
        SIM.close();
        CORE.stop(0);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("server.port", () -> PORT);
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", () -> "mfi_relay");
        r.add("spring.datasource.password", () -> "it-only-relay");
        r.add("spring.rabbitmq.host", MQ::getHost);
        r.add("spring.rabbitmq.port", MQ::getAmqpPort);
        r.add("spring.rabbitmq.username", MQ::getAdminUsername);
        r.add("spring.rabbitmq.password", MQ::getAdminPassword);
        String core = "http://127.0.0.1:" + CORE.getAddress().getPort();
        r.add("mfi.core.base-url", () -> core);
        r.add("mfi.core.token-uri", () -> core + "/token");
        r.add("mfi.daraja.base-url", SIM::baseUrl);
        r.add("mfi.daraja.callback-base-url", () -> "http://127.0.0.1:" + PORT);
        r.add("mfi.daraja.certificate-pem", SIM::publicKeyPem);
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

    /** What core's outbox would contain after a staff STK request (no phone number). */
    static String request(String phone) {
        String id = UUID.randomUUID().toString();
        PHONES.put(id, phone);
        publish(id);
        return id;
    }

    static void publish(String id) {
        owner().update("""
            INSERT INTO messaging.outbox_event (event_id, tenant_id, event_type, aggregate_type, aggregate_id, payload)
            VALUES (?::uuid, ?::uuid, 'MpesaStkPushRequested', 'stk_request', ?, ?::jsonb)
            """, UUID.randomUUID().toString(), A, id, "{\"requestId\":\"" + id + "\",\"shortCode\":\"174379\"}");
    }

    static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what + "; core calls: " + CALLS);
            }
            Thread.sleep(100);
        }
    }

    static List<String> calls(String action, String id) {
        return CALLS.stream().filter(c -> c.startsWith(action + " " + id + " ")).toList();
    }

    @Test
    void stkPushEndToEndThroughSimulator() throws Exception {
        String paid = request("254712345678");
        String cancelled = request("254712345032");
        String duplicate = request("254712345222");
        String refused = request("254712345400");
        String unknown = request("254712345500");

        await("all outcomes", () -> calls("result", paid).size() == 1 && calls("result", cancelled).size() == 1
                && calls("result", duplicate).size() == 1 && calls("send-failure", refused).size() == 1
                && SIM.deliveries().stream().filter(d -> d.url().endsWith(duplicate)).count() == 2);

        // Paid: prompt sent once with a valid password (else the simulator refuses), submission and
        // result reported; amount, receipt and time mapped; phone number not forwarded to core.
        assertEquals(1, SIM.stkCalls().stream().filter(c -> c.phone().equals("254712345678")).count());
        String submitted = calls("submitted", paid).get(0);
        String result = calls("result", paid).get(0);
        String checkout = SIM.stkCalls().stream().filter(c -> c.phone().equals("254712345678")).findFirst()
            .orElseThrow().checkoutRequestId();
        assertTrue(submitted.contains(checkout), submitted);
        assertTrue(result.contains("\"checkoutRequestId\":\"" + checkout + "\""), result);
        assertTrue(result.contains("\"resultCode\":\"0\""), result);
        assertTrue(result.contains("\"amount\":\"500.00\""), result);
        assertTrue(result.matches(".*\"receipt\":\"SIM[A-Z0-9]{7}\".*"), result);
        assertTrue(result.contains("+03:00"), result);
        assertTrue(!result.contains("254712345678"), "phone number must not be forwarded");
        assertTrue(result.contains("\"tenantId\":\"" + A + "\""), result);

        // Cancelled by the customer.
        assertTrue(calls("result", cancelled).get(0).contains("\"resultCode\":\"1032\""));
        // Delivered twice by the provider, stored and applied once.
        assertEquals(1, owner().queryForObject("""
            SELECT count(*) FROM integration.provider_callback WHERE callback_type = 'STK_RESULT' AND route_ref = ?
            """, Integer.class, duplicate));
        // Refused by the provider: definitive failure reported, no result.
        assertTrue(calls("send-failure", refused).get(0).contains("400.002.02"), calls("send-failure", refused).toString());
        // Provider error 500: outcome unknown, so NOT reported as failed; left for the callback / reconciliation.
        await("unknown outcome", () -> SIM.stkCalls().stream().anyMatch(c -> c.phone().equals("254712345500")));
        Thread.sleep(500);
        assertEquals(List.of(), calls("send-failure", unknown));
        assertEquals(List.of(), calls("submitted", unknown));

        // A redelivered event cannot send a second prompt.
        publish(paid);
        await("redelivery handled", () -> DISPATCHES.getOrDefault(paid, 0) == 2);
        Thread.sleep(500);
        assertEquals(1, SIM.stkCalls().stream().filter(c -> c.phone().equals("254712345678")).count());
    }

    @Test
    void callbacksAreAuthenticated() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String body = "{\"Body\":{\"stkCallback\":{\"MerchantRequestID\":\"m\",\"CheckoutRequestID\":\"ws_CO_forged\","
                + "\"ResultCode\":0,\"ResultDesc\":\"ok\"}}}";
        HttpResponse<String> wrongToken = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + PORT
                + "/daraja/stk/wrong-token/callback/" + UUID.randomUUID()))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(404, wrongToken.statusCode());
        HttpResponse<String> badId = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + PORT
                + "/daraja/stk/test-only-stk-token-9d2e/callback/not-a-uuid"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(400, badId.statusCode());
        assertEquals(0, owner().queryForObject(
                "SELECT count(*) FROM integration.provider_callback WHERE provider_ref = 'ws_CO_forged'", Integer.class));
    }

    @Test
    void plainHttpDarajaIsRefusedOutsideTests() {
        assertThrows(IllegalStateException.class, () -> new DarajaProperties(null, null, null, true, 1000, 10, null,
                "http://daraja.example", "k", "s", "https://cb.example", false, true, null, null, true));
    }

    @org.springframework.beans.factory.annotation.Autowired
    StkReconciler reconciler;

    /** No callback ever arrives: the reconciler asks Daraja (STK query) and reports what it learns. */
    @Test
    void overdueResultsAreQueried() throws Exception {
        String paidSilently = request("254712340999");
        String cancelledSilently = request("254712340997");
        String stillProcessing = request("254712340998");
        RECONCILE.addAll(List.of(paidSilently, cancelledSilently, stillProcessing));
        await("submissions", () -> SUBMITTED.keySet().containsAll(RECONCILE));

        assertEquals(3, reconciler.reconcile());
        assertTrue(SIM.queries().containsAll(List.of(SUBMITTED.get(paidSilently), SUBMITTED.get(cancelledSilently),
                SUBMITTED.get(stillProcessing))), SIM.queries().toString());
        String paid = calls("query-result", paidSilently).get(0);
        assertTrue(paid.contains("\"resultCode\":\"0\""), paid);
        String cancelled = calls("query-result", cancelledSilently).get(0);
        assertTrue(cancelled.contains("\"resultCode\":\"1032\""), cancelled);
        String processing = calls("query-result", stillProcessing).get(0);
        assertTrue(!processing.contains("resultCode"), processing);
        assertEquals(List.of(), calls("result", paidSilently));
    }

    static String disbursement(String phone) {
        String id = UUID.randomUUID().toString();
        PHONES.put(id, phone);
        publishB2c(id);
        return id;
    }

    static void publishB2c(String id) {
        owner().update("""
            INSERT INTO messaging.outbox_event (event_id, tenant_id, event_type, aggregate_type, aggregate_id, payload)
            VALUES (?::uuid, ?::uuid, 'MpesaB2cDisbursementApproved', 'b2c_disbursement', ?, ?::jsonb)
            """, UUID.randomUUID().toString(), A, id, "{\"disbursementId\":\"" + id + "\",\"shortCode\":\"600000\"}");
    }

    static List<DarajaSimulator.B2cCall> b2cCallsFor(String id) {
        return SIM.b2cCalls().stream().filter(c -> c.originatorConversationId().equals(id)).toList();
    }

    @Test
    void b2cDisbursementEndToEndThroughSimulator() throws Exception {
        String paid = disbursement("254712345678");
        String noFloat = disbursement("254712345001");
        String timedOut = disbursement("254712345777");
        String twice = disbursement("254712345222");
        String refused = disbursement("254712345400");
        String unknown = disbursement("254712345500");

        await("B2C outcomes", () -> calls("b2c-result", paid).size() == 1 && calls("b2c-result", noFloat).size() == 1
                && calls("b2c-timeout", timedOut).size() == 1 && calls("b2c-result", twice).size() == 1
                && calls("b2c-send-failure", refused).size() == 1
                && SIM.deliveries().stream().filter(d -> d.url().endsWith(twice)).count() == 2);

        // Paid: the simulator accepted the encrypted initiator credential, our id was the
        // OriginatorConversationID, and the result was mapped without the receiver's details.
        DarajaSimulator.B2cCall call = b2cCallsFor(paid).get(0);
        assertEquals(200, call.httpStatus());
        assertEquals("98000", call.amount());
        assertEquals("254712345678", call.partyB());
        assertTrue(calls("b2c-submitted", paid).get(0).contains(call.conversationId()));
        String result = calls("b2c-result", paid).get(0);
        assertTrue(result.contains("\"resultCode\":\"0\""), result);
        assertTrue(result.contains("\"amount\":\"98000.00\""), result);
        assertTrue(result.matches(".*\"transactionId\":\"SIM[A-Z0-9]{7}\".*"), result);
        assertTrue(result.contains("\"conversationId\":\"" + call.conversationId() + "\""), result);
        assertTrue(!result.contains("254712345678") && !result.contains("TEST CUSTOMER"), "receiver details not forwarded");

        String failed = calls("b2c-result", noFloat).get(0);
        assertTrue(failed.contains("\"resultCode\":\"1\"") && !failed.contains("transactionId"), failed);
        assertEquals(1, owner().queryForObject("""
            SELECT count(*) FROM integration.provider_callback WHERE callback_type = 'B2C_RESULT' AND route_ref = ?
            """, Integer.class, twice));
        assertTrue(calls("b2c-send-failure", refused).get(0).contains("400.002.02"));
        await("unknown B2C outcome", () -> !b2cCallsFor(unknown).isEmpty());
        Thread.sleep(500);
        assertEquals(List.of(), calls("b2c-send-failure", unknown));
        assertEquals(List.of(), calls("b2c-submitted", unknown));

        // A redelivered event never sends money twice.
        publishB2c(paid);
        await("B2C redelivery handled", () -> DISPATCHES.getOrDefault(paid, 0) == 2);
        Thread.sleep(500);
        assertEquals(1, b2cCallsFor(paid).size());

        // A result whose OriginatorConversationID is not the disbursement in the URL is refused.
        HttpResponse<String> misrouted = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + PORT + "/daraja/b2c/test-only-b2c-token-41aa/result/" + paid))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"Result\":{\"ResultCode\":0,\"OriginatorConversationID\":\""
                    + UUID.randomUUID() + "\"}}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, misrouted.statusCode());
    }
}
