package ke.co.technopick.mfi.simulators.daraja;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.json.JsonMapper;

/**
 * TEST-ONLY simulator of Safaricom Daraja: OAuth token, STK Push request, STK Push query and
 * asynchronous result callbacks. It implements the request/response shapes of the public Daraja
 * documentation closely enough to exercise the integration runtime; it is not a certification
 * environment and never moves money. Receipt numbers start with {@code SIM} so simulator data is
 * recognisable anywhere it ends up.
 *
 * <p>Outcome is scripted by the last three digits of the phone number:
 * <ul>
 *   <li>{@code 032} customer cancels (ResultCode 1032)</li>
 *   <li>{@code 001} insufficient balance (ResultCode 1)</li>
 *   <li>{@code 037} customer unreachable (ResultCode 1037)</li>
 *   <li>{@code 999} prompt accepted but no callback is ever sent</li>
 *   <li>{@code 222} success, callback delivered twice (duplicate delivery)</li>
 *   <li>{@code 400} request rejected with HTTP 400 (no prompt shown)</li>
 *   <li>{@code 500} HTTP 500 (outcome unknown to the caller; no prompt shown)</li>
 *   <li>anything else: success</li>
 * </ul>
 */
public final class DarajaSimulator implements AutoCloseable {

    public static final String LABEL = "TEST-ONLY Daraja simulator";
    private static final ZoneId EAT = ZoneId.of("Africa/Nairobi");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String ALNUM = "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789";

    /** One STK Push request as received, for test assertions. */
    public record StkCall(String shortCode, String phone, String amount, String accountReference, String callbackUrl,
                          String checkoutRequestId, int httpStatus) { }

    /** One callback delivery attempt, for test assertions. */
    public record Delivery(String checkoutRequestId, String url, int resultCode, int httpStatus) { }

