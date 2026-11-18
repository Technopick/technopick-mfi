package ke.co.technopick.mfi.integration.core;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** How the integration runtime reaches core banking (OAuth2 client credentials, role INTEGRATION). */
@ConfigurationProperties("mfi.core")
public record CoreProperties(String baseUrl, String tokenUri, String clientId, String clientSecret) { }
