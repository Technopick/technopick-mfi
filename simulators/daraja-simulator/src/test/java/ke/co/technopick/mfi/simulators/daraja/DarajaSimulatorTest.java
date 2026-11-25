package ke.co.technopick.mfi.simulators.daraja;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.Cipher;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class DarajaSimulatorTest {

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    private Map<?, ?> call(String url, String auth, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("Authorization", auth);
        b = body == null ? b.GET() : b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        Map<?, ?> m = json.readValue(r.body(), Map.class);
        return Map.of("status", r.statusCode(), "body", m);
    }

    @Test
    void passwordIsBase64OfShortCodePasskeyTimestamp() {
        assertEquals(Base64.getEncoder().encodeToString("174379pk20261118110000".getBytes(StandardCharsets.UTF_8)),
                DarajaSimulator.password("174379", "pk", "20261118110000"));
    }

    @Test
    void rejectsWrongCredentialsAndWrongPassword() throws Exception {
        try (DarajaSimulator sim = DarajaSimulator.start("k", "s", Map.of("174379", "pk"), 10, 0)) {
            String bad = "Basic " + Base64.getEncoder().encodeToString("k:wrong".getBytes(StandardCharsets.UTF_8));
            assertEquals(400, call(sim.baseUrl() + "/oauth/v1/generate?grant_type=client_credentials", bad, null).get("status"));
            String good = "Basic " + Base64.getEncoder().encodeToString("k:s".getBytes(StandardCharsets.UTF_8));
            Map<?, ?> token = (Map<?, ?>) call(sim.baseUrl() + "/oauth/v1/generate?grant_type=client_credentials", good, null).get("body");
            String bearer = "Bearer " + token.get("access_token");
            String body = """
                {"BusinessShortCode":"174379","Password":"%s","Timestamp":"20261118110000",
                 "TransactionType":"CustomerPayBillOnline","Amount":"10","PartyA":"254712345678","PartyB":"174379",
                 "PhoneNumber":"254712345678","CallBackURL":"http://127.0.0.1:1/cb","AccountReference":"0100000001",
                 "TransactionDesc":"Deposit"}""";
            Map<?, ?> wrong = call(sim.baseUrl() + "/mpesa/stkpush/v1/processrequest", bearer, body.formatted("nope"));
            assertEquals(400, wrong.get("status"));
            Map<?, ?> right = call(sim.baseUrl() + "/mpesa/stkpush/v1/processrequest", bearer,
                    body.formatted(DarajaSimulator.password("174379", "pk", "20261118110000")));
            assertEquals(200, right.get("status"));
            assertEquals("0", ((Map<?, ?>) right.get("body")).get("ResponseCode"));
            assertTrue(String.valueOf(((Map<?, ?>) right.get("body")).get("CheckoutRequestID")).startsWith("ws_CO_"));
            assertEquals(401, call(sim.baseUrl() + "/mpesa/stkpush/v1/processrequest", "Bearer forged", body).get("status"));
        }
    }

    static String credential(DarajaSimulator sim, String password) throws Exception {
        String pem = sim.publicKeyPem().replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        var key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.ENCRYPT_MODE, key);
        return Base64.getEncoder().encodeToString(c.doFinal(password.getBytes(StandardCharsets.UTF_8)));
    }

    /** A B2C payment without any result callback is still found by the Transaction Status API; the float falls. */
    @Test
    void transactionStatusAndAccountBalanceAnswerWhatHappened() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        com.sun.net.httpserver.HttpServer callbacks = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        callbacks.createContext("/", ex -> {
            received.add(ex.getRequestURI().getPath() + " " + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        callbacks.start();
        String cb = "http://127.0.0.1:" + callbacks.getAddress().getPort();
        try (DarajaSimulator sim = DarajaSimulator.start("k", "s", Map.of(),
                Map.of("600000", new DarajaSimulator.Initiator("testapi", "TEST-ONLY-pw")), 10, 0)) {
            String basic = "Basic " + Base64.getEncoder().encodeToString("k:s".getBytes(StandardCharsets.UTF_8));
            String bearer = "Bearer " + ((Map<?, ?>) call(sim.baseUrl() + "/oauth/v1/generate?grant_type=client_credentials",
                    basic, null).get("body")).get("access_token");
            String cred = credential(sim, "TEST-ONLY-pw");
            Map<?, ?> pay = call(sim.baseUrl() + "/mpesa/b2c/v3/paymentrequest", bearer, """
                {"OriginatorConversationID":"d-1","InitiatorName":"testapi","SecurityCredential":"%s",
                 "CommandID":"BusinessPayment","Amount":"9800","PartyA":"600000","PartyB":"254712345999","Remarks":"x",
                 "QueueTimeOutURL":"%s/timeout","ResultURL":"%s/result","Occassion":""}""".formatted(cred, cb, cb));
            assertEquals(200, pay.get("status"));
            assertEquals(new java.math.BigDecimal("990200.00"), sim.utilityBalance("600000"));
            String query = """
                {"Initiator":"testapi","SecurityCredential":"%s","CommandID":"TransactionStatusQuery",
                 "OriginalConversationID":"%s","PartyA":"600000","IdentifierType":"4","Remarks":"x","Occasion":"",
                 "ResultURL":"%s/status","QueueTimeOutURL":"%s/status-timeout"}""";
            assertEquals(400, call(sim.baseUrl() + "/mpesa/transactionstatus/v1/query", bearer,
                    query.formatted("bad", "d-1", cb, cb)).get("status"));
            assertEquals(200, call(sim.baseUrl() + "/mpesa/transactionstatus/v1/query", bearer,
                    query.formatted(cred, "d-1", cb, cb)).get("status"));
            assertEquals(200, call(sim.baseUrl() + "/mpesa/transactionstatus/v1/query", bearer,
                    query.formatted(cred, "never-sent", cb, cb)).get("status"));
            assertEquals(200, call(sim.baseUrl() + "/mpesa/accountbalance/v1/query", bearer, """
                {"Initiator":"testapi","SecurityCredential":"%s","CommandID":"AccountBalance","PartyA":"600000",
                 "IdentifierType":"4","Remarks":"x","ResultURL":"%s/balance","QueueTimeOutURL":"%s/balance-timeout"}"""
                    .formatted(cred, cb, cb)).get("status"));
            long until = System.currentTimeMillis() + 5000;
            while (received.size() < 3 && System.currentTimeMillis() < until) {
                Thread.sleep(20);
            }
            String txn = sim.payments().get("d-1").transactionId();
            assertEquals(List.of("d-1", "never-sent"), sim.statusQueries());
            assertTrue(received.stream().anyMatch(r -> r.startsWith("/status ") && r.contains("\"Completed\"") && r.contains(txn)),
                    String.valueOf(received));
            assertTrue(received.stream().anyMatch(r -> r.startsWith("/status ") && r.contains("No such transaction")));
            assertTrue(received.stream().anyMatch(r -> r.startsWith("/balance ")
                    && r.contains("Utility Account|KES|990200.00|990200.00")), String.valueOf(received));
            assertTrue(received.stream().noneMatch(r -> r.startsWith("/result ")), "999: no result callback");
        } finally {
            callbacks.stop(0);
        }
    }
}
