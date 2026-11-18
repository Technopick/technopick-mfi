package ke.co.technopick.mfi.core.lending.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import ke.co.technopick.mfi.canonical.Money;
import ke.co.technopick.mfi.core.common.ConflictException;
import ke.co.technopick.mfi.core.common.NotFoundException;
import ke.co.technopick.mfi.core.customer.application.CustomerService;
import ke.co.technopick.mfi.core.lending.domain.Installment;
import ke.co.technopick.mfi.core.lending.domain.LoanProductVersion;
import ke.co.technopick.mfi.core.messaging.persistence.Outbox;
import ke.co.technopick.mfi.core.tenancy.persistence.BusinessDates;
import ke.co.technopick.mfi.core.tenancy.persistence.TenantSession;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loan origination (ADR-0014): a loan officer submits an application against the product's
 * ACTIVE version (pinned at submission) and gets a repayment quote; a different person approves
 * or declines. Approval creates the loan (number, terms, submitter as creator, decider as
 * approver) and schedule version 1 in one transaction; the loan is then disbursed through the
 * existing B2C flow. The database enforces the same rules independently (V3, V14).
 */
@Service
public class LoanOriginationService {

    /** Latest first due date allowed, counted from the business date. */
    static final int MAX_FIRST_DUE_DAYS = 60;

    public record QuoteInstallment(int number, LocalDate dueDate, String principal, String interest, String total,
                                   String closingBalance) { }

    public record LoanQuote(String productCode, int productVersion, String interestMethod, String annualRate,
                            String principal, int termMonths, String upfrontFee, String netDisbursement,
                            String totalInterest, String totalRepayable, List<QuoteInstallment> installments) { }

    public record LoanApplicationView(UUID applicationId, String customerRef, String productCode, String branchCode,
                                      String principal, String currency, int termMonths, LocalDate firstDueDate,
                                      String purpose, String status, String submittedBy, OffsetDateTime submittedAt,
                                      String decidedBy, String decisionReason, String loanNumber, LoanQuote quote) { }

    public record LoanApplicationSubmitted(UUID applicationId, String status, boolean replayed, LoanQuote quote) { }

    public record LoanScheduleView(String loanNumber, String status, int scheduleVersion, List<QuoteInstallment> installments) { }

    private record App(UUID id, String key, String customerRef, UUID customerId, UUID versionId, String branchCode, BigDecimal principal,
                       int term, LocalDate firstDue, String purpose, String status, String submittedBy) { }

    private final JdbcClient jdbc;
    private final TenantSession session;
    private final BusinessDates businessDates;
    private final Outbox outbox;
    private final CustomerService customers;

    public LoanOriginationService(JdbcClient jdbc, TenantSession session, BusinessDates businessDates, Outbox outbox,
                                  CustomerService customers) {
        this.jdbc = jdbc;
        this.session = session;
        this.businessDates = businessDates;
        this.outbox = outbox;
        this.customers = customers;
    }

