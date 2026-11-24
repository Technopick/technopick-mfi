package ke.co.technopick.mfi.integration.ussd;

import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * USSD aggregator configuration (ADR-0034). Path tokens are secrets (secret store / environment),
 * never committed.
 *
 * @param services     one entry per tenant USSD code: unguessable callback path token, tenant, service code
 * @param allowedCidrs the aggregator's callback source ranges, from its current documentation
 */
@ConfigurationProperties("mfi.ussd")
public record UssdProperties(List<Service> services, List<String> allowedCidrs) {

    public UssdProperties {
        services = services == null ? List.of() : List.copyOf(services);
        allowedCidrs = allowedCidrs == null ? List.of() : List.copyOf(allowedCidrs);
    }

    public record Service(String token, UUID tenantId, String serviceCode) {
        @Override
        public String toString() {
            return "UssdService[" + tenantId + "/" + serviceCode + "]"; // never print the token
        }
    }
}
