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
     */
    public record InstallmentState(InstallmentDue outstanding, LocalDate periodStart, Money scheduledInterest,
                                   Money accruedSoFar, Money penaltySoFar) {
        public InstallmentState {
            Objects.requireNonNull(outstanding, "outstanding");
            Objects.requireNonNull(periodStart, "periodStart");
            Objects.requireNonNull(scheduledInterest, "scheduledInterest");
            Objects.requireNonNull(accruedSoFar, "accruedSoFar");
            Objects.requireNonNull(penaltySoFar, "penaltySoFar");
        }

        public int number() {
            return outstanding.installmentNumber();
        }
    }

    public record InstallmentAmount(int installmentNumber, Money amount) { }

    /** Everything the EOD job must persist for this loan and date. */
    public record Plan(LocalDate businessDate, JournalEntry accrualEntry, List<InstallmentAmount> accruals,
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

    public static Plan plan(LoanPostingRules.LoanContext ctx, LocalDate businessDate, List<InstallmentState> installments,
                            PenaltyRule penaltyRule, Money arrearsTolerance, String correlationId) {
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(businessDate, "businessDate");
        Objects.requireNonNull(installments, "installments");
        Objects.requireNonNull(penaltyRule, "penaltyRule");
        Currency ccy = ctx.product().currency();
        var rounding = ctx.product().rounding();

        List<InstallmentAmount> accruals = new ArrayList<>();
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
                    accruals.add(new InstallmentAmount(s.number(), accrue));
                    accrualTotal = accrualTotal.plus(accrue);
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
