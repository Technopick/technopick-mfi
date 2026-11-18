package ke.co.technopick.mfi.integration.daraja;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Transport to Safaricom Daraja (OAuth client credentials + STK Push). Outcomes are classified so
 * the caller never mistakes "unknown" for "failed": only a definitive refusal (HTTP 4xx, or a
 * non-zero ResponseCode) means no prompt was shown. Timeouts and 5xx are UNKNOWN; the result
 * callback, or reconciliation, settles them.
 */
@Component
public class DarajaClient {

    private static final Logger LOG = LoggerFactory.getLogger(DarajaClient.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() { };
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId EAT = ZoneId.of("Africa/Nairobi");

    public enum Kind { ACCEPTED, REJECTED, UNKNOWN }

    public record StkOutcome(Kind kind, String merchantRequestId, String checkoutRequestId, String code,
                             String description) { }

    private final DarajaProperties props;
    private final RestClient http;
    private final JsonMapper json = JsonMapper.builder().build();
    private String token;
    private Instant tokenExpires = Instant.EPOCH;

    public DarajaClient(DarajaProperties props) {
        this.props = props;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(20));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    public StkOutcome stkPush(DarajaProperties.Paybill paybill, String msisdn, String amount, String accountReference,
                              String description, String callbackUrl) {
        String timestamp = ZonedDateTime.now(EAT).format(STAMP);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("BusinessShortCode", paybill.shortCode());
        body.put("Password", Base64.getEncoder().encodeToString(
                (paybill.shortCode() + paybill.passkey() + timestamp).getBytes(StandardCharsets.UTF_8)));
        body.put("Timestamp", timestamp);
        body.put("TransactionType", "CustomerPayBillOnline");
        body.put("Amount", amount);
        body.put("PartyA", msisdn);
        body.put("PartyB", paybill.shortCode());
        body.put("PhoneNumber", msisdn);
        body.put("CallBackURL", callbackUrl);
        body.put("AccountReference", accountReference);
        body.put("TransactionDesc", description);
        String bearer;
        try {
            bearer = accessToken();
        } catch (RuntimeException e) {
            // Nothing was sent to the STK endpoint, so no prompt can have been shown.
            LOG.warn("Daraja OAuth failed: {}", e.toString());
            return new StkOutcome(Kind.REJECTED, null, null, "OAUTH", "Daraja access token unavailable");
        }
        try {
            Map<String, Object> r = Objects.requireNonNull(http.post()
                .uri(props.baseUrl() + "/mpesa/stkpush/v1/processrequest")
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(MAP));
            String code = String.valueOf(r.get("ResponseCode"));
            if ("0".equals(code)) {
                return new StkOutcome(Kind.ACCEPTED, str(r.get("MerchantRequestID")), str(r.get("CheckoutRequestID")),
                        code, str(r.get("ResponseDescription")));
            }
            return new StkOutcome(Kind.REJECTED, null, null, code, str(r.get("ResponseDescription")));
        } catch (HttpClientErrorException e) {
            Map<String, Object> err = parse(e.getResponseBodyAsString());
            return new StkOutcome(Kind.REJECTED, null, null,
                    str(err.getOrDefault("errorCode", "HTTP" + e.getStatusCode().value())),
                    str(err.getOrDefault("errorMessage", e.getStatusText())));
        } catch (RestClientException | IllegalStateException e) {
            LOG.warn("STK Push outcome unknown: {}", e.toString());
            return new StkOutcome(Kind.UNKNOWN, null, null, "UNKNOWN", e.getClass().getSimpleName());
        }
    }

    private Map<String, Object> parse(String body) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = json.readValue(body, Map.class);
            return m;
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private synchronized String accessToken() {
        if (token != null && Instant.now().isBefore(tokenExpires.minusSeconds(60))) {
            return token;
        }
        String basic = Base64.getEncoder().encodeToString(
                (props.consumerKey() + ":" + props.consumerSecret()).getBytes(StandardCharsets.UTF_8));
        Map<String, Object> r = Objects.requireNonNull(http.get()
            .uri(props.baseUrl() + "/oauth/v1/generate?grant_type=client_credentials")
            .header("Authorization", "Basic " + basic)
            .retrieve()
            .body(MAP));
        token = str(r.get("access_token"));
        tokenExpires = Instant.now().plusSeconds(Long.parseLong(String.valueOf(r.getOrDefault("expires_in", "3599"))));
        return token;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
