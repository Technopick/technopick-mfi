package ke.co.technopick.mfi.canonical;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * Exact monetary amount in a single ISO 4217 currency.
 *
 * <p>Rules (see docs/adr/0003-money-and-rounding.md):
 * <ul>
 *   <li>Backed by {@link BigDecimal}; binary floating point is never accepted.</li>
 *   <li>Always stored at the currency's minor-unit scale (KES = 2).</li>
 *   <li>Arithmetic between different currencies is rejected.</li>
 *   <li>Rounding to minor units uses {@link RoundingMode#HALF_EVEN} unless a product
 *       version specifies otherwise through {@link #of(BigDecimal, Currency, RoundingMode)}.</li>
 * </ul>
 */
public final class Money implements Comparable<Money> {

    public static final RoundingMode DEFAULT_ROUNDING = RoundingMode.HALF_EVEN;

    private final BigDecimal amount;
    private final Currency currency;

    private Money(BigDecimal amount, Currency currency) {
        this.amount = amount;
        this.currency = currency;
    }

    /** Exact construction: fails if {@code amount} has more decimals than the currency allows. */
    public static Money of(BigDecimal amount, Currency currency) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        int scale = minorUnits(currency);
        if (amount.stripTrailingZeros().scale() > scale) {
            throw new IllegalArgumentException(
                    "Amount " + amount.toPlainString() + " has more than " + scale
                            + " decimal places for " + currency.getCurrencyCode());
        }
        return new Money(amount.setScale(scale, RoundingMode.UNNECESSARY), currency);
    }

    /** Construction with explicit rounding to the currency's minor units. */
    public static Money of(BigDecimal amount, Currency currency, RoundingMode rounding) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(rounding, "rounding");
        return new Money(amount.setScale(minorUnits(currency), rounding), currency);
    }

    /** Parses a plain decimal string, e.g. {@code Money.of("100.50", "KES")}. */
    public static Money of(String amount, String currencyCode) {
        return of(new BigDecimal(amount), Currency.getInstance(currencyCode));
    }

    public static Money zero(Currency currency) {
        return of(BigDecimal.ZERO, currency);
    }

    public static Money zero(String currencyCode) {
        return zero(Currency.getInstance(currencyCode));
    }

    private static int minorUnits(Currency currency) {
        int digits = currency.getDefaultFractionDigits();
        if (digits < 0) {
            throw new IllegalArgumentException("Currency has no minor units definition: " + currency);
        }
        return digits;
    }

    public BigDecimal amount() {
        return amount;
    }

    public Currency currency() {
        return currency;
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    /** Multiplies by an exact factor (e.g. a rate) and rounds to minor units. */
    public Money times(BigDecimal factor, RoundingMode rounding) {
        Objects.requireNonNull(factor, "factor");
        return of(amount.multiply(factor), currency, rounding);
    }

    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Currency mismatch: " + currency.getCurrencyCode() + " vs " + other.currency.getCurrencyCode());
        }
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Money m)) {
            return false;
        }
        // Scale is normalised at construction, so equals on BigDecimal is safe here.
        return amount.equals(m.amount) && currency.equals(m.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount, currency);
    }

    @Override
    public String toString() {
        return currency.getCurrencyCode() + " " + amount.toPlainString();
    }
}
