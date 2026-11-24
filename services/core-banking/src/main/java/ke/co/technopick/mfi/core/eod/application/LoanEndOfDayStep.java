package ke.co.technopick.mfi.core.eod.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ke.co.technopick.mfi.canonical.Money;
import ke.co.technopick.mfi.core.accounting.application.JournalPosting;
import ke.co.technopick.mfi.core.accounting.domain.JournalEntry;
import ke.co.technopick.mfi.core.eod.domain.EndOfDayPlanner;
import ke.co.technopick.mfi.core.eod.domain.EndOfDayPlanner.InstallmentState;
import ke.co.technopick.mfi.core.eod.domain.EndOfDayPlanner.Plan;
import ke.co.technopick.mfi.core.lending.application.InterestSuspenseService;
import ke.co.technopick.mfi.core.lending.application.LoanClosureService;
import ke.co.technopick.mfi.core.lending.application.LoanRepaymentService;
import ke.co.technopick.mfi.core.lending.domain.DueComponent;
import ke.co.technopick.mfi.core.lending.domain.InstallmentDue;
import ke.co.technopick.mfi.core.lending.domain.LoanPostingRules;
import ke.co.technopick.mfi.core.lending.domain.LoanProductVersion;
import ke.co.technopick.mfi.core.lending.domain.PenaltyRule;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loan part of end of day (ADR-0020): for one ACTIVE loan and the business date being closed,
 * loads its schedule, repayments, accruals and penalties, runs {@link EndOfDayPlanner} and
 * persists the interest accrual, penalties and the arrears snapshot. One transaction per loan,
 * run inside the caller's tenant-bound transaction; the snapshot marks the loan as done for the
 * day, so a resumed run skips it. The database (V4, V19) re-checks dates, amounts, recognition
 * of prepaid interest, the penalty rule and the snapshot's principal.
 */
@Component
public class LoanEndOfDayStep {

    public record LoanResult(boolean processed, Money interestAccrued, Money penalties, long daysPastDue, Money creditApplied,
                             boolean closed) { }

    private record Installment(int no, LocalDate due, BigDecimal principal, BigDecimal interest, BigDecimal paidPrincipal,
                               BigDecimal paidInterest, BigDecimal paidPenalty, BigDecimal penalties, BigDecimal accrued) { }

    private final JdbcClient jdbc;
    private final JournalPosting journal;
    private final LoanRepaymentService repayments;
    private final LoanClosureService closure;
    private final InterestSuspenseService suspension;

