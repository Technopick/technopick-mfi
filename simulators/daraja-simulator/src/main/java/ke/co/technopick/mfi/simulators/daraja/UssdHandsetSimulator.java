package ke.co.technopick.mfi.simulators.daraja;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import tools.jackson.databind.json.JsonMapper;

/**
 * TEST-ONLY USSD handset and aggregator simulator (ADR-0034). A browser page shows a phone screen;
 * each key press is sent, like a Kenyan USSD aggregator does, as a form POST with sessionId,
 * serviceCode, phoneNumber and the cumulative text to the integration runtime's USSD callback, and
 * the "CON"/"END" answer is shown. It never talks to a real network or a real phone. Every response
 * carries {@code X-Simulator: TEST-ONLY USSD simulator}.
 */
public final class UssdHandsetSimulator implements AutoCloseable {

    public static final String LABEL = "TEST-ONLY USSD simulator";

    /** One request as forwarded, for test assertions (never holds the text: it may contain a PIN). */
    public record Forwarded(String sessionId, String phone, int inputs, int httpStatus, String response) { }

    private final HttpServer server;
    private final URI target;
    private final String serviceCode;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<Forwarded> forwarded = new CopyOnWriteArrayList<>();

    private UssdHandsetSimulator(String host, int port, URI target, String serviceCode) throws IOException {
        this.target = target;
        this.serviceCode = serviceCode;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/dial", this::dial);
        server.createContext("/", this::page);
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
    }

    /** Starts on the loopback interface; port 0 picks a free port. */
    public static UssdHandsetSimulator start(int port, URI target, String serviceCode) throws IOException {
        return new UssdHandsetSimulator("127.0.0.1", port, target, serviceCode);
    }

    static UssdHandsetSimulator start(String host, int port, URI target, String serviceCode) throws IOException {
        return new UssdHandsetSimulator(host, port, target, serviceCode);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public List<Forwarded> forwarded() {
        return List.copyOf(forwarded);
    }

    /** Sends one aggregator request and returns the raw "CON ..." / "END ..." answer. */
    public String send(String sessionId, String phone, String text) throws IOException, InterruptedException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("sessionId", sessionId);
        form.put("serviceCode", serviceCode);
        form.put("phoneNumber", phone);
        form.put("text", text);
        form.put("networkCode", "63902");
        String body = form.entrySet().stream()
            .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        String answer = r.statusCode() == 200 ? r.body() : "END (simulator) HTTP " + r.statusCode();
        forwarded.add(new Forwarded(sessionId, phone, text.isEmpty() ? 0 : text.split("\\*", -1).length, r.statusCode(), answer));
        return answer;
    }

    private void dial(HttpExchange ex) throws IOException {
        try (ex) {
            if (!"POST".equals(ex.getRequestMethod())) {
                respond(ex, 405, "application/json", "{}");
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> in = json.readValue(ex.getRequestBody().readAllBytes(), Map.class);
            String answer;
            try {
                answer = send(String.valueOf(in.get("sessionId")), String.valueOf(in.get("phone")), String.valueOf(in.getOrDefault("text", "")));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                answer = "END (simulator) interrupted";
            } catch (IOException e) {
                answer = "END (simulator) integration runtime not reachable";
            }
            respond(ex, 200, "application/json", json.writeValueAsString(Map.of("response", answer)));
        }
    }

    private void page(HttpExchange ex) throws IOException {
        try (ex) {
            if (!"/".equals(ex.getRequestURI().getPath())) {
                respond(ex, 404, "text/plain", "not found");
                return;
            }
            respond(ex, 200, "text/html; charset=utf-8", PAGE.replace("{CODE}", serviceCode));
        }
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
        server.stop(0);
    }

    private static final String PAGE = """
        <!DOCTYPE html>
        <html lang="en"><head><meta charset="utf-8"><title>TEST-ONLY USSD simulator</title>
        <style>
          body { font-family: system-ui, sans-serif; background: #f3f3f3; margin: 0; padding: 24px; }
          .banner { background: #b00020; color: #fff; padding: 8px 12px; font-weight: 600; margin-bottom: 16px; }
          .phone { width: 300px; background: #222; border-radius: 24px; padding: 20px; color: #fff; }
          #screen { background: #dfe8d0; color: #111; min-height: 180px; padding: 10px; white-space: pre-wrap;
                    font-family: ui-monospace, monospace; font-size: 14px; border-radius: 6px; }
          label { display: block; font-size: 13px; margin: 10px 0 4px; }
          input { width: 100%; box-sizing: border-box; padding: 6px; font-size: 16px; }
          button { margin-top: 8px; padding: 6px 12px; font-size: 15px; }
        </style></head>
        <body>
          <div class="banner">TEST-ONLY USSD simulator - not a real network. Never use real PINs or real phones.</div>
          <div class="phone">
            <label for="phone">Phone number</label>
            <input id="phone" value="+2547" autocomplete="off">
            <button id="dial">Dial {CODE}</button>
            <div id="screen" role="status" aria-live="polite">Ready.</div>
            <label for="reply">Reply</label>
            <input id="reply" autocomplete="off" disabled>
            <button id="send" disabled>Send</button>
            <button id="hangup" disabled>Hang up</button>
          </div>
        <script>
          let session = null, inputs = [];
          const $ = id => document.getElementById(id);
          function idle(text) { session = null; $('screen').textContent = text; $('reply').disabled = true;
            $('send').disabled = true; $('hangup').disabled = true; $('dial').disabled = false; }
          async function ask() {
            $('screen').textContent = 'Please wait...';
            $('dial').disabled = true; $('send').disabled = true; $('reply').disabled = true;
            const r = await fetch('/dial', { method: 'POST', headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ sessionId: session, phone: $('phone').value, text: inputs.join('*') }) });
            const answer = (await r.json()).response || 'END (simulator) no answer';
            const text = answer.substring(4);
            if (answer.startsWith('END ')) { idle(text); return; }
            $('screen').textContent = text; $('reply').disabled = false; $('reply').value = ''; $('reply').focus();
            $('send').disabled = false; $('hangup').disabled = false; $('dial').disabled = true;
          }
          $('dial').onclick = () => { session = 'SIM' + Date.now() + Math.floor(Math.random() * 1e6); inputs = []; ask(); };
          $('send').onclick = () => { inputs.push($('reply').value); ask(); };
          $('reply').addEventListener('keydown', e => { if (e.key === 'Enter' && !$('send').disabled) $('send').click(); });
          $('hangup').onclick = () => idle('Session ended.');
        </script>
        </body></html>
        """;
}
