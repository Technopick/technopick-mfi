package ke.co.technopick.mfi.core.member.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class UssdServiceTest {

    private static final LocalDate BORN = LocalDate.of(1985, 6, 1);
    private static final String PHONE = "254712345678";

    @Test
    void easyPinsAreRefused() {
        for (String weak : new String[] {"0000", "7777", "1234", "6789", "0123", "4321", "9876", "1985", "5678", "123", "12a4", "12345"}) {
            assertNotNull(UssdService.weakness(weak, BORN, PHONE), weak);
        }
        for (String fine : new String[] {"4826", "7391", "1357", "2580", "1122"}) {
            assertNull(UssdService.weakness(fine, BORN, PHONE), fine);
        }
    }

    @Test
    void phoneNumbersAreNormalised() {
        assertEquals("254712345678", UssdService.msisdn("+254712345678"));
        assertEquals("254712345678", UssdService.msisdn("0712345678"));
        assertEquals("254112345678", UssdService.msisdn("254 112 345 678"));
        assertNull(UssdService.msisdn("+255712345678"));
        assertNull(UssdService.msisdn("0812345678"));
        assertNull(UssdService.msisdn(null));
    }

    @Test
    void screensFitTheUssdLimit() {
        String longest = "x".repeat(400);
        assertTrue(("END " + UssdService.fit(longest)).length() <= UssdService.MAX_SCREEN);
        assertEquals("short", UssdService.fit("short"));
        assertEquals("1,234,567.50", UssdService.money("1234567.5"));
        assertEquals("500.00", UssdService.money("500"));
    }
}
