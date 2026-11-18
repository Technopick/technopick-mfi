package ke.co.technopick.mfi.integration.core;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Calls core banking's internal API with a cached client-credentials access token.
 * Business decisions are made by core; this class only transports requests (ADR-0001).
 */
@Component
public class CoreClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() { };

    private final CoreProperties props;
    private final RestClient http;
    private String token;
    private Instant tokenExpires = Instant.EPOCH;

    public CoreClient(CoreProperties props) {
        this.props = props;
        this.http = RestClient.builder().build();
    }

    public Map<String, Object> post(String path, Map<String, Object> body) {
        return Objects.requireNonNull(http.post()
            .uri(props.baseUrl() + path)
            .header("Authorization", "Bearer " + accessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(MAP));
    }

    private synchronized String accessToken() {
        if (token != null && Instant.now().isBefore(tokenExpires.minusSeconds(30))) {
            return token;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        Map<String, Object> response = Objects.requireNonNull(http.post()
            .uri(props.tokenUri())
            .headers(h -> h.setBasicAuth(props.clientId(), props.clientSecret()))
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(MAP));
        token = String.valueOf(response.get("access_token"));
        Object expiresIn = response.getOrDefault("expires_in", 60);
        tokenExpires = Instant.now().plusSeconds(Long.parseLong(String.valueOf(expiresIn)));
        return token;
    }
}
