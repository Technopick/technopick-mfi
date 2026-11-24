package ke.co.technopick.mfi.core.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import ke.co.technopick.mfi.core.notification.application.SmsTemplates;
import org.junit.jupiter.api.Test;

class SmsTemplatesTest {

    /** The longest values each placeholder takes in practice. */
    static final Map<String, String> WORST = Map.of(
        "sig", "Kenya Teachers Sacco", "amount", "1,250,000.00", "loan", "LN26-0000123", "account", "*1234",
        "date", "28/11/2026", "ref", "TKL9ABCDEF", "paybill", "6009931", "ussd", "*483*123*45#", "hours", "168",
        "time", "28/11/2026 21:45");

    @Test
    void everyTemplateFitsTwoSmsInBothLanguagesAsPlainText() {
        for (String template : SmsTemplates.ALL.keySet()) {
            for (String language : new String[] {"en", "sw"}) {
                String body = SmsTemplates.render(template, language, WORST);
                assertTrue(body.length() <= 306, template + "/" + language + " is " + body.length() + " characters");
                assertTrue(body.matches("^[ -~]+$"), template + "/" + language + " is not plain text");
                assertTrue(body.startsWith("Kenya Teachers Sacco: "), body);
                assertTrue(!body.contains("{"), "unfilled placeholder in " + template + "/" + language);
            }
        }
    }

    @Test
    void paymentAlertsFitOneSms() {
        for (String template : new String[] {"PAYMENT_LOAN", "PAYMENT_SAVINGS"}) {
            String body = SmsTemplates.render(template, "en", WORST);
            assertTrue(body.length() <= 160, template + " is " + body.length());
        }
    }

    @Test
    void swahiliAndEnglishDiffer() {
        assertEquals("Sacco: Tumepokea KES 500.00 kwa mkopo LN26-0000001 tarehe 28/11/2026. Kumbukumbu ya M-Pesa ABC123. Asante.",
                SmsTemplates.render("PAYMENT_LOAN", "sw", Map.of("sig", "Sacco", "amount", "500.00", "loan", "LN26-0000001",
                        "date", "28/11/2026", "ref", "ABC123")));
    }

    @Test
    void missingValuesAndUnsafeTextAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> SmsTemplates.render("PIN_SET", "en", Map.of("sig", "Sacco")));
        assertThrows(IllegalArgumentException.class, () -> SmsTemplates.render("PIN_SET", "en",
                Map.of("sig", "Sacco\n", "time", "now")));
        assertThrows(IllegalArgumentException.class, () -> SmsTemplates.render("NOPE", "en", Map.of()));
    }
}
