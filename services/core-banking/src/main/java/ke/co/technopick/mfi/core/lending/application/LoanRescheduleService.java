package ke.co.technopick.mfi.core.lending.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import ke.co.technopick.mfi.core.common.ConflictException;
import ke.co.technopick.mfi.core.common.NotFoundException;
import ke.co.technopick.mfi.core.lending.domain.LoanProductVersion;
import ke.co.technopick.mfi.core.lending.domain.ReschedulePlanner;
import ke.co.technopick.mfi.core.lending.domain.ReschedulePlanner.Current;
import ke.co.technopick.mfi.core.lending.domain.ReschedulePlanner.Kind;
import ke.co.technopick.mfi.core.lending.domain.ReschedulePlanner.Plan;
import ke.co.technopick.mfi.core.lending.domain.ReschedulePlanner.PrepaymentMode;
import ke.co.technopick.mfi.core.lending.domain.ReschedulePlanner.Row;
import ke.co.technopick.mfi.core.notification.application.NotificationService;
import ke.co.technopick.mfi.core.tenancy.persistence.BusinessDates;
import ke.co.technopick.mfi.core.tenancy.persistence.TenantSession;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loan restructuring and partial prepayment (ADR-0037): one person requests, another approves on
 * the same business date; approval writes the new schedule version planned by
 * {@link ReschedulePlanner} from the loan as it is at that moment.
 */
@Service
public class LoanRescheduleService {

    /** kind RESTRUCTURE (instalments, graceMonths) or PREPAYMENT (amount, mode SHORTEN_TERM / REDUCE_INSTALMENT). */
    public record RescheduleTerms(String kind, Integer instalments, Integer graceMonths, String amount, String mode) { }

    public record ScheduleRow(int no, LocalDate dueDate, String principal, String interest, String total) { }

    public record ReschedulePreview(String loanNumber, String kind, LocalDate businessDate, String outstandingPrincipal,
                                    String stillDueNow, String credit, int instalments, String instalmentAmount, LocalDate firstDue,
                                    String interestBefore, String interestAfter, int firstChanged, List<ScheduleRow> current,
                                    List<ScheduleRow> proposed) { }

    public record RescheduleView(UUID rescheduleId, String loanNumber, String kind, String status, LocalDate businessDate,
                                 Integer instalments, int graceMonths, String prepayment, String prepaymentMode, String outstanding,
                                 String reason, int fromVersion, Integer toVersion, String requestedBy, OffsetDateTime requestedAt,
                                 String decidedBy, OffsetDateTime decidedAt, String decisionReason, boolean replayed) { }

    private record LoanRow(UUID id, String number, String status, LoanProductVersion product) { }

    private static final String VIEW = """
            SELECT r.reschedule_id, l.loan_number, r.kind, r.status, r.business_date, r.instalments, r.grace_months, r.prepayment,
                   r.prepayment_mode, r.outstanding, r.reason, r.from_version, r.to_version, r.requested_by, r.requested_at,
                   r.decided_by, r.decided_at, r.decision_reason
              FROM lending.loan_reschedule r JOIN lending.loan l ON l.tenant_id = r.tenant_id AND l.loan_id = r.loan_id
            """;

    private final JdbcClient jdbc;
    private final TenantSession session;
    private final BusinessDates businessDates;
    private final LoanRepaymentService repayments;
    private final NotificationService notifications;

