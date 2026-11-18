package ke.co.technopick.mfi.core.customer.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import ke.co.technopick.mfi.canonical.Msisdn;
import ke.co.technopick.mfi.core.common.ConflictException;
import ke.co.technopick.mfi.core.common.NotFoundException;
import ke.co.technopick.mfi.core.messaging.persistence.Outbox;
import ke.co.technopick.mfi.core.tenancy.persistence.BusinessDates;
import ke.co.technopick.mfi.core.tenancy.persistence.TenantSession;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customers and KYC (ADR-0016). One person registers a customer, with the customer's
 * data-processing consent recorded; a different person verifies the identity document. Only
 * ACTIVE (verified) customers can apply for loans, and loan payouts go only to the customer's
 * registered M-Pesa number. The database enforces the same rules (V15).
 */
@Service
public class CustomerService {

    /** Current consent wording version; change when the consent text shown to customers changes. */
    public static final String CONSENT_VERSION = "DPA-2026-01";

    public enum IdType { NATIONAL_ID, PASSPORT, ALIEN_ID }

    public record Registration(String idempotencyKey, String firstName, String middleName, String lastName,
                               LocalDate dateOfBirth, IdType idType, String idNumber, String kraPin, String phoneNumber,
                               String email, String branchCode, boolean consentGiven) { }

    public record CustomerRegistered(String customerNumber, String status, boolean replayed) { }

    /** List view: identity number and phone masked. */
    public record CustomerSummary(String customerNumber, String fullName, String idType, String idNumberMasked,
                                  String phoneMasked, String branchCode, String status) { }

    public record CustomerDetail(String customerNumber, String firstName, String middleName, String lastName,
                                 LocalDate dateOfBirth, String idType, String idNumber, String kraPin, String phoneNumber,
                                 String email, String branchCode, String status, String consentVersion,
                                 OffsetDateTime consentAt, String createdBy, OffsetDateTime createdAt, String verifiedBy,
                                 OffsetDateTime verifiedAt, String verificationNote, String statusReason) { }

    /** What other modules may know about a customer. */
    public record CustomerRef(UUID customerId, String customerNumber, String status, String msisdn, String fullName) { }

    private final JdbcClient jdbc;
    private final TenantSession session;
    private final BusinessDates businessDates;
    private final Outbox outbox;

    public CustomerService(JdbcClient jdbc, TenantSession session, BusinessDates businessDates, Outbox outbox) {
        this.jdbc = jdbc;
        this.session = session;
        this.businessDates = businessDates;
        this.outbox = outbox;
    }