    private final String consumerKey;
    private final String consumerSecret;
    private final Map<String, String> passkeys;
    private final long callbackDelayMs;
    private final HttpServer server;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "daraja-sim-callbacks");
        t.setDaemon(true);
        return t;
    });
    private final JsonMapper json = JsonMapper.builder().build();
    private final SecureRandom random = new SecureRandom();
    private final Map<String, String> tokens = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> results = new ConcurrentHashMap<>();
    private final List<StkCall> stkCalls = new CopyOnWriteArrayList<>();
    private final List<Delivery> deliveries = new CopyOnWriteArrayList<>();

    private DarajaSimulator(String consumerKey, String consumerSecret, Map<String, String> passkeys, long callbackDelayMs,
                            String host, int port) throws IOException {
        this.consumerKey = consumerKey;
        this.consumerSecret = consumerSecret;
        this.passkeys = Map.copyOf(passkeys);
        this.callbackDelayMs = callbackDelayMs;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/oauth/v1/generate", this::token);
        server.createContext("/mpesa/stkpush/v1/processrequest", this::stkPush);
        server.createContext("/mpesa/stkpushquery/v1/query", this::stkQuery);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
    }

    /** Starts on the loopback interface; port 0 picks a free port. */
    public static DarajaSimulator start(String consumerKey, String consumerSecret, Map<String, String> passkeysByShortCode,
                                        long callbackDelayMs, int port) throws IOException {
        return new DarajaSimulator(consumerKey, consumerSecret, passkeysByShortCode, callbackDelayMs, "127.0.0.1", port);
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<StkCall> stkCalls() {
        return List.copyOf(stkCalls);
    }

    public List<Delivery> deliveries() {
        return List.copyOf(deliveries);
    }

    /** Password as Daraja defines it: Base64(shortCode + passkey + timestamp). */
    public static String password(String shortCode, String passkey, String timestamp) {
        return Base64.getEncoder().encodeToString((shortCode + passkey + timestamp).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        server.stop(0);
        scheduler.shutdownNow();
    }

    private void token(HttpExchange ex) throws IOException {
        String expected = "Basic " + Base64.getEncoder().encodeToString(
                (consumerKey + ":" + consumerSecret).getBytes(StandardCharsets.UTF_8));
        if (!"GET".equals(ex.getRequestMethod()) || !expected.equals(ex.getRequestHeaders().getFirst("Authorization"))
                || ex.getRequestURI().getQuery() == null
                || !ex.getRequestURI().getQuery().contains("grant_type=client_credentials")) {
            reply(ex, 400, Map.of("errorCode", "400.008.01", "errorMessage", "Invalid Authentication passed"));
            return;
        }
        String token = "SIM" + UUID.randomUUID().toString().replace("-", "");
        tokens.put(token, "valid");
        reply(ex, 200, Map.of("access_token", token, "expires_in", "3599"));
    }

    private boolean authorised(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        return h != null && h.startsWith("Bearer ") && tokens.containsKey(h.substring(7));
    }

    private void stkPush(HttpExchange ex) throws IOException {
        if (!authorised(ex)) {
            reply(ex, 401, Map.of("errorCode", "404.001.04", "errorMessage", "Invalid Access Token"));
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> r = json.readValue(ex.getRequestBody().readAllBytes(), Map.class);
        String shortCode = str(r.get("BusinessShortCode"));
        String timestamp = str(r.get("Timestamp"));
        String phone = str(r.get("PhoneNumber"));
        String amount = str(r.get("Amount"));
        String accountRef = str(r.get("AccountReference"));
        String callback = str(r.get("CallBackURL"));
        String error = null;
        String passkey = passkeys.get(shortCode);
        if (passkey == null) {
            error = "Invalid BusinessShortCode";
        } else if (!password(shortCode, passkey, timestamp).equals(str(r.get("Password")))) {
            error = "Invalid Password";
        } else if (!"CustomerPayBillOnline".equals(str(r.get("TransactionType")))) {
            error = "Invalid TransactionType";
        } else if (!amount.matches("^[1-9][0-9]{0,5}$")) {
            error = "Invalid Amount";
        } else if (!phone.matches("^254[17][0-9]{8}$") || !phone.equals(str(r.get("PartyA")))) {
            error = "Invalid PhoneNumber";
        } else if (!shortCode.equals(str(r.get("PartyB")))) {
            error = "Invalid PartyB";
        } else if (accountRef.isEmpty() || accountRef.length() > 12) {
            error = "Invalid AccountReference";
        } else if (!callback.startsWith("http")) {
            error = "Invalid CallBackURL";
        } else if (phone.endsWith("400")) {
            error = "Invalid PhoneNumber";
        }
        if (error != null) {
            stkCalls.add(new StkCall(shortCode, phone, amount, accountRef, callback, null, 400));
            reply(ex, 400, Map.of("requestId", UUID.randomUUID().toString(), "errorCode", "400.002.02",
                    "errorMessage", "Bad Request - " + error));
            return;
        }
        if (phone.endsWith("500")) {
            stkCalls.add(new StkCall(shortCode, phone, amount, accountRef, callback, null, 500));
            reply(ex, 500, Map.of("requestId", UUID.randomUUID().toString(), "errorCode", "500.001.1001",
                    "errorMessage", "Simulated internal error"));
            return;
        }
        String merchant = random.nextInt(10000, 99999) + "-" + random.nextInt(1000000, 9999999) + "-1";
        // Opaque id: timestamp plus random digits (never the customer's phone number).
        String checkout = "ws_CO_" + ZonedDateTime.now(EAT).format(DateTimeFormatter.ofPattern("ddMMyyyyHHmmssSSS"))
                + random.nextInt(100_000_000, 999_999_999);
        stkCalls.add(new StkCall(shortCode, phone, amount, accountRef, callback, checkout, 200));
        Map<String, Object> callbackBody = outcome(merchant, checkout, phone, amount);
        results.put(checkout, callbackBody);
        if (!phone.endsWith("999")) {
            int times = phone.endsWith("222") ? 2 : 1;
            for (int i = 0; i < times; i++) {
                scheduler.schedule(() -> deliver(callback, checkout, callbackBody), callbackDelayMs * (i + 1),
                        TimeUnit.MILLISECONDS);
            }
        }
        reply(ex, 200, Map.of("MerchantRequestID", merchant, "CheckoutRequestID", checkout, "ResponseCode", "0",
                "ResponseDescription", "Success. Request accepted for processing",
                "CustomerMessage", "Success. Request accepted for processing"));
    }

    private Map<String, Object> outcome(String merchant, String checkout, String phone, String amount) {
        Map<String, Object> cb = new LinkedHashMap<>();
        cb.put("MerchantRequestID", merchant);
        cb.put("CheckoutRequestID", checkout);
        if (phone.endsWith("032")) {
            cb.put("ResultCode", 1032);
            cb.put("ResultDesc", "Request cancelled by user");
        } else if (phone.endsWith("001")) {
            cb.put("ResultCode", 1);
            cb.put("ResultDesc", "The balance is insufficient for the transaction");
        } else if (phone.endsWith("037")) {
            cb.put("ResultCode", 1037);
            cb.put("ResultDesc", "DS timeout user cannot be reached");
        } else {
            StringBuilder receipt = new StringBuilder("SIM");
            for (int i = 0; i < 7; i++) {
                receipt.append(ALNUM.charAt(random.nextInt(ALNUM.length())));
            }
            cb.put("ResultCode", 0);
            cb.put("ResultDesc", "The service request is processed successfully.");
            List<Map<String, Object>> items = new ArrayList<>();
            items.add(Map.of("Name", "Amount", "Value", new BigDecimal(amount).setScale(2)));
            items.add(Map.of("Name", "MpesaReceiptNumber", "Value", receipt.toString()));
            items.add(Map.of("Name", "TransactionDate", "Value", Long.parseLong(ZonedDateTime.now(EAT).format(STAMP))));
            items.add(Map.of("Name", "PhoneNumber", "Value", Long.parseLong(phone)));
            cb.put("CallbackMetadata", Map.of("Item", items));
        }
        return Map.of("Body", Map.of("stkCallback", cb));
    }

    private void deliver(String url, String checkout, Map<String, Object> body) {
        int resultCode = ((Number) castMap(castMap(body.get("Body")).get("stkCallback")).get("ResultCode")).intValue();
        try {
            HttpResponse<Void> response = client.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                    HttpResponse.BodyHandlers.discarding());
            deliveries.add(new Delivery(checkout, url, resultCode, response.statusCode()));
        } catch (IOException e) {
            deliveries.add(new Delivery(checkout, url, resultCode, -1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void stkQuery(HttpExchange ex) throws IOException {
        if (!authorised(ex)) {
            reply(ex, 401, Map.of("errorCode", "404.001.04", "errorMessage", "Invalid Access Token"));
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> r = json.readValue(ex.getRequestBody().readAllBytes(), Map.class);
        Map<String, Object> result = results.get(str(r.get("CheckoutRequestID")));
        if (result == null) {
            reply(ex, 500, Map.of("errorCode", "500.001.1001", "errorMessage", "The transaction is being processed"));
            return;
        }
        Map<String, Object> cb = castMap(castMap(result.get("Body")).get("stkCallback"));
        reply(ex, 200, Map.of("ResponseCode", "0", "ResponseDescription", "The service request has been accepted successsfully",
                "MerchantRequestID", cb.get("MerchantRequestID"), "CheckoutRequestID", cb.get("CheckoutRequestID"),
                "ResultCode", String.valueOf(cb.get("ResultCode")), "ResultDesc", cb.get("ResultDesc")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private void reply(HttpExchange ex, int status, Map<String, Object> body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.getResponseHeaders().add("X-Simulator", LABEL);
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    /**
     * Standalone use for local development only. Refuses to start unless
     * {@code DARAJA_SIMULATOR_ACK=TEST_ONLY_NOT_FOR_PRODUCTION}; listens on 127.0.0.1 only.
     * Credentials come from the environment and are test values, never real Daraja keys.
     */
    public static void main(String[] args) throws IOException {
        if (!"TEST_ONLY_NOT_FOR_PRODUCTION".equals(System.getenv("DARAJA_SIMULATOR_ACK"))) {
            System.err.println(LABEL + ": refusing to start. Set DARAJA_SIMULATOR_ACK=TEST_ONLY_NOT_FOR_PRODUCTION.");
            System.exit(2);
        }
        String shortCode = System.getenv().getOrDefault("DARAJA_SIM_SHORTCODE", "174379");
        DarajaSimulator sim = start(System.getenv().getOrDefault("DARAJA_SIM_CONSUMER_KEY", "sim-key"),
                System.getenv().getOrDefault("DARAJA_SIM_CONSUMER_SECRET", "sim-secret"),
                Map.of(shortCode, System.getenv().getOrDefault("DARAJA_SIM_PASSKEY", "sim-passkey")),
                Long.parseLong(System.getenv().getOrDefault("DARAJA_SIM_CALLBACK_DELAY_MS", "1000")),
                Integer.parseInt(System.getenv().getOrDefault("DARAJA_SIM_PORT", "18090")));
        System.out.println(LABEL + " listening on " + sim.baseUrl() + " - NOT FOR PRODUCTION");
    }
}
