package ke.co.technopick.mfi.integration.daraja;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Daraja configuration. Tokens, consumer key/secret and passkeys are secrets (secret store /
 * environment), never committed.
 *
 * @param paybills                  one entry per tenant paybill: path token, tenant, short code, STK passkey
 * @param allowedCidrs              callback source ranges allowed to reach the callback endpoints
 * @param validationOnError         what to answer Daraja if core banking cannot be asked (default ACCEPT)
 * @param baseUrl                   Daraja API base URL (sandbox or production, per environment)
 * @param callbackBaseUrl           public https base URL of this runtime, used to build STK callback URLs
 * @param allowInsecureTestEndpoint permits a plain-http Daraja base URL; only for the TEST-ONLY simulator
 * @param stkConsumerEnabled        consume STK Push requests from the event broker
 * @param b2cAccounts               one entry per tenant B2C short code: callback path token, tenant, short code,
 *                                  initiator name and initiator password (secret store)
 * @param certificatePem            Safaricom's public certificate for the environment (or, for the TEST-ONLY
 *                                  simulator, its public key), used to encrypt the initiator password
 * @param b2cConsumerEnabled        consume approved B2C disbursements from the event broker
 */
@ConfigurationProperties("mfi.daraja")
public record DarajaProperties(List<Paybill> paybills, List<String> allowedCidrs, OnError validationOnError,
                               boolean processorEnabled, long processIntervalMs, int maxAttempts,
                               Duration retryBackoff, String baseUrl, String consumerKey, String consumerSecret,
                               String callbackBaseUrl, boolean allowInsecureTestEndpoint, boolean stkConsumerEnabled,
                               List<B2cAccount> b2cAccounts, String certificatePem, boolean b2cConsumerEnabled) {

    public enum OnError { REJECT, ACCEPT }

    public record Paybill(String token, UUID tenantId, String shortCode, String passkey) {
        @Override
        public String toString() {
            return "Paybill[" + tenantId + "/" + shortCode + "]"; // never print secrets
        }
    }

    public record B2cAccount(String token, UUID tenantId, String shortCode, String initiatorName,
                             String initiatorPassword) {
        @Override
        public String toString() {
            return "B2cAccount[" + tenantId + "/" + shortCode + "]"; // never print secrets
        }
    }

    public DarajaProperties {
        paybills = paybills == null ? List.of() : List.copyOf(paybills);
        allowedCidrs = allowedCidrs == null ? List.of() : List.copyOf(allowedCidrs);
        b2cAccounts = b2cAccounts == null ? List.of() : List.copyOf(b2cAccounts);
        if (validationOnError == null) {
            validationOnError = OnError.ACCEPT; // ADR-0009, business decision 2026-11-18
        }
        if (retryBackoff == null) {
            retryBackoff = Duration.ofSeconds(30);
        }
        if (baseUrl != null && baseUrl.startsWith("http://") && !allowInsecureTestEndpoint) {
            throw new IllegalStateException("mfi.daraja.base-url must be https; plain http is only allowed for the "
                    + "TEST-ONLY simulator with mfi.daraja.allow-insecure-test-endpoint=true");
        }
        if (callbackBaseUrl != null && callbackBaseUrl.startsWith("http://") && !allowInsecureTestEndpoint) {
            throw new IllegalStateException("mfi.daraja.callback-base-url must be https");
        }
    }

    /** True when STK Push can be sent: an API base URL and consumer credentials are configured. */
    public boolean stkConfigured() {
        return baseUrl != null && !baseUrl.isBlank() && consumerKey != null && !consumerKey.isBlank()
                && consumerSecret != null && !consumerSecret.isBlank() && callbackBaseUrl != null
                && !callbackBaseUrl.isBlank();
    }

    /** True when B2C can be sent: API credentials, callback URL and the encryption certificate are configured. */
    public boolean b2cConfigured() {
        return stkConfigured() && certificatePem != null && !certificatePem.isBlank();
    }
}
