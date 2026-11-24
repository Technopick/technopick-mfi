package ke.co.technopick.mfi.simulators.daraja;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.json.JsonMapper;

/**
 * TEST-ONLY SMS provider simulator (ADR-0035). Implements the small JSON contract of the
 * integration runtime's HttpSmsGateway: {@code POST /messages} answers {@code {"id":...}} and, half
 * a second later, posts a delivery report to the message's callback URL. Scripted by the last three
 * digits of the recipient: 998 answers 503 (try later), 997 is reported FAILED, anything else is
 * delivered. A browser page shows each phone's inbox. Nothing is ever sent to a real phone. Every
 * response carries {@code X-Simulator: TEST-ONLY SMS simulator}.
 */
public final class SmsProviderSimulator implements AutoCloseable {

    public static final String LABEL = "TEST-ONLY SMS simulator";

    public record Sms(String id, String to, String from, String message, Instant at, String status) { }

    private final HttpServer server;
    private final String apiKey;
    private final List<Sms> inbox = new CopyOnWriteArrayList<>();
    private final AtomicLong ids = new AtomicLong();
    private final ScheduledExecutorService later = Executors.newSingleThreadScheduledExecutor();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final JsonMapper json = JsonMapper.builder().build();

    private SmsProviderSimulator(String host, int port, String apiKey) throws IOException {
        this.apiKey = apiKey;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/messages", this::messages);
        server.createContext("/", this::page);
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
    }

    public static SmsProviderSimulator start(int port, String apiKey) throws IOException {
        return new SmsProviderSimulator("127.0.0.1", port, apiKey);
    }

    static SmsProviderSimulator start(String host, int port, String apiKey) throws IOException {
        return new SmsProviderSimulator(host, port, apiKey);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public List<Sms> inbox() {
        return List.copyOf(inbox);
    }

    private void messages(HttpExchange ex) throws IOException {
        try (ex) {
            if ("GET".equals(ex.getRequestMethod())) {
                String to = query(ex, "to");
                respond(ex, 200, "application/json", json.writeValueAsString(
                        inbox.stream().filter(s -> to == null || s.to().equals(to)).toList()));
                return;
            }
            if (!"POST".equals(ex.getRequestMethod())) {
                respond(ex, 405, "application/json", "{}");
                return;
            }
            if (apiKey != null && !("Bearer " + apiKey).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                respond(ex, 401, "application/json", "{\"error\":\"bad API key\"}");
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> in = json.readValue(ex.getRequestBody().readAllBytes(), Map.class);
            String to = String.valueOf(in.get("to"));
            if (to.endsWith("998")) {
                respond(ex, 503, "application/json", "{\"error\":\"simulated outage\"}");
                return;
            }
            String id = "SIMSMS" + ids.incrementAndGet();
            boolean fails = to.endsWith("997");
            Object from = in.get("from");
            inbox.add(new Sms(id, to, from == null ? null : String.valueOf(from), String.valueOf(in.get("message")), Instant.now(),
                    fails ? "FAILED" : "DELIVERED"));
            Object callback = in.get("callbackUrl");
            if (callback != null) {
                later.schedule(() -> report(String.valueOf(callback), id, fails), 500, TimeUnit.MILLISECONDS);
            }
            respond(ex, 201, "application/json", json.writeValueAsString(Map.of("id", id, "status", "Sent")));
        }
    }

    private void report(String url, String id, boolean fails) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("status", fails ? "Failed" : "Delivered");
        if (fails) {
            body.put("reason", "simulated: absent subscriber");
        }
        try {
            client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            System.err.println(LABEL + ": delivery report for " + id + " not delivered: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void page(HttpExchange ex) throws IOException {
        try (ex) {
            if (!"/".equals(ex.getRequestURI().getPath())) {
                respond(ex, 404, "text/plain", "not found");
                return;
            }
            String to = query(ex, "to");
            List<Sms> shown = new ArrayList<>(inbox.stream().filter(s -> to == null || s.to().equals(to)).toList());
            java.util.Collections.reverse(shown);
            StringBuilder rows = new StringBuilder();
            for (Sms s : shown) {
                rows.append("<li><div class=\"meta\">").append(html(s.at().toString())).append(" - to ").append(html(s.to()))
                    .append(" from ").append(html(s.from() == null ? "(default)" : s.from())).append(" - ").append(s.status())
                    .append("</div><div class=\"text\">").append(html(s.message())).append("</div></li>");
            }
            respond(ex, 200, "text/html; charset=utf-8", PAGE.replace("{TO}", html(to == null ? "" : to))
                    .replace("{ROWS}", rows.isEmpty() ? "<li>No messages.</li>" : rows.toString()));
        }
    }

    private static String query(HttpExchange ex, String name) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) {
            return null;
        }
        for (String part : q.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(name)) {
                String v = URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8).strip();
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    private static String html(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static void respond(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.getResponseHeaders().add("X-Simulator", LABEL);
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        later.shutdownNow();
        server.stop(0);
    }

    private static final String PAGE = """
        <!DOCTYPE html>
        <html lang="en"><head><meta charset="utf-8"><title>TEST-ONLY SMS simulator</title>
        <style>
          body { font-family: system-ui, sans-serif; background: #f3f3f3; margin: 0; padding: 24px; }
          .banner { background: #b00020; color: #fff; padding: 8px 12px; font-weight: 600; margin-bottom: 16px; }
          ul { list-style: none; padding: 0; max-width: 640px; }
          li { background: #fff; border-radius: 8px; padding: 10px 12px; margin-bottom: 8px; }
          .meta { color: #555; font-size: 12px; margin-bottom: 4px; }
        </style></head>
        <body>
          <div class="banner">TEST-ONLY SMS simulator - nothing is sent to real phones.</div>
          <form method="get"><label for="to">Phone</label> <input id="to" name="to" value="{TO}"> <button>Show inbox</button></form>
          <h1>Inbox</h1>
          <ul>{ROWS}</ul>
        </body></html>
        """;
}
