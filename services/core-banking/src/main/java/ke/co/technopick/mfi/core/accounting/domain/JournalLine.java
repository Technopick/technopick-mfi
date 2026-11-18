package ke.co.technopick.mfi.core.accounting.domain;

import java.util.Objects;
import ke.co.technopick.mfi.canonical.Money;

/**
 * One side of a journal entry. Amounts are always strictly positive; direction is
 * carried by {@link Side}. A line is immutable.
 *
 * @param glAccountCode tenant chart-of-accounts code
 * @param side          debit or credit
 * @param amount        positive amount
 * @param branchCode    balancing dimension (branch); required for every line
 * @param narrative     optional free text (never contains PAN, PIN or secrets)
 */
public record JournalLine(String glAccountCode, Side side, Money amount, String branchCode, String narrative) {

    public JournalLine {
        if (glAccountCode == null || glAccountCode.isBlank()) {
            throw new IllegalArgumentException("glAccountCode is required");
        }
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(amount, "amount");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Journal line amount must be positive, was " + amount);
        }
        if (branchCode == null || branchCode.isBlank()) {
            throw new IllegalArgumentException("branchCode is required");
        }
    }

    public static JournalLine debit(String account, Money amount, String branch) {
        return new JournalLine(account, Side.DEBIT, amount, branch, null);
    }

    public static JournalLine credit(String account, Money amount, String branch) {
        return new JournalLine(account, Side.CREDIT, amount, branch, null);
    }

    /** The same line with the opposite side, used to build reversal entries. */
    public JournalLine opposite() {
        return new JournalLine(glAccountCode, side.opposite(), amount, branchCode, narrative);
    }
}
