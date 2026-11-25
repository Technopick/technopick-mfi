package ke.co.technopick.mfi.core.payments.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import ke.co.technopick.mfi.core.common.ConflictException;
import ke.co.technopick.mfi.core.common.NotFoundException;
import ke.co.technopick.mfi.core.tenancy.persistence.TenantSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asks M-Pesa (Transaction Status API) what happened to B2C disbursements whose outcome is unknown
 * (ADR-0039): the send outcome never became known, M-Pesa's queue timed out, or the result is
 * overdue. Only a positive answer - status Completed with a receipt and amount - acts, and only
 * through the normal result path, which checks the amount and activates the loan. Anything else
 * is kept as evidence for the operations close-out (ADR-0013); a disbursement is never failed
 * from a query answer.
 */
@Service
public class B2cStatusQueryService {

    private static final Logger LOG = LoggerFactory.getLogger(B2cStatusQueryService.class);

    public record StatusQueryDue(UUID queryId, UUID disbursementId, String shortCode, String transactionId,
                                 String conversationId) { }

    public record StatusQueryView(UUID queryId, UUID disbursementId, String reason, String status, String requestedBy,
                                  OffsetDateTime requestedAt, OffsetDateTime sentAt, OffsetDateTime answeredAt,
                                  String resultCode, String resultDesc, String reportedStatus, String reportedReceipt,
                                  String reportedAmount, String outcome) { }

    public record StatusAnswer(String resultCode, String resultDesc, String transactionStatus, String receiptNo,
                               BigDecimal amount) { }

    private final JdbcClient jdbc;
    private final TenantSession session;
    private final B2cDisbursementService disbursements;
    private final Duration resultOverdue;
    private final Duration retryInterval;
    private final Duration answerWait;
    private final int maxAutomatic;

    public B2cStatusQueryService(JdbcClient jdbc, TenantSession session, B2cDisbursementService disbursements,
                                 @Value("${mfi.b2c.status.result-overdue:PT10M}") Duration resultOverdue,
                                 @Value("${mfi.b2c.status.retry-interval:PT30M}") Duration retryInterval,
                                 @Value("${mfi.b2c.status.answer-wait:PT15M}") Duration answerWait,
                                 @Value("${mfi.b2c.status.max-automatic:5}") int maxAutomatic) {
        this.jdbc = jdbc;
        this.session = session;
        this.disbursements = disbursements;
        this.resultOverdue = resultOverdue;
        this.retryInterval = retryInterval;
        this.answerWait = answerWait;
        this.maxAutomatic = maxAutomatic;
    }

    /**
     * For the integration runtime: closes queries left unanswered, opens automatic queries for
     * disbursements that need one, and returns every query waiting to be sent.
     */
    @Transactional
    public List<StatusQueryDue> due(UUID tenantId, int limit) {
        session.bind(tenantId);
        jdbc.sql("""
                UPDATE payments.b2c_status_query SET status = 'NO_ANSWER', result_desc = 'no answer within the wait time'
                 WHERE status IN ('PENDING', 'SENT')
                   AND COALESCE(sent_at, requested_at) <= clock_timestamp() - make_interval(secs => :wait)
                """).param("wait", seconds(answerWait)).update();
        jdbc.sql("""
                INSERT INTO payments.b2c_status_query (tenant_id, query_id, disbursement_id, reason, requested_by)
                SELECT d.tenant_id, gen_random_uuid(), d.disbursement_id,
                       CASE WHEN d.attention IN ('SEND_OUTCOME_UNKNOWN', 'TIMEOUT_UNKNOWN') THEN d.attention
                            ELSE 'RESULT_OVERDUE' END, 'system'
                  FROM payments.b2c_disbursement d
                 WHERE d.status IN ('DISPATCHED', 'SUBMITTED')
                   AND (d.attention IN ('SEND_OUTCOME_UNKNOWN', 'TIMEOUT_UNKNOWN')
                        OR (d.status = 'SUBMITTED' AND d.attention IS NULL
                            AND d.updated_at <= clock_timestamp() - make_interval(secs => :overdue)))
                   AND NOT EXISTS (SELECT 1 FROM payments.b2c_status_query q
                                    WHERE q.tenant_id = d.tenant_id AND q.disbursement_id = d.disbursement_id
                                      AND (q.status IN ('PENDING', 'SENT')
                                           OR q.requested_at > clock_timestamp() - make_interval(secs => :interval)))
                   AND (SELECT count(*) FROM payments.b2c_status_query q
                         WHERE q.tenant_id = d.tenant_id AND q.disbursement_id = d.disbursement_id AND q.reason <> 'MANUAL') < :max
                 ORDER BY d.updated_at
                 LIMIT :limit
                """)
            .param("overdue", seconds(resultOverdue)).param("interval", seconds(retryInterval))
            .param("max", maxAutomatic).param("limit", limit).update();
        return jdbc.sql("""
                SELECT q.query_id, q.disbursement_id, d.short_code, d.transaction_id, d.conversation_id
                  FROM payments.b2c_status_query q
                  JOIN payments.b2c_disbursement d ON d.tenant_id = q.tenant_id AND d.disbursement_id = q.disbursement_id
                 WHERE q.status = 'PENDING'
                 ORDER BY q.requested_at
                 LIMIT :limit
                """).param("limit", limit)
            .query((rs, n) -> new StatusQueryDue(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                    rs.getString(4), rs.getString(5)))
            .list();
    }

