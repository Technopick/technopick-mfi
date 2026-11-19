package ke.co.technopick.mfi.core.lending.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import ke.co.technopick.mfi.canonical.Money;
import ke.co.technopick.mfi.core.accounting.domain.JournalEntry;
import ke.co.technopick.mfi.core.accounting.domain.JournalLine;

/**
 * Turns loan business events into balanced journal entries using the loan's pinned
 * product version GL mapping. Pure: persistence happens in the accounting module.
 *
 * <p>Idempotency keys are derived from the loan and the event reference, so replaying the
 * same event (retry, duplicate callback, re-run EOD) collides on the ledger's unique key
 * instead of posting twice.
 *
 * <p>ACCOUNTING POLICY NOTE (requires finance sign-off): upfront fees are recognised as
 * income at disbursement. Under IFRS 9 fees integral to the effective interest rate are
 * normally deferred and amortised; if the finance reviewer requires that, a new product
 * version maps the fee to a deferred-fee liability and an amortisation event is added.
 */
public final class LoanPostingRules {

    /** Identity and dimensions shared by every entry for one loan. */
    public record LoanContext(UUID tenantId, UUID loanId, String branchCode, LoanProductVersion product) {
        public LoanContext {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(loanId, "loanId");
            Objects.requireNonNull(branchCode, "branchCode");
            Objects.requireNonNull(product, "product");
        }
    }

    private LoanPostingRules() {
    }

    /**
     * Disbursement net of the upfront fee. Dr principal receivable (gross), Cr channel clearing
     * (net cash out), Cr fee income (fee).
     */
    public static JournalEntry disbursement(LoanContext ctx, Money principal, String channelClearingAccount,
                                            LocalDate businessDate, String disbursementRef, String correlationId) {
        Money fee = ctx.product().upfrontFee(principal);
        Money net = principal.minus(fee);
        if (!net.isPositive()) {
            throw new IllegalArgumentException("net disbursement must be positive");
        }
        List<JournalLine> lines = new ArrayList<>();
        lines.add(JournalLine.debit(ctx.product().gl().principalReceivable(), principal, ctx.branchCode()));
        lines.add(JournalLine.credit(channelClearingAccount, net, ctx.branchCode()));
        if (fee.isPositive()) {
            lines.add(JournalLine.credit(ctx.product().gl().feeIncome(), fee, ctx.branchCode()));
        }
        return entry(ctx, "DISBURSE:" + disbursementRef, businessDate, "LOAN", correlationId, lines);
    }

    /** Daily interest accrual. Returns null when nothing accrues (zero amount). */
    public static JournalEntry interestAccrual(LoanContext ctx, Money amount, LocalDate businessDate,
                                               String correlationId) {
        if (amount.isZero()) {
            return null;
        }
        LoanGlMapping gl = ctx.product().gl();
        return entry(ctx, "ACCRUE:" + businessDate, businessDate, "LOAN_EOD", correlationId, List.of(
                JournalLine.debit(gl.interestReceivable(), amount, ctx.branchCode()),
                JournalLine.credit(gl.interestIncome(), amount, ctx.branchCode())));
    }

    public static JournalEntry penaltyCharged(LoanContext ctx, Money amount, LocalDate businessDate,
                                              String chargeRef, String correlationId) {
        LoanGlMapping gl = ctx.product().gl();
        return entry(ctx, "PENALTY:" + chargeRef, businessDate, "LOAN_EOD", correlationId, List.of(
                JournalLine.debit(gl.penaltyReceivable(), amount, ctx.branchCode()),
                JournalLine.credit(gl.penaltyIncome(), amount, ctx.branchCode())));
    }

    public static JournalEntry feeCharged(LoanContext ctx, Money amount, LocalDate businessDate,
                                          String chargeRef, String correlationId) {
        LoanGlMapping gl = ctx.product().gl();
        return entry(ctx, "FEE:" + chargeRef, businessDate, "LOAN", correlationId, List.of(
                JournalLine.debit(gl.feeReceivable(), amount, ctx.branchCode()),
                JournalLine.credit(gl.feeIncome(), amount, ctx.branchCode())));
    }

    /**
     * Repayment: Dr the channel clearing account with the whole payment; Cr each component's
     * receivable with the allocated total; Cr overpayment liability with any unallocated
     * remainder. Precondition: interest allocated has been accrued (EOD accrues each period
     * fully by its due date, and allocation only pays amounts already due).
     */
    public static JournalEntry repayment(LoanContext ctx, Money payment, RepaymentAllocator.Result allocation,
                                         String channelClearingAccount, LocalDate businessDate, String paymentRef,
                                         String correlationId) {
        return repayment(ctx, payment, allocation, channelClearingAccount, businessDate, paymentRef, correlationId,
                Money.zero(payment.currency()));
    }

