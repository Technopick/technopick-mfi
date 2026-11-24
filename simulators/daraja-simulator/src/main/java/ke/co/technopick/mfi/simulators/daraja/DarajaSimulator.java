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
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
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
 * TEST-ONLY simulator of Safaricom Daraja: OAuth token, STK Push request, STK Push query, B2C
 * payment request (v3) and asynchronous result / queue-timeout callbacks. It implements the request/response shapes of the public Daraja
 * documentation closely enough to exercise the integration runtime; it is not a certification
 * environment and never moves money. Receipt numbers start with {@code SIM} so simulator data is
 * recognisable anywhere it ends up.
 *
 * <p>Outcome is scripted by the last three digits of the phone number:
 * <ul>
 *   <li>{@code 032} customer cancels (ResultCode 1032)</li>
 *   <li>{@code 001} insufficient balance (ResultCode 1)</li>
 *   <li>{@code 037} customer unreachable (ResultCode 1037)</li>
 *   <li>{@code 999} customer pays but no callback is ever sent (STK query reports success)</li>
 *   <li>{@code 997} customer cancels but no callback is ever sent (STK query reports 1032)</li>
 *   <li>{@code 998} no callback, and STK query keeps answering "being processed"</li>
 *   <li>{@code 222} success, callback delivered twice (duplicate delivery)</li>
 *   <li>{@code 400} request rejected with HTTP 400 (no prompt shown)</li>
 *   <li>{@code 500} HTTP 500 (outcome unknown to the caller; no prompt shown)</li>
 *   <li>anything else: success</li>
 * </ul>
 *
 * <p>B2C outcome is scripted by the last three digits of PartyB: {@code 001} insufficient float
 * (ResultCode 1), {@code 777} queue timeout (callback to QueueTimeOutURL), {@code 999} no callback,
 * {@code 222} result delivered twice, {@code 400} refused, {@code 500} provider error, else success.
 * The SecurityCredential is checked by decrypting it with the simulator's own RSA key; tests
 * configure the integration runtime with {@link #publicKeyPem()} in place of Safaricom's certificate.
 */
public final class DarajaSimulator implements AutoCloseable {

    public static final String LABEL = "TEST-ONLY Daraja simulator";
    private static final ZoneId EAT = ZoneId.of("Africa/Nairobi");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String ALNUM = "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789";

    /** One STK Push request as received, for test assertions. */
    public record StkCall(String shortCode, String phone, String amount, String accountReference, String callbackUrl,
                          String checkoutRequestId, int httpStatus) { }

    /** One B2C payment request as received, for test assertions. */
    public record B2cCall(String originatorConversationId, String shortCode, String partyB, String amount,
                          String conversationId, int httpStatus) { }

    /** B2C initiator credentials per short code (TEST values). */
    public record Initiator(String name, String password) { }

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
    private final List<String> queries = new CopyOnWriteArrayList<>();
    private final List<B2cCall> b2cCalls = new CopyOnWriteArrayList<>();
    private final Map<String, Initiator> initiators;
    private final KeyPair keys;

    private DarajaSimulator(String consumerKey, String consumerSecret, Map<String, String> passkeys,
                            Map<String, Initiator> initiators, long callbackDelayMs, String host, int port)
            throws IOException {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            this.keys = generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        this.initiators = Map.copyOf(initiators);
        this.consumerKey = consumerKey;
        this.consumerSecret = consumerSecret;
        this.passkeys = Map.copyOf(passkeys);
        this.callbackDelayMs = callbackDelayMs;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/oauth/v1/generate", this::token);
        server.createContext("/mpesa/stkpush/v1/processrequest", this::stkPush);
        server.createContext("/mpesa/stkpushquery/v1/query", this::stkQuery);
        server.createContext("/mpesa/b2c/v3/paymentrequest", this::b2c);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
    }

    /** Starts on the loopback interface; port 0 picks a free port. */
    public static DarajaSimulator start(String consumerKey, String consumerSecret, Map<String, String> passkeysByShortCode,
                                        long callbackDelayMs, int port) throws IOException {
        return start(consumerKey, consumerSecret, passkeysByShortCode, Map.of(), callbackDelayMs, port);
    }

    /** As {@link #start(String, String, Map, long, int)}, with B2C initiators per short code. */
    public static DarajaSimulator start(String consumerKey, String consumerSecret, Map<String, String> passkeysByShortCode,
                                        Map<String, Initiator> b2cInitiators, long callbackDelayMs, int port)
            throws IOException {
        return new DarajaSimulator(consumerKey, consumerSecret, passkeysByShortCode, b2cInitiators, callbackDelayMs,
                "127.0.0.1", port);
    }

    /** Public key (X.509 SubjectPublicKeyInfo PEM) to encrypt B2C initiator passwords for this simulator. */
    public String publicKeyPem() {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(keys.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
    }

    public List<B2cCall> b2cCalls() {
        return List.copyOf(b2cCalls);
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<StkCall> stkCalls() {
        return List.copyOf(stkCalls);
    }

    /** CheckoutRequestIDs queried via the STK query API (password-checked), in order. */
    public List<String> queries() {
        return List.copyOf(queries);
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
        if (!phone.endsWith("998")) {
            results.put(checkout, callbackBody);
        }
        if (!phone.endsWith("999") && !phone.endsWith("998") && !phone.endsWith("997")) {
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
        if (phone.endsWith("032") || phone.endsWith("997")) {
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
        Map<String, Object> inner = body.containsKey("Body") ? castMap(castMap(body.get("Body")).get("stkCallback"))
                : castMap(body.get("Result"));
        Object code = inner.get("ResultCode");
        int resultCode = code instanceof Number n ? n.intValue() : -1;
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
        String shortCode = str(r.get("BusinessShortCode"));
        String passkey = passkeys.get(shortCode);
        if (passkey == null || !password(shortCode, passkey, str(r.get("Timestamp"))).equals(str(r.get("Password")))) {
            reply(ex, 400, Map.of("errorCode", "400.002.02", "errorMessage", "Bad Request - Invalid Password"));
            return;
        }
        queries.add(str(r.get("CheckoutRequestID")));
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

    private boolean credentialValid(String credential, String expectedPassword) {
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.DECRYPT_MODE, keys.getPrivate());
            String plain = new String(cipher.doFinal(Base64.getDecoder().decode(credential)), StandardCharsets.UTF_8);
            return plain.equals(expectedPassword);
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    private void b2c(HttpExchange ex) throws IOException {
        if (!authorised(ex)) {
            reply(ex, 401, Map.of("errorCode", "404.001.04", "errorMessage", "Invalid Access Token"));
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> r = json.readValue(ex.getRequestBody().readAllBytes(), Map.class);
        String originator = str(r.get("OriginatorConversationID"));
        String shortCode = str(r.get("PartyA"));
        String partyB = str(r.get("PartyB"));
        String amount = str(r.get("Amount"));
        String resultUrl = str(r.get("ResultURL"));
        String timeoutUrl = str(r.get("QueueTimeOutURL"));
        Initiator initiator = initiators.get(shortCode);
        String error = null;
        if (initiator == null) {
            error = "Invalid PartyA";
        } else if (!initiator.name().equals(str(r.get("InitiatorName")))) {
            error = "Invalid InitiatorName";
        } else if (!credentialValid(str(r.get("SecurityCredential")), initiator.password())) {
            error = "Invalid SecurityCredential";
        } else if (originator.isEmpty()) {
            error = "Invalid OriginatorConversationID";
        } else if (!List.of("BusinessPayment", "SalaryPayment", "PromotionPayment").contains(str(r.get("CommandID")))) {
            error = "Invalid CommandID";
        } else if (!amount.matches("^[1-9][0-9]{1,5}$")) {
            error = "Invalid Amount";
        } else if (!partyB.matches("^254[17][0-9]{8}$") || partyB.endsWith("400")) {
            error = "Invalid PartyB";
        } else if (!resultUrl.startsWith("http") || !timeoutUrl.startsWith("http")) {
            error = "Invalid ResultURL";
        }
        if (error != null) {
            b2cCalls.add(new B2cCall(originator, shortCode, partyB, amount, null, 400));
            reply(ex, 400, Map.of("requestId", UUID.randomUUID().toString(), "errorCode", "400.002.02",
                    "errorMessage", "Bad Request - " + error));
            return;
        }
        if (partyB.endsWith("500")) {
            b2cCalls.add(new B2cCall(originator, shortCode, partyB, amount, null, 500));
            reply(ex, 500, Map.of("requestId", UUID.randomUUID().toString(), "errorCode", "500.003.1001",
                    "errorMessage", "Simulated internal error"));
            return;
        }
        String conversation = "AG_" + ZonedDateTime.now(EAT).format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "_"
                + random.nextInt(100_000_000, 999_999_999);
        b2cCalls.add(new B2cCall(originator, shortCode, partyB, amount, conversation, 200));
        if (partyB.endsWith("777")) {
            Map<String, Object> timeout = Map.of("Result", Map.of("ResultType", 1, "ResultCode", "SIM-TIMEOUT",
                    "ResultDesc", "Simulated queue timeout", "OriginatorConversationID", originator,
                    "ConversationID", conversation));
            scheduler.schedule(() -> deliver(timeoutUrl, conversation, timeout), callbackDelayMs, TimeUnit.MILLISECONDS);
        } else if (!partyB.endsWith("999")) {
            Map<String, Object> result = b2cResult(originator, conversation, partyB, amount);
            int times = partyB.endsWith("222") ? 2 : 1;
            for (int i = 0; i < times; i++) {
                scheduler.schedule(() -> deliver(resultUrl, conversation, result), callbackDelayMs * (i + 1),
                        TimeUnit.MILLISECONDS);
            }
        }
        reply(ex, 200, Map.of("ConversationID", conversation, "OriginatorConversationID", originator,
                "ResponseCode", "0", "ResponseDescription", "Accept the service request successfully."));
    }

    private Map<String, Object> b2cResult(String originator, String conversation, String partyB, String amount) {
        StringBuilder txn = new StringBuilder("SIM");
        for (int i = 0; i < 7; i++) {
            txn.append(ALNUM.charAt(random.nextInt(ALNUM.length())));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ResultType", 0);
        result.put("OriginatorConversationID", originator);
        result.put("ConversationID", conversation);
        result.put("TransactionID", txn.toString());
        if (partyB.endsWith("001")) {
            result.put("ResultCode", 1);
            result.put("ResultDesc", "The balance is insufficient for the transaction.");
        } else {
            result.put("ResultCode", 0);
            result.put("ResultDesc", "The service request is processed successfully.");
            List<Map<String, Object>> params = new ArrayList<>();
            params.add(Map.of("Key", "TransactionAmount", "Value", new BigDecimal(amount)));
            params.add(Map.of("Key", "TransactionReceipt", "Value", txn.toString()));
            params.add(Map.of("Key", "ReceiverPartyPublicName", "Value", partyB + " - TEST CUSTOMER"));
            params.add(Map.of("Key", "TransactionCompletedDateTime",
                    "Value", ZonedDateTime.now(EAT).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"))));
            params.add(Map.of("Key", "B2CUtilityAccountAvailableFunds", "Value", new BigDecimal("1000000.00")));
            params.add(Map.of("Key", "B2CRecipientIsRegisteredCustomer", "Value", "Y"));
            result.put("ResultParameters", Map.of("ResultParameter", params));
        }
        return Map.of("Result", result);
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
     * Standalone use for local development and the TEST-ONLY end-to-end stack. Refuses to start
     * unless {@code DARAJA_SIMULATOR_ACK=TEST_ONLY_NOT_FOR_PRODUCTION}. Listens on 127.0.0.1 unless
     * {@code DARAJA_SIM_BIND} says otherwise (a container sets 0.0.0.0 on its private network).
     * Credentials come from the environment and are test values, never real Daraja keys. If
     * {@code DARAJA_SIM_PUBLIC_KEY_FILE} is set, the public key for B2C SecurityCredential
     * encryption is written there (the key pair is generated at start and never stored).
     */
    public static void main(String[] args) throws IOException {
        Map<String, String> env = System.getenv();
        if (!"TEST_ONLY_NOT_FOR_PRODUCTION".equals(env.get("DARAJA_SIMULATOR_ACK"))) {
            System.err.println(LABEL + ": refusing to start. Set DARAJA_SIMULATOR_ACK=TEST_ONLY_NOT_FOR_PRODUCTION.");
            System.exit(2);
        }
        String shortCode = env.getOrDefault("DARAJA_SIM_SHORTCODE", "174379");
        String b2cShortCode = env.getOrDefault("DARAJA_SIM_B2C_SHORTCODE", "600000");
        Map<String, Initiator> initiators = env.containsKey("DARAJA_SIM_B2C_INITIATOR_PASSWORD")
                ? Map.of(b2cShortCode, new Initiator(env.getOrDefault("DARAJA_SIM_B2C_INITIATOR_NAME", "testapi"),
                        env.get("DARAJA_SIM_B2C_INITIATOR_PASSWORD")))
                : Map.of();
        DarajaSimulator sim = new DarajaSimulator(env.getOrDefault("DARAJA_SIM_CONSUMER_KEY", "sim-key"),
                env.getOrDefault("DARAJA_SIM_CONSUMER_SECRET", "sim-secret"),
                Map.of(shortCode, env.getOrDefault("DARAJA_SIM_PASSKEY", "sim-passkey")), initiators,
                Long.parseLong(env.getOrDefault("DARAJA_SIM_CALLBACK_DELAY_MS", "1000")),
                env.getOrDefault("DARAJA_SIM_BIND", "127.0.0.1"),
                Integer.parseInt(env.getOrDefault("DARAJA_SIM_PORT", "18090")));
        String keyFile = env.get("DARAJA_SIM_PUBLIC_KEY_FILE");
        if (keyFile != null && !keyFile.isBlank()) {
            java.nio.file.Path path = java.nio.file.Path.of(keyFile);
            if (path.getParent() != null) {
                java.nio.file.Files.createDirectories(path.getParent());
            }
            java.nio.file.Files.writeString(path, sim.publicKeyPem(), StandardCharsets.US_ASCII);
        }
        System.out.println(LABEL + " listening on " + sim.server.getAddress() + " - NOT FOR PRODUCTION");
        String ussdTarget = env.get("USSD_SIM_TARGET_URL");
        if (ussdTarget != null && !ussdTarget.isBlank()) {
            UssdHandsetSimulator handset = UssdHandsetSimulator.start(env.getOrDefault("DARAJA_SIM_BIND", "127.0.0.1"),
                    Integer.parseInt(env.getOrDefault("USSD_SIM_PORT", "18091")), URI.create(ussdTarget),
                    env.getOrDefault("USSD_SIM_SERVICE_CODE", "*483*1#"));
            System.out.println(UssdHandsetSimulator.LABEL + " listening on port " + handset.port() + " - NOT FOR PRODUCTION");
        }
        String smsPort = env.get("SMS_SIM_PORT");
        if (smsPort != null && !smsPort.isBlank()) {
            SmsProviderSimulator sms = SmsProviderSimulator.start(env.getOrDefault("DARAJA_SIM_BIND", "127.0.0.1"),
                    Integer.parseInt(smsPort), env.get("SMS_SIM_API_KEY"));
            System.out.println(SmsProviderSimulator.LABEL + " listening on port " + sms.port() + " - NOT FOR PRODUCTION");
        }
    }
}