    public LoanEndOfDayStep(JdbcClient jdbc, JournalPosting journal, LoanRepaymentService repayments,
                            LoanClosureService closure, InterestSuspenseService suspension) {
        this.suspension = suspension;
        this.jdbc = jdbc;
        this.journal = journal;
        this.repayments = repayments;
        this.closure = closure;
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<UUID> loansToProcess(LocalDate date) {
        return jdbc.sql("""
                SELECT l.loan_id FROM lending.loan l
                 WHERE l.status = 'ACTIVE'
                   AND NOT EXISTS (SELECT 1 FROM lending.arrears_snapshot s
                                    WHERE s.tenant_id = l.tenant_id AND s.loan_id = l.loan_id AND s.as_of = :d)
                 ORDER BY l.loan_number
                """)
            .param("d", date).query(UUID.class).list();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LoanResult process(UUID tenantId, UUID loanId, LocalDate date, String actor) {
        Money creditApplied = applyCredit(tenantId, loanId, date, actor);
        if (closure.closeIfRepaid(tenantId, loanId, date, actor).isPresent()
                || !"ACTIVE".equals(jdbc.sql("SELECT status FROM lending.loan WHERE loan_id = :loan").param("loan", loanId)
                        .query(String.class).single())) {
            Money zero = Money.zero(creditApplied.currency());
            return new LoanResult(true, zero, zero, 0, creditApplied, true);
        }
        record Loan(String status, String branch, BigDecimal principal, LoanProductVersion product, LocalDate disbursedOn,
                    PenaltyRule penalty, BigDecimal tolerance) { }
        Loan loan = jdbc.sql("""
                SELECT l.status, l.branch_code, l.principal, p.currency, e.business_date AS disbursed_on, v.*
                  FROM lending.loan l
                  JOIN lending.loan_product_version v ON v.tenant_id = l.tenant_id AND v.version_id = l.product_version_id
                  JOIN lending.loan_product p ON p.tenant_id = v.tenant_id AND p.product_code = v.product_code
                  JOIN ledger.journal_entry e ON e.tenant_id = l.tenant_id AND e.entry_id = l.disbursement_entry_id
                 WHERE l.loan_id = :loan
                   FOR UPDATE OF l
                """)
            .param("loan", loanId)
            .query((rs, i) -> {
                LoanProductVersion v = LoanRepaymentService.product(rs);
                Currency c = v.currency();
                PenaltyRule rule = new PenaltyRule(PenaltyRule.Method.valueOf(rs.getString("penalty_method")),
                        rs.getInt("penalty_grace_days"), money(rs.getBigDecimal("penalty_flat"), c),
                        rs.getBigDecimal("penalty_daily_rate"), money(rs.getBigDecimal("penalty_cap"), c), v.rounding());
                return new Loan(rs.getString("status"), rs.getString("branch_code"), rs.getBigDecimal("principal"), v,
                        rs.getObject("disbursed_on", LocalDate.class), rule, rs.getBigDecimal("arrears_tolerance"));
            })
            .single();
        boolean done = jdbc.sql("SELECT count(*) FROM lending.arrears_snapshot WHERE loan_id = :loan AND as_of = :d")
            .param("loan", loanId).param("d", date).query(Integer.class).single() > 0;
        Currency ccy = loan.product().currency();
        if (!"ACTIVE".equals(loan.status()) || done) {
            return new LoanResult(false, Money.zero(ccy), Money.zero(ccy), 0, creditApplied, false);
        }

        List<Installment> rows = jdbc.sql("""
                WITH schedule AS (
                    SELECT installment_no, due_date, principal, interest FROM lending.schedule_installment
                     WHERE loan_id = :loan
                       AND schedule_version = (SELECT max(schedule_version) FROM lending.schedule_installment WHERE loan_id = :loan)),
                paid AS (
                    SELECT a.installment_no,
                           sum(a.amount) FILTER (WHERE a.component = 'PRINCIPAL') AS principal,
                           sum(a.amount) FILTER (WHERE a.component = 'INTEREST')  AS interest,
                           sum(a.amount) FILTER (WHERE a.component = 'PENALTY')   AS penalty
                      FROM lending.repayment_allocation a
                      JOIN lending.repayment r ON r.tenant_id = a.tenant_id AND r.repayment_id = a.repayment_id
                     WHERE r.loan_id = :loan GROUP BY a.installment_no)
                SELECT s.installment_no, s.due_date, s.principal, s.interest,
                       COALESCE(p.principal, 0) AS paid_principal, COALESCE(p.interest, 0) AS paid_interest,
                       COALESCE(p.penalty, 0) AS paid_penalty,
                       COALESCE((SELECT sum(amount) FROM lending.penalty_charge c
                                  WHERE c.loan_id = :loan AND c.installment_no = s.installment_no), 0) AS penalties,
                       COALESCE((SELECT sum(amount) FROM lending.loan_accrual a
                                  WHERE a.loan_id = :loan AND a.installment_no = s.installment_no), 0) AS accrued
                  FROM schedule s LEFT JOIN paid p ON p.installment_no = s.installment_no
                 ORDER BY s.due_date, s.installment_no
                """)
            .param("loan", loanId)
            .query((rs, i) -> new Installment(rs.getInt("installment_no"), rs.getObject("due_date", LocalDate.class),
                    rs.getBigDecimal("principal"), rs.getBigDecimal("interest"), rs.getBigDecimal("paid_principal"),
                    rs.getBigDecimal("paid_interest"), rs.getBigDecimal("paid_penalty"), rs.getBigDecimal("penalties"),
                    rs.getBigDecimal("accrued")))
            .list();

        List<InstallmentState> states = new ArrayList<>();
        LocalDate periodStart = loan.disbursedOn();
        BigDecimal principalRepaid = BigDecimal.ZERO;
        for (Installment r : rows) {
            Map<DueComponent, Money> outstanding = new EnumMap<>(DueComponent.class);
            outstanding.put(DueComponent.PRINCIPAL, money(r.principal().subtract(r.paidPrincipal()), ccy));
            outstanding.put(DueComponent.INTEREST, money(r.interest().subtract(r.paidInterest()), ccy));
            outstanding.put(DueComponent.PENALTY, money(r.penalties().subtract(r.paidPenalty()), ccy));
            states.add(new InstallmentState(new InstallmentDue(r.no(), r.due(), outstanding), periodStart,
                    money(r.interest(), ccy), money(r.accrued(), ccy), money(r.penalties(), ccy),
                    money(r.paidInterest(), ccy)));
            periodStart = r.due();
            principalRepaid = principalRepaid.add(r.paidPrincipal());
        }
        LoanPostingRules.LoanContext ctx = new LoanPostingRules.LoanContext(tenantId, loanId, loan.branch(), loan.product());
        Plan plan = EndOfDayPlanner.plan(ctx, date, states, loan.penalty(), money(loan.tolerance(), ccy), "eod:" + date);

        UUID accrualEntryId = null;
        if (plan.accrualEntry() != null) {
            journal.post(plan.accrualEntry(), actor);
            accrualEntryId = plan.accrualEntry().entryId();
        }
        Money accrued = Money.zero(ccy);
        for (EndOfDayPlanner.Accrual a : plan.accruals()) {
            jdbc.sql("""
                    INSERT INTO lending.loan_accrual (tenant_id, loan_id, installment_no, business_date, amount,
                        recognized_earlier, entry_id)
                    VALUES (:tenant, :loan, :no, :d, :amount, :earlier, :entry)
                    """)
                .param("tenant", tenantId).param("loan", loanId).param("no", a.installmentNumber()).param("d", date)
                .param("amount", a.amount().amount()).param("earlier", a.recognizedEarlier().amount())
                .param("entry", accrualEntryId).update();
            accrued = accrued.plus(a.amount());
        }
        Money penalties = Money.zero(ccy);
        for (int i = 0; i < plan.penalties().size(); i++) {
            EndOfDayPlanner.InstallmentAmount pen = plan.penalties().get(i);
            JournalEntry entry = plan.penaltyEntries().get(i);
            journal.post(entry, actor);
            jdbc.sql("""
                    INSERT INTO lending.penalty_charge (tenant_id, loan_id, installment_no, business_date, amount, entry_id)
                    VALUES (:tenant, :loan, :no, :d, :amount, :entry)
                    """)
                .param("tenant", tenantId).param("loan", loanId).param("no", pen.installmentNumber()).param("d", date)
                .param("amount", pen.amount().amount()).param("entry", entry.entryId()).update();
            penalties = penalties.plus(pen.amount());
        }
        var arrears = plan.arrears();
        jdbc.sql("""
                INSERT INTO lending.arrears_snapshot (tenant_id, loan_id, as_of, days_past_due, bucket, classification,
                    arrears_amount, outstanding_principal)
                VALUES (:tenant, :loan, :d, :dpd, :bucket, :class, :arrears, :principal)
                """)
            .param("tenant", tenantId).param("loan", loanId).param("d", date).param("dpd", (int) arrears.daysPastDue())
            .param("bucket", arrears.bucket().name()).param("class", arrears.classification().name())
            .param("arrears", arrears.arrearsAmount().amount()).param("principal", loan.principal().subtract(principalRepaid))
            .update();
        suspension.adjustAtEndOfDay(tenantId, loanId, date, arrears.daysPastDue(), actor);
        return new LoanResult(true, accrued, penalties, arrears.daysPastDue(), creditApplied, false);
    }

    /**
     * Money the customer overpaid earlier pays installments as they fall due: an INTERNAL
     * repayment from the overpayment account, allocated like any other payment (ADR-0021).
     */
    private Money applyCredit(UUID tenantId, UUID loanId, LocalDate date, String actor) {
        record L(String number, String currency, String overpaymentGl) { }
        L l = jdbc.sql("""
                SELECT l.loan_number, l.currency, v.gl_overpayment FROM lending.loan l
                  JOIN lending.loan_product_version v ON v.tenant_id = l.tenant_id AND v.version_id = l.product_version_id
                 WHERE l.loan_id = :loan
                """)
            .param("loan", loanId)
            .query((rs, i) -> new L(rs.getString(1), rs.getString(2), rs.getString(3))).single();
        Currency ccy = Currency.getInstance(l.currency());
        Money credit = money(repayments.credit(loanId), ccy);
        if (!credit.isPositive()) {
            return Money.zero(ccy);
        }
        Money due = repayments.amountDue(loanId, date, ccy);
        if (!due.isPositive()) {
            return Money.zero(ccy);
        }
        Money pay = credit.compareTo(due) <= 0 ? credit : due;
        repayments.apply(tenantId, loanId, pay, "INTERNAL", "CREDIT:" + l.number() + ":" + date, l.overpaymentGl(), date,
                "eod:" + date, actor);
        return pay;
    }

    private static Money money(BigDecimal value, Currency c) {
        return value == null ? null : Money.of(value.setScale(c.getDefaultFractionDigits(), RoundingMode.UNNECESSARY), c);
    }
}
