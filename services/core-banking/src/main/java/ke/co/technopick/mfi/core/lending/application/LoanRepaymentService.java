package ke.co.technopick.mfi.core.lending.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ke.co.technopick.mfi.canonical.Money;
import ke.co.technopick.mfi.core.accounting.domain.JournalEntry;
import ke.co.technopick.mfi.core.accounting.persistence.JdbcJournal;
import ke.co.technopick.mfi.core.lending.domain.DueComponent;
import ke.co.technopick.mfi.core.lending.domain.InstallmentDue;
import ke.co.technopick.mfi.core.lending.domain.LoanGlMapping;
import ke.co.technopick.mfi.core.lending.domain.LoanPostingRules;
import ke.co.technopick.mfi.core.lending.domain.LoanProductVersion;
import ke.co.technopick.mfi.core.lending.domain.RepaymentAllocator;
import ke.co.technopick.mfi.core.messaging.persistence.Outbox;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies a repayment to a loan inside the caller's transaction (tenant already bound).
 *
 * <p>Dues are the unpaid principal, interest and penalties of installments due on or before the
 * business date (schedule minus earlier allocations; penalties from EOD charges). The payment is
 * allocated with the loan's pinned product version; any remainder becomes an overpayment
 * liability (never silently prepaid). Interest paid before the EOD accrual caught up is credited
 * to income rather than the receivable. The loan row is locked, so concurrent repayments on one
 * loan are serialised. Database rules (V3) re-check that allocations + remainder = payment and that
 * the repayment equals its journal entry.
 */
@Service
public class LoanRepaymentService {

    public record LoanRef(UUID loanId, String loanNumber, String status) { }

    public record Result(UUID repaymentId, UUID loanId, String loanNumber, UUID journalEntryId, Money allocated,
                         Money overpayment) { }

    private final JdbcClient jdbc;
    private final JdbcJournal journal;
    private final Outbox outbox;

    public LoanRepaymentService(JdbcClient jdbc, JdbcJournal journal, Outbox outbox) {
        this.jdbc = jdbc;
        this.journal = journal;
        this.outbox = outbox;
    }

    public Optional<LoanRef> findByNumber(String loanNumber) {
        return jdbc.sql("SELECT loan_id, loan_number, status FROM lending.loan WHERE loan_number = :n")
            .param("n", loanNumber)
            .query((rs, i) -> new LoanRef(rs.getObject("loan_id", UUID.class), rs.getString("loan_number"),
                    rs.getString("status")))
            .optional();
    }

