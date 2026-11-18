package ke.co.technopick.mfi.core.savings.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import ke.co.technopick.mfi.canonical.Money;
import ke.co.technopick.mfi.core.savings.domain.AccountHold;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Holds table adapter. Status rules are enforced again by V2 triggers. */
@Repository
public class JdbcHolds {

    private final JdbcClient jdbc;

    public JdbcHolds(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AccountHold h) {
        jdbc.sql("""
                INSERT INTO savings.account_hold (tenant_id, hold_id, account_id, kind, amount, currency, reference,
                    idempotency_key, expires_at)
                VALUES (:tenant, :hold, :account, :kind, :amount, :ccy, :ref, :key, :expires)
                """)
            .param("tenant", h.tenantId())
            .param("hold", h.holdId())
            .param("account", h.accountId())
            .param("kind", h.kind().name())
            .param("amount", h.amount().amount())
            .param("ccy", h.amount().currency().getCurrencyCode())
            .param("ref", h.reference())
            .param("key", h.idempotencyKey())
            .param("expires", h.expiresAt() == null ? null : OffsetDateTime.ofInstant(h.expiresAt(), ZoneOffset.UTC))
            .update();
    }

    public Optional<AccountHold> findByIdempotencyKey(String key) {
        return jdbc.sql(SELECT + " WHERE idempotency_key = :key").param("key", key).query(JdbcHolds::map).optional();
    }

    /** Locks the hold row for the rest of the transaction. */
    public Optional<AccountHold> lock(UUID holdId) {
        return jdbc.sql(SELECT + " WHERE hold_id = :id FOR UPDATE").param("id", holdId).query(JdbcHolds::map).optional();
    }

    public void capture(UUID holdId, UUID entryId) {
        jdbc.sql("UPDATE savings.account_hold SET status = 'CAPTURED', captured_by_entry_id = :entry WHERE hold_id = :id")
            .param("entry", entryId).param("id", holdId).update();
    }

    public void release(UUID holdId) {
        jdbc.sql("UPDATE savings.account_hold SET status = 'RELEASED' WHERE hold_id = :id").param("id", holdId).update();
    }

    private static final String SELECT = """
            SELECT hold_id, tenant_id, account_id, kind, amount, currency, reference, idempotency_key, expires_at,
                   status, captured_by_entry_id
              FROM savings.account_hold""";

    private static AccountHold map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        Currency c = Currency.getInstance(rs.getString("currency"));
        OffsetDateTime expires = rs.getObject("expires_at", OffsetDateTime.class);
        Instant expiresAt = expires == null ? null : expires.toInstant();
        return new AccountHold(rs.getObject("hold_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("account_id", UUID.class), AccountHold.Kind.valueOf(rs.getString("kind")),
                Money.of(rs.getBigDecimal("amount").setScale(c.getDefaultFractionDigits(), java.math.RoundingMode.UNNECESSARY), c),
                rs.getString("reference"), rs.getString("idempotency_key"), expiresAt,
                AccountHold.Status.valueOf(rs.getString("status")), rs.getObject("captured_by_entry_id", UUID.class));
    }
}
