package ke.co.technopick.mfi.core.savings.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import ke.co.technopick.mfi.canonical.Money;
import org.junit.jupiter.api.Test;

class AccountHoldTest {

    private static final Instant EXPIRY = Instant.parse("2026-11-25T00:00:00Z");

    private static AccountHold active() {
        return AccountHold.place(UUID.randomUUID(), UUID.randomUUID(), AccountHold.Kind.AUTHORIZATION,
                Money.of("5050", "KES"), "RRN632207000001", "hold-sw-000001", EXPIRY);
    }

    @Test
    void captureLinksJournalEntry() {
        UUID entry = UUID.randomUUID();
        AccountHold captured = active().capture(entry);
        assertEquals(AccountHold.Status.CAPTURED, captured.status());
        assertEquals(entry, captured.capturedByEntryId());
    }

    @Test
    void terminalStatesAreFinal() {
        AccountHold released = active().release();
        assertThrows(IllegalStateException.class, () -> released.capture(UUID.randomUUID()));
        assertThrows(IllegalStateException.class, released::release);
        AccountHold captured = active().capture(UUID.randomUUID());
        assertThrows(IllegalStateException.class, captured::release);
    }

    @Test
    void expiryOnlyAfterExpiresAt() {
        assertThrows(IllegalStateException.class, () -> active().expire(EXPIRY.minusSeconds(1)));
        assertEquals(AccountHold.Status.EXPIRED, active().expire(EXPIRY).status());
    }

    @Test
    void authorizationHoldsMustExpireAndBePositive() {
        assertThrows(IllegalArgumentException.class, () -> AccountHold.place(UUID.randomUUID(), UUID.randomUUID(),
                AccountHold.Kind.AUTHORIZATION, Money.of("1", "KES"), "r", "k", null));
        assertThrows(IllegalArgumentException.class, () -> AccountHold.place(UUID.randomUUID(), UUID.randomUUID(),
                AccountHold.Kind.LEGAL, Money.of("0", "KES"), "r", "k", null));
        assertTrue(AccountHold.place(UUID.randomUUID(), UUID.randomUUID(), AccountHold.Kind.LEGAL,
                Money.of("1", "KES"), "court order 12/2026", "k", null).isActive());
    }
}
