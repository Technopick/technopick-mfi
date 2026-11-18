package ke.co.technopick.mfi.core.tenancy.persistence;

import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Binds the current database transaction to one tenant ({@code SET LOCAL app.tenant_id}).
 * PostgreSQL row-level security then filters every table by it. The setting is
 * transaction-local, so a pooled connection never carries a tenant into the next request.
 * Calling this outside a transaction is a programming error and fails loudly.
 */
@Component
public class TenantSession {

    private final JdbcClient jdbc;

    public TenantSession(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void bind(UUID tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("tenant binding requires an active transaction");
        }
        jdbc.sql("SELECT set_config('app.tenant_id', :tenant, true)")
            .param("tenant", tenantId.toString())
            .query(String.class)
            .single();
    }
}
