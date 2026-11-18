package ke.co.technopick.mfi.core.accounting.domain;

import java.time.LocalDate;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import ke.co.technopick.mfi.canonical.Money;

/**
 * A balanced, immutable double-entry journal entry ready to be posted.
 *
 * <p>Invariants checked on construction (and again by the database inside the posting
 * transaction - see V1__tenancy_and_ledger.sql):
 * <ol>
 *   <li>At least two lines.</li>
 *   <li>For every currency, total debits equal total credits.</li>
 *   <li>For every currency and branch, total debits equal total credits
 *       (branch is a required balancing dimension; inter-branch movements must
 *       go through inter-branch control accounts).</li>
 *   <li>An idempotency key is present; the database enforces its uniqueness per tenant.</li>
 * </ol>
 *
 * <p>Posted entries are never edited. Corrections are made with {@link #reversal}.
 */
public record JournalEntry(
        UUID entryId,
        UUID tenantId,
        String idempotencyKey,
        LocalDate businessDate,
        String source,
        String correlationId,
        String switchReference,
        UUID reversesEntryId,
        List<JournalLine> lines) {

    public JournalEntry {
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(tenantId, "tenantId");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        Objects.requireNonNull(businessDate, "businessDate");
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source is required");
        }
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(lines, "lines");
        lines = List.copyOf(lines);
        assertBalanced(lines);
    }

    static void assertBalanced(List<JournalLine> lines) {
        if (lines.size() < 2) {
            throw new UnbalancedJournalException("A journal entry needs at least two lines");
        }
        Map<Currency, Money> netByCurrency = new HashMap<>();
        Map<String, Money> netByCurrencyAndBranch = new HashMap<>();
        for (JournalLine line : lines) {
            Money signed = line.side() == Side.DEBIT ? line.amount() : line.amount().negate();
            Currency ccy = signed.currency();
            netByCurrency.merge(ccy, signed, Money::plus);
            netByCurrencyAndBranch.merge(ccy.getCurrencyCode() + "/" + line.branchCode(), signed, Money::plus);
        }
        netByCurrency.forEach((ccy, net) -> {
            if (!net.isZero()) {
                throw new UnbalancedJournalException(
                        "Debits and credits differ by " + net + " in " + ccy.getCurrencyCode());
            }
        });
        netByCurrencyAndBranch.forEach((key, net) -> {
            if (!net.isZero()) {
                throw new UnbalancedJournalException(
                        "Entry does not balance for currency/branch " + key + " (difference " + net + ")");
            }
        });
    }

    /** Total debits in the given currency. */
    public Money totalDebits(Currency currency) {
        return lines.stream()
                .filter(l -> l.side() == Side.DEBIT && l.amount().currency().equals(currency))
                .map(JournalLine::amount)
                .reduce(Money.zero(currency), Money::plus);
    }

    /**
     * Builds the linked reversal of this entry: same accounts and amounts, opposite sides.
     * The original remains untouched.
     */
    public JournalEntry reversal(UUID newEntryId, String reversalIdempotencyKey, LocalDate reversalBusinessDate,
                                 String reversalCorrelationId) {
        if (reversesEntryId != null) {
            throw new IllegalStateException(
                    "Reversing a reversal is not allowed; post a new corrected entry instead");
        }
        return new JournalEntry(
                newEntryId,
                tenantId,
                reversalIdempotencyKey,
                reversalBusinessDate,
                "REVERSAL",
                reversalCorrelationId,
                switchReference,
                entryId,
                lines.stream().map(JournalLine::opposite).toList());
    }
}
