package ke.co.technopick.mfi.core.payments.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import ke.co.technopick.mfi.canonical.Money;
import ke.co.technopick.mfi.core.common.ConflictException;
import ke.co.technopick.mfi.core.common.NotFoundException;
import ke.co.technopick.mfi.core.lending.application.LoanDisbursementService;
import ke.co.technopick.mfi.core.messaging.persistence.Outbox;
import ke.co.technopick.mfi.core.tenancy.persistence.BusinessDates;
import ke.co.technopick.mfi.core.tenancy.persistence.TenantSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loan disbursement by M-Pesa B2C (ADR-0012). Money leaves the institution here, so the rules are
 * stricter than for inbound payments:
 * <ul>
 *   <li>one person requests, a different person approves; only then is an event emitted;</li>
 *   <li>the integration runtime claims an approved disbursement once ({@link #dispatch});</li>
 *   <li>FAILED only on a definitive answer (provider refusal or a failed result); timeouts and
 *       unknown send outcomes are flagged for a person, never retried automatically;</li>
 *   <li>the loan is posted and activated only on a successful result for the expected amount;
 *       a different amount, or success after a recorded failure, raises an alarm instead.</li>
 * </ul>
 */
@Service
public class B2cDisbursementService {

    private static final Logger LOG = LoggerFactory.getLogger(B2cDisbursementService.class);

    public enum Status { REQUESTED, APPROVED, REJECTED, DISPATCHED, SUBMITTED, COMPLETED, FAILED }

    public record Created(UUID disbursementId, Status status, String amount, String fee, boolean replayed) { }

    public record View(UUID disbursementId, String loanNumber, String shortCode, String amount, String currency,
                       String msisdnMasked, Status status, String requestedBy, String decidedBy, String conversationId,
                       String transactionId, String resultCode, String resultDesc, UUID journalEntryId,
                       String attention, OffsetDateTime requestedAt) { }

    public record Dispatch(UUID disbursementId, String shortCode, String msisdn, String amount, String remarks,
                           String occasion) { }

    public record Result(String conversationId, String resultCode, String resultDesc, String transactionId,
                         Money amount) { }

    private record Row(UUID id, String key, UUID loanId, String loanNumber, String shortCode, BigDecimal amount,
                       String currency, String msisdn, String msisdnMasked, Status status, String requestedBy,
                       String conversationId, String transactionId, String glClearing) { }

    private final JdbcClient jdbc;
    private final TenantSession session;
    private final BusinessDates businessDates;
    private final LoanDisbursementService loans;
    private final Outbox outbox;

    public B2cDisbursementService(JdbcClient jdbc, TenantSession session, BusinessDates businessDates,
                                  LoanDisbursementService loans, Outbox outbox) {
        this.jdbc = jdbc;
        this.session = session;
        this.businessDates = businessDates;
        this.loans = loans;
        this.outbox = outbox;
    }

    @Transactional
    public Created request(UUID tenantId, String idempotencyKey, String loanNumber, String phone, String shortCode,
                           String actor) {
        session.bind(tenantId);
        String msisdn = MpesaStkService.normaliseMsisdn(phone);
        String number = loanNumber.strip().toUpperCase(Locale.ROOT);
        LoanDisbursementService.Disbursable loan = loans.disbursable(number)
                .orElseThrow(() -> new IllegalArgumentException("loan " + number + " not found"));
        Optional<Row> existing = jdbc.sql(SELECT + " WHERE d.idempotency_key = :key").param("key", idempotencyKey)
            .query(B2cDisbursementService::row).optional();
        if (existing.isPresent()) {
            Row r = existing.get();
            boolean same = r.loanId().equals(loan.loanId())
                    && (r.msisdn() != null ? r.msisdn().equals(msisdn) : r.msisdnMasked().equals(MpesaStkService.mask(msisdn)));
            if (!same) {
                throw new ConflictException("idempotency key already used for a different disbursement");
            }
            return new Created(r.id(), r.status(), plain(r.amount()), loan.fee().amount().toPlainString(), true);
        }
        if (!"APPROVED".equals(loan.status())) {
            throw new IllegalArgumentException("loan " + number + " is " + loan.status() + "; only approved loans are disbursed");
        }
        BigDecimal net = loan.netAmount().amount();
        if (net.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("net disbursement " + net.toPlainString()
                    + " is not whole shillings; M-Pesa B2C pays whole shillings (check the product's fee rate)");
        }
        String code = shortCode != null && !shortCode.isBlank() ? shortCode : singleAccount();
        BigDecimal max = jdbc.sql("SELECT max_amount FROM payments.mpesa_b2c_account WHERE short_code = :c AND active")
            .param("c", code).query(BigDecimal.class).optional()
            .orElseThrow(() -> new IllegalArgumentException("B2C short code " + code + " not configured"));
        if (net.compareTo(max) > 0) {
            throw new IllegalArgumentException("net disbursement exceeds the B2C limit of " + plain(max));
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO payments.b2c_disbursement (tenant_id, disbursement_id, idempotency_key, loan_id, short_code, amount,
                    currency, msisdn, msisdn_masked, requested_by)
                VALUES (:tenant, :id, :key, :loan, :code, :amount, :ccy, :msisdn, :masked, :actor)
                """)
            .param("tenant", tenantId).param("id", id).param("key", idempotencyKey).param("loan", loan.loanId())
            .param("code", code).param("amount", net).param("ccy", loan.netAmount().currency().getCurrencyCode())
            .param("msisdn", msisdn).param("masked", MpesaStkService.mask(msisdn)).param("actor", actor)
            .update();
        return new Created(id, Status.REQUESTED, plain(net), loan.fee().amount().toPlainString(), false);
    }

    /** Checker decision. The requester can never decide their own request (also enforced by the database). */
    @Transactional
    public View decide(UUID tenantId, UUID id, boolean approve, String actor) {
        session.bind(tenantId);
        Row r = lock(id);
        if (r.status() != Status.REQUESTED) {
            throw new ConflictException("disbursement is already " + r.status());
        }
        if (r.requestedBy().equals(actor)) {
            throw new AccessDeniedException("the requester cannot approve or reject their own disbursement");
        }
        if (approve) {
            jdbc.sql("UPDATE payments.b2c_disbursement SET status = 'APPROVED', decided_by = :actor WHERE disbursement_id = :id")
                .param("actor", actor).param("id", id).update();
            // Carries no personal data; the integration runtime claims the details on dispatch.
            outbox.record(tenantId, "MpesaB2cDisbursementApproved", "b2c_disbursement", id.toString(),
                    "{\"disbursementId\":\"%s\",\"shortCode\":\"%s\"}".formatted(id, r.shortCode()), "b2c:" + id);
        } else {
            jdbc.sql("""
                    UPDATE payments.b2c_disbursement SET status = 'REJECTED', decided_by = :actor, msisdn = NULL
                     WHERE disbursement_id = :id
                    """)
                .param("actor", actor).param("id", id).update();
        }
        return view(tenantId, id);
    }

    @Transactional(readOnly = true)
    public View view(UUID tenantId, UUID id) {
        session.bind(tenantId);
        return jdbc.sql("""
                SELECT d.disbursement_id, l.loan_number, d.short_code, d.amount, d.currency, d.msisdn_masked, d.status,
                       d.requested_by, d.decided_by, d.conversation_id, d.transaction_id, d.result_code, d.result_desc,
                       d.journal_entry_id, d.attention, d.requested_at
                  FROM payments.b2c_disbursement d
                  JOIN lending.loan l ON l.tenant_id = d.tenant_id AND l.loan_id = d.loan_id
                 WHERE d.disbursement_id = :id
                """)
            .param("id", id)
            .query((rs, i) -> new View(rs.getObject("disbursement_id", UUID.class), rs.getString("loan_number"),
                    rs.getString("short_code"), plain(rs.getBigDecimal("amount")), rs.getString("currency"),
                    rs.getString("msisdn_masked"), Status.valueOf(rs.getString("status")), rs.getString("requested_by"),
                    rs.getString("decided_by"), rs.getString("conversation_id"), rs.getString("transaction_id"),
                    rs.getString("result_code"), rs.getString("result_desc"), rs.getObject("journal_entry_id", UUID.class),
                    rs.getString("attention"), rs.getObject("requested_at", OffsetDateTime.class)))
            .optional()
            .orElseThrow(() -> new NotFoundException("disbursement not found"));
    }

    @Transactional(readOnly = true)
    public List<View> attention(UUID tenantId) {
        session.bind(tenantId);
        return jdbc.sql("SELECT disbursement_id FROM payments.b2c_disbursement WHERE attention IS NOT NULL ORDER BY requested_at")
            .query(UUID.class).list().stream().map(id -> view(tenantId, id)).toList();
    }

    /** Claims an approved disbursement for sending: at most once. */
    @Transactional
    public Dispatch dispatch(UUID tenantId, UUID id) {
        session.bind(tenantId);
        Row r = lock(id);
        if (r.status() != Status.APPROVED) {
            throw new ConflictException("disbursement is " + r.status() + ", not APPROVED");
        }
        jdbc.sql("UPDATE payments.b2c_disbursement SET status = 'DISPATCHED' WHERE disbursement_id = :id")
            .param("id", id).update();
        return new Dispatch(id, r.shortCode(), r.msisdn(), r.amount().setScale(0, RoundingMode.UNNECESSARY).toPlainString(),
                "Loan " + r.loanNumber(), r.loanNumber());
    }

    @Transactional
    public View submitted(UUID tenantId, UUID id, String conversationId) {
        session.bind(tenantId);
        Row r = lock(id);
        checkConversation(r, conversationId);
        if (r.status() == Status.DISPATCHED) {
            jdbc.sql("UPDATE payments.b2c_disbursement SET status = 'SUBMITTED', conversation_id = :c WHERE disbursement_id = :id")
                .param("c", conversationId).param("id", id).update();
        } else if (r.status() == Status.REQUESTED || r.status() == Status.APPROVED || r.status() == Status.REJECTED) {
            throw new ConflictException("disbursement was never dispatched");
        }
        return view(tenantId, id);
    }

    /** The provider definitively refused the request: no money left. */
    @Transactional
    public View sendFailed(UUID tenantId, UUID id, String code, String description) {
        session.bind(tenantId);
        Row r = lock(id);
        if (r.status() == Status.DISPATCHED) {
            jdbc.sql("""
                    UPDATE payments.b2c_disbursement SET status = 'FAILED', result_code = :code, result_desc = :desc,
                           msisdn = NULL, attention = NULL
                     WHERE disbursement_id = :id
                    """)
                .param("code", truncate("SEND:" + code, 20)).param("desc", truncate(description, 200)).param("id", id).update();
            outbox.record(tenantId, "MpesaB2cDisbursementFailed", "b2c_disbursement", id.toString(),
                    "{\"disbursementId\":\"%s\",\"resultCode\":\"%s\"}".formatted(id, truncate("SEND:" + code, 20)), "b2c:" + id);
        } else if (r.status() != Status.FAILED) {
            throw new ConflictException("disbursement is " + r.status());
        }
        return view(tenantId, id);
    }

    /** Applies the provider's final result exactly once. */
    @Transactional
    public View result(UUID tenantId, UUID id, Result result, String correlationId) {
        session.bind(tenantId);
        Row r = lock(id);
        checkConversation(r, result.conversationId());
        if (r.status() == Status.REQUESTED || r.status() == Status.APPROVED || r.status() == Status.REJECTED) {
            throw new ConflictException("disbursement was never dispatched");
        }
        if (r.status() == Status.COMPLETED) {
            if (result.transactionId() != null && !result.transactionId().equals(r.transactionId())) {
                throw new ConflictException("a different transaction already completed this disbursement");
            }
            return view(tenantId, id);
        }
        if (!"0".equals(result.resultCode())) {
            if (r.status() != Status.FAILED) {
                jdbc.sql("""
                        UPDATE payments.b2c_disbursement SET status = 'FAILED', result_code = :code, result_desc = :desc,
                               msisdn = NULL, attention = NULL, conversation_id = COALESCE(conversation_id, :c)
                         WHERE disbursement_id = :id
                        """)
                    .param("code", truncate(result.resultCode(), 20)).param("desc", truncate(result.resultDesc(), 200))
                    .param("c", result.conversationId()).param("id", id).update();
                outbox.record(tenantId, "MpesaB2cDisbursementFailed", "b2c_disbursement", id.toString(),
                        "{\"disbursementId\":\"%s\",\"resultCode\":\"%s\"}".formatted(id, result.resultCode()), correlationId);
            }
            return view(tenantId, id);
        }
        if (result.transactionId() == null || result.amount() == null) {
            throw new IllegalArgumentException("a successful result needs the transaction id and amount");
        }
        if (r.status() == Status.FAILED) {
            LOG.error("ALARM: B2C disbursement {} reported successful (transaction {}) after it was recorded as failed",
                    id, result.transactionId());
            jdbc.sql("""
                    UPDATE payments.b2c_disbursement SET attention = 'SUCCESS_AFTER_FAILURE', transaction_id = :t,
                           conversation_id = COALESCE(conversation_id, :c)
                     WHERE disbursement_id = :id AND attention IS NULL
                    """)
                .param("t", result.transactionId()).param("c", result.conversationId()).param("id", id).update();
            return view(tenantId, id);
        }
        if (result.amount().amount().compareTo(r.amount()) != 0
                || !result.amount().currency().getCurrencyCode().equals(r.currency())) {
            LOG.error("ALARM: B2C disbursement {} paid {} but {} was approved; not posted", id,
                    result.amount().amount(), r.amount());
            jdbc.sql("""
                    UPDATE payments.b2c_disbursement SET attention = 'AMOUNT_MISMATCH', conversation_id = COALESCE(conversation_id, :c)
                     WHERE disbursement_id = :id
                    """)
                .param("c", result.conversationId()).param("id", id).update();
            return view(tenantId, id);
        }
        UUID entry = loans.activate(tenantId, r.loanId(), r.glClearing(), id.toString(), businessDates.current(),
                correlationId, "provider:mpesa");
        jdbc.sql("""
                UPDATE payments.b2c_disbursement SET status = 'COMPLETED', transaction_id = :t, journal_entry_id = :entry,
                       result_code = '0', result_desc = :desc, msisdn = NULL, attention = NULL,
                       conversation_id = COALESCE(conversation_id, :c)
                 WHERE disbursement_id = :id
                """)
            .param("t", result.transactionId()).param("entry", entry).param("desc", truncate(result.resultDesc(), 200))
            .param("c", result.conversationId()).param("id", id).update();
        return view(tenantId, id);
    }

    /** The provider gave up waiting in its queue: the outcome is unknown, so a person must check. */
    @Transactional
    public View timeout(UUID tenantId, UUID id) {
        session.bind(tenantId);
        Row r = lock(id);
        if (r.status() == Status.DISPATCHED || r.status() == Status.SUBMITTED) {
            jdbc.sql("UPDATE payments.b2c_disbursement SET attention = 'TIMEOUT_UNKNOWN' WHERE disbursement_id = :id")
                .param("id", id).update();
        }
        return view(tenantId, id);
    }

    /** Flags dispatched disbursements whose send outcome never became known. Returns how many. */
    @Transactional
    public int housekeep(UUID tenantId, Duration unknownAfter) {
        session.bind(tenantId);
        return jdbc.sql("""
                UPDATE payments.b2c_disbursement SET attention = 'SEND_OUTCOME_UNKNOWN'
                 WHERE disbursement_id IN (SELECT disbursement_id FROM payments.b2c_disbursement
                                            WHERE status = 'DISPATCHED' AND conversation_id IS NULL AND attention IS NULL
                                              AND updated_at <= clock_timestamp() - make_interval(secs => :secs)
                                            LIMIT 500 FOR UPDATE SKIP LOCKED)
                """)
            .param("secs", (double) unknownAfter.toMillis() / 1000.0).update();
    }

    private void checkConversation(Row r, String conversationId) {
        if (r.conversationId() != null && conversationId != null && !r.conversationId().equals(conversationId)) {
            throw new ConflictException("ConversationID does not match this disbursement");
        }
    }

    private String singleAccount() {
        List<String> codes = jdbc.sql("SELECT short_code FROM payments.mpesa_b2c_account WHERE active ORDER BY short_code")
            .query(String.class).list();
        if (codes.size() != 1) {
            throw new IllegalArgumentException(codes.isEmpty() ? "no active B2C account configured"
                    : "several B2C accounts configured: specify shortCode");
        }
        return codes.get(0);
    }

    private Row lock(UUID id) {
        return jdbc.sql(SELECT + " WHERE d.disbursement_id = :id FOR UPDATE OF d").param("id", id)
            .query(B2cDisbursementService::row).optional()
            .orElseThrow(() -> new NotFoundException("disbursement not found"));
    }

    private static final String SELECT = """
            SELECT d.disbursement_id, d.idempotency_key, d.loan_id, l.loan_number, d.short_code, d.amount, d.currency,
                   d.msisdn, d.msisdn_masked, d.status, d.requested_by, d.conversation_id, d.transaction_id, a.gl_clearing
              FROM payments.b2c_disbursement d
              JOIN lending.loan l ON l.tenant_id = d.tenant_id AND l.loan_id = d.loan_id
              JOIN payments.mpesa_b2c_account a ON a.tenant_id = d.tenant_id AND a.short_code = d.short_code""";

    private static Row row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Row(rs.getObject("disbursement_id", UUID.class), rs.getString("idempotency_key"),
                rs.getObject("loan_id", UUID.class), rs.getString("loan_number"), rs.getString("short_code"),
                rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("msisdn"), rs.getString("msisdn_masked"),
                Status.valueOf(rs.getString("status")), rs.getString("requested_by"), rs.getString("conversation_id"),
                rs.getString("transaction_id"), rs.getString("gl_clearing"));
    }

    private static String plain(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    public static Money amount(String value, Currency currency) {
        return MpesaC2bService.kes(value, currency);
    }
}
