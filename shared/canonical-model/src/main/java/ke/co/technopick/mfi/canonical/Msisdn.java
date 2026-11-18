package ke.co.technopick.mfi.canonical;

import java.util.regex.Pattern;

/** Kenyan mobile numbers (MSISDN) in the canonical 2547XXXXXXXX / 2541XXXXXXXX form. */
public final class Msisdn {

    private static final Pattern CANONICAL = Pattern.compile("^254[17][0-9]{8}$");

    private Msisdn() {
    }

    /** Accepts 07.., 01.., 2547.., +254 7.. with spaces or dashes; rejects anything else. */
    public static String normalise(String phone) {
        String digits = phone == null ? "" : phone.replaceAll("[\\s-]", "");
        if (digits.startsWith("+")) {
            digits = digits.substring(1);
        }
        if (digits.matches("^0[17][0-9]{8}$")) {
            digits = "254" + digits.substring(1);
        }
        if (!CANONICAL.matcher(digits).matches()) {
            throw new IllegalArgumentException("phone number must be a Kenyan mobile number (2547XXXXXXXX or 2541XXXXXXXX)");
        }
        return digits;
    }

    /** 254712345678 -> 2547*****678 */
    public static String mask(String msisdn) {
        return msisdn.substring(0, 4) + "*****" + msisdn.substring(msisdn.length() - 3);
    }
}