    @Transactional
    public LoanApplicationSubmitted submit(UUID tenantId, String idempotencyKey, String customerRef, String productCode,
                                           String branchCode, BigDecimal principalAmount, int termMonths,
                                           LocalDate firstDueDate, String purpose, String actor) {
        session.bind(tenantId);
        CustomerService.CustomerRef customer = customers.byNumber(customerRef)
                .filter(c -> "ACTIVE".equals(c.status()))
                .orElseThrow(() -> new IllegalArgumentException("customer " + customerRef
                        + " not found or not verified; register and verify the customer first"));
        String customerNumber = customer.customerNumber();
        LoanProductVersion product = activeVersion(productCode.strip().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new IllegalArgumentException("product " + productCode + " has no active version"));
        Money principal = money(principalAmount, product);
        product.validateRequest(principal, termMonths);
        LocalDate today = businessDates.current();
        LocalDate firstDue = firstDueDate == null ? today.plusMonths(1) : firstDueDate;
        if (!firstDue.isAfter(today) || firstDue.isAfter(today.plusDays(MAX_FIRST_DUE_DAYS))) {
            throw new IllegalArgumentException("first due date must be after " + today + " and within "
                    + MAX_FIRST_DUE_DAYS + " days");
        }
        Integer branch = jdbc.sql("SELECT count(*) FROM tenancy.branch WHERE branch_code = :b").param("b", branchCode)
            .query(Integer.class).single();
        if (branch == 0) {
            throw new IllegalArgumentException("branch " + branchCode + " not found");
        }
        Optional<App> existing = jdbc.sql(SELECT + " WHERE idempotency_key = :key").param("key", idempotencyKey)
            .query(LoanOriginationService::app).optional();
        if (existing.isPresent()) {
            App a = existing.get();
            boolean same = a.customerRef().equals(customerNumber) && a.versionId().equals(product.versionId())
                    && a.branchCode().equals(branchCode) && a.principal().compareTo(principal.amount()) == 0
                    && a.term() == termMonths && a.firstDue().equals(firstDue);
            if (!same) {
                throw new ConflictException("idempotency key already used for a different application");
            }
            return new LoanApplicationSubmitted(a.id(), a.status(), true, quote(product, principal, termMonths, firstDue));
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO lending.loan_application (tenant_id, application_id, idempotency_key, customer_ref, customer_id,
                    product_version_id, branch_code, currency, principal, term_months, first_due_date, purpose, submitted_by)
                VALUES (:tenant, :id, :key, :customer, :customerId, :version, :branch, :ccy, :principal, :term, :due, :purpose,
                    :actor)
                """)
            .param("tenant", tenantId).param("id", id).param("key", idempotencyKey).param("customer", customerNumber)
            .param("customerId", customer.customerId()).param("version", product.versionId()).param("branch", branchCode)
            .param("ccy", product.currency().getCurrencyCode()).param("principal", principal.amount())
            .param("term", termMonths).param("due", firstDue).param("purpose", purpose.strip()).param("actor", actor)
            .update();
        return new LoanApplicationSubmitted(id, "SUBMITTED", false, quote(product, principal, termMonths, firstDue));
    }

    @Transactional
    public LoanApplicationView decide(UUID tenantId, UUID applicationId, boolean approve, String reason, String actor) {
        session.bind(tenantId);
        App a = jdbc.sql(SELECT + " WHERE application_id = :id FOR UPDATE").param("id", applicationId)
            .query(LoanOriginationService::app).optional()
            .orElseThrow(() -> new NotFoundException("application not found"));
        if (!"SUBMITTED".equals(a.status())) {
            throw new ConflictException("application is already " + a.status());
        }
        if (a.submittedBy().equals(actor)) {
            throw new AccessDeniedException("the submitter cannot decide their own application");
        }
        if (!approve) {
            if (reason == null || reason.strip().length() < 3) {
                throw new IllegalArgumentException("a decline needs a reason");
            }
            jdbc.sql("""
                    UPDATE lending.loan_application SET status = 'DECLINED', decided_by = :actor, decision_reason = :reason
                     WHERE application_id = :id
                    """)
                .param("actor", actor).param("reason", reason.strip()).param("id", applicationId).update();
            return view(tenantId, applicationId);
        }
        LoanProductVersion product = versionById(a.versionId());
        String versionStatus = jdbc.sql("SELECT status FROM lending.loan_product_version WHERE version_id = :v")
            .param("v", a.versionId()).query(String.class).single();
        if (!"ACTIVE".equals(versionStatus)) {
            throw new ConflictException("the product version of this application is no longer active; resubmit");
        }
        LocalDate today = businessDates.current();
        if (!a.firstDue().isAfter(today)) {
            throw new ConflictException("the first due date has passed; resubmit with a new date");
        }
        Money principal = money(a.principal(), product);
        String loanNumber = jdbc.sql("SELECT lending.next_loan_number(:year)").param("year", today.getYear())
            .query(String.class).single();
        UUID loanId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO lending.loan (tenant_id, loan_id, loan_number, customer_ref, customer_id, product_version_id,
                    branch_code, currency, principal, term_months, created_by, approved_by)
                VALUES (:tenant, :loan, :number, :customer, :customerId, :version, :branch, :ccy, :principal, :term, :maker,
                    :checker)
                """)
            .param("tenant", tenantId).param("loan", loanId).param("number", loanNumber).param("customer", a.customerRef())
            .param("customerId", a.customerId()).param("version", a.versionId()).param("branch", a.branchCode())
            .param("ccy", product.currency().getCurrencyCode()).param("principal", principal.amount())
            .param("term", a.term()).param("maker", a.submittedBy()).param("checker", actor)
            .update();
        List<Installment> schedule = product.schedule(principal, a.term());
        for (Installment i : schedule) {
            jdbc.sql("""
                    INSERT INTO lending.schedule_installment (tenant_id, loan_id, schedule_version, installment_no, due_date,
                        principal, interest)
                    VALUES (:tenant, :loan, 1, :no, :due, :principal, :interest)
                    """)
                .param("tenant", tenantId).param("loan", loanId).param("no", i.number())
                .param("due", a.firstDue().plusMonths(i.number() - 1L)).param("principal", i.principal().amount())
                .param("interest", i.interest().amount())
                .update();
        }
        jdbc.sql("""
                UPDATE lending.loan_application SET status = 'APPROVED', decided_by = :actor, decision_reason = :reason,
                       loan_id = :loan
                 WHERE application_id = :id
                """)
            .param("actor", actor).param("reason", reason == null || reason.isBlank() ? null : reason.strip())
            .param("loan", loanId).param("id", applicationId).update();
        outbox.record(tenantId, "LoanApproved", "loan", loanId.toString(),
                "{\"loanId\":\"%s\",\"loanNumber\":\"%s\",\"applicationId\":\"%s\",\"principal\":\"%s\",\"currency\":\"%s\"}"
                        .formatted(loanId, loanNumber, applicationId, principal.amount().toPlainString(),
                                product.currency().getCurrencyCode()),
                "application:" + applicationId);
        return view(tenantId, applicationId);
    }

