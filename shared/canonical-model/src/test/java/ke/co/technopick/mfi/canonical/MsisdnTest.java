package ke.co.technopick.mfi.canonical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MsisdnTest {

    @Test
    void normalisesCommonKenyanForms() {
        assertEquals("254712345678", Msisdn.normalise("0712345678"));
        assertEquals("254112345678", Msisdn.normalise("0112 345 678"));
        assertEquals("254712345678", Msisdn.normalise("+254 712-345-678"));
        assertEquals("254712345678", Msisdn.normalise("254712345678"));
    }

    @Test
    void rejectsNonKenyanOrMalformedNumbers() {
        for (String bad : new String[] {"", "12345", "0812345678", "+255712345678", "07123456789", "07123456ab"}) {
            assertThrows(IllegalArgumentException.class, () -> Msisdn.normalise(bad), bad);
        }
    }

    @Test
    void masksAllButPrefixAndLastThree() {
        assertEquals("2547*****678", Msisdn.mask("254712345678"));
    }
}
