package ke.co.technopick.mfi.canonical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransactionRequestTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final Instant T7 = Instant.parse("2026-11-18T07:15:00Z");

    private static CounterpartyKeys keys(String stan) {
        return CounterpartyKeys.iso8583("123456", "AGT00001", stan, "632207000001", T7);
    }

    private static TransactionRequest withdrawal(MessageFunction function, String stan, UUID original) {
        return new TransactionRequest(UUID.randomUUID(), "corr-1", TENANT, TransactionType.CASH_WITHDRAWAL,
                function, Money.of("5000", "KES"), Money.of("50", "KES"),
                AccountRef.internal("0100123456"), null, keys(stan), original);
    }

    @Test
    void totalDebitIncludesFee() {
        assertEquals(Money.of("5050.00", "KES"), withdrawal(MessageFunction.REQUEST, "000001", null).totalDebit());
    }

    @Test
    void financialTransactionNeedsPositiveAmount() {
        assertThrows(IllegalArgumentException.class, () -> new TransactionRequest(UUID.randomUUID(), "c", TENANT,
                TransactionType.CASH_WITHDRAWAL, MessageFunction.REQUEST, Money.zero("KES"), Money.zero("KES"),
                AccountRef.internal("1"), null, keys("000001"), null));
    }

    @Test
    void enquiryMustNotCarryAmount() {
        assertThrows(IllegalArgumentException.class, () -> new TransactionRequest(UUID.randomUUID(), "c", TENANT,
                TransactionType.BALANCE_ENQUIRY, MessageFunction.REQUEST, Money.of("1", "KES"), Money.zero("KES"),
                AccountRef.internal("1"), null, keys("000001"), null));
    }

    @Test
    void reversalRequiresOriginal() {
        assertThrows(IllegalArgumentException.class, () -> withdrawal(MessageFunction.REVERSAL, "000002", null));
    }

    @Test
    void repeatHasSameDuplicateKeyAsAdvice() {
        UUID original = UUID.randomUUID();
        TransactionRequest advice = withdrawal(MessageFunction.ADVICE, "000003", original);
        TransactionRequest repeat = withdrawal(MessageFunction.REPEAT, "000003", original);
        assertEquals(advice.duplicateKey(), repeat.duplicateKey());
    }

    @Test
    void requestAndReversalWithSameStanAreDistinct() {
        TransactionRequest request = withdrawal(MessageFunction.REQUEST, "000004", null);
        TransactionRequest reversal = withdrawal(MessageFunction.REVERSAL, "000004", UUID.randomUUID());
        assertNotEquals(request.duplicateKey(), reversal.duplicateKey());
    }

    @Test
    void stanMustBeSixDigits() {
        assertThrows(IllegalArgumentException.class, () -> keys("12345"));
    }

    @Test
    void accountRefMasksAllButLastFour() {
        AccountRef ref = AccountRef.internal("0100123456");
        assertEquals("INTERNAL_ACCOUNT:******3456", ref.masked());
        assertFalse(ref.toString().contains("0100123456"));
    }

    @Test
    void msisdnFormatIsValidated() {
        assertThrows(IllegalArgumentException.class, () -> AccountRef.msisdn("07XX"));
        assertTrue(AccountRef.msisdn("254712345678").masked().endsWith("5678"));
    }
}
