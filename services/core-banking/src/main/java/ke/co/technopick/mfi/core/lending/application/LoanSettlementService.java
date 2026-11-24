package ke.co.technopick.mfi.core.lending.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ke.co.technopick.mfi.canonical.Money;
import ke.co.technopick.mfi.core.common.ConflictException;
import ke.co.technopick.mfi.core.common.NotFoundException;
import ke.co.technopick.mfi.core.tenancy.persistence.BusinessDates;
import ke.co.technopick.mfi.core.tenancy.persistence.TenantSession;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Early settlement (ADR-0032). The payoff is what the member owes today: principal outstanding,
 * interest accrued up to the last close and still unpaid, and unpaid penalties. Scheduled interest
 * that has not accrued is not charged. Settlement needs the member's credit on the loan (money
 * paid beyond what was due) to cover the payoff. It then writes a new schedule version (every
 * unpaid instalment due today, interest cut to what accrued), pays it from the credit like any
 * internal repayment, and the loan closes. Any credit left stays on the loan for refund.
 */
@Service
public class LoanSettlementService {

    public record SettlementQuote(String loanNumber, String status, LocalDate businessDate, String currency, String principal,
                                  String interest, String penalty, String payoff, String interestWaived, String creditHeld,
                                  String stillToPay, boolean canSettle, SettlementView settlement) { }

    public record SettlementView(UUID settlementId, LocalDate businessDate, String principal, String interest, String penalty,
                                 String payoff, String interestWaived, String creditLeft, int scheduleVersion, String settledBy,
                                 OffsetDateTime settledAt) { }

    private record Inst(int no, LocalDate due, BigDecimal principal, BigDecimal interest, BigDecimal paidPrincipal,
                        BigDecimal paidInterest, BigDecimal paidPenalty, BigDecimal penalties, BigDecimal accrued) {
        BigDecimal settledInterest() {
            return accrued.max(paidInterest);
        }
    }

    private record Figures(BigDecimal principal, BigDecimal interest, BigDecimal penalty, BigDecimal waived) {
        BigDecimal payoff() {
            return principal.add(interest).add(penalty);
        }
    }

    private record LoanRow(UUID id, String number, String status, String currency, String overpaymentGl) { }

    private final JdbcClient jdbc;
    private final TenantSession session;
    private final BusinessDates businessDates;
    private final LoanRepaymentService repayments;

    public LoanSettlementService(JdbcClient jdbc, TenantSession session, BusinessDates businessDates,
                                 LoanRepaymentService repayments) {
        this.jdbc = jdbc;
        this.session = session;
        this.businessDates = businessDates;
        this.repayments = repayments;
    }

    private static String m(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private LoanRow loan(String loanNumber, boolean lock) {
        return jdbc.sql("""
                SELECT l.loan_id, l.loan_number, l.status, l.currency, v.gl_overpayment FROM lending.loan l
                  JOIN lending.loan_product_version v ON v.tenant_id = l.tenant_id AND v.version_id = l.product_version_id
                 WHERE l.loan_number = :n
                """ + (lock ? " FOR UPDATE OF l" : ""))
            .param("n", loanNumber)
            .query((rs, i) -> new LoanRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getString(5)))
            .optional().orElseThrow(() -> new NotFoundException("loan " + loanNumber + " not found"));
    }

    private List<Inst> installments(UUID loanId) {
        return jdbc.sql("""
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
                       COALESCE(p.principal, 0), COALESCE(p.interest, 0), COALESCE(p.penalty, 0),
                       COALESCE((SELECT sum(amount) FROM lending.penalty_charge c
                                  WHERE c.loan_id = :loan AND c.installment_no = s.installment_no), 0),
                       COALESCE((SELECT sum(amount) FROM lending.loan_accrual a
                                  WHERE a.loan_id = :loan AND a.installment_no = s.installment_no), 0)
                  FROM schedule s LEFT JOIN paid p ON p.installment_no = s.installment_no
                 ORDER BY s.installment_no
                """).param("loan", loanId)
            .query((rs, i) -> new Inst(rs.getInt(1), rs.getObject(2, LocalDate.class), rs.getBigDecimal(3), rs.getBigDecimal(4),
                    rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getBigDecimal(9)))
            .list();
    }

    private static Figures figures(List<Inst> rows) {
        BigDecimal p = BigDecimal.ZERO;
        BigDecimal in = BigDecimal.ZERO;
        BigDecimal pen = BigDecimal.ZERO;
        BigDecimal waived = BigDecimal.ZERO;
        for (Inst r : rows) {
            p = p.add(r.principal().subtract(r.paidPrincipal()));
            in = in.add(r.settledInterest().subtract(r.paidInterest()));
            pen = pen.add(r.penalties().subtract(r.paidPenalty()).max(BigDecimal.ZERO));
            waived = waived.add(r.interest().subtract(r.settledInterest()).max(BigDecimal.ZERO));
        }
        return new Figures(p, in, pen, waived);
    }

