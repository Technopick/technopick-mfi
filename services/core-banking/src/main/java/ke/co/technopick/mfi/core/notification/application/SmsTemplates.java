package ke.co.technopick.mfi.core.notification.application;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SMS wording in English and Swahili (ADR-0035). Plain printable ASCII only (GSM-7 safe; the
 * database refuses anything else). The Swahili wording is a draft that needs review by a native
 * speaker on the business side before live use. {sig} is the tenant's signature.
 */
public final class SmsTemplates {

    public enum Category { TRANSACTIONAL, REMINDER, SECURITY }

    public record Template(Category category, String en, String sw) { }

    public static final Map<String, Template> ALL = Map.of(
        "PAYMENT_LOAN", new Template(Category.TRANSACTIONAL,
            "{sig}: We received KES {amount} for loan {loan} on {date}. M-Pesa ref {ref}. Thank you.",
            "{sig}: Tumepokea KES {amount} kwa mkopo {loan} tarehe {date}. Kumbukumbu ya M-Pesa {ref}. Asante."),
        "PAYMENT_SAVINGS", new Template(Category.TRANSACTIONAL,
            "{sig}: KES {amount} was deposited to your savings account {account} on {date}. M-Pesa ref {ref}.",
            "{sig}: KES {amount} zimewekwa kwenye akaunti yako ya akiba {account} tarehe {date}. Kumbukumbu ya M-Pesa {ref}."),
        "LOAN_DUE_SOON", new Template(Category.REMINDER,
            "{sig}: Your loan {loan} instalment of KES {amount} is due on {date}. Pay by M-Pesa paybill {paybill}, account {loan}.",
            "{sig}: Awamu ya mkopo wako {loan} ya KES {amount} inadaiwa tarehe {date}. Lipa kwa M-Pesa paybill {paybill}, akaunti {loan}."),
        "LOAN_MISSED", new Template(Category.REMINDER,
            "{sig}: Your loan {loan} instalment due {date} is unpaid; KES {amount} is overdue. Please pay by paybill {paybill}, account {loan}.",
            "{sig}: Awamu ya mkopo wako {loan} ya tarehe {date} haijalipwa; KES {amount} imechelewa. Tafadhali lipa kwa paybill {paybill}, akaunti {loan}."),
        "PIN_SETUP_READY", new Template(Category.SECURITY,
            "{sig}: Your mobile banking PIN setup is ready. Dial {ussd} from this phone within {hours} hours to choose your PIN. Never share your PIN.",
            "{sig}: PIN yako ya huduma kwa simu iko tayari kusajiliwa. Piga {ussd} kwa simu hii ndani ya saa {hours} kuchagua PIN. Usimpe mtu PIN yako."),
        "PIN_SET", new Template(Category.SECURITY,
            "{sig}: Your mobile banking PIN was set on {time}. If this was not you, call your branch at once.",
            "{sig}: PIN yako ya huduma kwa simu imewekwa {time}. Kama si wewe, piga simu tawi lako mara moja."),
        "PIN_CHANGED", new Template(Category.SECURITY,
            "{sig}: Your mobile banking PIN was changed on {time}. If this was not you, call your branch at once.",
            "{sig}: PIN yako ya huduma kwa simu imebadilishwa {time}. Kama si wewe, piga simu tawi lako mara moja."),
        "PIN_LOCKED", new Template(Category.SECURITY,
            "{sig}: Your mobile banking PIN is locked after wrong attempts. Visit your branch to reset it. If this was not you, call your branch.",
            "{sig}: PIN yako ya huduma kwa simu imefungwa baada ya majaribio yasiyo sahihi. Tembelea tawi lako. Kama si wewe, piga simu tawi lako."));

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z]+)}");
    private static final Pattern SAFE = Pattern.compile("^[ -~]*$");

    private SmsTemplates() { }

    /** Fills a template; every placeholder must be given, and the result must be plain printable text. */
    public static String render(String template, String language, Map<String, String> params) {
        Template t = ALL.get(template);
        if (t == null) {
            throw new IllegalArgumentException("unknown SMS template " + template);
        }
        String text = "sw".equals(language) ? t.sw() : t.en();
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = params.get(m.group(1));
            if (value == null) {
                throw new IllegalArgumentException("SMS template " + template + " needs " + m.group(1));
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        String body = out.toString();
        if (!SAFE.matcher(body).matches() || body.length() > 320) {
            throw new IllegalArgumentException("SMS text must be plain printable text of at most 320 characters");
        }
        return body;
    }
}