    @Transactional(readOnly = true)
    public LoanApplicationView view(UUID tenantId, UUID applicationId) {
        session.bind(tenantId);
        return views(" WHERE a.application_id = :id", applicationId).stream().findFirst()
            .orElseThrow(() -> new NotFoundException("application not found"));
    }

    @Transactional(readOnly = true)
    public List<LoanApplicationView> pending(UUID tenantId) {
        session.bind(tenantId);
        return views(" WHERE a.status = 'SUBMITTED'", null);
    }

    @Transactional(readOnly = true)
    public LoanScheduleView schedule(UUID tenantId, String loanNumber) {
        session.bind(tenantId);
        record L(UUID id, String status) { }
        L loan = jdbc.sql("SELECT loan_id, status FROM lending.loan WHERE loan_number = :n")
            .param("n", loanNumber.strip().toUpperCase(Locale.ROOT))
            .query((rs, i) -> new L(rs.getObject("loan_id", UUID.class), rs.getString("status"))).optional()
            .orElseThrow(() -> new NotFoundException("loan not found"));
        Integer version = jdbc.sql("SELECT max(schedule_version) FROM lending.schedule_installment WHERE loan_id = :l")
            .param("l", loan.id()).query(Integer.class).single();
        if (version == null) {
            return new LoanScheduleView(loanNumber, loan.status(), 0, List.of());
        }
        List<QuoteInstallment> lines = new ArrayList<>();
        BigDecimal[] balance = {jdbc.sql("SELECT principal FROM lending.loan WHERE loan_id = :l").param("l", loan.id())
                .query(BigDecimal.class).single()};
        jdbc.sql("""
                SELECT installment_no, due_date, principal, interest FROM lending.schedule_installment
                 WHERE loan_id = :l AND schedule_version = :v ORDER BY installment_no
                """)
            .param("l", loan.id()).param("v", version)
            .query((rs, i) -> {
                BigDecimal p = rs.getBigDecimal("principal");
                BigDecimal in = rs.getBigDecimal("interest");
                balance[0] = balance[0].subtract(p);
                return new QuoteInstallment(rs.getInt("installment_no"), rs.getObject("due_date", LocalDate.class),
                        plain(p), plain(in), plain(p.add(in)), plain(balance[0]));
            })
            .list().forEach(lines::add);
        return new LoanScheduleView(loanNumber.strip().toUpperCase(Locale.ROOT), loan.status(), version, lines);
    }

    // ------------------------------------------------------------------ helpers

    static LoanQuote quote(LoanProductVersion product, Money principal, int term, LocalDate firstDue) {
        List<Installment> schedule = product.schedule(principal, term);
        List<QuoteInstallment> lines = new ArrayList<>();
        Money totalInterest = Money.zero(principal.currency());
        for (Installment i : schedule) {
            totalInterest = totalInterest.plus(i.interest());
            lines.add(new QuoteInstallment(i.number(), firstDue.plusMonths(i.number() - 1L), plain(i.principal().amount()),
                    plain(i.interest().amount()), plain(i.principal().plus(i.interest()).amount()),
                    plain(i.closingBalance().amount())));
        }
        Money fee = product.upfrontFee(principal);
        return new LoanQuote(product.productCode(), product.version(), product.interestMethod().name(),
                product.annualRate().stripTrailingZeros().toPlainString(), plain(principal.amount()), term,
                plain(fee.amount()), plain(principal.minus(fee).amount()), plain(totalInterest.amount()),
                plain(principal.plus(totalInterest).amount()), List.copyOf(lines));
    }