    public LoanRescheduleService(JdbcClient jdbc, TenantSession session, BusinessDates businessDates, LoanRepaymentService repayments,
                                 NotificationService notifications) {
        this.jdbc = jdbc;
        this.session = session;
        this.businessDates = businessDates;
        this.repayments = repayments;
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public ReschedulePreview preview(UUID tenantId, String loanNumber, RescheduleTerms t) {
        session.bind(tenantId);
        LoanRow l = loan(loanNumber, false);
        return preview(l, terms(t), t);
    }

    @Transactional
    public RescheduleView request(UUID tenantId, String loanNumber, String idempotencyKey, RescheduleTerms t, String reason,
                                  String actor) {
        session.bind(tenantId);
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 80) {
            throw new IllegalArgumentException("idempotencyKey (up to 80 characters) is required");
        }
        var replay = jdbc.sql(VIEW + " WHERE r.idempotency_key = :k").param("k", idempotencyKey.strip())
            .query((rs, i) -> view(rs, true)).optional();
        if (replay.isPresent()) {
            return replay.get();
        }
        if (reason == null || reason.strip().length() < 10 || reason.length() > 300) {
            throw new IllegalArgumentException("a reason of 10 to 300 characters is required (why, and what the member agreed)");
        }
        LoanRow l = loan(loanNumber, true);
        if (jdbc.sql("SELECT EXISTS (SELECT 1 FROM lending.loan_reschedule WHERE loan_id = :l AND status = 'REQUESTED')")
                .param("l", l.id()).query(Boolean.class).single()) {
            throw new ConflictException("loan " + l.number() + " already has a reschedule waiting for approval");
        }
        ReschedulePlanner.Request r = terms(t);
        ReschedulePreview p = preview(l, r, t);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO lending.loan_reschedule (tenant_id, reschedule_id, idempotency_key, loan_id, kind, business_date,
                    from_version, instalments, grace_months, prepayment, prepayment_mode, outstanding, reason, requested_by)
                VALUES (tenancy.current_tenant(), :id, :k, :loan, :kind, :d, :v, :n, :g, :amount, :mode, :out, :reason, :actor)
                """).param("id", id).param("k", idempotencyKey.strip()).param("loan", l.id()).param("kind", r.kind().name())
            .param("d", businessDates.current()).param("v", latestVersion(l.id()))
            .param("n", r.kind() == Kind.RESTRUCTURE ? r.instalments() : null).param("g", r.graceMonths())
            .param("amount", r.prepayment()).param("mode", r.mode() == null ? null : r.mode().name())
            .param("out", new BigDecimal(p.outstandingPrincipal())).param("reason", reason.strip()).param("actor", actor).update();
        return view(id);
    }

    /** A second person approves (writing the new schedule) or rejects. */
    @Transactional
    public RescheduleView decide(UUID tenantId, UUID rescheduleId, boolean approve, String reason, String actor) {
        session.bind(tenantId);
        record R(UUID loanId, String loanNumber, String status, String requestedBy, int fromVersion, LocalDate date, String kind,
                 Integer instalments, int grace, BigDecimal amount, String mode) { }
        R r = jdbc.sql("""
                SELECT r.loan_id, l.loan_number, r.status, r.requested_by, r.from_version, r.business_date, r.kind, r.instalments,
                       r.grace_months, r.prepayment, r.prepayment_mode
                  FROM lending.loan_reschedule r JOIN lending.loan l ON l.tenant_id = r.tenant_id AND l.loan_id = r.loan_id
                 WHERE r.reschedule_id = :id FOR UPDATE OF r
                """).param("id", rescheduleId)
            .query((rs, i) -> new R(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                    rs.getObject(6, LocalDate.class), rs.getString(7), (Integer) rs.getObject(8), rs.getInt(9), rs.getBigDecimal(10),
                    rs.getString(11)))
            .optional().orElseThrow(() -> new NotFoundException("reschedule not found"));
        if (!"REQUESTED".equals(r.status())) {
            throw new ConflictException("reschedule is already " + r.status());
        }
        if (r.requestedBy().equals(actor)) {
            throw new AccessDeniedException("the person who requested the reschedule cannot decide it");
        }
        String why = reason == null || reason.isBlank() ? null : reason.strip();
        if (why != null && why.length() > 300) {
            throw new IllegalArgumentException("reason is at most 300 characters");
        }
        if (!approve) {
            if (why == null || why.length() < 3) {
                throw new IllegalArgumentException("a rejection needs a reason");
            }
            jdbc.sql("""
                    UPDATE lending.loan_reschedule SET status = 'REJECTED', decided_by = :a, decided_at = clock_timestamp(),
                           decision_reason = :w WHERE reschedule_id = :id
                    """).param("a", actor).param("w", why).param("id", rescheduleId).update();
            return view(rescheduleId);
        }
        if (!r.date().equals(businessDates.current())) {
            throw new ConflictException("requested on " + r.date() + "; the loan has moved on since. Reject this and request again");
        }
        LoanRow l = loan(r.loanNumber(), true);
        if (latestVersion(l.id()) != r.fromVersion()) {
            throw new ConflictException("the loan's schedule changed after the request. Reject this and request again");
        }
        ReschedulePlanner.Request terms = new ReschedulePlanner.Request(Kind.valueOf(r.kind()), r.instalments(), r.grace(), r.amount(),
                r.mode() == null ? null : PrepaymentMode.valueOf(r.mode()));
        Plan plan = plan(l, terms);
        int version = r.fromVersion() + 1;
        jdbc.sql("""
                UPDATE lending.loan_reschedule SET status = 'APPROVED', decided_by = :a, decided_at = clock_timestamp(),
                       decision_reason = :w, to_version = :v WHERE reschedule_id = :id
                """).param("a", actor).param("w", why).param("v", version).param("id", rescheduleId).update();
        for (Row row : plan.rows()) {
            jdbc.sql("""
                    INSERT INTO lending.schedule_installment (tenant_id, loan_id, schedule_version, installment_no, due_date,
                        principal, interest)
                    VALUES (tenancy.current_tenant(), :loan, :v, :no, :due, :p, :i)
                    """).param("loan", l.id()).param("v", version).param("no", row.no()).param("due", row.due())
                .param("p", row.principal()).param("i", row.interest()).update();
        }
        notifications.loanRescheduled(l.id(), plan.firstDue(), plan.instalments(), plan.instalmentAmount(), rescheduleId, actor);
        return view(rescheduleId);
    }

    @Transactional(readOnly = true)
    public List<RescheduleView> forLoan(UUID tenantId, String loanNumber) {
        session.bind(tenantId);
        LoanRow l = loan(loanNumber, false);
        return jdbc.sql(VIEW + " WHERE r.loan_id = :l ORDER BY r.requested_at DESC").param("l", l.id())
            .query((rs, i) -> view(rs, false)).list();
    }

    @Transactional(readOnly = true)
    public List<RescheduleView> pending(UUID tenantId) {
        session.bind(tenantId);
        return jdbc.sql(VIEW + " WHERE r.status = 'REQUESTED' ORDER BY r.requested_at").query((rs, i) -> view(rs, false)).list();
    }

    // ------------------------------------------------------------------

    private ReschedulePreview preview(LoanRow l, ReschedulePlanner.Request r, RescheduleTerms t) {
        if (!"ACTIVE".equals(l.status())) {
            throw new ConflictException("loan " + l.number() + " is " + l.status() + "; only active loans are rescheduled");
        }
        BigDecimal credit = repayments.credit(l.id());
        if (r.kind() == Kind.PREPAYMENT && credit.compareTo(r.prepayment()) < 0) {
            throw new ConflictException("the member has paid " + m(credit) + " ahead; a prepayment of " + m(r.prepayment())
                    + " needs that much credit on the loan first");
        }
        List<Current> now = current(l.id());
        Plan plan = plan(l, r, now);
        LocalDate today = businessDates.current();
        List<ScheduleRow> before = now.stream().filter(c -> c.due().isAfter(today)
                || c.principal().add(c.interest()).compareTo(c.paidPrincipal().add(c.paidInterest())) > 0)
            .map(c -> row(c.no(), c.due(), c.principal(), c.interest())).toList();
        List<ScheduleRow> after = plan.rows().stream().filter(x -> x.no() >= plan.firstChanged())
            .map(x -> row(x.no(), x.due(), x.principal(), x.interest())).toList();
        return new ReschedulePreview(l.number(), r.kind().name(), today, m(plan.outstanding()), m(plan.stillDue()), m(credit),
                plan.instalments(), m(plan.instalmentAmount()), plan.firstDue(), m(plan.interestBefore()), m(plan.interestAfter()),
                plan.firstChanged(), before, after);
    }

    private Plan plan(LoanRow l, ReschedulePlanner.Request r) {
        if (r.kind() == Kind.PREPAYMENT && repayments.credit(l.id()).compareTo(r.prepayment()) < 0) {
            throw new ConflictException("the member's credit on the loan no longer covers the prepayment");
        }
        return plan(l, r, current(l.id()));
    }

    private Plan plan(LoanRow l, ReschedulePlanner.Request r, List<Current> now) {
        try {
            return ReschedulePlanner.plan(l.product(), now, businessDates.current(), r);
        } catch (IllegalStateException e) {
            throw new ConflictException(e.getMessage());
        }
    }

    private static ReschedulePlanner.Request terms(RescheduleTerms t) {
        if (t == null || t.kind() == null) {
            throw new IllegalArgumentException("kind (RESTRUCTURE or PREPAYMENT) is required");
        }
        Kind kind;
        try {
            kind = Kind.valueOf(t.kind().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("kind must be RESTRUCTURE or PREPAYMENT");
        }
        if (kind == Kind.RESTRUCTURE) {
            return new ReschedulePlanner.Request(kind, t.instalments(), t.graceMonths() == null ? 0 : t.graceMonths(), null, null);
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(t.amount() == null ? "" : t.amount().strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("amount must be a number like 3000.00");
        }
        PrepaymentMode mode;
        try {
            mode = PrepaymentMode.valueOf(t.mode() == null ? "" : t.mode().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("mode must be SHORTEN_TERM or REDUCE_INSTALMENT");
        }
        return new ReschedulePlanner.Request(kind, null, 0, amount, mode);
    }

    private List<Current> current(UUID loanId) {
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
                       COALESCE(p.principal, 0), COALESCE(p.interest, 0),
                       COALESCE((SELECT sum(amount) FROM lending.loan_accrual a WHERE a.loan_id = :loan AND a.installment_no = s.installment_no), 0),
                       COALESCE((SELECT sum(amount) FROM lending.penalty_charge c WHERE c.loan_id = :loan AND c.installment_no = s.installment_no), 0)
                         - COALESCE(p.penalty, 0)
                  FROM schedule s LEFT JOIN paid p ON p.installment_no = s.installment_no
                 ORDER BY s.due_date, s.installment_no
                """).param("loan", loanId)
            .query((rs, i) -> new Current(rs.getInt(1), rs.getObject(2, LocalDate.class), two(rs.getBigDecimal(3)), two(rs.getBigDecimal(4)),
                    two(rs.getBigDecimal(5)), two(rs.getBigDecimal(6)), two(rs.getBigDecimal(7)), two(rs.getBigDecimal(8)).max(BigDecimal.ZERO)))
            .list();
    }

    private int latestVersion(UUID loanId) {
        return jdbc.sql("SELECT max(schedule_version) FROM lending.schedule_installment WHERE loan_id = :l").param("l", loanId)
            .query(Integer.class).single();
    }

    private LoanRow loan(String loanNumber, boolean lock) {
        return jdbc.sql("""
                SELECT l.loan_id, l.loan_number, l.status, l.currency, v.* FROM lending.loan l
                  JOIN lending.loan_product_version v ON v.tenant_id = l.tenant_id AND v.version_id = l.product_version_id
                 WHERE l.loan_number = :n
                """ + (lock ? " FOR UPDATE OF l" : ""))
            .param("n", loanNumber == null ? "" : loanNumber.strip().toUpperCase(Locale.ROOT))
            .query((rs, i) -> new LoanRow(rs.getObject("loan_id", UUID.class), rs.getString("loan_number"), rs.getString("status"),
                    LoanRepaymentService.product(rs)))
            .optional().orElseThrow(() -> new NotFoundException("loan " + loanNumber + " not found"));
    }

    private RescheduleView view(UUID id) {
        return jdbc.sql(VIEW + " WHERE r.reschedule_id = :id").param("id", id).query((rs, i) -> view(rs, false)).single();
    }

    private static RescheduleView view(java.sql.ResultSet rs, boolean replayed) throws java.sql.SQLException {
        BigDecimal prepayment = rs.getBigDecimal(8);
        return new RescheduleView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getObject(5, LocalDate.class), (Integer) rs.getObject(6), rs.getInt(7), prepayment == null ? null : m(prepayment),
                rs.getString(9), m(rs.getBigDecimal(10)), rs.getString(11), rs.getInt(12), (Integer) rs.getObject(13), rs.getString(14),
                rs.getObject(15, OffsetDateTime.class), rs.getString(16), rs.getObject(17, OffsetDateTime.class), rs.getString(18), replayed);
    }

    private static ScheduleRow row(int no, LocalDate due, BigDecimal principal, BigDecimal interest) {
        return new ScheduleRow(no, due, m(principal), m(interest), m(principal.add(interest)));
    }

    private static BigDecimal two(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_EVEN);
    }

    private static String m(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }
}
