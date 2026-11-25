package ke.co.technopick.mfi.core.payments.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import ke.co.technopick.mfi.core.payments.domain.MpesaAccountBalance.SubAccount;
import org.junit.jupiter.api.Test;

class MpesaAccountBalanceTest {

    @Test
    void readsEverySubAccount() {
        List<SubAccount> a = MpesaAccountBalance.parse("Working Account|KES|700000.00|700000.00|0.00|0.00"
                + "&Float Account|KES|0.00|0.00|0.00|0.00&Utility Account|KES|228037.00|228000.50|36.50|0.00"
                + "&Charges Paid Account|KES|-1540.00|-1540.00|0.00|0.00&Organization Settlement Account|KES|0.00|0.00|0.00|0.00");
        assertEquals(5, a.size());
        SubAccount utility = MpesaAccountBalance.find(a, " utility account ").orElseThrow();
        assertEquals(new BigDecimal("228037.00"), utility.current());
        assertEquals(new BigDecimal("228000.50"), utility.available());
        assertEquals(new BigDecimal("36.50"), utility.reserved());
        assertEquals(new BigDecimal("-1540.00"), MpesaAccountBalance.find(a, "Charges Paid Account").orElseThrow().current());
        assertTrue(MpesaAccountBalance.find(a, "Merchant Account").isEmpty());
    }

    @Test
    void reservedAndUnclearedMayBeMissing() {
        SubAccount u = MpesaAccountBalance.parse("Utility Account|KES|1,000.00|900").getFirst();
        assertEquals(new BigDecimal("1000.00"), u.current());
        assertEquals(new BigDecimal("900.00"), u.available());
        assertEquals(new BigDecimal("0.00"), u.reserved());
    }

    @Test
    void refusesOtherLayouts() {
        assertThrows(IllegalArgumentException.class, () -> MpesaAccountBalance.parse(""));
        assertThrows(IllegalArgumentException.class, () -> MpesaAccountBalance.parse("Utility Account|KES|100.00"));
        assertThrows(IllegalArgumentException.class, () -> MpesaAccountBalance.parse("Utility Account|KES|abc|100.00"));
        assertThrows(IllegalArgumentException.class, () -> MpesaAccountBalance.parse("Utility Account|KES|1.005|1.00"));
        assertThrows(IllegalArgumentException.class, () -> MpesaAccountBalance.parse("|KES|1.00|1.00"));
    }
}
