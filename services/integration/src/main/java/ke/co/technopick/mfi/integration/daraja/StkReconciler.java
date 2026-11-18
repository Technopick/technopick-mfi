package ke.co.technopick.mfi.integration.daraja;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import ke.co.technopick.mfi.integration.core.CoreClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Asks Daraja about STK requests whose result is overdue (ADR-0011). Core decides what an answer
 * means; this class only transports: a final code, "still processing" (no code), or nothing at all
 * when Daraja could not be asked (the next pass tries again).
 */
@Component
public class StkReconciler {

    private static final Logger LOG = LoggerFactory.getLogger(StkReconciler.class);

    private final CoreClient core;
    private final DarajaClient daraja;
    private final DarajaProperties props;
    private final Duration overdueAfter;

    public StkReconciler(CoreClient core, DarajaClient daraja, DarajaProperties props,
                         @Value("${mfi.daraja.reconcile-after:PT3M}") Duration overdueAfter) {
        this.core = core;
        this.daraja = daraja;
        this.props = props;
        this.overdueAfter = overdueAfter;
    }

    /** One pass over every configured tenant; returns how many outcomes were reported to core. */
    public int reconcile() {
        if (!props.stkConfigured()) {
            return 0;
        }
        Set<UUID> tenants = new LinkedHashSet<>();
        props.paybills().forEach(p -> tenants.add(p.tenantId()));
        int reported = 0;
        for (UUID tenant : tenants) {
            try {
                reported += reconcile(tenant);
            } catch (RuntimeException e) {
                LOG.warn("STK reconciliation failed for tenant {}: {}", tenant, e.toString());
            }
        }
        return reported;
    }

    @SuppressWarnings("unchecked")
    private int reconcile(UUID tenant) {
        Map<String, Object> stale = core.post("/api/v1/payments/mpesa/stk-requests/reconciliation/stale",
                Map.of("tenantId", tenant.toString(), "olderThanSeconds", overdueAfter.toSeconds(), "limit", 50));
        int reported = 0;
        for (Map<String, Object> item : (List<Map<String, Object>>) stale.getOrDefault("items", List.of())) {
            String requestId = String.valueOf(item.get("requestId"));
            String shortCode = String.valueOf(item.get("shortCode"));
            String checkout = String.valueOf(item.get("checkoutRequestId"));
            Optional<DarajaProperties.Paybill> paybill = props.paybills().stream()
                .filter(p -> p.tenantId().equals(tenant) && p.shortCode().equals(shortCode)
                        && p.passkey() != null && !p.passkey().isBlank())
                .findFirst();
            if (paybill.isEmpty()) {
                continue;
            }
            DarajaClient.QueryOutcome q = daraja.stkQuery(paybill.get(), checkout);
            if (q.kind() == DarajaClient.QueryKind.ERROR) {
                LOG.warn("STK query for request {} not answered: {}", requestId, q.resultDesc());
                continue;
            }
            Map<String, Object> body = new HashMap<>();
            body.put("tenantId", tenant.toString());
            body.put("checkoutRequestId", checkout);
            if (q.kind() == DarajaClient.QueryKind.FINAL) {
                body.put("resultCode", q.resultCode());
                body.put("resultDesc", q.resultDesc() == null ? null
                        : q.resultDesc().length() > 200 ? q.resultDesc().substring(0, 200) : q.resultDesc());
            }
            core.post("/api/v1/payments/mpesa/stk-requests/" + requestId + "/query-result", body);
            reported++;
        }
        return reported;
    }
}
