package ke.co.technopick.mfi.core.payments.domain;

import java.util.Objects;
import ke.co.technopick.mfi.canonical.Money;

/**
 * Per-channel limits configured for a tenant/product (maker-checker controlled).
 *
 * @param perTransactionMax maximum single debit (amount + fee)
 * @param dailyMax          maximum total debits per business day through this channel
 */
public record ChannelLimits(Money perTransactionMax, Money dailyMax) {

    public ChannelLimits {
        Objects.requireNonNull(perTransactionMax, "perTransactionMax");
        Objects.requireNonNull(dailyMax, "dailyMax");
        if (!perTransactionMax.isPositive() || !dailyMax.isPositive()) {
            throw new IllegalArgumentException("limits must be positive");
        }
        if (!perTransactionMax.currency().equals(dailyMax.currency())) {
            throw new IllegalArgumentException("limits must share a currency");
        }
    }
}
