package ke.co.technopick.mfi.core.payments.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import ke.co.technopick.mfi.canonical.Money;
import ke.co.technopick.mfi.core.common.ConflictException;
import ke.co.technopick.mfi.core.common.NotFoundException;
import ke.co.technopick.mfi.core.tenancy.persistence.TenantSession;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operations close-out of M-Pesa items the system could not settle by itself (ADR-0013): an
 * operations user proposes PAID (with the receipt / transaction id and amount seen in the M-Pesa
 * org portal) or NOT_PAID, with evidence; a different supervisor approves or rejects. Approval
 * applies the outcome through the same exactly-once paths as a provider callback, so a manual
 * PAID can never credit or disburse twice, and the normal safeguards (amount check, success after
 * failure alarm) still apply.
 */
@Service
public class MpesaResolutionService {

    public enum ResolutionItemType { STK_REQUEST, B2C_DISBURSEMENT }

    public enum ResolutionOutcome { PAID, NOT_PAID }

    public record MpesaResolutionView(UUID resolutionId, ResolutionItemType itemType, UUID itemId, ResolutionOutcome outcome,
                                      String providerRef, String amount, String evidence, String status,
                                      String requestedBy, String decidedBy, String resultStatus,
                                      OffsetDateTime requestedAt) { }

    private record Row(ResolutionItemType itemType, UUID itemId, ResolutionOutcome outcome, String providerRef, BigDecimal amount,
                       String status, String requestedBy) { }

    private static final Set<String> B2C_RESOLVABLE = Set.of("TIMEOUT_UNKNOWN", "SEND_OUTCOME_UNKNOWN");

    private final JdbcClient jdbc;
    private final TenantSession session;
    private final MpesaStkService stk;
    private final B2cDisbursementService b2c;

    public MpesaResolutionService(JdbcClient jdbc, TenantSession session, MpesaStkService stk,
                                  B2cDisbursementService b2c) {
        this.jdbc = jdbc;
        this.session = session;
        this.stk = stk;
        this.b2c = b2c;
    }

