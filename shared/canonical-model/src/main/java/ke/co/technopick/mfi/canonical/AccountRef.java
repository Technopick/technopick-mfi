package ke.co.technopick.mfi.canonical;

import java.util.Objects;

/**
 * Typed account reference used throughout the canonical model.
 *
 * <p>There is intentionally no PAN kind: card numbers must be tokenized or resolved to an
 * internal account at the switch edge, so a clear PAN cannot be represented here by
 * construction. This keeps core banking and integration out of PCI cardholder-data scope.
 */
public record AccountRef(Kind kind, String value) {

    public enum Kind {
        /** Core banking account number/ID owned by the tenant. */
        INTERNAL_ACCOUNT,
        /** Token issued by the switch tokenization service in place of a PAN. */
        TOKEN,
        /** Mobile money wallet (E.164 MSISDN, e.g. 2547XXXXXXXX). */
        MSISDN,
        /** Account at another institution (bank account, IBAN-like or proprietary). */
        EXTERNAL_ACCOUNT
    }

    public AccountRef {
        Objects.requireNonNull(kind, "kind");
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("account value is required");
        }
        if (kind == Kind.MSISDN && !value.matches("\\d{10,15}")) {
            throw new IllegalArgumentException("MSISDN must be 10-15 digits in international format");
        }
    }

    public static AccountRef internal(String accountNumber) {
        return new AccountRef(Kind.INTERNAL_ACCOUNT, accountNumber);
    }

    public static AccountRef token(String token) {
        return new AccountRef(Kind.TOKEN, token);
    }

    public static AccountRef msisdn(String msisdn) {
        return new AccountRef(Kind.MSISDN, msisdn);
    }

    /** Masked form for logs and UIs: keeps the last 4 characters only. */
    public String masked() {
        int keep = Math.min(4, value.length());
        return kind + ":" + "*".repeat(value.length() - keep) + value.substring(value.length() - keep);
    }

    @Override
    public String toString() {
        return masked();
    }
}
