package ke.co.technopick.mfi.core.payments.domain;

import java.util.Objects;
import java.util.Set;
import ke.co.technopick.mfi.canonical.Money;
import ke.co.technopick.mfi.canonical.ResponseReason;
import ke.co.technopick.mfi.canonical.TransactionRequest;
import ke.co.technopick.mfi.canonical.TransactionType;
import ke.co.technopick.mfi.core.savings.domain.AccountSnapshot;

/**
 * Decides whether a switched or channel transaction may proceed against a deposit account.
 *
 * <p>This is the single place where balance and limit decisions are made for the switch
 * (design section 6). It is pure: the application service loads the {@link AccountSnapshot}
 * under a row lock, calls {@link #decide}, then places the hold and/or posts in the same
 * database transaction, where V2's available-balance guard re-checks the result.
 *
 * <p>Check order (first failure wins, so the response code is deterministic):
 * tenant match, account status, currency, amount validity, per-transaction limit,
 * daily limit, available balance.
 */
public final class AuthorizationDecider {

    private static final Set<TransactionType> DEBITS = Set.of(
            TransactionType.CASH_WITHDRAWAL, TransactionType.PURCHASE, TransactionType.FUNDS_TRANSFER,
            TransactionType.BILL_PAYMENT, TransactionType.PRE_AUTHORIZATION);

    private static final Set<TransactionType> CREDITS = Set.of(TransactionType.CASH_DEPOSIT, TransactionType.REFUND);

    private static final Set<TransactionType> ENQUIRIES = Set.of(
            TransactionType.BALANCE_ENQUIRY, TransactionType.MINI_STATEMENT);

    private AuthorizationDecider() {
    }

    public static AuthorizationDecision decide(TransactionRequest request, AccountSnapshot account, ChannelLimits limits) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(limits, "limits");
        if (account == null) {
            return AuthorizationDecision.decline(ResponseReason.ACCOUNT_NOT_FOUND);
        }
        // Defence in depth: tenant is derived upstream, but a mismatch must never authorize.
        if (!account.tenantId().equals(request.tenantId())) {
            return AuthorizationDecision.decline(ResponseReason.ACCOUNT_NOT_FOUND);
        }
        TransactionType type = request.type();
        boolean isDebit = DEBITS.contains(type);
        boolean isCredit = CREDITS.contains(type);
        if (!isDebit && !isCredit && !ENQUIRIES.contains(type)) {
            return AuthorizationDecision.decline(ResponseReason.INVALID_TRANSACTION);
        }
        switch (account.status()) {
            case CLOSED, BLOCKED -> {
                return AuthorizationDecision.decline(ResponseReason.ACCOUNT_BLOCKED);
            }
            case DORMANT, DEBIT_BLOCKED -> {
                if (isDebit) {
                    return AuthorizationDecision.decline(ResponseReason.ACCOUNT_BLOCKED);
                }
            }
            case ACTIVE -> { }
        }
        if (!account.currency().equals(request.amount().currency())) {
            return AuthorizationDecision.decline(ResponseReason.INVALID_TRANSACTION);
        }
        if (ENQUIRIES.contains(type)) {
            return new AuthorizationDecision(ResponseReason.APPROVED, null, account.available());
        }
        if (isCredit) {
            return new AuthorizationDecision(ResponseReason.APPROVED, null, account.available().plus(request.amount()));
        }

        Money debit = request.totalDebit();
        if (!limits.perTransactionMax().currency().equals(debit.currency())) {
            return AuthorizationDecision.decline(ResponseReason.INVALID_TRANSACTION);
        }
        if (debit.compareTo(limits.perTransactionMax()) > 0) {
            return AuthorizationDecision.decline(ResponseReason.LIMIT_EXCEEDED);
        }
        if (account.withdrawnToday().plus(debit).compareTo(limits.dailyMax()) > 0) {
            return AuthorizationDecision.decline(ResponseReason.LIMIT_EXCEEDED);
        }
        Money availableAfter = account.available().minus(debit);
        if (availableAfter.isNegative()) {
            return AuthorizationDecision.decline(ResponseReason.INSUFFICIENT_FUNDS);
        }
        return new AuthorizationDecision(ResponseReason.APPROVED, debit, availableAfter);
    }
}
