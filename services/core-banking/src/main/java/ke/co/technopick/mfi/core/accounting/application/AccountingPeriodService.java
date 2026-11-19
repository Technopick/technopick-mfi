package ke.co.technopick.mfi.core.accounting.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import ke.co.technopick.mfi.core.common.ConflictException;
import ke.co.technopick.mfi.core.common.NotFoundException;
import ke.co.technopick.mfi.core.messaging.persistence.Outbox;
import ke.co.technopick.mfi.core.tenancy.persistence.TenantSession;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accounting periods (ADR-0025): calendar months, opened one after another and closed by finance
 * once end of day has closed the month's last day and no manual journal is pending in it. A
 * closed month accepts no postings and cannot be reopened in the application.
 */
@Service
public class AccountingPeriodService {

    public record PeriodView(String periodId, LocalDate startDate, LocalDate endDate, String status, String closedBy,
                             OffsetDateTime closedAt, String closeNote, int pendingJournals, Integer reconciliationDifferences,
                             boolean lastDayClosed) { }

    private final JdbcClient jdbc;
    private final TenantSession session;
    private final Outbox outbox;

    public AccountingPeriodService(JdbcClient jdbc, TenantSession session, Outbox outbox) {
        this.jdbc = jdbc;
        this.session = session;
        this.outbox = outbox;
    }

    @Transactional(readOnly = true)
    public List<PeriodView> periods(UUID tenantId) {
        session.bind(tenantId);
        return jdbc.sql("SELECT period_id FROM ledger.accounting_period ORDER BY start_date DESC LIMIT 36")
            .query(String.class).list().stream().map(this::view).toList();
    }

    /** Opens the calendar month after the latest period. */
    @Transactional
    public PeriodView openNext(UUID tenantId, String actor) {
        session.bind(tenantId);
        LocalDate latest = jdbc.sql("SELECT max(end_date) FROM ledger.accounting_period").query(LocalDate.class).optional()
            .orElseThrow(() -> new IllegalStateException("no accounting period has been set up for this institution"));
        LocalDate start = latest.plusDays(1);
        LocalDate today = jdbc.sql("SELECT current_date_ FROM tenancy.business_date").query(LocalDate.class).single();
        if (start.isAfter(today.plusMonths(2).withDayOfMonth(1))) {
            throw new ConflictException("periods are opened at most two months ahead");
        }
        String id = start.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        jdbc.sql("INSERT INTO ledger.accounting_period (tenant_id, period_id, start_date, end_date) VALUES (:t, :id, :s, :e)")
            .param("t", tenantId).param("id", id).param("s", start).param("e", start.withDayOfMonth(start.lengthOfMonth()))
            .update();
        outbox.record(tenantId, "AccountingPeriodOpened", "accounting_period", tenantId + ":" + id,
                "{\"periodId\":\"%s\",\"openedBy\":\"%s\"}".formatted(id, actor), "period:" + id);
        return view(id);
    }

    @Transactional
    public PeriodView close(UUID tenantId, String periodId, String note, String actor) {
        session.bind(tenantId);
        PeriodView p = view(periodId);
        if (!"OPEN".equals(p.status())) {
            throw new ConflictException("period " + periodId + " is already " + p.status());
        }
        if (!p.lastDayClosed()) {
            throw new IllegalArgumentException("end of day has not closed " + p.endDate() + " yet");
        }
        if (p.pendingJournals() > 0) {
            throw new IllegalArgumentException(p.pendingJournals() + " manual journal(s) dated in " + periodId
                    + " are still pending; post or reject them first");
        }
        if (p.reconciliationDifferences() != null && p.reconciliationDifferences() > 0
                && (note == null || note.strip().length() < 10)) {
            throw new IllegalArgumentException("the reconciliation of " + p.endDate() + " recorded "
                    + p.reconciliationDifferences() + " difference(s); explain them in the closing note");
        }
        jdbc.sql("UPDATE ledger.accounting_period SET status = 'CLOSED', closed_by = :a, close_note = :n WHERE period_id = :id")
            .param("a", actor).param("n", note == null || note.isBlank() ? null : note.strip()).param("id", periodId).update();
        outbox.record(tenantId, "AccountingPeriodClosed", "accounting_period", tenantId + ":" + periodId,
                "{\"periodId\":\"%s\",\"closedBy\":\"%s\"}".formatted(periodId, actor), "period:" + periodId);
        return view(periodId);
    }

    private PeriodView view(String periodId) {
        return jdbc.sql("""
                SELECT p.period_id, p.start_date, p.end_date, p.status, p.closed_by, p.closed_at, p.close_note,
                       (SELECT count(*) FROM ledger.manual_journal j
                         WHERE j.tenant_id = p.tenant_id AND j.status = 'PENDING'
                           AND j.business_date BETWEEN p.start_date AND p.end_date) AS pending,
                       (SELECT count(*) FILTER (WHERE r.difference <> 0) FROM ledger.reconciliation_result r
                         WHERE r.tenant_id = p.tenant_id AND r.business_date = p.end_date
                        HAVING count(*) > 0) AS differences,
                       (SELECT current_date_ FROM tenancy.business_date b WHERE b.tenant_id = p.tenant_id) > p.end_date AS day_closed
                  FROM ledger.accounting_period p WHERE p.period_id = :id
                """)
            .param("id", periodId)
            .query((rs, i) -> new PeriodView(rs.getString(1), rs.getObject(2, LocalDate.class), rs.getObject(3, LocalDate.class),
                    rs.getString(4), rs.getString(5), rs.getObject(6, OffsetDateTime.class), rs.getString(7), rs.getInt(8),
                    rs.getObject(9) == null ? null : rs.getInt(9), rs.getBoolean(10)))
            .optional().orElseThrow(() -> new NotFoundException("accounting period " + periodId + " not found"));
    }
}
