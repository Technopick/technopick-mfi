package ke.co.technopick.mfi.core.payments.domain;

import java.util.Objects;
import java.util.Optional;
import ke.co.technopick.mfi.canonical.Money;
import ke.co.technopick.mfi.canonical.ResponseReason;

/**
 * Outcome of authorizing a canonical transaction against a deposit account.
 *
 * @param reason        internal response reason (mapped per counterparty at the edge)
 * @param holdAmount    amount to hold when approved and the transaction debits the account
 * @param balanceAfter  available balance after the hold, for balance-returning responses
 */
public record AuthorizationDecision(ResponseReason reason, Money holdAmount, Money balanceAfter) {

    public AuthorizationDecision {
        Objects.requireNonNull(reason, "reason");
        if (!reason.isApproved() && holdAmount != null) {
            throw new IllegalArgumentException("declines never place a hold");
        }
    }

    static AuthorizationDecision decline(ResponseReason reason) {
        return new AuthorizationDecision(reason, null, null);
    }

    public boolean approved() {
        return reason.isApproved();
    }

    public Optional<Money> hold() {
        return Optional.ofNullable(holdAmount);
    }
}
