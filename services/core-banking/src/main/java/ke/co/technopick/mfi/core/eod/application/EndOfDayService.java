package ke.co.technopick.mfi.core.eod.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import ke.co.technopick.mfi.core.common.ConflictException;
import ke.co.technopick.mfi.core.messaging.persistence.Outbox;
import ke.co.technopick.mfi.core.savings.application.SavingsInterestService;
import ke.co.technopick.mfi.core.savings.application.SavingsInterestService.AccrualGroup;
import ke.co.technopick.mfi.core.savings.application.SavingsInterestService.AccrualResult;
import ke.co.technopick.mfi.core.savings.application.SavingsInterestService.PostingResult;
import ke.co.technopick.mfi.core.savings.domain.SavingsInterest;
import ke.co.technopick.mfi.core.tenancy.persistence.TenantSession;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Closes the business day (ADR-0019). Steps, each in its own transaction so a crash resumes
 * without double posting: start (or resume) the run, accrue savings interest per product and
 * branch, credit interest to accounts on the last day of the month, then complete the run and
 * move the business date forward one day. The database refuses to complete while any till is
 * open (V18) and refuses to move the date before the run has completed (V4).
 *
 * <p>Loan end of day (accrual, penalties, arrears snapshots) is not wired yet; until it is, the
 * run refuses tenants with active loans rather than closing days without their loan accruals.
 */
@Service
public class EndOfDayService {

    /** A run left RUNNING longer than this is treated as interrupted and may be resumed. */
    static final int STALE_MINUTES = 10;

    public record OpenTill(String tillCode, String teller, String status) { }

    public record EndOfDayStatus(LocalDate businessDate, String runStatus, OffsetDateTime runStartedAt, String failureReason,
                                 List<OpenTill> openTills, int activeLoans, boolean interestPostingDay,
                                 LocalDate lastClosedDate) { }

    public record EndOfDaySummary(LocalDate closedDate, LocalDate newBusinessDate, int accountsAccrued,
                                  String interestAccrued, int accountsCredited, String interestCredited,
                                  String taxWithheld) { }

    private final JdbcClient jdbc;
    private final TenantSession session;
    private final SavingsInterestService interest;
    private final Outbox outbox;
    private final TransactionTemplate tx;

    public EndOfDayService(JdbcClient jdbc, TenantSession session, SavingsInterestService interest, Outbox outbox,
                           PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.session = session;
        this.interest = interest;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(transactions);
    }

    public EndOfDayStatus status(UUID tenantId) {
        return tx.execute(s -> {
            session.bind(tenantId);
            return readStatus();
        });
    }