    private Optional<SettlementView> existing(String where, Object value) {
        return jdbc.sql("""
                SELECT settlement_id, business_date, principal, interest, penalty, payoff, interest_waived, credit_left,
                       schedule_version, settled_by, settled_at
                  FROM lending.settlement
                """ + " WHERE " + where + " = :v")
            .param("v", value)
            .query((rs, i) -> new SettlementView(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class),
                    m(rs.getBigDecimal(3)), m(rs.getBigDecimal(4)), m(rs.getBigDecimal(5)), m(rs.getBigDecimal(6)),
                    m(rs.getBigDecimal(7)), m(rs.getBigDecimal(8)), rs.getInt(9), rs.getString(10),
                    rs.getObject(11, OffsetDateTime.class)))
            .optional();
    }

    @Transactional(readOnly = true)
    public SettlementQuote quote(UUID tenantId, String loanNumber) {
        session.bind(tenantId);
        LoanRow l = loan(loanNumber, false);
        LocalDate today = businessDates.current();
        BigDecimal credit = repayments.credit(l.id());
        Optional<SettlementView> done = existing("loan_id", l.id());
        if (!"ACTIVE".equals(l.status())) {
            return new SettlementQuote(l.number(), l.status(), today, l.currency(), null, null, null, null, null, m(credit), null,
                    false, done.orElse(null));
        }
        Figures f = figures(installments(l.id()));
        BigDecimal toPay = f.payoff().subtract(credit).max(BigDecimal.ZERO);
        return new SettlementQuote(l.number(), l.status(), today, l.currency(), m(f.principal()), m(f.interest()), m(f.penalty()),
                m(f.payoff()), m(f.waived()), m(credit), m(toPay), toPay.signum() == 0 && f.payoff().signum() > 0, null);
    }

    @Transactional
    public SettlementView settle(UUID tenantId, String loanNumber, String idempotencyKey, String actor) {
        session.bind(tenantId);
        LoanRow l = loan(loanNumber, true);
        Optional<SettlementView> replay = existing("idempotency_key", idempotencyKey);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (!"ACTIVE".equals(l.status())) {
            throw new ConflictException("loan " + l.number() + " is " + l.status());
        }
        LocalDate today = businessDates.current();
        List<Inst> rows = installments(l.id());
        Figures f = figures(rows);
        BigDecimal credit = repayments.credit(l.id());
        if (f.payoff().signum() <= 0) {
            throw new ConflictException("nothing is owed on loan " + l.number());
        }
        if (credit.compareTo(f.payoff()) < 0) {
            throw new ConflictException("payoff is " + m(f.payoff()) + " but the member has paid " + m(credit)
                    + " towards it; " + m(f.payoff().subtract(credit)) + " more is needed");
        }
        int version = jdbc.sql("SELECT max(schedule_version) FROM lending.schedule_installment WHERE loan_id = :loan")
            .param("loan", l.id()).query(Integer.class).single() + 1;
        for (Inst r : rows) {
            jdbc.sql("""
                    INSERT INTO lending.schedule_installment (tenant_id, loan_id, schedule_version, installment_no, due_date,
                        principal, interest)
                    VALUES (:t, :loan, :v, :no, :due, :p, :i)
                    """).param("t", tenantId).param("loan", l.id()).param("v", version).param("no", r.no())
                .param("due", r.due().isAfter(today) ? today : r.due()).param("p", r.principal()).param("i", r.settledInterest())
                .update();
        }
        Currency ccy = Currency.getInstance(l.currency());
        LoanRepaymentService.Result paid = repayments.apply(tenantId, l.id(),
                Money.of(f.payoff().setScale(ccy.getDefaultFractionDigits(), RoundingMode.UNNECESSARY), ccy), "INTERNAL",
                "CREDIT:" + l.number() + ":SETTLEMENT:" + today, l.overpaymentGl(), today, "settlement:" + l.number(), actor);
        BigDecimal creditLeft = repayments.credit(l.id());
        UUID id = UUID.nameUUIDFromBytes((tenantId + "|settlement|" + idempotencyKey).getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO lending.settlement (tenant_id, settlement_id, idempotency_key, loan_id, business_date, schedule_version,
                    principal, interest, penalty, payoff, interest_waived, credit_left, repayment_id, settled_by)
                VALUES (:t, :id, :k, :loan, :d, :v, :p, :i, :x, :payoff, :w, :left, :rep, :by)
                """).param("t", tenantId).param("id", id).param("k", idempotencyKey).param("loan", l.id()).param("d", today)
            .param("v", version).param("p", f.principal()).param("i", f.interest()).param("x", f.penalty())
            .param("payoff", f.payoff()).param("w", f.waived()).param("left", creditLeft).param("rep", paid.repaymentId())
            .param("by", actor).update();
        return existing("settlement_id", id).orElseThrow();
    }
}
