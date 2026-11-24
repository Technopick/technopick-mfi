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

class UssdHandsetSimulatorTest {

    @Test
    void forwardsKeysLikeAnAggregatorAndShowsTheAnswer() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/ussd/t", ex -> {
            received.add(ex.getRequestHeaders().getFirst("Content-Type") + " "
                    + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] b = "CON Enter your PIN:".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        target.start();
        try (UssdHandsetSimulator sim = UssdHandsetSimulator.start(0,
                URI.create("http://127.0.0.1:" + target.getAddress().getPort() + "/ussd/t"), "*483*1#")) {
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<String> page = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + sim.port() + "/")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("TEST-ONLY USSD simulator"));
            assertEquals(UssdHandsetSimulator.LABEL, page.headers().firstValue("X-Simulator").orElseThrow());

            HttpResponse<String> dial = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + sim.port() + "/dial"))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"sessionId\":\"SIM1\",\"phone\":\"+254700000001\",\"text\":\"1*2\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals("{\"response\":\"CON Enter your PIN:\"}", dial.body());
            assertEquals(1, received.size());
            String form = received.getFirst();
            assertTrue(form.startsWith("application/x-www-form-urlencoded "), form);
            assertTrue(form.contains("sessionId=SIM1") && form.contains("serviceCode=*483*1%23")
                    && form.contains("phoneNumber=%2B254700000001") && form.contains("text=1*2"), form);
            assertEquals(2, sim.forwarded().getFirst().inputs());
        } finally {
            target.stop(0);
        }
    }
}
