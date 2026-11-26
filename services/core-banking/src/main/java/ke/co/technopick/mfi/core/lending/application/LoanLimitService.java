package ke.co.technopick.mfi.core.lending.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import ke.co.technopick.mfi.core.common.ConflictException;
import ke.co.technopick.mfi.core.common.NotFoundException;
import ke.co.technopick.mfi.core.tenancy.persistence.TenantSession;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loan limits and guarantors (ADR-0043): per-product limit rules, a member's limit, and guarantees
 * pledged on applications, each locked by an EARMARK hold on the guarantor's savings. The database
 * re-checks every rule (V40), including the limit and cover at approval.
 */
@Service
public class LoanLimitService {

    public record LoanLimitRuleView(String productCode, String productName, String savingsMultiple, boolean includeShares,
                                    boolean deductOutstanding, boolean guaranteesRequired, String updatedBy, OffsetDateTime updatedAt) { }

    public record LoanLimitView(String customerNumber, String productCode, boolean ruleApplies, String savings, String pledgedForOthers,
                                String shares, String outstanding, String ownCover, String savingsMultiple, String limit,
                                boolean guaranteesRequired) { }

    public record GuaranteeView(UUID guaranteeId, UUID applicationId, String borrowerNumber, String borrowerName,
                                String guarantorNumber, String guarantorName, String accountNumber, String amount,
                                String consentReference, String status, String pledgedBy, OffsetDateTime pledgedAt, String endedBy,
                                OffsetDateTime endedAt, String endReason, String loanNumber, String applicationStatus) { }

    /** Where an application stands against its limit and the cover its guarantees must give. */
    public record ApplicationCover(UUID applicationId, String borrowerNumber, String principal, LoanLimitView limit,
                                   String guaranteeNeeded, String pledged, boolean withinLimit, boolean covered,
                                   List<GuaranteeView> guarantees) { }

    private final JdbcClient jdbc;
    private final TenantSession session;

    public LoanLimitService(JdbcClient jdbc, TenantSession session) {
        this.jdbc = jdbc;
        this.session = session;
    }

    // ------------------------------------------------------------------ rules

    @Transactional(readOnly = true)
    public List<LoanLimitRuleView> rules(UUID tenantId) {
        session.bind(tenantId);
        return jdbc.sql("""
                SELECT p.product_code, p.name, r.savings_multiple, r.include_shares, r.deduct_outstanding, r.guarantees_required,
                       r.updated_by, r.updated_at
                  FROM lending.loan_product p LEFT JOIN lending.loan_limit_rule r ON r.tenant_id = p.tenant_id AND r.product_code = p.product_code
                 ORDER BY p.product_code
                """).query((rs, n) -> new LoanLimitRuleView(rs.getString(1), rs.getString(2),
                    rs.getBigDecimal(3) == null ? null : rs.getBigDecimal(3).setScale(2, RoundingMode.HALF_UP).toPlainString(),
                    rs.getBoolean(4), rs.getBoolean(5), rs.getBoolean(6), rs.getString(7), rs.getObject(8, OffsetDateTime.class)))
            .list();
    }

    @Transactional
    public LoanLimitRuleView saveRule(UUID tenantId, String productCode, BigDecimal multiple, boolean includeShares,
                                      boolean deductOutstanding, boolean guaranteesRequired, String actor) {
        session.bind(tenantId);
        if (jdbc.sql("SELECT count(*) FROM lending.loan_product WHERE product_code = :p").param("p", productCode).query(Integer.class).single() == 0) {
            throw new NotFoundException("loan product " + productCode + " not found");
        }
        jdbc.sql("""
                INSERT INTO lending.loan_limit_rule (tenant_id, product_code, savings_multiple, include_shares, deduct_outstanding,
                    guarantees_required, updated_by)
                VALUES (tenancy.current_tenant(), :p, :m, :s, :d, :g, :a)
                ON CONFLICT (tenant_id, product_code) DO UPDATE SET savings_multiple = EXCLUDED.savings_multiple,
                       include_shares = EXCLUDED.include_shares, deduct_outstanding = EXCLUDED.deduct_outstanding,
                       guarantees_required = EXCLUDED.guarantees_required, updated_by = EXCLUDED.updated_by, updated_at = clock_timestamp()
                """).param("p", productCode).param("m", multiple).param("s", includeShares).param("d", deductOutstanding)
            .param("g", guaranteesRequired).param("a", actor).update();
        return rules(tenantId).stream().filter(r -> r.productCode().equals(productCode)).findFirst().orElseThrow();
    }

