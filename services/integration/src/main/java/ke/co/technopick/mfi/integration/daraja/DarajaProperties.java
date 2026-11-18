package ke.co.technopick.mfi.integration.daraja;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Daraja configuration. Tokens are secrets (secret store / environment), never committed.
 *
 * @param paybills          one entry per tenant paybill: path token, tenant, short code
 * @param allowedCidrs      callback source ranges allowed to reach the callback endpoints
 * @param validationOnError what to answer Daraja if core banking cannot be asked (default ACCEPT)
 */
@ConfigurationProperties("mfi.daraja")
public record DarajaProperties(List<Paybill> paybills, List<String> allowedCidrs, OnError validationOnError,
                               boolean processorEnabled, long processIntervalMs, int maxAttempts,
                               Duration retryBackoff) {

    public enum OnError { REJECT, ACCEPT }

    public record Paybill(String token, UUID tenantId, String shortCode) { }

    public DarajaProperties {
        paybills = paybills == null ? List.of() : List.copyOf(paybills);
        allowedCidrs = allowedCidrs == null ? List.of() : List.copyOf(allowedCidrs);
        if (validationOnError == null) {
            validationOnError = OnError.ACCEPT; // ADR-0009, business decision 2026-11-18
        }
        if (retryBackoff == null) {
            retryBackoff = Duration.ofSeconds(30);
        }
    }
}