    public EndOfDaySummary run(UUID tenantId, String actor) {
        LocalDate date = tx.execute(s -> {
            session.bind(tenantId);
            EndOfDayStatus st = readStatus();
            if (st.activeLoans() > 0) {
                throw new ConflictException("loan end of day (accrual, penalties, arrears) is not built yet; this tenant has "
                        + st.activeLoans() + " active loan(s), so the day cannot be closed without them");
            }
            if (!st.openTills().isEmpty()) {
                throw new ConflictException("close and balance every till first: " + st.openTills().stream()
                        .map(t -> t.tillCode() + " (" + t.teller() + ", " + t.status() + ")").toList());
            }
            if ("RUNNING".equals(st.runStatus()) && st.runStartedAt() != null
                    && st.runStartedAt().isAfter(OffsetDateTime.now().minusMinutes(STALE_MINUTES))) {
                throw new ConflictException("end of day for " + st.businessDate() + " is already running");
            }
            if (st.runStatus() == null) {
                jdbc.sql("INSERT INTO ledger.eod_run (tenant_id, business_date) VALUES (:t, :d)")
                    .param("t", tenantId).param("d", st.businessDate()).update();
            } else if ("FAILED".equals(st.runStatus())) {
                jdbc.sql("UPDATE ledger.eod_run SET status = 'RUNNING', failure_reason = NULL WHERE business_date = :d")
                    .param("d", st.businessDate()).update();
            } else if ("COMPLETED".equals(st.runStatus())) {
                throw new IllegalStateException("end of day for " + st.businessDate() + " completed but the date did not move");
            }
            return st.businessDate();
        });
        try {
            int accrued = 0;
            BigDecimal accruedAmount = BigDecimal.ZERO.setScale(2);
            List<AccrualGroup> groups = tx.execute(s -> {
                session.bind(tenantId);
                return interest.accrualGroups();
            });
            for (AccrualGroup g : groups) {
                AccrualResult r = tx.execute(s -> {
                    session.bind(tenantId);
                    return interest.accrue(tenantId, g, date, actor);
                });
                accrued += r.accounts();
                accruedAmount = accruedAmount.add(r.amount());
            }
            int credited = 0;
            BigDecimal net = BigDecimal.ZERO.setScale(2);
            BigDecimal tax = BigDecimal.ZERO.setScale(2);
            if (SavingsInterest.isPostingDay(date)) {
                List<UUID> accounts = tx.execute(s -> {
                    session.bind(tenantId);
                    return interest.accountsToCredit(date);
                });
                for (UUID account : accounts) {
                    PostingResult r = tx.execute(s -> {
                        session.bind(tenantId);
                        return interest.credit(tenantId, account, date, actor);
                    });
                    credited++;
                    net = net.add(r.net());
                    tax = tax.add(r.tax());
                }
            }
            int accruedAccounts = accrued;
            BigDecimal accruedTotal = accruedAmount;
            int creditedAccounts = credited;
            BigDecimal creditedNet = net;
            BigDecimal taxTotal = tax;
            return tx.execute(s -> {
                session.bind(tenantId);
                jdbc.sql("UPDATE ledger.eod_run SET status = 'COMPLETED' WHERE business_date = :d").param("d", date).update();
                jdbc.sql("UPDATE tenancy.business_date SET current_date_ = current_date_ + 1").update();
                outbox.record(tenantId, "BusinessDayClosed", "eod_run", tenantId + ":" + date,
                        "{\"closedDate\":\"%s\",\"newBusinessDate\":\"%s\",\"interestAccrued\":\"%s\",\"interestCredited\":\"%s\"}"
                                .formatted(date, date.plusDays(1), accruedTotal.toPlainString(), creditedNet.toPlainString()),
                        "eod:" + date);
                return new EndOfDaySummary(date, date.plusDays(1), accruedAccounts, accruedTotal.toPlainString(),
                        creditedAccounts, creditedNet.toPlainString(), taxTotal.toPlainString());
            });
        } catch (RuntimeException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            tx.executeWithoutResult(s -> {
                session.bind(tenantId);
                jdbc.sql("UPDATE ledger.eod_run SET status = 'FAILED', failure_reason = :r WHERE business_date = :d AND status = 'RUNNING'")
                    .param("r", reason.length() > 500 ? reason.substring(0, 500) : reason).param("d", date).update();
            });
            throw e;
        }
    }

    private EndOfDayStatus readStatus() {
        LocalDate date = jdbc.sql("SELECT current_date_ FROM tenancy.business_date").query(LocalDate.class).optional()
            .orElseThrow(() -> new IllegalStateException("tenant has no business date configured"));
        record Run(String status, OffsetDateTime startedAt, String reason) { }
        Run run = jdbc.sql("SELECT status, started_at, failure_reason FROM ledger.eod_run WHERE business_date = :d")
            .param("d", date)
            .query((rs, i) -> new Run(rs.getString(1), rs.getObject(2, OffsetDateTime.class), rs.getString(3)))
            .optional().orElse(new Run(null, null, null));
        List<OpenTill> tills = jdbc.sql("""
                SELECT t.till_code, s.teller, s.status FROM savings.till_session s
                  JOIN savings.till t ON t.tenant_id = s.tenant_id AND t.till_id = s.till_id
                 WHERE s.status <> 'CLOSED' ORDER BY t.till_code
                """)
            .query((rs, i) -> new OpenTill(rs.getString(1), rs.getString(2), rs.getString(3))).list();
        int loans = jdbc.sql("SELECT count(*) FROM lending.loan WHERE status = 'ACTIVE'").query(Integer.class).single();
        LocalDate last = jdbc.sql("SELECT max(business_date) FROM ledger.eod_run WHERE status = 'COMPLETED'")
            .query(LocalDate.class).optional().orElse(null);
        return new EndOfDayStatus(date, run.status(), run.startedAt(), run.reason(), tills, loans,
                SavingsInterest.isPostingDay(date), last);
    }
}