    /**
     * @param channel       lending.repayment channel (MPESA_C2B, SUSPENSE, ...)
     * @param providerRef   unique reference within the channel (M-Pesa receipt, resolution id, ...)
     * @param debitAccount  GL account the money comes from (paybill clearing, suspense, ...)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Result apply(UUID tenantId, UUID loanId, Money payment, String channel, String providerRef,
                        String debitAccount, LocalDate businessDate, String correlationId, String actor) {
        Loaded loan = load(loanId);
        if (!"ACTIVE".equals(loan.status())) {
            throw new IllegalArgumentException("loan " + loan.loanNumber() + " is " + loan.status());
        }
        Currency ccy = payment.currency();
        if (!loan.product().currency().equals(ccy)) {
            throw new IllegalArgumentException("payment currency differs from the loan");
        }
        List<InstallmentDue> dues = dues(loanId, businessDate, ccy);
        RepaymentAllocator.Result allocation = loan.product().allocator().allocate(payment, dues);

        Map<Integer, Money> accruedUnpaid = accruedUnpaidInterest(loanId, ccy);
        Money notYetAccrued = Money.zero(ccy);
        for (RepaymentAllocator.Allocation a : allocation.allocations()) {
            if (a.component() == DueComponent.INTEREST) {
                Money covered = accruedUnpaid.getOrDefault(a.installmentNumber(), Money.zero(ccy));
                if (a.amount().compareTo(covered) > 0) {
                    notYetAccrued = notYetAccrued.plus(a.amount().minus(covered.isNegative() ? Money.zero(ccy) : covered));
                }
            }
        }
        LoanPostingRules.LoanContext ctx = new LoanPostingRules.LoanContext(tenantId, loanId, loan.branchCode(),
                loan.product());
        JournalEntry entry = LoanPostingRules.repayment(ctx, payment, allocation, debitAccount, businessDate,
                channel + ":" + providerRef, correlationId, notYetAccrued);
        journal.post(entry, actor);

        UUID repaymentId = UUID.nameUUIDFromBytes((tenantId + "|repayment|" + channel + "|" + providerRef)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO lending.repayment (tenant_id, repayment_id, loan_id, amount, unallocated, currency, channel,
                    provider_ref, journal_entry_id, received_at)
                VALUES (:tenant, :id, :loan, :amount, :unallocated, :ccy, :channel, :ref, :entry, clock_timestamp())
                """)
            .param("tenant", tenantId).param("id", repaymentId).param("loan", loanId)
            .param("amount", payment.amount()).param("unallocated", allocation.unallocated().amount())
            .param("ccy", ccy.getCurrencyCode()).param("channel", channel).param("ref", providerRef)
            .param("entry", entry.entryId())
            .update();
        int line = 1;
        for (RepaymentAllocator.Allocation a : allocation.allocations()) {
            jdbc.sql("""
                    INSERT INTO lending.repayment_allocation (tenant_id, repayment_id, line_no, installment_no, component, amount)
                    VALUES (:tenant, :id, :line, :inst, :component, :amount)
                    """)
                .param("tenant", tenantId).param("id", repaymentId).param("line", line++)
                .param("inst", a.installmentNumber()).param("component", a.component().name())
                .param("amount", a.amount().amount())
                .update();
        }
        outbox.record(tenantId, "LoanRepaymentReceived", "loan_repayment", repaymentId.toString(),
                "{\"repaymentId\":\"%s\",\"loanId\":\"%s\",\"amount\":\"%s\",\"overpayment\":\"%s\",\"currency\":\"%s\"}"
                        .formatted(repaymentId, loanId, payment.amount().toPlainString(),
                                allocation.unallocated().amount().toPlainString(), ccy.getCurrencyCode()),
                correlationId);
        return new Result(repaymentId, loanId, loan.loanNumber(), entry.entryId(), allocation.totalAllocated(ccy),
                allocation.unallocated());
    }

    private record Loaded(String loanNumber, String status, String branchCode, LoanProductVersion product) { }

    private Loaded load(UUID loanId) {
        return jdbc.sql("""
                SELECT l.loan_number, l.status, l.branch_code, p.currency, v.*
                  FROM lending.loan l
                  JOIN lending.loan_product_version v ON v.tenant_id = l.tenant_id AND v.version_id = l.product_version_id
                  JOIN lending.loan_product p ON p.tenant_id = v.tenant_id AND p.product_code = v.product_code
                 WHERE l.loan_id = :loan
                   FOR UPDATE OF l
                """)
            .param("loan", loanId)
            .query((rs, i) -> {
                Currency c = Currency.getInstance(rs.getString("currency"));
                java.sql.Array order = rs.getArray("allocation_order");
                List<DueComponent> components = new ArrayList<>();
                for (Object o : (Object[]) order.getArray()) {
                    components.add(DueComponent.valueOf(String.valueOf(o)));
                }
                LoanProductVersion v = new LoanProductVersion(rs.getObject("version_id", UUID.class),
                        rs.getString("product_code"), rs.getInt("version"), c,
                        LoanProductVersion.InterestMethod.valueOf(rs.getString("interest_method")),
                        rs.getBigDecimal("annual_rate"), RoundingMode.valueOf(rs.getString("rounding_mode")),
                        money(rs.getBigDecimal("min_principal"), c), money(rs.getBigDecimal("max_principal"), c),
                        rs.getInt("min_term_months"), rs.getInt("max_term_months"), rs.getBigDecimal("upfront_fee_rate"),
                        RepaymentAllocator.Strategy.valueOf(rs.getString("allocation_strategy")), components,
                        new LoanGlMapping(rs.getString("gl_principal"), rs.getString("gl_interest_receivable"),
                                rs.getString("gl_interest_income"), rs.getString("gl_fee_receivable"),
                                rs.getString("gl_fee_income"), rs.getString("gl_penalty_receivable"),
                                rs.getString("gl_penalty_income"), rs.getString("gl_overpayment"),
                                rs.getString("gl_write_off")));
                return new Loaded(rs.getString("loan_number"), rs.getString("status"), rs.getString("branch_code"), v);
            })
            .optional()
            .orElseThrow(() -> new IllegalArgumentException("loan not found"));
    }

    /** Unpaid principal, interest and penalties of installments due on or before the business date. */
    private List<InstallmentDue> dues(UUID loanId, LocalDate businessDate, Currency ccy) {
        record Row(int no, LocalDate due, BigDecimal principal, BigDecimal interest, BigDecimal penalty,
                   BigDecimal paidPrincipal, BigDecimal paidInterest, BigDecimal paidPenalty) { }
        List<Row> rows = jdbc.sql("""
                WITH schedule AS (
                    SELECT installment_no, due_date, principal, interest FROM lending.schedule_installment
                     WHERE loan_id = :loan
                       AND schedule_version = (SELECT max(schedule_version) FROM lending.schedule_installment WHERE loan_id = :loan)),
                paid AS (
                    SELECT a.installment_no, a.component, sum(a.amount) AS amount
                      FROM lending.repayment_allocation a
                      JOIN lending.repayment r ON r.tenant_id = a.tenant_id AND r.repayment_id = a.repayment_id
                     WHERE r.loan_id = :loan
                     GROUP BY a.installment_no, a.component),
                penalties AS (
                    SELECT installment_no, sum(amount) AS amount FROM lending.penalty_charge
                     WHERE loan_id = :loan GROUP BY installment_no)
                SELECT s.installment_no, s.due_date, s.principal, s.interest, COALESCE(pc.amount, 0) AS penalty,
                       COALESCE((SELECT amount FROM paid WHERE installment_no = s.installment_no AND component = 'PRINCIPAL'), 0) AS paid_principal,
                       COALESCE((SELECT amount FROM paid WHERE installment_no = s.installment_no AND component = 'INTEREST'), 0) AS paid_interest,
                       COALESCE((SELECT amount FROM paid WHERE installment_no = s.installment_no AND component = 'PENALTY'), 0) AS paid_penalty
                  FROM schedule s LEFT JOIN penalties pc ON pc.installment_no = s.installment_no
                 WHERE s.due_date <= :date
                 ORDER BY s.due_date, s.installment_no
                """)
            .param("loan", loanId).param("date", businessDate)
            .query((rs, i) -> new Row(rs.getInt("installment_no"), rs.getObject("due_date", LocalDate.class),
                    rs.getBigDecimal("principal"), rs.getBigDecimal("interest"), rs.getBigDecimal("penalty"),
                    rs.getBigDecimal("paid_principal"), rs.getBigDecimal("paid_interest"), rs.getBigDecimal("paid_penalty")))
            .list();
        List<InstallmentDue> dues = new ArrayList<>();
        for (Row r : rows) {
            Map<DueComponent, Money> outstanding = new EnumMap<>(DueComponent.class);
            outstanding.put(DueComponent.PRINCIPAL, money(r.principal().subtract(r.paidPrincipal()), ccy));
            outstanding.put(DueComponent.INTEREST, money(r.interest().subtract(r.paidInterest()), ccy));
            outstanding.put(DueComponent.PENALTY, money(r.penalty().subtract(r.paidPenalty()), ccy));
            dues.add(new InstallmentDue(r.no(), r.due(), outstanding));
        }
        return dues;
    }

