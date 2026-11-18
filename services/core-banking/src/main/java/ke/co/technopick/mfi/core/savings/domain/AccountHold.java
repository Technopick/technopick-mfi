package ke.co.technopick.mfi.core.savings.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import ke.co.technopick.mfi.canonical.Money;

/**
 * An amount reserved on a deposit account. Reduces available balance, never ledger balance.
 *
 * <p>Life cycle (also enforced by the database, V2 migration):
 * ACTIVE -> CAPTURED (converted to a posted journal entry, in the same DB transaction),
 * ACTIVE -> RELEASED (reversal, decline after hold, manual release under maker-checker),
 * ACTIVE -> EXPIRED  (expiry job). Terminal states never change again.
 */
public record AccountHold(
        UUID holdId,
        UUID tenantId,
        UUID accountId,
        Kind kind,
        Money amount,
        String reference,
        String idempotencyKey,
        Instant expiresAt,
        Status status,
        UUID capturedByEntryId) {

    public enum Kind { AUTHORIZATION, EARMARK, LEGAL }

    public enum Status { ACTIVE, CAPTURED, RELEASED, EXPIRED }

    public AccountHold {
        Objects.requireNonNull(holdId, "holdId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(status, "status");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("hold amount must be positive");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        if (kind == Kind.AUTHORIZATION && expiresAt == null) {
            throw new IllegalArgumentException("authorization holds must expire");
        }
        if ((status == Status.CAPTURED) != (capturedByEntryId != null)) {
            throw new IllegalArgumentException("capturedByEntryId is required exactly when CAPTURED");
        }
    }

    public static AccountHold place(UUID tenantId, UUID accountId, Kind kind, Money amount, String reference,
                                    String idempotencyKey, Instant expiresAt) {
        return new AccountHold(UUID.randomUUID(), tenantId, accountId, kind, amount, reference, idempotencyKey,
                expiresAt, Status.ACTIVE, null);
    }

    public AccountHold capture(UUID journalEntryId) {
        Objects.requireNonNull(journalEntryId, "journalEntryId");
        return transition(Status.CAPTURED, journalEntryId);
    }

    public AccountHold release() {
        return transition(Status.RELEASED, null);
    }

    public AccountHold expire(Instant now) {
        if (expiresAt == null || now.isBefore(expiresAt)) {
            throw new IllegalStateException("hold " + holdId + " has not reached its expiry");
        }
        return transition(Status.EXPIRED, null);
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    private AccountHold transition(Status target, UUID entryId) {
        if (status != Status.ACTIVE) {
            throw new IllegalStateException("hold " + holdId + " is " + status + "; cannot move to " + target);
        }
        return new AccountHold(holdId, tenantId, accountId, kind, amount, reference, idempotencyKey, expiresAt,
                target, entryId);
    }
}
