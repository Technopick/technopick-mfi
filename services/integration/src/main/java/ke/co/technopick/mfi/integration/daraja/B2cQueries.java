package ke.co.technopick.mfi.integration.daraja;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import ke.co.technopick.mfi.integration.core.CoreClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sends the B2C status queries and float checks core says are due (ADR-0039). Core decides what is
 * due and what an answer means; this class only transports. A request whose acceptance is unknown
 * is reported as sent: either an answer arrives, or core gives up waiting and asks again.
 */
@Component
public class B2cQueries {

    private static final Logger LOG = LoggerFactory.getLogger(B2cQueries.class);
    private static final String BASE = "/api/v1/payments/mpesa/b2c";

    private final CoreClient core;
    private final DarajaClient daraja;
    private final DarajaProperties props;

    public B2cQueries(CoreClient core, DarajaClient daraja, DarajaProperties props) {
        this.core = core;
        this.daraja = daraja;
        this.props = props;
    }

    /** One pass over every tenant with a B2C account; returns how many requests were sent. */
    public int run() {
        if (!props.b2cConfigured()) {
            return 0;
        }
        Set<UUID> tenants = new LinkedHashSet<>();
        props.b2cAccounts().forEach(a -> tenants.add(a.tenantId()));
        int sent = 0;
        for (UUID tenant : tenants) {
            try {
                sent += statusQueries(tenant) + balanceChecks(tenant);
            } catch (RuntimeException e) {
                LOG.warn("B2C queries failed for tenant {}: {}", tenant, e.toString());
            }
        }
        return sent;
    }

    private int statusQueries(UUID tenant) {
        int sent = 0;
        for (Map<String, Object> q : core.postForList(BASE + "/status-queries/due", Map.of("tenantId", tenant.toString(), "limit", 50))) {
            String queryId = String.valueOf(q.get("queryId"));
            Optional<DarajaProperties.B2cAccount> account = account(tenant, String.valueOf(q.get("shortCode")));
            if (account.isEmpty()) {
                report(BASE + "/status-queries/" + queryId + "/send-failure", tenant, "no B2C credentials for this short code");
                continue;
            }
            String urls = props.callbackBaseUrl() + "/daraja/b2c/" + account.get().token();
            DarajaClient.B2cOutcome o = daraja.transactionStatus(account.get(), str(q.get("transactionId")),
                    String.valueOf(q.get("disbursementId")), urls + "/status-result/" + queryId,
                    urls + "/status-timeout/" + queryId);
            sent += outcome(BASE + "/status-queries/" + queryId, tenant, o);
        }
        return sent;
    }

    private int balanceChecks(UUID tenant) {
        int sent = 0;
        for (Map<String, Object> c : core.postForList(BASE + "/balance-checks/due", Map.of("tenantId", tenant.toString()))) {
            String checkId = String.valueOf(c.get("checkId"));
            Optional<DarajaProperties.B2cAccount> account = account(tenant, String.valueOf(c.get("shortCode")));
            if (account.isEmpty()) {
                report(BASE + "/balance-checks/" + checkId + "/send-failure", tenant, "no B2C credentials for this short code");
                continue;
            }
            String urls = props.callbackBaseUrl() + "/daraja/b2c/" + account.get().token();
            DarajaClient.B2cOutcome o = daraja.accountBalance(account.get(), urls + "/balance-result/" + checkId,
                    urls + "/balance-timeout/" + checkId);
            sent += outcome(BASE + "/balance-checks/" + checkId, tenant, o);
        }
        return sent;
    }

    private int outcome(String base, UUID tenant, DarajaClient.B2cOutcome o) {
        if (o.kind() == DarajaClient.Kind.REJECTED) {
            report(base + "/send-failure", tenant, (o.code() + " " + (o.description() == null ? "" : o.description())).strip());
            return 0;
        }
        core.post(base + "/sent", Map.of("tenantId", tenant.toString()));
        return 1;
    }

    private void report(String path, UUID tenant, String description) {
        core.post(path, Map.of("tenantId", tenant.toString(),
                "description", description.length() > 200 ? description.substring(0, 200) : description));
    }

    private Optional<DarajaProperties.B2cAccount> account(UUID tenant, String shortCode) {
        return props.b2cAccounts().stream()
            .filter(a -> a.tenantId().equals(tenant) && a.shortCode().equals(shortCode)).findFirst();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