    /** Per installment: interest accrued by EOD minus interest already paid (may be zero or negative). */
    private Map<Integer, Money> accruedUnpaidInterest(UUID loanId, Currency ccy) {
        Map<Integer, Money> result = new HashMap<>();
        jdbc.sql("""
                SELECT s.installment_no,
                       COALESCE((SELECT sum(amount) FROM lending.loan_accrual a
                                  WHERE a.loan_id = :loan AND a.installment_no = s.installment_no), 0)
                     - COALESCE((SELECT sum(al.amount) FROM lending.repayment_allocation al
                                   JOIN lending.repayment r ON r.tenant_id = al.tenant_id AND r.repayment_id = al.repayment_id
                                  WHERE r.loan_id = :loan AND al.installment_no = s.installment_no
                                    AND al.component = 'INTEREST'), 0) AS accrued_unpaid
                  FROM (SELECT DISTINCT installment_no FROM lending.schedule_installment WHERE loan_id = :loan) s
                """)
            .param("loan", loanId)
            .query((rs, i) -> Map.entry(rs.getInt(1), money(rs.getBigDecimal(2), ccy)))
            .list()
            .forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    private static Money money(BigDecimal value, Currency c) {
        return Money.of(value.setScale(c.getDefaultFractionDigits(), RoundingMode.UNNECESSARY), c);
    }
}
