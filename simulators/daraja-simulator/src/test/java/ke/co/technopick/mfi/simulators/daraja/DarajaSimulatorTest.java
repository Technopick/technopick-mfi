package ke.co.technopick.mfi.simulators.daraja;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
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
}