    /**
     * Repayment where part of the interest paid has not been accrued yet (paid before the EOD
     * accrual caught up). That part is credited straight to interest income, so interest
     * receivable never goes negative.
     */
    public static JournalEntry repayment(LoanContext ctx, Money payment, RepaymentAllocator.Result allocation,
                                         String channelClearingAccount, LocalDate businessDate, String paymentRef,
                                         String correlationId, Money interestNotYetAccrued) {
        Currency ccy = payment.currency();
        if (!allocation.totalAllocated(ccy).plus(allocation.unallocated()).equals(payment)) {
            throw new IllegalArgumentException("allocation does not add up to the payment");
        }
        Map<DueComponent, Money> byComponent = new EnumMap<>(DueComponent.class);
        for (RepaymentAllocator.Allocation a : allocation.allocations()) {
            byComponent.merge(a.component(), a.amount(), Money::plus);
        }
        List<JournalLine> lines = new ArrayList<>();
        lines.add(JournalLine.debit(channelClearingAccount, payment, ctx.branchCode()));
        LoanGlMapping gl = ctx.product().gl();
        Money interestPaid = byComponent.getOrDefault(DueComponent.INTEREST, Money.zero(ccy));
        if (interestNotYetAccrued.isNegative() || interestNotYetAccrued.compareTo(interestPaid) > 0) {
            throw new IllegalArgumentException("unaccrued interest must be between 0 and the interest paid");
        }
        byComponent.forEach((component, amount) -> {
            if (component == DueComponent.INTEREST && interestNotYetAccrued.isPositive()) {
                Money accrued = amount.minus(interestNotYetAccrued);
                if (accrued.isPositive()) {
                    lines.add(JournalLine.credit(gl.interestReceivable(), accrued, ctx.branchCode()));
                }
                lines.add(JournalLine.credit(gl.interestIncome(), interestNotYetAccrued, ctx.branchCode()));
            } else {
                lines.add(JournalLine.credit(gl.receivableFor(component), amount, ctx.branchCode()));
            }
        });
        if (allocation.unallocated().isPositive()) {
            lines.add(JournalLine.credit(gl.overpaymentLiability(), allocation.unallocated(), ctx.branchCode()));
        }
        return entry(ctx, "REPAY:" + paymentRef, businessDate, "LOAN", correlationId, lines);
    }

    /** Write-off of outstanding principal and accrued-but-unpaid interest. */
    public static JournalEntry writeOff(LoanContext ctx, Money principalOutstanding, Money interestOutstanding,
                                        LocalDate businessDate, String approvalRef, String correlationId) {
        return writeOff(ctx, principalOutstanding, interestOutstanding, Money.zero(principalOutstanding.currency()),
                businessDate, approvalRef, correlationId);
    }

    /** Write-off of outstanding principal, accrued-but-unpaid interest and unpaid penalties. */
    public static JournalEntry writeOff(LoanContext ctx, Money principalOutstanding, Money interestOutstanding,
                                        Money penaltyOutstanding, LocalDate businessDate, String approvalRef,
                                        String correlationId) {
        LoanGlMapping gl = ctx.product().gl();
        Money total = principalOutstanding.plus(interestOutstanding).plus(penaltyOutstanding);
        List<JournalLine> lines = new ArrayList<>();
        lines.add(JournalLine.debit(gl.writeOffExpense(), total, ctx.branchCode()));
        if (principalOutstanding.isPositive()) {
            lines.add(JournalLine.credit(gl.principalReceivable(), principalOutstanding, ctx.branchCode()));
        }
        if (interestOutstanding.isPositive()) {
            lines.add(JournalLine.credit(gl.interestReceivable(), interestOutstanding, ctx.branchCode()));
        }
        if (penaltyOutstanding.isPositive()) {
            lines.add(JournalLine.credit(gl.penaltyReceivable(), penaltyOutstanding, ctx.branchCode()));
        }
        return entry(ctx, "WRITEOFF:" + approvalRef, businessDate, "LOAN", correlationId, lines);
    }

    public static String idempotencyKey(UUID loanId, String event) {
        return "loan:" + loanId + ":" + event;
    }

    private static JournalEntry entry(LoanContext ctx, String event, LocalDate businessDate, String source,
                                      String correlationId, List<JournalLine> lines) {
        String key = idempotencyKey(ctx.loanId(), event);
        // Deterministic entry id from the idempotency key: a replay produces the same id.
        UUID entryId = UUID.nameUUIDFromBytes((ctx.tenantId() + "|" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new JournalEntry(entryId, ctx.tenantId(), key, businessDate, source, correlationId, null, null, lines);
    }
}
