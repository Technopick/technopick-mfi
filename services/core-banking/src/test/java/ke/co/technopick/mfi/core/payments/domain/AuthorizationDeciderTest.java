package ke.co.technopick.mfi.core.payments.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import ke.co.technopick.mfi.canonical.AccountRef;
import ke.co.technopick.mfi.canonical.CounterpartyKeys;
import ke.co.technopick.mfi.canonical.MessageFunction;
import ke.co.technopick.mfi.canonical.Money;
import ke.co.technopick.mfi.canonical.ResponseReason;
import ke.co.technopick.mfi.canonical.TransactionRequest;
import ke.co.technopick.mfi.canonical.TransactionType;
import ke.co.technopick.mfi.core.savings.domain.AccountSnapshot;
import org.junit.jupiter.api.Test;

class AuthorizationDeciderTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER_TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID ACCOUNT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final Currency KES = Currency.getInstance("KES");
    private static final ChannelLimits AGENT_LIMITS = new ChannelLimits(kes("70000.00"), kes("150000.00"));

    private static Money kes(String v) {
        return Money.of(v, "KES");
    }

    private static AccountSnapshot account(AccountSnapshot.Status status, String ledger, String holds, String today) {
        return new AccountSnapshot(ACCOUNT, TENANT, status, KES, kes(ledger), kes(holds), kes("0"), kes(today));
    }

    private static TransactionRequest request(UUID tenant, TransactionType type, String amount, String fee) {
        return new TransactionRequest(UUID.randomUUID(), "corr", tenant, type, MessageFunction.REQUEST,
                kes(amount), kes(fee), AccountRef.internal("0100123456"),
                type == TransactionType.FUNDS_TRANSFER ? AccountRef.internal("0100999999") : null,
                CounterpartyKeys.iso8583("123456", "AGT00001", "000101", null, Instant.parse("2026-11-18T07:00:00Z")),
                null);
    }

    private static TransactionRequest withdrawal(String amount) {
        return request(TENANT, TransactionType.CASH_WITHDRAWAL, amount, "50");
    }

    @Test
    void approvesAndHoldsAmountPlusFee() {
        AuthorizationDecision d = AuthorizationDecider.decide(withdrawal("5000"),
                account(AccountSnapshot.Status.ACTIVE, "20000.00", "1000.00", "0"), AGENT_LIMITS);
        assertTrue(d.approved());
        assertEquals(kes("5050.00"), d.holdAmount());
        assertEquals(kes("13950.00"), d.balanceAfter());
    }

    @Test
    void exactAvailableIsApprovedOneCentShortIsDeclined() {
        AccountSnapshot a = account(AccountSnapshot.Status.ACTIVE, "6050.00", "1000.00", "0");
        assertTrue(AuthorizationDecider.decide(withdrawal("5000"), a, AGENT_LIMITS).approved());
        AuthorizationDecision d = AuthorizationDecider.decide(withdrawal("5000.01"), a, AGENT_LIMITS);
        assertEquals(ResponseReason.INSUFFICIENT_FUNDS, d.reason());
        assertTrue(d.hold().isEmpty());
    }

    @Test
    void activeHoldsReduceAvailable() {
        AuthorizationDecision d = AuthorizationDecider.decide(withdrawal("5000"),
                account(AccountSnapshot.Status.ACTIVE, "6000.00", "1000.00", "0"), AGENT_LIMITS);
        assertEquals(ResponseReason.INSUFFICIENT_FUNDS, d.reason());
    }

    @Test
    void limitsAreCheckedBeforeBalance() {
        AccountSnapshot rich = account(AccountSnapshot.Status.ACTIVE, "1000000.00", "0", "0");
        assertEquals(ResponseReason.LIMIT_EXCEEDED,
                AuthorizationDecider.decide(withdrawal("69951"), rich, AGENT_LIMITS).reason());
        AccountSnapshot usedToday = account(AccountSnapshot.Status.ACTIVE, "1000000.00", "0", "145000.00");
        assertEquals(ResponseReason.LIMIT_EXCEEDED,
                AuthorizationDecider.decide(withdrawal("5000"), usedToday, AGENT_LIMITS).reason());
        assertTrue(AuthorizationDecider.decide(withdrawal("4950"), usedToday, AGENT_LIMITS).approved());
    }

    @Test
    void blockedDormantAndClosedAccounts() {
        assertEquals(ResponseReason.ACCOUNT_BLOCKED, AuthorizationDecider.decide(withdrawal("10"),
                account(AccountSnapshot.Status.BLOCKED, "500", "0", "0"), AGENT_LIMITS).reason());
        assertEquals(ResponseReason.ACCOUNT_BLOCKED, AuthorizationDecider.decide(withdrawal("10"),
                account(AccountSnapshot.Status.DORMANT, "500", "0", "0"), AGENT_LIMITS).reason());
        // A dormant account can still receive a deposit.
        assertTrue(AuthorizationDecider.decide(request(TENANT, TransactionType.CASH_DEPOSIT, "100", "0"),
                account(AccountSnapshot.Status.DORMANT, "500", "0", "0"), AGENT_LIMITS).approved());
        assertEquals(ResponseReason.ACCOUNT_BLOCKED, AuthorizationDecider.decide(
                request(TENANT, TransactionType.CASH_DEPOSIT, "100", "0"),
                account(AccountSnapshot.Status.CLOSED, "0", "0", "0"), AGENT_LIMITS).reason());
    }

    @Test
    void crossTenantAccountIsNeverAuthorized() {
        AuthorizationDecision d = AuthorizationDecider.decide(
                request(OTHER_TENANT, TransactionType.CASH_WITHDRAWAL, "10", "0"),
                account(AccountSnapshot.Status.ACTIVE, "1000000", "0", "0"), AGENT_LIMITS);
        assertFalse(d.approved());
        assertEquals(ResponseReason.ACCOUNT_NOT_FOUND, d.reason());
    }

    @Test
    void missingAccount() {
        assertEquals(ResponseReason.ACCOUNT_NOT_FOUND,
                AuthorizationDecider.decide(withdrawal("10"), null, AGENT_LIMITS).reason());
    }

    @Test
    void balanceEnquiryPlacesNoHold() {
        AuthorizationDecision d = AuthorizationDecider.decide(
                request(TENANT, TransactionType.BALANCE_ENQUIRY, "0", "0"),
                account(AccountSnapshot.Status.ACTIVE, "2500.00", "500.00", "0"), AGENT_LIMITS);
        assertTrue(d.approved());
        assertTrue(d.hold().isEmpty());
        assertEquals(kes("2000.00"), d.balanceAfter());
    }

    @Test
    void currencyMismatchDeclined() {
        AccountSnapshot usd = new AccountSnapshot(ACCOUNT, TENANT, AccountSnapshot.Status.ACTIVE,
                Currency.getInstance("USD"), Money.of("100", "USD"), Money.zero("USD"), Money.zero("USD"),
                Money.zero("USD"));
        assertEquals(ResponseReason.INVALID_TRANSACTION,
                AuthorizationDecider.decide(withdrawal("10"), usd, AGENT_LIMITS).reason());
    }

    @Test
    void declineCannotCarryHold() {
        assertThrows(IllegalArgumentException.class,
                () -> new AuthorizationDecision(ResponseReason.INSUFFICIENT_FUNDS, kes("1"), null));
    }
}
