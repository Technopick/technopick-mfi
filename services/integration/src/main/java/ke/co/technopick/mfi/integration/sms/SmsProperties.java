package ke.co.technopick.mfi.integration.sms;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SMS sending (ADR-0035). The provider API key and the delivery-report path token are secrets
 * (secret store / environment), never committed.
 *
 * @param enabled         whether this runtime claims and sends messages
 * @param providerUrl     base URL of the SMS provider adapter's API
 * @param apiKey          the provider API key
 * @param callbackBaseUrl public base URL of this runtime, for delivery reports
 * @param deliveryToken   unguessable path token in the delivery-report URL
 * @param allowedCidrs    the provider's delivery-report source ranges
 * @param pollIntervalMs  how often to ask core for due messages
 * @param batchSize       messages per claim (1 to 100)
 */
@ConfigurationProperties("mfi.sms")
public record SmsProperties(boolean enabled, String providerUrl, String apiKey, String callbackBaseUrl, String deliveryToken,
                            List<String> allowedCidrs, long pollIntervalMs, int batchSize) {

    public SmsProperties {
        allowedCidrs = allowedCidrs == null ? List.of() : List.copyOf(allowedCidrs);
        pollIntervalMs = pollIntervalMs <= 0 ? 2000 : pollIntervalMs;
        batchSize = batchSize < 1 || batchSize > 100 ? 20 : batchSize;
    }

    @Override
    public String toString() {
        return "SmsProperties[enabled=" + enabled + ", providerUrl=" + providerUrl + "]"; // never print secrets
    }
}