    /** A person asks M-Pesa now, whatever the automatic schedule says. */
    @Transactional
    public StatusQueryView request(UUID tenantId, UUID disbursementId, String actor) {
        session.bind(tenantId);
        String status = jdbc.sql("SELECT status FROM payments.b2c_disbursement WHERE disbursement_id = :id FOR UPDATE")
            .param("id", disbursementId).query(String.class).optional()
            .orElseThrow(() -> new NotFoundException("disbursement not found"));
        if (!"DISPATCHED".equals(status) && !"SUBMITTED".equals(status)) {
            throw new ConflictException("the disbursement is " + status + "; its outcome is already known");
        }
        boolean open = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM payments.b2c_status_query
                                WHERE disbursement_id = :id AND status IN ('PENDING', 'SENT'))
                """).param("id", disbursementId).query(Boolean.class).single();
        if (open) {
            throw new ConflictException("a status query for this disbursement is already waiting for M-Pesa");
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO payments.b2c_status_query (tenant_id, query_id, disbursement_id, reason, requested_by)
                VALUES (tenancy.current_tenant(), :q, :d, 'MANUAL', :actor)
                """).param("q", id).param("d", disbursementId).param("actor", actor).update();
        return view(id);
    }

    @Transactional
    public StatusQueryView sent(UUID tenantId, UUID queryId) {
        session.bind(tenantId);
        move(queryId, "PENDING", "status = 'SENT', sent_at = clock_timestamp()", null);
        return view(queryId);
    }

    @Transactional
    public StatusQueryView sendFailed(UUID tenantId, UUID queryId, String description) {
        session.bind(tenantId);
        move(queryId, "PENDING", "status = 'SEND_FAILED', result_desc = :desc", truncate(description, 200));
        return view(queryId);
    }

    /** M-Pesa's queue timed out on the query itself; the next pass asks again. */
    @Transactional
    public StatusQueryView noAnswer(UUID tenantId, UUID queryId) {
        session.bind(tenantId);
        StatusQueryView q = lock(queryId);
        if ("PENDING".equals(q.status()) || "SENT".equals(q.status())) {
            jdbc.sql("UPDATE payments.b2c_status_query SET status = 'NO_ANSWER', result_desc = 'M-Pesa queue timeout' "
                    + "WHERE query_id = :id").param("id", queryId).update();
        }
        return view(queryId);
    }

    /** Records M-Pesa's answer once; a confirmed payment goes through the disbursement's result path. */
    @Transactional
    public StatusQueryView answer(UUID tenantId, UUID queryId, StatusAnswer a) {
        session.bind(tenantId);
        StatusQueryView q = lock(queryId);
        if ("ANSWERED".equals(q.status())) {
            return q;
        }
        if (!"SENT".equals(q.status()) && !"PENDING".equals(q.status())) {
            throw new ConflictException("query is " + q.status());
        }
        if ("PENDING".equals(q.status())) {
            // the answer overtook the "sent" report
            jdbc.sql("UPDATE payments.b2c_status_query SET status = 'SENT', sent_at = clock_timestamp() WHERE query_id = :id")
                .param("id", queryId).update();
        }
        String disbursementStatus = jdbc.sql("SELECT status FROM payments.b2c_disbursement WHERE disbursement_id = :id FOR UPDATE")
            .param("id", q.disbursementId()).query(String.class).single();
        boolean confirmed = "0".equals(a.resultCode()) && "Completed".equalsIgnoreCase(a.transactionStatus())
                && a.receiptNo() != null && a.amount() != null;
        String outcome;
        if (!"DISPATCHED".equals(disbursementStatus) && !"SUBMITTED".equals(disbursementStatus)) {
            outcome = "ALREADY_FINAL";
        } else if (confirmed) {
            B2cDisbursementService.DisbursementView after = disbursements.result(tenantId, q.disbursementId(),
                    new B2cDisbursementService.Result(null, "0", "Confirmed by M-Pesa transaction status query",
                            a.receiptNo(), B2cDisbursementService.amount(a.amount().setScale(2, RoundingMode.UNNECESSARY)
                                    .toPlainString(), Currency.getInstance("KES"))),
                    "b2c-status:" + queryId);
            outcome = after.status() == B2cDisbursementService.DisbursementStatus.COMPLETED ? "COMPLETED_DISBURSEMENT"
                    : "AMOUNT_MISMATCH";
            LOG.info("B2C disbursement {} {} by status query {}", q.disbursementId(), outcome, queryId);
        } else {
            outcome = "NOT_CONFIRMED";
        }
        jdbc.sql("""
                UPDATE payments.b2c_status_query
                   SET status = 'ANSWERED', answered_at = clock_timestamp(), result_code = :code, result_desc = :desc,
                       reported_status = :rs, reported_receipt = :receipt, reported_amount = :amount, outcome = :outcome
                 WHERE query_id = :id
                """)
            .param("code", truncate(a.resultCode(), 20)).param("desc", truncate(a.resultDesc(), 200))
            .param("rs", truncate(a.transactionStatus(), 40)).param("receipt", a.receiptNo()).param("amount", a.amount())
            .param("outcome", outcome).param("id", queryId).update();
        return view(queryId);
    }

    @Transactional(readOnly = true)
    public List<StatusQueryView> forDisbursement(UUID tenantId, UUID disbursementId) {
        session.bind(tenantId);
        return jdbc.sql(SELECT + " WHERE disbursement_id = :id ORDER BY requested_at DESC").param("id", disbursementId)
            .query(B2cStatusQueryService::row).list();
    }

    private void move(UUID queryId, String from, String set, String desc) {
        StatusQueryView q = lock(queryId);
        if (!from.equals(q.status())) {
            throw new ConflictException("query is " + q.status());
        }
        var stmt = jdbc.sql("UPDATE payments.b2c_status_query SET " + set + " WHERE query_id = :id").param("id", queryId);
        if (desc != null || set.contains(":desc")) {
            stmt = stmt.param("desc", desc);
        }
        stmt.update();
    }

    private StatusQueryView lock(UUID queryId) {
        return jdbc.sql(SELECT + " WHERE query_id = :id FOR UPDATE").param("id", queryId)
            .query(B2cStatusQueryService::row).optional()
            .orElseThrow(() -> new NotFoundException("status query not found"));
    }

    private StatusQueryView view(UUID queryId) {
        return jdbc.sql(SELECT + " WHERE query_id = :id").param("id", queryId).query(B2cStatusQueryService::row).single();
    }

    private static final String SELECT = "SELECT * FROM payments.b2c_status_query";

    private static StatusQueryView row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        BigDecimal amount = rs.getBigDecimal("reported_amount");
        return new StatusQueryView(rs.getObject("query_id", UUID.class), rs.getObject("disbursement_id", UUID.class),
                rs.getString("reason"), rs.getString("status"), rs.getString("requested_by"),
                rs.getObject("requested_at", OffsetDateTime.class), rs.getObject("sent_at", OffsetDateTime.class),
                rs.getObject("answered_at", OffsetDateTime.class), rs.getString("result_code"), rs.getString("result_desc"),
                rs.getString("reported_status"), rs.getString("reported_receipt"),
                amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP).toPlainString(), rs.getString("outcome"));
    }

    private static double seconds(Duration d) {
        return d.toMillis() / 1000.0;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