    @Transactional
    public void removeRule(UUID tenantId, String productCode) {
        session.bind(tenantId);
        jdbc.sql("DELETE FROM lending.loan_limit_rule WHERE product_code = :p").param("p", productCode).update();
    }

    // ------------------------------------------------------------------ limits

    @Transactional(readOnly = true)
    public LoanLimitView limit(UUID tenantId, String customerNumber, String productCode) {
        session.bind(tenantId);
        UUID customer = customerId(customerNumber);
        return limit(customerNumber, customer, productCode, null);
    }

    /** The member's limit on a product (empty view when the product has no rule), excluding one loan. */
    LoanLimitView limit(String customerNumber, UUID customerId, String productCode, UUID excludeLoan) {
        return jdbc.sql("SELECT * FROM lending.loan_limit(tenancy.current_tenant(), :c, :p, :x)")
            .param("c", customerId).param("p", productCode).param("x", excludeLoan)
            .query((rs, n) -> new LoanLimitView(customerNumber, productCode, true, m(rs.getBigDecimal("savings")),
                    m(rs.getBigDecimal("pledged_for_others")), m(rs.getBigDecimal("shares")), m(rs.getBigDecimal("outstanding")),
                    m(rs.getBigDecimal("own_cover")), m(rs.getBigDecimal("savings_multiple")), m(rs.getBigDecimal("limit_amount")),
                    rs.getBoolean("guarantees_required")))
            .optional().orElse(new LoanLimitView(customerNumber, productCode, false, null, null, null, null, null, null, null, false));
    }

    /** Refuses a principal above the member's limit, in words the officer can act on. */
    void checkLimit(String customerNumber, UUID customerId, String productCode, BigDecimal principal, UUID excludeLoan) {
        LoanLimitView l = limit(customerNumber, customerId, productCode, excludeLoan);
        if (l.ruleApplies() && principal.compareTo(new BigDecimal(l.limit())) > 0) {
            throw new ConflictException(customerNumber + " may borrow up to " + l.limit() + " on " + productCode + " (" + l.savingsMultiple()
                    + " x own cover " + l.ownCover() + ", less " + l.outstanding() + " outstanding); " + m(principal) + " was asked for");
        }
    }

    /** Refuses approval when guarantees do not cover the part above own cover. */
    void checkCover(UUID applicationId, String customerNumber, UUID customerId, String productCode, BigDecimal principal, UUID excludeLoan) {
        LoanLimitView l = limit(customerNumber, customerId, productCode, excludeLoan);
        if (!l.ruleApplies() || !l.guaranteesRequired()) {
            return;
        }
        BigDecimal needed = principal.subtract(new BigDecimal(l.ownCover())).max(BigDecimal.ZERO);
        BigDecimal pledged = pledged(applicationId);
        if (pledged.compareTo(needed) < 0) {
            throw new ConflictException("guarantees of " + m(needed) + " are needed above the member's own cover of " + l.ownCover()
                    + "; " + m(pledged) + " is pledged");
        }
    }

    @Transactional(readOnly = true)
    public ApplicationCover cover(UUID tenantId, UUID applicationId) {
        session.bind(tenantId);
        return cover(applicationId);
    }

    private ApplicationCover cover(UUID applicationId) {
        record A(String customerNumber, UUID customerId, String product, BigDecimal principal) { }
        A a = jdbc.sql("""
                SELECT a.customer_ref, a.customer_id, v.product_code, a.principal FROM lending.loan_application a
                  JOIN lending.loan_product_version v ON v.tenant_id = a.tenant_id AND v.version_id = a.product_version_id
                 WHERE a.application_id = :id
                """).param("id", applicationId)
            .query((rs, n) -> new A(rs.getString(1), rs.getObject(2, UUID.class), rs.getString(3), rs.getBigDecimal(4))).optional()
            .orElseThrow(() -> new NotFoundException("application not found"));
        LoanLimitView l = limit(a.customerNumber(), a.customerId(), a.product(), null);
        BigDecimal pledged = pledged(applicationId);
        BigDecimal needed = l.ruleApplies() && l.guaranteesRequired()
                ? a.principal().subtract(new BigDecimal(l.ownCover())).max(BigDecimal.ZERO) : BigDecimal.ZERO;
        boolean within = !l.ruleApplies() || a.principal().compareTo(new BigDecimal(l.limit())) <= 0;
        return new ApplicationCover(applicationId, a.customerNumber(), m(a.principal()), l, m(needed), m(pledged), within,
                pledged.compareTo(needed) >= 0, guarantees(" WHERE g.application_id = :id", applicationId));
    }

