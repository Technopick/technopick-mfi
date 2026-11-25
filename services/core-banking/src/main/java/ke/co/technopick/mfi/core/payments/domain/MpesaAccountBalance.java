package ke.co.technopick.mfi.core.payments.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The "AccountBalance" result parameter of the M-Pesa Account Balance API (ADR-0039): sub-accounts
 * separated by {@code &}, each {@code name|currency|current|available|reserved|uncleared}, for
 * example {@code Working Account|KES|700000.00|700000.00|0.00|0.00&Utility Account|KES|...}.
 * Reserved and uncleared may be missing.
 */
public final class MpesaAccountBalance {

    public record SubAccount(String name, String currency, BigDecimal current, BigDecimal available, BigDecimal reserved,
                             BigDecimal uncleared) { }

    private MpesaAccountBalance() { }

    /** @throws IllegalArgumentException when the text is not in that layout */
    public static List<SubAccount> parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("no AccountBalance value");
        }
        List<SubAccount> accounts = new ArrayList<>();
        for (String part : text.strip().split("&")) {
            if (part.isBlank()) {
                continue;
            }
            String[] f = part.split("\\|", -1);
            if (f.length < 4) {
                throw new IllegalArgumentException("sub-account \"" + part + "\" has fewer than 4 fields");
            }
            String name = f[0].strip();
            String currency = f[1].strip();
            if (name.isEmpty() || !currency.matches("^[A-Z]{3}$")) {
                throw new IllegalArgumentException("sub-account \"" + part + "\" has no name or currency");
            }
            accounts.add(new SubAccount(name, currency, amount(f[2], name), amount(f[3], name),
                    f.length > 4 ? amount(f[4], name) : BigDecimal.ZERO.setScale(2),
                    f.length > 5 ? amount(f[5], name) : BigDecimal.ZERO.setScale(2)));
        }
        if (accounts.isEmpty()) {
            throw new IllegalArgumentException("no sub-accounts in AccountBalance");
        }
        return List.copyOf(accounts);
    }

    /** The named sub-account, ignoring case and surrounding spaces. */
    public static Optional<SubAccount> find(List<SubAccount> accounts, String name) {
        return accounts.stream().filter(a -> a.name().equalsIgnoreCase(name.strip())).findFirst();
    }

    private static BigDecimal amount(String text, String account) {
        String t = text.strip().replace(",", "");
        if (t.isEmpty()) {
            return BigDecimal.ZERO.setScale(2);
        }
        try {
            BigDecimal v = new BigDecimal(t);
            if (v.scale() > 2) {
                throw new IllegalArgumentException(account + ": \"" + text + "\" has more than 2 decimals");
            }
            return v.setScale(2);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(account + ": \"" + text + "\" is not an amount", e);
        }
    }
}
