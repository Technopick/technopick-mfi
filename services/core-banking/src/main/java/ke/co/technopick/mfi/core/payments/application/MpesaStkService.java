package ke.co.technopick.mfi.core.payments.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import ke.co.technopick.mfi.canonical.Money;
import ke.co.technopick.mfi.core.common.ConflictException;
import ke.co.technopick.mfi.core.common.NotFoundException;
import ke.co.technopick.mfi.core.lending.application.LoanRepaymentService;
import ke.co.technopick.mfi.core.messaging.persistence.Outbox;
import ke.co.technopick.mfi.core.tenancy.persistence.TenantSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M-Pesa STK Push (Lipa na M-Pesa Online) in core banking (ADR-0011).
 *
 * <p>Staff ask for a payment prompt on a customer's phone for a deposit account or loan. Core
 * records the request and an outbox event that carries only the request id; the integration
 * runtime then claims the request ({@link #dispatch}), which is the only place the full phone
 * number leaves core. The provider's result is applied through the same exactly-once receipt
 * path as C2B, so a receipt reported by both channels is credited once. A successful result is
 * always applied, whatever state the request is in, because the customer's money has moved.
 */
@Service
public class MpesaStkService {

    private static final Logger LOG = LoggerFactory.getLogger(MpesaStkService.class);
    private static final Pattern MSISDN = Pattern.compile("^254[17][0-9]{8}$");

    public enum Status { PENDING, DISPATCHED, SUBMITTED, COMPLETED, FAILED }

    public record Created(UUID requestId, Status status, boolean replayed) { }

    public record View(UUID requestId, String shortCode, String targetReference, String amount, String currency,
                       String msisdnMasked, Status status, String checkoutRequestId, String resultCode,
                       String resultDesc, UUID paymentId, String requestedBy, OffsetDateTime requestedAt) { }

    /** What the integration runtime needs to send the prompt; only returned once, on dispatch. */
    public record Dispatch(UUID requestId, String shortCode, String msisdn, String amount, String accountReference,
                           String description) { }

    public record Result(String checkoutRequestId, String merchantRequestId, String resultCode, String resultDesc,
                         String receipt, Money amount, OffsetDateTime transTime) { }

    private record Row(UUID requestId, String idempotencyKey, String shortCode, String targetReference,
                       BigDecimal amount, String currency, String msisdn, String msisdnMasked, String description,
                       Status status, String checkoutRequestId, UUID paymentId) { }

    private final JdbcClient jdbc;
    private final TenantSession session;
    private final MpesaC2bService receipts;
    private final LoanRepaymentService loans;
    private final Outbox outbox;

    public MpesaStkService(JdbcClient jdbc, TenantSession session, MpesaC2bService receipts,
                           LoanRepaymentService loans, Outbox outbox) {
        this.jdbc = jdbc;
        this.session = session;
        this.receipts = receipts;
        this.loans = loans;
        this.outbox = outbox;
    }

    /** Kenyan numbers as 2547XXXXXXXX / 2541XXXXXXXX, accepting the common local and +254 forms. */
    public static String normaliseMsisdn(String phone) {
        String digits = phone == null ? "" : phone.replaceAll("[\\s-]", "");
        if (digits.startsWith("+")) {
            digits = digits.substring(1);
        }
        if (digits.matches("^0[17][0-9]{8}$")) {
            digits = "254" + digits.substring(1);
        }
        if (!MSISDN.matcher(digits).matches()) {
            throw new IllegalArgumentException("phone number must be a Kenyan mobile number (2547XXXXXXXX or 2541XXXXXXXX)");
        }
        return digits;
    }

    static String mask(String msisdn) {
        return msisdn.substring(0, 4) + "*****" + msisdn.substring(msisdn.length() - 3);
    }

    @Transactional
    public Created request(UUID tenantId, String idempotencyKey, String shortCode, String phone, Money amount,
                           String targetReference, String actor) {
        session.bind(tenantId);
        String msisdn = normaliseMsisdn(phone);
        String target = targetReference.strip().toUpperCase(Locale.ROOT);
        if (amount.amount().stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("STK Push amounts are whole shillings");
        }
        String code = shortCode != null && !shortCode.isBlank() ? shortCode : singlePaybill();
        Optional<Row> existing = byKey(idempotencyKey);
        if (existing.isPresent()) {
            Row r = existing.get();
            boolean same = r.shortCode().equals(code) && r.targetReference().equals(target)
                    && r.amount().compareTo(amount.amount()) == 0 && r.currency().equals(amount.currency().getCurrencyCode())
                    && (r.msisdn() != null ? r.msisdn().equals(msisdn) : r.msisdnMasked().equals(mask(msisdn)));
            if (!same) {
                throw new ConflictException("idempotency key already used for a different STK request");
            }
            return new Created(r.requestId(), r.status(), true);
        }
        MpesaC2bService.Validation v = receipts.validate(tenantId, code, target, amount);
        if (!"0".equals(v.resultCode())) {
            throw new IllegalArgumentException("STK request refused: " + v.resultDesc());
        }
        boolean loan = loans.findByNumber(target).isPresent();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO payments.stk_request (tenant_id, request_id, idempotency_key, short_code, target_reference,
                    amount, currency, msisdn, msisdn_masked, description, requested_by)
                VALUES (:tenant, :id, :key, :code, :target, :amount, :ccy, :msisdn, :masked, :desc, :actor)
                """)
            .param("tenant", tenantId).param("id", id).param("key", idempotencyKey).param("code", code)
            .param("target", target).param("amount", amount.amount()).param("ccy", amount.currency().getCurrencyCode())
            .param("msisdn", msisdn).param("masked", mask(msisdn)).param("desc", loan ? "Loan payment" : "Deposit")
            .param("actor", actor)
            .update();
        // Carries no personal data: the integration runtime fetches the phone number on dispatch.
        outbox.record(tenantId, "MpesaStkPushRequested", "stk_request", id.toString(),
                "{\"requestId\":\"%s\",\"shortCode\":\"%s\"}".formatted(id, code), "stk:" + id);
        return new Created(id, Status.PENDING, false);
    }

    @Transactional(readOnly = true)
    public View view(UUID tenantId, UUID requestId) {
        session.bind(tenantId);
        return jdbc.sql("""
                SELECT request_id, short_code, target_reference, amount, currency, msisdn_masked, status,
                       checkout_request_id, result_code, result_desc, payment_id, requested_by, requested_at
                  FROM payments.stk_request WHERE request_id = :id
                """)
            .param("id", requestId)
            .query((rs, i) -> new View(rs.getObject("request_id", UUID.class), rs.getString("short_code"),
                    rs.getString("target_reference"),
                    rs.getBigDecimal("amount").setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                    rs.getString("currency"), rs.getString("msisdn_masked"), Status.valueOf(rs.getString("status")),
                    rs.getString("checkout_request_id"), rs.getString("result_code"), rs.getString("result_desc"),
                    rs.getObject("payment_id", UUID.class), rs.getString("requested_by"),
                    rs.getObject("requested_at", OffsetDateTime.class)))
            .optional()
            .orElseThrow(() -> new NotFoundException("STK request not found"));
    }

    /** Claims a PENDING request for sending; a second claim is refused, so a prompt is sent at most once. */
    @Transactional
    public Dispatch dispatch(UUID tenantId, UUID requestId) {
        session.bind(tenantId);
        Row r = lock(requestId);
        if (r.status() != Status.PENDING) {
            throw new ConflictException("STK request is already " + r.status());
        }
        jdbc.sql("UPDATE payments.stk_request SET status = 'DISPATCHED' WHERE request_id = :id")
            .param("id", requestId).update();
        return new Dispatch(requestId, r.shortCode(), r.msisdn(), r.amount().setScale(0, RoundingMode.UNNECESSARY).toPlainString(),
                r.targetReference(), r.description());
    }

    /** The provider accepted the prompt request. Idempotent; tolerates the result having arrived first. */
    @Transactional
    public View submitted(UUID tenantId, UUID requestId, String merchantRequestId, String checkoutRequestId) {
        session.bind(tenantId);
        Row r = lock(requestId);
        checkCheckout(r, checkoutRequestId);
        if (r.status() == Status.DISPATCHED) {
            jdbc.sql("""
                    UPDATE payments.stk_request SET status = 'SUBMITTED', merchant_request_id = :m, checkout_request_id = :c
                     WHERE request_id = :id
                    """)
                .param("m", merchantRequestId).param("c", checkoutRequestId).param("id", requestId).update();
        } else if (r.status() == Status.PENDING) {
            throw new ConflictException("STK request was never dispatched");
        }
        return view(tenantId, requestId);
    }

    /** The provider definitively refused the prompt request (no prompt was shown). */
    @Transactional
    public View sendFailed(UUID tenantId, UUID requestId, String code, String description) {
        session.bind(tenantId);
        Row r = lock(requestId);
        if (r.status() == Status.DISPATCHED) {
            jdbc.sql("""
                    UPDATE payments.stk_request SET status = 'FAILED', result_code = :code, result_desc = :desc, msisdn = NULL
                     WHERE request_id = :id
                    """)
                .param("code", truncate("SEND:" + code, 20)).param("desc", truncate(description, 200))
                .param("id", requestId).update();
        } else if (r.status() != Status.FAILED) {
            throw new ConflictException("STK request is " + r.status());
        }
        return view(tenantId, requestId);
    }

    /** Applies the provider's final result exactly once. */
    @Transactional
    public View result(UUID tenantId, UUID requestId, Result result, String correlationId) {
        session.bind(tenantId);
        Row r = lock(requestId);
        checkCheckout(r, result.checkoutRequestId());
        if (r.status() == Status.COMPLETED) {
            return view(tenantId, requestId);
        }
        if ("0".equals(result.resultCode())) {
            if (result.receipt() == null || result.amount() == null) {
                throw new IllegalArgumentException("a successful result needs the receipt number and amount");
            }
            if (result.amount().amount().compareTo(r.amount()) != 0) {
                // The customer's money moved: apply what was actually paid, and make it visible.
                LOG.warn("STK request {} asked {} but provider reports {}; applying the provider amount", requestId,
                        r.amount(), result.amount().amount());
            }
            MpesaC2bService.Applied applied = receipts.applyReceipt(new MpesaC2bService.C2bPayment(tenantId,
                    result.receipt(), r.shortCode(), result.amount(), r.targetReference(), r.msisdnMasked(),
                    Objects.requireNonNullElse(result.transTime(), OffsetDateTime.now()), correlationId), "MPESA_STK");
            jdbc.sql("""
                    UPDATE payments.stk_request SET status = 'COMPLETED', payment_id = :payment, result_code = '0',
                           result_desc = :desc, msisdn = NULL,
                           checkout_request_id = COALESCE(checkout_request_id, :c),
                           merchant_request_id = COALESCE(merchant_request_id, :m)
                     WHERE request_id = :id
                    """)
                .param("payment", applied.paymentId()).param("desc", truncate(result.resultDesc(), 200))
                .param("c", result.checkoutRequestId()).param("m", result.merchantRequestId()).param("id", requestId)
                .update();
        } else if (r.status() != Status.FAILED) {
            jdbc.sql("""
                    UPDATE payments.stk_request SET status = 'FAILED', result_code = :code, result_desc = :desc, msisdn = NULL,
                           checkout_request_id = COALESCE(checkout_request_id, :c),
                           merchant_request_id = COALESCE(merchant_request_id, :m)
                     WHERE request_id = :id
                    """)
                .param("code", truncate(result.resultCode(), 20)).param("desc", truncate(result.resultDesc(), 200))
                .param("c", result.checkoutRequestId()).param("m", result.merchantRequestId()).param("id", requestId)
                .update();
            outbox.record(tenantId, "MpesaStkPushFailed", "stk_request", requestId.toString(),
                    "{\"requestId\":\"%s\",\"resultCode\":\"%s\"}".formatted(requestId, result.resultCode()), correlationId);
        }
        return view(tenantId, requestId);
    }

    private void checkCheckout(Row r, String checkoutRequestId) {
        if (r.checkoutRequestId() != null && !r.checkoutRequestId().equals(checkoutRequestId)) {
            throw new ConflictException("CheckoutRequestID does not match this STK request");
        }
    }

    private String singlePaybill() {
        List<String> codes = jdbc.sql("SELECT short_code FROM payments.mpesa_paybill WHERE active ORDER BY short_code")
            .query(String.class).list();
        if (codes.size() != 1) {
            throw new IllegalArgumentException(codes.isEmpty() ? "no active paybill configured"
                    : "several paybills configured: specify shortCode");
        }
        return codes.get(0);
    }

    private Optional<Row> byKey(String key) {
        return jdbc.sql(SELECT + " WHERE idempotency_key = :key").param("key", key).query(MpesaStkService::row).optional();
    }

    private Row lock(UUID requestId) {
        return jdbc.sql(SELECT + " WHERE request_id = :id FOR UPDATE").param("id", requestId)
            .query(MpesaStkService::row).optional()
            .orElseThrow(() -> new NotFoundException("STK request not found"));
    }

    private static final String SELECT = """
            SELECT request_id, idempotency_key, short_code, target_reference, amount, currency, msisdn, msisdn_masked,
                   description, status, checkout_request_id, payment_id
              FROM payments.stk_request""";

    private static Row row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Row(rs.getObject("request_id", UUID.class), rs.getString("idempotency_key"), rs.getString("short_code"),
                rs.getString("target_reference"), rs.getBigDecimal("amount"), rs.getString("currency"),
                rs.getString("msisdn"), rs.getString("msisdn_masked"), rs.getString("description"),
                Status.valueOf(rs.getString("status")), rs.getString("checkout_request_id"),
                rs.getObject("payment_id", UUID.class));
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    /** Whole-shilling amount as Money in the given currency. */
    public static Money amount(String value, Currency currency) {
        return MpesaC2bService.kes(value, currency);
    }
}