    private Optional<LoanProductVersion> activeVersion(String productCode) {
        return jdbc.sql("""
                SELECT p.currency, v.* FROM lending.loan_product_version v
                  JOIN lending.loan_product p ON p.tenant_id = v.tenant_id AND p.product_code = v.product_code
                 WHERE v.product_code = :code AND v.status = 'ACTIVE'
                 ORDER BY v.version DESC LIMIT 1
                """)
            .param("code", productCode).query((rs, i) -> LoanRepaymentService.product(rs)).optional();
    }

    private LoanProductVersion versionById(UUID versionId) {
        return jdbc.sql("""
                SELECT p.currency, v.* FROM lending.loan_product_version v
                  JOIN lending.loan_product p ON p.tenant_id = v.tenant_id AND p.product_code = v.product_code
                 WHERE v.version_id = :v
                """)
            .param("v", versionId).query((rs, i) -> LoanRepaymentService.product(rs)).single();
    }

    private List<LoanApplicationView> views(String where, UUID id) {
        JdbcClient.StatementSpec spec = jdbc.sql("""
                SELECT a.application_id, a.customer_ref, v.product_code, a.branch_code, a.principal, a.currency,
                       a.term_months, a.first_due_date, a.purpose, a.status, a.submitted_by, a.submitted_at, a.decided_by,
                       a.decision_reason, l.loan_number, a.product_version_id
                  FROM lending.loan_application a
                  JOIN lending.loan_product_version v ON v.tenant_id = a.tenant_id AND v.version_id = a.product_version_id
                  LEFT JOIN lending.loan l ON l.tenant_id = a.tenant_id AND l.loan_id = a.loan_id""" + where
                + " ORDER BY a.submitted_at");
        if (id != null) {
            spec = spec.param("id", id);
        }
        record Raw(LoanApplicationView view, UUID versionId) { }
        List<Raw> raws = spec.query((rs, i) -> new Raw(new LoanApplicationView(rs.getObject("application_id", UUID.class),
                rs.getString("customer_ref"), rs.getString("product_code"), rs.getString("branch_code"),
                plain(rs.getBigDecimal("principal")), rs.getString("currency"), rs.getInt("term_months"),
                rs.getObject("first_due_date", LocalDate.class), rs.getString("purpose"), rs.getString("status"),
                rs.getString("submitted_by"), rs.getObject("submitted_at", OffsetDateTime.class), rs.getString("decided_by"),
                rs.getString("decision_reason"), rs.getString("loan_number"), null),
                rs.getObject("product_version_id", UUID.class))).list();
        List<LoanApplicationView> out = new ArrayList<>();
        for (Raw r : raws) {
            LoanApplicationView v = r.view();
            LoanProductVersion product = versionById(r.versionId());
            LoanQuote q = quote(product, money(new BigDecimal(v.principal()), product), v.termMonths(), v.firstDueDate());
            out.add(new LoanApplicationView(v.applicationId(), v.customerRef(), v.productCode(), v.branchCode(),
                    v.principal(), v.currency(), v.termMonths(), v.firstDueDate(), v.purpose(), v.status(),
                    v.submittedBy(), v.submittedAt(), v.decidedBy(), v.decisionReason(), v.loanNumber(), q));
        }
        return out;
    }

    private static final String SELECT = """
            SELECT application_id, idempotency_key, customer_ref, customer_id, product_version_id, branch_code, principal,
                   term_months,
                   first_due_date, purpose, status, submitted_by
              FROM lending.loan_application""";

    private static App app(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new App(rs.getObject("application_id", UUID.class), rs.getString("idempotency_key"),
                rs.getString("customer_ref"), rs.getObject("customer_id", UUID.class),
                rs.getObject("product_version_id", UUID.class), rs.getString("branch_code"),
                rs.getBigDecimal("principal"), rs.getInt("term_months"), rs.getObject("first_due_date", LocalDate.class),
                rs.getString("purpose"), rs.getString("status"), rs.getString("submitted_by"));
    }

    private static Money money(BigDecimal amount, LoanProductVersion product) {
        int scale = product.currency().getDefaultFractionDigits();
        if (amount.stripTrailingZeros().scale() > scale) {
            throw new IllegalArgumentException("amount has more than " + scale + " decimal places");
        }
        return Money.of(amount.setScale(scale, RoundingMode.UNNECESSARY), product.currency());
    }

    private static String plain(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }
}
