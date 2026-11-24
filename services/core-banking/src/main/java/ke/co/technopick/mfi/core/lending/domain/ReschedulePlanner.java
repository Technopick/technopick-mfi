package ke.co.technopick.mfi.core.lending.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import ke.co.technopick.mfi.canonical.Money;

/**
 * Plans a new schedule version for a restructure or a partial prepayment (ADR-0037). Pure: the
 * caller supplies the loan's current schedule with what was paid and accrued on each instalment.
 *
 * <p>Rules (the database checks the same at commit, V34):
 * <ul>
 *   <li>Instalments due on or before today keep their due date and interest. A restructure lowers
 *       their principal to what was paid on them; the unpaid principal is planned again. Overdue
 *       interest and penalties stay due.</li>
 *   <li>The instalment accruing today keeps its due date and interest (its accrual depends on
 *       both); only its principal changes.</li>
 *   <li>Later instalments are planned again with the product's method and rate: a restructure over
 *       {@code instalments} after {@code graceMonths} interest-only months; a prepayment adds the
 *       amount to the accruing instalment's principal (paid from the member's credit on its due
 *       date) and plans the rest over the same number of instalments (lower instalment) or the
 *       fewest that keep the instalment no higher (shorter term).</li>
 * </ul>
 */
public final class ReschedulePlanner {

    public enum Kind { RESTRUCTURE, PREPAYMENT }

    public enum PrepaymentMode { SHORTEN_TERM, REDUCE_INSTALMENT }

    /** One instalment of the current schedule with what has happened on it. */
    public record Current(int no, LocalDate due, BigDecimal principal, BigDecimal interest, BigDecimal paidPrincipal,
                          BigDecimal paidInterest, BigDecimal accrued, BigDecimal unpaidPenalty) {
        BigDecimal unpaid() {
            return principal.add(interest).subtract(paidPrincipal).subtract(paidInterest);
        }
    }

    public record Request(Kind kind, Integer instalments, int graceMonths, BigDecimal prepayment, PrepaymentMode mode) { }

    public record Row(int no, LocalDate due, BigDecimal principal, BigDecimal interest) { }

    /**
     * @param rows             the whole new schedule version
     * @param firstChanged     number of the first instalment whose figures changed going forward
     * @param outstanding      principal not yet paid
     * @param stillDue         overdue interest and penalties that stay due now
     * @param instalments      instalments from the accruing one (or the first new one) to the end
     * @param instalmentAmount the regular instalment of the new plan (first amortising instalment)
     * @param interestBefore   scheduled interest not yet due, before
     * @param interestAfter    scheduled interest not yet due, after
     */
    public record Plan(List<Row> rows, int firstChanged, BigDecimal outstanding, BigDecimal stillDue, int instalments,
                       BigDecimal instalmentAmount, LocalDate firstDue, BigDecimal interestBefore, BigDecimal interestAfter) { }

    private ReschedulePlanner() {
    }

