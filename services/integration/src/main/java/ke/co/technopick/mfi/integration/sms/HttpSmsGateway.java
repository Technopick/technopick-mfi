package ke.co.technopick.mfi.integration.sms;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON SMS API: {@code POST <providerUrl>/messages} with a bearer key and
 * {@code {"to":"+2547...","from":"SENDER","message":"...","callbackUrl":"..."}}, answering
 * {@code {"id":"..."}}. 429 and 5xx (and timeouts) are retried; other 4xx are rejected.
 */
@Component
public class HttpSmsGateway implements SmsGateway {

    private final SmsProperties props;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json = JsonMapper.builder().build();

    public HttpSmsGateway(SmsProperties props) {
        this.props = props;
    }

    @Override
    public Result send(String msisdn, String senderId, String text, String deliveryCallbackUrl) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("to", "+" + msisdn);
        if (senderId != null && !senderId.isBlank()) {
            body.put("from", senderId);
        }
        body.put("message", text);
        body.put("callbackUrl", deliveryCallbackUrl);
        HttpResponse<String> r;
        try {
            r = http.send(HttpRequest.newBuilder(URI.create(props.providerUrl() + "/messages")).timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + props.apiKey()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            return Result.failed(Kind.RETRY, "IO", e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failed(Kind.RETRY, "INTERRUPTED", "sender stopping");
        }
        int s = r.statusCode();
        if (s >= 200 && s < 300) {
            Object id = json.readValue(r.body(), Map.class).get("id");
            return id == null ? Result.failed(Kind.RETRY, "NO_ID", "provider answered without a message id")
                    : Result.accepted(String.valueOf(id));
        }
        String reason = r.body() == null ? "" : r.body().length() > 150 ? r.body().substring(0, 150) : r.body();
        return Result.failed(s == 429 || s >= 500 ? Kind.RETRY : Kind.REJECTED, "HTTP " + s, reason);
    }
}
