package ke.co.technopick.mfi.canonical;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Standard-neutral transaction as seen by routing, limits and the core authorization port.
 *
 * <p>{@code tenantId} is always derived by the switch or API layer from authenticated
 * channel, terminal, participant or token identity; it must never be copied from free-form
 * message content.
 *
 * @param switchRef          internal unique reference for this message
 * @param correlationId      propagated across HTTP, ISO messages and queues
 * @param tenantId           derived tenant
 * @param type               business meaning
 * @param function           request, advice, reversal or repeat
 * @param amount             transaction amount; zero for non-financial enquiries
 * @param fee                fee to be charged with the transaction (zero if none)
 * @param sourceAccount      typed account reference (never a clear PAN)
 * @param destinationAccount typed account reference, may be null
 * @param keys               counterparty identifiers
 * @param originalSwitchRef  for reversals/completions: the original transaction
 */
public record TransactionRequest(
        UUID switchRef,
        String correlationId,
        UUID tenantId,
        TransactionType type,
        MessageFunction function,
        Money amount,
        Money fee,
        AccountRef sourceAccount,
        AccountRef destinationAccount,
        CounterpartyKeys keys,
        UUID originalSwitchRef) {

    public TransactionRequest {
        Objects.requireNonNull(switchRef, "switchRef");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(function, "function");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(fee, "fee");
        Objects.requireNonNull(sourceAccount, "sourceAccount");
        Objects.requireNonNull(keys, "keys");
        if (!amount.currency().equals(fee.currency())) {
            throw new IllegalArgumentException("amount and fee must share a currency");
        }
        if (amount.isNegative() || fee.isNegative()) {
            throw new IllegalArgumentException("amount and fee must not be negative");
        }
        if (type.isFinancial() && !amount.isPositive()) {
            throw new IllegalArgumentException(type + " requires a positive amount");
        }
        if (!type.isFinancial() && !amount.isZero()) {
            throw new IllegalArgumentException(type + " must not carry an amount");
        }
        if (type == TransactionType.FUNDS_TRANSFER && destinationAccount == null) {
            throw new IllegalArgumentException("FUNDS_TRANSFER requires destinationAccount");
        }
        boolean needsOriginal = function == MessageFunction.REVERSAL
                || type == TransactionType.PRE_AUTHORIZATION_COMPLETION;
        if (needsOriginal && originalSwitchRef == null) {
            throw new IllegalArgumentException(function + " " + type + " requires originalSwitchRef");
        }
    }

    /** Total that must be available (and held) on the source account. */
    public Money totalDebit() {
        return amount.plus(fee);
    }

    public DuplicateKey duplicateKey() {
        return DuplicateKey.of(keys, function);
    }

    public Optional<UUID> original() {
        return Optional.ofNullable(originalSwitchRef);
    }
}