    @Transactional
    public UUID propose(UUID tenantId, ResolutionItemType type, UUID itemId, ResolutionOutcome outcome, String providerRef, Money amount,
                        String evidence, String actor) {
        session.bind(tenantId);
        if ((outcome == ResolutionOutcome.PAID) != (providerRef != null && amount != null)) {
            throw new IllegalArgumentException(outcome == ResolutionOutcome.PAID
                    ? "PAID needs the M-Pesa receipt / transaction id and the amount"
                    : "NOT_PAID takes no receipt or amount");
        }
        switch (type) {
            case STK_REQUEST -> {
                MpesaStkService.View v = stk.view(tenantId, itemId);
                boolean open = v.status() == MpesaStkService.Status.DISPATCHED || v.status() == MpesaStkService.Status.SUBMITTED;
                // A receipt proves payment even for a request recorded as failed (success always wins).
                if (outcome == ResolutionOutcome.PAID ? !(open || v.status() == MpesaStkService.Status.FAILED) : !open) {
                    throw new ConflictException("STK request is " + v.status() + "; nothing to resolve");
                }
            }
            case B2C_DISBURSEMENT -> {
                B2cDisbursementService.DisbursementView v = b2c.view(tenantId, itemId);
                boolean open = v.status() == B2cDisbursementService.DisbursementStatus.DISPATCHED
                        || v.status() == B2cDisbursementService.DisbursementStatus.SUBMITTED;
                if (!open || v.attention() == null || !B2C_RESOLVABLE.contains(v.attention())) {
                    throw new ConflictException("only disbursements with an unknown outcome can be resolved here"
                            + (v.attention() != null && !B2C_RESOLVABLE.contains(v.attention())
                                ? "; " + v.attention() + " is handled by finance" : ""));
                }
            }
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO payments.mpesa_resolution (tenant_id, resolution_id, item_type, item_id, outcome, provider_ref,
                    amount, evidence, requested_by)
                VALUES (:tenant, :id, :type, :item, :outcome, :ref, :amount, :evidence, :actor)
                """)
            .param("tenant", tenantId).param("id", id).param("type", type.name()).param("item", itemId)
            .param("outcome", outcome.name()).param("ref", providerRef)
            .param("amount", amount == null ? null : amount.amount()).param("evidence", evidence.strip())
            .param("actor", actor)
            .update();
        return id;
    }

    @Transactional
    public MpesaResolutionView decide(UUID tenantId, UUID resolutionId, boolean approve, String actor) {
        session.bind(tenantId);
        Row r = jdbc.sql("""
                SELECT item_type, item_id, outcome, provider_ref, amount, status, requested_by
                  FROM payments.mpesa_resolution WHERE resolution_id = :id FOR UPDATE
                """)
            .param("id", resolutionId)
            .query((rs, i) -> new Row(ResolutionItemType.valueOf(rs.getString("item_type")), rs.getObject("item_id", UUID.class),
                    ResolutionOutcome.valueOf(rs.getString("outcome")), rs.getString("provider_ref"), rs.getBigDecimal("amount"),
                    rs.getString("status"), rs.getString("requested_by")))
            .optional()
            .orElseThrow(() -> new NotFoundException("resolution not found"));
        if (!"PENDING".equals(r.status())) {
            throw new ConflictException("resolution is already " + r.status());
        }
        if (r.requestedBy().equals(actor)) {
            throw new AccessDeniedException("the proposer cannot decide their own resolution");
        }
        if (!approve) {
            jdbc.sql("UPDATE payments.mpesa_resolution SET status = 'REJECTED', decided_by = :actor WHERE resolution_id = :id")
                .param("actor", actor).param("id", resolutionId).update();
            return view(tenantId, resolutionId);
        }
        String correlation = "resolution:" + resolutionId;
        Currency kes = Currency.getInstance("KES");
        Money amount = r.amount() == null ? null
                : Money.of(r.amount().setScale(kes.getDefaultFractionDigits(), RoundingMode.UNNECESSARY), kes);
        String resultStatus = switch (r.itemType()) {
            case STK_REQUEST -> {
                String checkout = stk.view(tenantId, r.itemId()).checkoutRequestId();
                MpesaStkService.Result result = r.outcome() == ResolutionOutcome.PAID
                        ? new MpesaStkService.Result(checkout, null, "0", "Confirmed paid by operations",
                                r.providerRef(), amount, OffsetDateTime.now())
                        : new MpesaStkService.Result(checkout, null, "MANUAL", "Confirmed not paid by operations",
                                null, null, null);
                yield stk.result(tenantId, r.itemId(), result, correlation).status().name();
            }
            case B2C_DISBURSEMENT -> {
                B2cDisbursementService.Result result = r.outcome() == ResolutionOutcome.PAID
                        ? new B2cDisbursementService.Result(null, "0", "Confirmed paid by operations", r.providerRef(), amount)
                        : new B2cDisbursementService.Result(null, "MANUAL", "Confirmed not paid by operations", null, null);
                B2cDisbursementService.DisbursementView v = b2c.result(tenantId, r.itemId(), result, correlation);
                yield v.attention() == null ? v.status().name() : v.status().name() + ":" + v.attention();
            }
        };
        jdbc.sql("""
                UPDATE payments.mpesa_resolution SET status = 'APPROVED', decided_by = :actor, result_status = :result
                 WHERE resolution_id = :id
                """)
            .param("actor", actor).param("result", resultStatus.length() > 16 ? resultStatus.substring(0, 16) : resultStatus)
            .param("id", resolutionId).update();
        return view(tenantId, resolutionId);
    }

    @Transactional(readOnly = true)
    public MpesaResolutionView view(UUID tenantId, UUID resolutionId) {
        session.bind(tenantId);
        return list(" WHERE resolution_id = :id", resolutionId).stream().findFirst()
            .orElseThrow(() -> new NotFoundException("resolution not found"));
    }

    @Transactional(readOnly = true)
    public List<MpesaResolutionView> pending(UUID tenantId) {
        session.bind(tenantId);
        return list(" WHERE status = 'PENDING'", null);
    }

    private List<MpesaResolutionView> list(String where, UUID id) {
        JdbcClient.StatementSpec spec = jdbc.sql("""
                SELECT resolution_id, item_type, item_id, outcome, provider_ref, amount, evidence, status, requested_by,
                       decided_by, result_status, requested_at
                  FROM payments.mpesa_resolution""" + where + " ORDER BY requested_at");
        if (id != null) {
            spec = spec.param("id", id);
        }
        return spec.query((rs, i) -> new MpesaResolutionView(rs.getObject("resolution_id", UUID.class),
                    ResolutionItemType.valueOf(rs.getString("item_type")), rs.getObject("item_id", UUID.class),
                    ResolutionOutcome.valueOf(rs.getString("outcome")), rs.getString("provider_ref"),
                    rs.getBigDecimal("amount") == null ? null
                            : rs.getBigDecimal("amount").setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                    rs.getString("evidence"), rs.getString("status"), rs.getString("requested_by"),
                    rs.getString("decided_by"), rs.getString("result_status"),
                    rs.getObject("requested_at", OffsetDateTime.class)))
            .list();
    }
}
