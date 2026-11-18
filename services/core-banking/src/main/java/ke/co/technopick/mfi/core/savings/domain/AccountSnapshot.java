package ke.co.technopick.mfi.core.savings.domain;

import java.util.Currency;
import java.util.Objects;
import java.util.UUID;
import ke.co.technopick.mfi.canonical.Money;

/**
 * Point-in-time view of a deposit account, read under a row lock in the same transaction
 * that will place the hold or post the entry.
 *
 * @param ledgerBalance  sum of posted subledger lines (credit-positive for deposit accounts)
 * @param activeHolds    sum of ACTIVE holds
 * @param overdraftLimit approved overdraft (zero unless the product allows it)
 * @param withdrawnToday sum of today's debits through the channel being authorized (for limits)
 */
public record AccountSnapshot(
        UUID accountId,
        UUID tenantId,
        Status status,
        Currency currency,
        Money ledgerBalance,
        Money activeHolds,
        Money overdraftLimit,
        Money withdrawnToday) {

    public enum Status { ACTIVE, DORMANT, BLOCKED, DEBIT_BLOCKED, CLOSED }

    public AccountSnapshot {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(currency, "currency");
        for (Money m : new Money[] {ledgerBalance, activeHolds, overdraftLimit, withdrawnToday}) {
            Objects.requireNonNull(m, "money field");
            if (!m.currency().equals(currency)) {
                throw new IllegalArgumentException("snapshot amounts must be in " + currency);
            }
        }
        if (activeHolds.isNegative() || overdraftLimit.isNegative() || withdrawnToday.isNegative()) {
            throw new IllegalArgumentException("holds, overdraft and withdrawnToday must not be negative");
        }
    }

    /** Ledger balance + overdraft - active holds. */
    public Money available() {
        return ledgerBalance.plus(overdraftLimit).minus(activeHolds);
    }
}