    public static Plan plan(LoanProductVersion product, List<Current> schedule, LocalDate today, Request r) {
        List<Current> rows = new ArrayList<>(schedule);
        rows.sort(Comparator.comparing(Current::due).thenComparingInt(Current::no));
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("the loan has no schedule");
        }
        List<Current> past = rows.stream().filter(c -> !c.due().isAfter(today)).toList();
        Current accruing = rows.stream().filter(c -> c.due().isAfter(today)).findFirst().orElse(null);
        List<Current> later = accruing == null ? List.of() : rows.subList(rows.indexOf(accruing) + 1, rows.size());
        for (Current c : later) {
            if (c.paidPrincipal().signum() != 0 || c.paidInterest().signum() != 0 || c.accrued().signum() != 0) {
                throw new IllegalStateException("instalment " + c.no() + " is not due yet but already has payments or accruals");
            }
        }
        BigDecimal total = rows.stream().map(Current::principal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal paid = rows.stream().map(Current::paidPrincipal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal outstanding = total.subtract(paid);
        BigDecimal stillDue = BigDecimal.ZERO;
        for (Current c : past) {
            stillDue = stillDue.add(c.interest().subtract(c.paidInterest()).max(BigDecimal.ZERO)).add(c.unpaidPenalty());
        }
        BigDecimal interestBefore = (accruing == null ? List.<Current>of() : rows.subList(rows.indexOf(accruing), rows.size()))
            .stream().map(Current::interest).reduce(BigDecimal.ZERO, BigDecimal::add);
        return switch (r.kind()) {
            case RESTRUCTURE -> restructure(product, rows, past, accruing, today, r, total, outstanding, stillDue, interestBefore);
            case PREPAYMENT -> prepayment(product, rows, past, accruing, later, r, outstanding, stillDue, interestBefore);
        };
    }

    private static Plan restructure(LoanProductVersion product, List<Current> rows, List<Current> past, Current accruing,
                                    LocalDate today, Request r, BigDecimal total, BigDecimal outstanding, BigDecimal stillDue,
                                    BigDecimal interestBefore) {
        int n = r.instalments() == null ? 0 : r.instalments();
        if (n < 1 || n > 120) {
            throw new IllegalArgumentException("instalments must be 1 to 120");
        }
        if (r.graceMonths() < 0 || r.graceMonths() > 12) {
            throw new IllegalArgumentException("grace months must be 0 to 12");
        }
        List<Row> out = new ArrayList<>();
        BigDecimal kept = BigDecimal.ZERO;
        for (Current c : past) {
            out.add(new Row(c.no(), c.due(), c.paidPrincipal(), c.interest()));
            kept = kept.add(c.paidPrincipal());
        }
        BigDecimal paidOnAccruing = accruing == null ? BigDecimal.ZERO : accruing.paidPrincipal();
        BigDecimal toPlan = total.subtract(kept).subtract(paidOnAccruing);
        if (toPlan.signum() <= 0) {
            throw new IllegalArgumentException("no principal is left to reschedule");
        }
        int startNo = accruing != null ? accruing.no() : rows.stream().mapToInt(Current::no).max().orElse(0) + 1;
        LocalDate firstDue = accruing != null ? accruing.due() : today.plusMonths(1);
        BigDecimal fixedInterest = accruing == null ? null : accruing.interest();
        Money planned = Money.of(toPlan, product.currency());
        List<Installment> amortising = calc(product, planned, n);
        int no = startNo;
        BigDecimal interestAfter = BigDecimal.ZERO;
        for (int j = 0; j < r.graceMonths() + n; j++) {
            boolean grace = j < r.graceMonths();
            BigDecimal principal = grace ? BigDecimal.ZERO : amortising.get(j - r.graceMonths()).principal().amount();
            BigDecimal interest = grace ? planned.times(product.periodicRate(), product.rounding()).amount()
                    : amortising.get(j - r.graceMonths()).interest().amount();
            if (j == 0 && fixedInterest != null) {
                interest = fixedInterest;
                principal = principal.add(paidOnAccruing);
            }
            out.add(new Row(no++, firstDue.plusMonths(j), principal, interest));
            interestAfter = interestAfter.add(interest);
        }
        Installment first = amortising.getFirst();
        return new Plan(out, startNo, outstanding, stillDue, r.graceMonths() + n, first.total().amount(), firstDue, interestBefore,
                interestAfter);
    }

    private static Plan prepayment(LoanProductVersion product, List<Current> rows, List<Current> past, Current accruing,
                                   List<Current> later, Request r, BigDecimal outstanding, BigDecimal stillDue,
                                   BigDecimal interestBefore) {
        if (r.prepayment() == null || r.prepayment().signum() <= 0 || r.prepayment().scale() > 2) {
            throw new IllegalArgumentException("prepayment must be a positive amount with at most 2 decimals");
        }
        if (r.mode() == null) {
            throw new IllegalArgumentException("choose SHORTEN_TERM or REDUCE_INSTALMENT");
        }
        for (Current c : past) {
            if (c.unpaid().signum() > 0 || c.unpaidPenalty().signum() > 0) {
                throw new IllegalStateException("the loan is in arrears; clear the arrears before a prepayment");
            }
        }
        if (accruing == null || later.isEmpty()) {
            throw new IllegalStateException("no instalments are left to shorten or lower; use early settlement");
        }
        BigDecimal remainingLater = later.stream().map(Current::principal).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (r.prepayment().compareTo(remainingLater) >= 0) {
            throw new IllegalArgumentException("the prepayment covers everything after the next instalment; use early settlement");
        }
        List<Row> out = new ArrayList<>();
        for (Current c : past) {
            out.add(new Row(c.no(), c.due(), c.principal(), c.interest()));
        }
        out.add(new Row(accruing.no(), accruing.due(), accruing.principal().add(r.prepayment()), accruing.interest()));
        Money rest = Money.of(remainingLater.subtract(r.prepayment()), product.currency());
        BigDecimal oldInstalment = later.getFirst().principal().add(later.getFirst().interest());
        int n = later.size();
        if (r.mode() == PrepaymentMode.SHORTEN_TERM) {
            for (int k = 1; k <= later.size(); k++) {
                if (calc(product, rest, k).getFirst().total().amount().compareTo(oldInstalment) <= 0) {
                    n = k;
                    break;
                }
            }
        }
        List<Installment> plan = calc(product, rest, n);
        BigDecimal interestAfter = accruing.interest();
        for (int i = 0; i < n; i++) {
            Installment in = plan.get(i);
            out.add(new Row(accruing.no() + 1 + i, accruing.due().plusMonths(i + 1L), in.principal().amount(), in.interest().amount()));
            interestAfter = interestAfter.add(in.interest().amount());
        }
        return new Plan(out, accruing.no(), outstanding, stillDue, n + 1, plan.getFirst().total().amount(), accruing.due(),
                interestBefore, interestAfter);
    }

    private static List<Installment> calc(LoanProductVersion product, Money principal, int n) {
        return switch (product.interestMethod()) {
            case REDUCING_BALANCE -> ScheduleCalculator.reducingBalance(principal, product.periodicRate(), n, product.rounding());
            case FLAT -> ScheduleCalculator.flat(principal, product.periodicRate(), n, product.rounding());
        };
    }
}