    @Transactional
    public CustomerRegistered register(UUID tenantId, Registration r, String actor) {
        session.bind(tenantId);
        if (!r.consentGiven()) {
            throw new IllegalArgumentException("the customer's consent to data processing must be recorded");
        }
        String msisdn = Msisdn.normalise(r.phoneNumber());
        String idNumber = r.idNumber().strip().toUpperCase(Locale.ROOT);
        String idPattern = switch (r.idType()) {
            case NATIONAL_ID -> "^[0-9]{6,9}$";
            case PASSPORT -> "^[A-Z0-9]{6,12}$";
            case ALIEN_ID -> "^[0-9]{6,10}$";
        };
        if (!idNumber.matches(idPattern)) {
            throw new IllegalArgumentException("the " + r.idType().name().replace('_', ' ').toLowerCase(Locale.ROOT)
                    + " number is not in a valid format");
        }
        String kraPin = r.kraPin() == null || r.kraPin().isBlank() ? null : r.kraPin().strip().toUpperCase(Locale.ROOT);
        if (kraPin != null && !kraPin.matches("^[AP][0-9]{9}[A-Z]$")) {
            throw new IllegalArgumentException("KRA PIN is not in a valid format");
        }
        LocalDate today = businessDates.current();
        if (r.dateOfBirth().isAfter(today.minusYears(18))) {
            throw new IllegalArgumentException("customers must be 18 or older");
        }
        Optional<CustomerRegistered> existing = jdbc.sql("""
                SELECT customer_number, status, id_type, id_number FROM customer.customer WHERE idempotency_key = :key
                """)
            .param("key", r.idempotencyKey())
            .query((rs, i) -> {
                if (!rs.getString("id_type").equals(r.idType().name()) || !rs.getString("id_number").equals(idNumber)) {
                    throw new ConflictException("idempotency key already used for a different customer");
                }
                return new CustomerRegistered(rs.getString("customer_number"), rs.getString("status"), true);
            })
            .optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        Integer sameDocument = jdbc.sql("SELECT count(*) FROM customer.customer WHERE id_type = :t AND id_number = :n")
            .param("t", r.idType().name()).param("n", idNumber).query(Integer.class).single();
        if (sameDocument > 0) {
            throw new ConflictException("a customer with this " + r.idType().name().replace('_', ' ').toLowerCase(Locale.ROOT)
                    + " is already registered");
        }
        String number = jdbc.sql("SELECT customer.next_customer_number(:y)").param("y", today.getYear())
            .query(String.class).single();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer.customer (tenant_id, customer_id, customer_number, idempotency_key, first_name, middle_name,
                    last_name, date_of_birth, id_type, id_number, kra_pin, msisdn, email, branch_code, consent_version,
                    consent_at, created_by)
                VALUES (:tenant, :id, :number, :key, :first, :middle, :last, :dob, :idType, :idNumber, :kra, :msisdn, :email,
                    :branch, :consent, clock_timestamp(), :actor)
                """)
            .param("tenant", tenantId).param("id", id).param("number", number).param("key", r.idempotencyKey())
            .param("first", r.firstName().strip()).param("middle", blankToNull(r.middleName()))
            .param("last", r.lastName().strip()).param("dob", r.dateOfBirth()).param("idType", r.idType().name())
            .param("idNumber", idNumber).param("kra", kraPin).param("msisdn", msisdn)
            .param("email", blankToNull(r.email())).param("branch", r.branchCode().strip())
            .param("consent", CONSENT_VERSION).param("actor", actor)
            .update();
        // No personal data in the event: consumers look the customer up if they need details.
        outbox.record(tenantId, "CustomerRegistered", "customer", id.toString(),
                "{\"customerId\":\"%s\",\"customerNumber\":\"%s\"}".formatted(id, number), "customer:" + id);
        return new CustomerRegistered(number, "PENDING_VERIFICATION", false);
    }

    /** KYC verification by someone other than the registrar, with a note of what was checked. */
    @Transactional
    public CustomerDetail verify(UUID tenantId, String customerNumber, String note, String actor) {
        session.bind(tenantId);
        record Row(UUID id, String status, String createdBy) { }
        Row row = jdbc.sql("SELECT customer_id, status, created_by FROM customer.customer WHERE customer_number = :n FOR UPDATE")
            .param("n", normaliseNumber(customerNumber))
            .query((rs, i) -> new Row(rs.getObject("customer_id", UUID.class), rs.getString("status"), rs.getString("created_by")))
            .optional().orElseThrow(() -> new NotFoundException("customer not found"));
        if (!"PENDING_VERIFICATION".equals(row.status())) {
            throw new ConflictException("customer is already " + row.status());
        }
        if (row.createdBy().equals(actor)) {
            throw new AccessDeniedException("the person who registered the customer cannot verify them");
        }
        if (note == null || note.strip().length() < 10) {
            throw new IllegalArgumentException("record what was checked (at least 10 characters)");
        }
        jdbc.sql("""
                UPDATE customer.customer SET status = 'ACTIVE', verified_by = :actor, verification_note = :note
                 WHERE customer_id = :id
                """)
            .param("actor", actor).param("note", note.strip()).param("id", row.id()).update();
        outbox.record(tenantId, "CustomerVerified", "customer", row.id().toString(),
                "{\"customerId\":\"%s\"}".formatted(row.id()), "customer:" + row.id());
        return detail(tenantId, customerNumber);
    }

    @Transactional(readOnly = true)
    public CustomerDetail detail(UUID tenantId, String customerNumber) {
        session.bind(tenantId);
        return jdbc.sql("""
                SELECT customer_number, first_name, middle_name, last_name, date_of_birth, id_type, id_number, kra_pin, msisdn,
                       email, branch_code, status, consent_version, consent_at, created_by, created_at, verified_by,
                       verified_at, verification_note, status_reason
                  FROM customer.customer WHERE customer_number = :n
                """)
            .param("n", normaliseNumber(customerNumber))
            .query((rs, i) -> new CustomerDetail(rs.getString("customer_number"), rs.getString("first_name"),
                    rs.getString("middle_name"), rs.getString("last_name"), rs.getObject("date_of_birth", LocalDate.class),
                    rs.getString("id_type"), rs.getString("id_number"), rs.getString("kra_pin"), rs.getString("msisdn"),
                    rs.getString("email"), rs.getString("branch_code"), rs.getString("status"),
                    rs.getString("consent_version"), rs.getObject("consent_at", OffsetDateTime.class),
                    rs.getString("created_by"), rs.getObject("created_at", OffsetDateTime.class), rs.getString("verified_by"),
                    rs.getObject("verified_at", OffsetDateTime.class), rs.getString("verification_note"),
                    rs.getString("status_reason")))
            .optional().orElseThrow(() -> new NotFoundException("customer not found"));
    }

    /**
     * Search by customer number, identity number, phone or name (at least 3 characters). Results
     * show masked identity numbers and phones; at most 50.
     */
    @Transactional(readOnly = true)
    public List<CustomerSummary> search(UUID tenantId, String query, String status) {
        session.bind(tenantId);
        String q = query == null ? "" : query.strip();
        String phone = null;
        try {
            phone = Msisdn.normalise(q);
        } catch (IllegalArgumentException notAPhone) {
            // not a phone number
        }
        if (q.length() < 3 && status == null) {
            throw new IllegalArgumentException("search needs at least 3 characters or a status");
        }
        return jdbc.sql("""
                SELECT customer_number, first_name, middle_name, last_name, id_type, id_number, msisdn, branch_code, status
                  FROM customer.customer
                 WHERE (:status IS NULL OR status = :status)
                   AND (:q = '' OR customer_number = upper(:q) OR id_number = upper(:q) OR msisdn = :phone
                        OR lower(last_name || ' ' || first_name) LIKE lower(:like)
                        OR lower(first_name || ' ' || last_name) LIKE lower(:like))
                 ORDER BY created_at DESC LIMIT 50
                """)
            .param("status", status, java.sql.Types.VARCHAR).param("q", q).param("phone", phone, java.sql.Types.VARCHAR)
            .param("like", "%" + q.replace("%", "").replace("_", "") + "%")
            .query((rs, i) -> new CustomerSummary(rs.getString("customer_number"),
                    fullName(rs.getString("first_name"), rs.getString("middle_name"), rs.getString("last_name")),
                    rs.getString("id_type"), maskId(rs.getString("id_number")), Msisdn.mask(rs.getString("msisdn")),
                    rs.getString("branch_code"), rs.getString("status")))
            .list();
    }

    /** For other modules, inside their transaction (tenant already bound). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<CustomerRef> byNumber(String customerNumber) {
        return ref(" WHERE customer_number = :v", normaliseNumber(customerNumber));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<CustomerRef> byId(UUID customerId) {
        return ref(" WHERE customer_id = :v", customerId);
    }

    private Optional<CustomerRef> ref(String where, Object value) {
        return jdbc.sql("SELECT customer_id, customer_number, status, msisdn, first_name, middle_name, last_name FROM customer.customer"
                + where).param("v", value)
            .query((rs, i) -> new CustomerRef(rs.getObject("customer_id", UUID.class), rs.getString("customer_number"),
                    rs.getString("status"), rs.getString("msisdn"),
                    fullName(rs.getString("first_name"), rs.getString("middle_name"), rs.getString("last_name"))))
            .optional();
    }

    static String maskId(String id) {
        return id.length() <= 3 ? "***" : "*".repeat(id.length() - 3) + id.substring(id.length() - 3);
    }

    private static String fullName(String first, String middle, String last) {
        return middle == null || middle.isBlank() ? first + " " + last : first + " " + middle + " " + last;
    }

    private static String normaliseNumber(String number) {
        return number.strip().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
