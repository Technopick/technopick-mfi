package ke.co.technopick.mfi.simulators.daraja;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class SmsProviderSimulatorTest {

    @Test
    void acceptsMessagesReportsDeliveryAndShowsTheInbox() throws Exception {
        List<String> reports = new CopyOnWriteArrayList<>();
        HttpServer callback = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        callback.createContext("/dlr", ex -> {
            reports.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        callback.start();
        try (SmsProviderSimulator sim = SmsProviderSimulator.start(0, "test-only-key")) {
            HttpClient http = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + sim.port();
            String dlr = "http://127.0.0.1:" + callback.getAddress().getPort() + "/dlr";
            HttpResponse<String> unauthorised = http.send(HttpRequest.newBuilder(URI.create(base + "/messages"))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"to\":\"+254700000001\",\"message\":\"x\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, unauthorised.statusCode());
            HttpResponse<String> sent = send(http, base, "+254700000001", dlr);
            assertEquals(201, sent.statusCode());
            assertTrue(sent.body().contains("\"id\":\"SIMSMS1\""), sent.body());
            assertEquals(503, send(http, base, "+254700000998", dlr).statusCode());
            assertEquals(201, send(http, base, "+254700000997", dlr).statusCode());
            for (int i = 0; i < 50 && reports.size() < 2; i++) {
                Thread.sleep(100);
            }
            assertEquals(2, reports.size(), reports.toString());
            assertTrue(reports.stream().anyMatch(r -> r.contains("\"id\":\"SIMSMS1\"") && r.contains("\"status\":\"Delivered\"")));
            assertTrue(reports.stream().anyMatch(r -> r.contains("\"status\":\"Failed\"")));
            HttpResponse<String> page = http.send(HttpRequest.newBuilder(URI.create(base + "/?to=%2B254700000001")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(page.body().contains("TEST-ONLY SMS simulator") && page.body().contains("Hello &amp; welcome"), page.body());
            assertTrue(!page.body().contains("+254700000997"), "inbox is per phone");
            assertEquals(SmsProviderSimulator.LABEL, page.headers().firstValue("X-Simulator").orElseThrow());
        } finally {
            callback.stop(0);
        }
    }

    private static HttpResponse<String> send(HttpClient http, String base, String to, String dlr) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + "/messages")).header("Authorization", "Bearer test-only-key")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"to\":\"" + to + "\",\"from\":\"SACCO\",\"message\":\"Hello & welcome\",\"callbackUrl\":\"" + dlr + "\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
}
