package ke.co.technopick.mfi.core.lending.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import ke.co.technopick.mfi.core.messaging.persistence.Outbox;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Closes a loan once everything on its schedule and every penalty is paid (ADR-0021). Called
 * after each repayment and during end of day; the database (V20) refuses a closure while anything
 * is unpaid and records the credit (overpayment) still held for the customer.
 */
@Service
public class LoanClosureService {

    private final JdbcClient jdbc;
    private final Outbox outbox;
    private final InterestSuspenseService suspension;

    public LoanClosureService(JdbcClient jdbc, Outbox outbox, InterestSuspenseService suspension) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.suspension = suspension;
    }

    /** @return the credit still held when the loan was closed now; empty if it stays open */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<BigDecimal> closeIfRepaid(UUID tenantId, UUID loanId, LocalDate businessDate, String actor) {
        record State(String status, String number, BigDecimal unpaid, BigDecimal credit, String currency) { }
        State s = jdbc.sql("""
                SELECT status, loan_number, lending.loan_unpaid(tenant_id, loan_id) AS unpaid,
                       lending.loan_credit(tenant_id, loan_id) AS credit, currency
                  FROM lending.loan WHERE loan_id = :loan
                """)
            .param("loan", loanId)
            .query((rs, i) -> new State(rs.getString("status"), rs.getString("loan_number"), rs.getBigDecimal("unpaid"),
                    rs.getBigDecimal("credit"), rs.getString("currency")))
            .single();
        if (!"ACTIVE".equals(s.status()) || s.unpaid().signum() != 0) {
            return Optional.empty();
        }
        // Everything has been paid: interest held in suspense is now income (ADR-0031).
        suspension.releaseAll(tenantId, loanId, businessDate, InterestSuspenseService.Reason.CLOSURE, actor);
        jdbc.sql("""
                INSERT INTO lending.loan_closure (tenant_id, loan_id, closed_on, reason, credit_balance, closed_by)
                VALUES (:tenant, :loan, :date, 'REPAID', :credit, :actor)
                """)
            .param("tenant", tenantId).param("loan", loanId).param("date", businessDate).param("credit", s.credit())
            .param("actor", actor).update();
        jdbc.sql("UPDATE lending.loan SET status = 'CLOSED' WHERE loan_id = :loan").param("loan", loanId).update();
        String credit = s.credit().setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString();
        outbox.record(tenantId, "LoanClosed", "loan", loanId.toString(),
                "{\"loanNumber\":\"%s\",\"closedOn\":\"%s\",\"reason\":\"REPAID\",\"creditBalance\":\"%s\",\"currency\":\"%s\"}"
                        .formatted(s.number(), businessDate, credit, s.currency()),
                "loan-closed:" + loanId);
        return Optional.of(s.credit());
    }
}