    // ------------------------------------------------------------------ guarantees

    @Transactional
    public ApplicationCover pledge(UUID tenantId, UUID applicationId, String idempotencyKey, String guarantorNumber, String accountNumber,
                                   BigDecimal amount, String consentReference, String actor) {
        session.bind(tenantId);
        if (jdbc.sql("SELECT count(*) FROM lending.loan_guarantee WHERE idempotency_key = :k").param("k", idempotencyKey)
                .query(Integer.class).single() > 0) {
            return cover(applicationId);
        }
        record A(String status, UUID borrower) { }
        A app = jdbc.sql("SELECT status, customer_id FROM lending.loan_application WHERE application_id = :id FOR UPDATE")
            .param("id", applicationId).query((rs, n) -> new A(rs.getString(1), rs.getObject(2, UUID.class))).optional()
            .orElseThrow(() -> new NotFoundException("application not found"));
        if (!"SUBMITTED".equals(app.status())) {
            throw new ConflictException("the application is " + app.status() + "; guarantees are pledged while it is open");
        }
        UUID guarantor = customerId(guarantorNumber);
        if (guarantor.equals(app.borrower())) {
            throw new IllegalArgumentException("a member cannot guarantee their own loan");
        }
        record Acc(UUID id, UUID owner, String status, String currency, BigDecimal available, String ownerStatus) { }
        Acc acc = jdbc.sql("""
                SELECT a.account_id, a.customer_id, a.status, a.currency, b.available_balance, c.status
                  FROM savings.deposit_account a
                  JOIN savings.account_balance b ON b.tenant_id = a.tenant_id AND b.account_id = a.account_id
                  LEFT JOIN customer.customer c ON c.tenant_id = a.tenant_id AND c.customer_id = a.customer_id
                 WHERE a.account_number = :n FOR UPDATE OF a
                """).param("n", accountNumber)
            .query((rs, n) -> new Acc(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                    rs.getBigDecimal(5), rs.getString(6)))
            .optional().orElseThrow(() -> new NotFoundException("savings account " + accountNumber + " not found"));
        if (!guarantor.equals(acc.owner())) {
            throw new IllegalArgumentException("account " + accountNumber + " does not belong to " + guarantorNumber);
        }
        if (!"ACTIVE".equals(acc.ownerStatus())) {
            throw new ConflictException(guarantorNumber + " is not an active member");
        }
        if (!"ACTIVE".equals(acc.status())) {
            throw new ConflictException("account " + accountNumber + " is " + acc.status());
        }
        if (acc.available().compareTo(amount) < 0) {
            throw new ConflictException(guarantorNumber + " has " + m(acc.available()) + " available in " + accountNumber + "; "
                    + m(amount) + " cannot be pledged");
        }
        boolean already = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM lending.loan_guarantee WHERE application_id = :a AND guarantor_customer_id = :g AND status = 'PLEDGED')
                """).param("a", applicationId).param("g", guarantor).query(Boolean.class).single();
        if (already) {
            throw new ConflictException(guarantorNumber + " already guarantees this application; withdraw that pledge to change it");
        }
        UUID id = UUID.nameUUIDFromBytes((tenantId + "|guarantee|" + idempotencyKey).getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO savings.account_hold (tenant_id, hold_id, account_id, kind, amount, currency, reference, idempotency_key)
                VALUES (tenancy.current_tenant(), :id, :acc, 'EARMARK', :amt, :ccy, :ref, :key)
                """).param("id", id).param("acc", acc.id()).param("amt", amount).param("ccy", acc.currency())
            .param("ref", "guarantee:" + applicationId).param("key", "guarantee-hold:" + idempotencyKey).update();
        jdbc.sql("""
                INSERT INTO lending.loan_guarantee (tenant_id, guarantee_id, idempotency_key, application_id, guarantor_customer_id,
                    savings_account_id, amount, consent_reference, hold_id, pledged_by)
                VALUES (tenancy.current_tenant(), :id, :key, :app, :g, :acc, :amt, :consent, :id, :actor)
                """).param("id", id).param("key", idempotencyKey).param("app", applicationId).param("g", guarantor)
            .param("acc", acc.id()).param("amt", amount).param("consent", consentReference.strip()).param("actor", actor).update();
        return cover(applicationId);
    }

    @Transactional
    public ApplicationCover withdraw(UUID tenantId, UUID guaranteeId, String reason, String actor) {
        session.bind(tenantId);
        record G(UUID application, String status, String applicationStatus) { }
        G g = jdbc.sql("""
                SELECT g.application_id, g.status, a.status FROM lending.loan_guarantee g
                  JOIN lending.loan_application a ON a.tenant_id = g.tenant_id AND a.application_id = g.application_id
                 WHERE g.guarantee_id = :id FOR UPDATE OF g
                """).param("id", guaranteeId).query((rs, n) -> new G(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)))
            .optional().orElseThrow(() -> new NotFoundException("guarantee not found"));
        if (!"PLEDGED".equals(g.status())) {
            throw new ConflictException("the guarantee is already " + g.status());
        }
        if (!"SUBMITTED".equals(g.applicationStatus())) {
            throw new ConflictException("the application is " + g.applicationStatus() + "; the guarantee holds until the loan is repaid");
        }
        jdbc.sql("""
                UPDATE lending.loan_guarantee SET status = 'WITHDRAWN', ended_by = :a, end_reason = :r WHERE guarantee_id = :id
                """).param("a", actor).param("r", reason.strip()).param("id", guaranteeId).update();
        return cover(g.application());
    }

    /** Guarantees a member has given (as guarantor), newest first. */
    @Transactional(readOnly = true)
    public List<GuaranteeView> givenBy(UUID tenantId, String guarantorNumber) {
        session.bind(tenantId);
        return guarantees(" WHERE g.guarantor_customer_id = :id", customerId(guarantorNumber));
    }

    // ------------------------------------------------------------------ helpers

    private BigDecimal pledged(UUID applicationId) {
        return jdbc.sql("SELECT COALESCE(sum(amount), 0) FROM lending.loan_guarantee WHERE application_id = :a AND status = 'PLEDGED'")
            .param("a", applicationId).query(BigDecimal.class).single();
    }

    private UUID customerId(String customerNumber) {
        return jdbc.sql("SELECT customer_id FROM customer.customer WHERE customer_number = :n").param("n", customerNumber)
            .query(UUID.class).optional().orElseThrow(() -> new NotFoundException("member " + customerNumber + " not found"));
    }

    private List<GuaranteeView> guarantees(String where, UUID id) {
        return jdbc.sql("""
                SELECT g.*, b.customer_number AS borrower_number, b.first_name || ' ' || b.last_name AS borrower_name,
                       c.customer_number AS guarantor_number, c.first_name || ' ' || c.last_name AS guarantor_name,
                       d.account_number, l.loan_number, a.status AS application_status
                  FROM lending.loan_guarantee g
                  JOIN lending.loan_application a ON a.tenant_id = g.tenant_id AND a.application_id = g.application_id
                  JOIN customer.customer b ON b.tenant_id = a.tenant_id AND b.customer_id = a.customer_id
                  JOIN customer.customer c ON c.tenant_id = g.tenant_id AND c.customer_id = g.guarantor_customer_id
                  JOIN savings.deposit_account d ON d.tenant_id = g.tenant_id AND d.account_id = g.savings_account_id
                  LEFT JOIN lending.loan l ON l.tenant_id = a.tenant_id AND l.loan_id = a.loan_id
                """ + where + " ORDER BY g.pledged_at DESC LIMIT 200").param("id", id).query((rs, n) -> guarantee(rs)).list();
    }

    private static GuaranteeView guarantee(ResultSet rs) throws SQLException {
        return new GuaranteeView(rs.getObject("guarantee_id", UUID.class), rs.getObject("application_id", UUID.class),
                rs.getString("borrower_number"), rs.getString("borrower_name"), rs.getString("guarantor_number"),
                rs.getString("guarantor_name"), rs.getString("account_number"), m(rs.getBigDecimal("amount")),
                rs.getString("consent_reference"), rs.getString("status"), rs.getString("pledged_by"),
                rs.getObject("pledged_at", OffsetDateTime.class), rs.getString("ended_by"), rs.getObject("ended_at", OffsetDateTime.class),
                rs.getString("end_reason"), rs.getString("loan_number"), rs.getString("application_status"));
    }

    static String m(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
