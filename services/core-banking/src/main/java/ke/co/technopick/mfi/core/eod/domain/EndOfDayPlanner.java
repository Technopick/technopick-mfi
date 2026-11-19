package ke.co.technopick.mfi.core.eod.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import ke.co.technopick.mfi.canonical.Money;
import ke.co.technopick.mfi.core.accounting.domain.JournalEntry;
import ke.co.technopick.mfi.core.collections.domain.ArrearsCalculator;
import ke.co.technopick.mfi.core.lending.domain.DueComponent;
import ke.co.technopick.mfi.core.lending.domain.InstallmentDue;
import ke.co.technopick.mfi.core.lending.domain.InterestAccrual;
import ke.co.technopick.mfi.core.lending.domain.LoanPostingRules;
import ke.co.technopick.mfi.core.lending.domain.PenaltyRule;

/**
 * Module {@code eod} (depends on lending and collections; nothing depends on it).
 *
 * <p>Plans one loan's end-of-day work for one business date: interest accrual, penalties and
 * the arrears snapshot. Pure and deterministic: the EOD job loads state, calls
 * {@link #plan}, then persists the entries, accrual/penalty records and the snapshot in one
 * transaction per loan. Re-planning the same date after a crash yields the same idempotency
 * keys, so nothing is posted twice.
 */
public final class EndOfDayPlanner {

    /**
     * State of one installment going into the run.
     *
     * @param periodStart       previous due date (or disbursement date for installment 1)
     * @param scheduledInterest interest for the period per the schedule
     * @param accruedSoFar      interest already accrued for this installment
     * @param penaltySoFar      penalty already charged on this installment
     * @param outstanding       unpaid amounts per component (from allocations)
     * @param interestPaid      interest already paid on this installment; interest paid before it
     *                          was accrued was recognised as income at repayment, so accruing it
     *                          later must not recognise it again
     */
    public record InstallmentState(InstallmentDue outstanding, LocalDate periodStart, Money scheduledInterest,
                                   Money accruedSoFar, Money penaltySoFar, Money interestPaid) {
        public InstallmentState {
            Objects.requireNonNull(outstanding, "outstanding");
            Objects.requireNonNull(periodStart, "periodStart");
            Objects.requireNonNull(scheduledInterest, "scheduledInterest");
            Objects.requireNonNull(accruedSoFar, "accruedSoFar");
            Objects.requireNonNull(penaltySoFar, "penaltySoFar");
            Objects.requireNonNull(interestPaid, "interestPaid");
        }

        /** No interest paid yet. */
        public InstallmentState(InstallmentDue outstanding, LocalDate periodStart, Money scheduledInterest,
                                Money accruedSoFar, Money penaltySoFar) {
            this(outstanding, periodStart, scheduledInterest, accruedSoFar, penaltySoFar, Money.zero(accruedSoFar.currency()));
        }

        public int number() {
            return outstanding.installmentNumber();
        }
    }

    public record InstallmentAmount(int installmentNumber, Money amount) { }

    /**
     * Interest accrued today on one installment. {@code recognizedEarlier} is the part already
     * taken to income when the customer paid it before it accrued; only the rest is posted
     * (Dr interest receivable / Cr interest income). Income recognised to date on an installment
     * is always max(accrued, paid).
     */
    public record Accrual(int installmentNumber, Money amount, Money recognizedEarlier) {
        public Money toPost() {
            return amount.minus(recognizedEarlier);
        }
    }

    /** Everything the EOD job must persist for this loan and date. */
    public record Plan(LocalDate businessDate, JournalEntry accrualEntry, List<Accrual> accruals,
                       List<JournalEntry> penaltyEntries, List<InstallmentAmount> penalties,
                       ArrearsCalculator.Position arrears) {
        public Plan {
            accruals = List.copyOf(accruals);
            penaltyEntries = List.copyOf(penaltyEntries);
            penalties = List.copyOf(penalties);
        }
    }

    private EndOfDayPlanner() {
    }

    private static Money max(Money a, Money b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    public static Plan plan(LoanPostingRules.LoanContext ctx, LocalDate businessDate, List<InstallmentState> installments,
                            PenaltyRule penaltyRule, Money arrearsTolerance, String correlationId) {
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(businessDate, "businessDate");
        Objects.requireNonNull(installments, "installments");
        Objects.requireNonNull(penaltyRule, "penaltyRule");
        Currency ccy = ctx.product().currency();
        var rounding = ctx.product().rounding();

        List<Accrual> accruals = new ArrayList<>();
        Money accrualTotal = Money.zero(ccy);
        List<InstallmentAmount> penalties = new ArrayList<>();
        List<JournalEntry> penaltyEntries = new ArrayList<>();

        for (InstallmentState s : installments) {
            LocalDate due = s.outstanding().dueDate();
            // Accrue while the period has started; catch-up continues past the due date until complete.
            if (businessDate.isAfter(s.periodStart())) {
                Money accrue = InterestAccrual.accrualDue(s.scheduledInterest(), s.periodStart(), due, businessDate,
                        s.accruedSoFar(), rounding);
                if (accrue.isPositive()) {
                    Money before = max(s.accruedSoFar(), s.interestPaid());
                    Money after = max(s.accruedSoFar().plus(accrue), s.interestPaid());
                    Money post = after.minus(before);
                    accruals.add(new Accrual(s.number(), accrue, accrue.minus(post)));
                    accrualTotal = accrualTotal.plus(post);
                }
            }
            Money unpaid = s.outstanding().outstanding(DueComponent.PRINCIPAL, ccy)
                    .plus(s.outstanding().outstanding(DueComponent.INTEREST, ccy));
            Money penalty = penaltyRule.chargeFor(due, businessDate, unpaid, s.penaltySoFar());
            if (penalty.isPositive()) {
                penalties.add(new InstallmentAmount(s.number(), penalty));
                penaltyEntries.add(LoanPostingRules.penaltyCharged(ctx, penalty, businessDate,
                        s.number() + ":" + businessDate, correlationId));
            }
        }
        JournalEntry accrualEntry = LoanPostingRules.interestAccrual(ctx, accrualTotal, businessDate, correlationId);
        ArrearsCalculator.Position arrears = ArrearsCalculator.position(
                installments.stream().map(InstallmentState::outstanding).toList(), businessDate, arrearsTolerance);
        return new Plan(businessDate, accrualEntry, accruals, penaltyEntries, penalties, arrears);
    }
}
