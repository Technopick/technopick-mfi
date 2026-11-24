package ke.co.technopick.mfi.core.notification.application;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ke.co.technopick.mfi.core.common.ConflictException;
import ke.co.technopick.mfi.core.common.NotFoundException;
import ke.co.technopick.mfi.core.tenancy.persistence.TenantSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SMS sending state (ADR-0035): the integration runtime claims due messages, sends them and
 * reports back; retries with back-off and recovery of claims that were never reported are decided
 * here. Also the staff views: a member's messages and preferences, tenant settings, and the
 * operations list.
 */
@Service
public class SmsService {

    public record ClaimedSms(UUID tenantId, UUID messageId, String msisdn, String body, String senderId) { }

    public record SmsView(UUID messageId, OffsetDateTime createdAt, String customerNumber, String template, String category,
                          String language, String body, String status, String suppressedReason, int attempts,
                          OffsetDateTime notBefore, OffsetDateTime sentAt, OffsetDateTime deliveredAt, String lastError) { }

    public record SmsPreferences(String language, boolean paymentAlerts, boolean reminders, String updatedBy,
                                 OffsetDateTime updatedAt) { }

    public record MemberSms(String customerNumber, String phone, SmsPreferences preferences, boolean smsEnabled,
                            List<SmsView> messages) { }

    public record SmsSettings(boolean enabled, String signature, String senderId, String ussdCode, int reminderHour,
                              String updatedBy, OffsetDateTime updatedAt) { }

    public record SmsOverview(Map<String, Integer> lastSevenDays, List<SmsView> messages) { }

    private static final Logger LOG = LoggerFactory.getLogger(SmsService.class);
    private static final long[] BACKOFF_MINUTES = {1, 5, 15, 60};
    private static final String VIEW = """
            SELECT m.message_id, m.created_at, c.customer_number, m.template, m.category, m.language, m.body, m.status,
                   m.suppressed_reason, m.attempts, m.not_before, m.sent_at, m.delivered_at, m.last_error
              FROM notify.sms_message m JOIN customer.customer c ON c.tenant_id = m.tenant_id AND c.customer_id = m.customer_id
            """;

    private final JdbcClient jdbc;
    private final TenantSession session;
    private final TransactionTemplate tx;
    private final int maxAttempts;
    private final Duration staleClaim;

    public SmsService(JdbcClient jdbc, TenantSession session, PlatformTransactionManager transactions,
                      @Value("${mfi.sms.max-attempts:5}") int maxAttempts,
                      @Value("${mfi.sms.stale-claim:PT5M}") Duration staleClaim) {
        this.jdbc = jdbc;
        this.session = session;
        this.tx = new TransactionTemplate(transactions);
        this.maxAttempts = Math.max(1, Math.min(maxAttempts, 10));
        this.staleClaim = staleClaim;
    }

    // ------------------------------------------------------------------ integration runtime

    /**
     * Claims up to {@code limit} due messages across tenants with SMS switched on. A claim that was
     * never reported (the sender stopped) is queued again after {@code mfi.sms.stale-claim}; the
     * member may then get the message twice, which is preferred to never.
     */
    public List<ClaimedSms> claim(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be 1 to 100");
        }
        List<ClaimedSms> out = new ArrayList<>();
        List<UUID> tenants = tx.execute(s -> jdbc.sql("SELECT tenancy.active_tenant_ids()").query(UUID.class).list());
        for (UUID tenant : tenants) {
            if (out.size() >= limit) {
                break;
            }
            int room = limit - out.size();
            out.addAll(tx.execute(s -> {
                session.bind(tenant);
                String sender = jdbc.sql("SELECT COALESCE(sender_id, '') FROM notify.sms_settings WHERE enabled")
                    .query(String.class).optional().orElse(null);
                if (sender == null) {
                    return List.<ClaimedSms>of();
                }
                int stale = jdbc.sql("""
                        UPDATE notify.sms_message
                           SET status = CASE WHEN attempts >= :max THEN 'FAILED' ELSE 'QUEUED' END,
                               failed_at = CASE WHEN attempts >= :max THEN clock_timestamp() END,
                               last_error = 'no report from the sender', not_before = clock_timestamp()
                         WHERE status = 'DISPATCHED' AND claimed_at < clock_timestamp() - make_interval(secs => :stale)
                        """).param("max", maxAttempts).param("stale", staleClaim.toSeconds()).update();
                if (stale > 0) {
                    LOG.warn("tenant {}: {} SMS claims were never reported and were queued again", tenant, stale);
                }
                return jdbc.sql("""
                        UPDATE notify.sms_message m SET status = 'DISPATCHED', attempts = m.attempts + 1, claimed_at = clock_timestamp()
                         WHERE m.message_id IN (SELECT message_id FROM notify.sms_message
                                                 WHERE status = 'QUEUED' AND not_before <= clock_timestamp()
                                                 ORDER BY not_before LIMIT :n FOR UPDATE SKIP LOCKED)
                        RETURNING m.message_id, m.msisdn, m.body
                        """).param("n", room)
                    .query((rs, i) -> new ClaimedSms(tenant, rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                            sender.isEmpty() ? null : sender))
                    .list();
            }));
        }
        return out;
    }

    @Transactional
    public SmsView sent(UUID tenantId, UUID messageId, String providerMessageId) {
        session.bind(tenantId);
        requireRef(providerMessageId);
        Row r = row(messageId);
        if ("DISPATCHED".equals(r.status())) {
            jdbc.sql("UPDATE notify.sms_message SET status = 'SENT', provider_message_id = :p, sent_at = clock_timestamp() WHERE message_id = :id")
                .param("p", providerMessageId).param("id", messageId).update();
        } else if (!providerMessageId.equals(r.providerMessageId())) {
            throw new ConflictException("SMS is " + r.status() + "; it was not waiting for a send report");
        }
        return view(messageId);
    }

    /** A send that failed: queued again with back-off if the provider says it may work later, else failed. */
    @Transactional
    public SmsView failed(UUID tenantId, UUID messageId, String code, String description, boolean retryable) {
        session.bind(tenantId);
        Row r = row(messageId);
        if (!"DISPATCHED".equals(r.status())) {
            throw new ConflictException("SMS is " + r.status() + "; it was not waiting for a send report");
        }
        String error = ((code == null ? "" : code + ": ") + (description == null ? "" : description)).strip();
        error = error.length() > 200 ? error.substring(0, 200) : error;
        if (retryable && r.attempts() < maxAttempts) {
            long minutes = BACKOFF_MINUTES[Math.min(r.attempts(), BACKOFF_MINUTES.length) - 1];
            jdbc.sql("""
                    UPDATE notify.sms_message SET status = 'QUEUED', last_error = :e,
                           not_before = clock_timestamp() + make_interval(mins => :m) WHERE message_id = :id
                    """).param("e", error).param("m", (int) minutes).param("id", messageId).update();
        } else {
            jdbc.sql("UPDATE notify.sms_message SET status = 'FAILED', last_error = :e, failed_at = clock_timestamp() WHERE message_id = :id")
                .param("e", error).param("id", messageId).update();
        }
        return view(messageId);
    }

    /** The provider's delivery report. Reports for finished messages are answered with the current state. */
    @Transactional
    public SmsView delivery(UUID tenantId, UUID messageId, String providerMessageId, boolean delivered, String reason) {
        session.bind(tenantId);
        requireRef(providerMessageId);
        Row r = row(messageId);
        if (r.providerMessageId() != null && !r.providerMessageId().equals(providerMessageId)) {
            throw new ConflictException("delivery report is for another provider message");
        }
        if ("DISPATCHED".equals(r.status())) {
            // The report overtook the send acknowledgement.
            jdbc.sql("UPDATE notify.sms_message SET status = 'SENT', provider_message_id = :p, sent_at = clock_timestamp() WHERE message_id = :id")
                .param("p", providerMessageId).param("id", messageId).update();
        } else if (!"SENT".equals(r.status())) {
            return view(messageId);
        }
        String why = reason == null ? "not delivered" : reason.length() > 200 ? reason.substring(0, 200) : reason;
        jdbc.sql(delivered
                ? "UPDATE notify.sms_message SET status = 'DELIVERED', delivered_at = clock_timestamp() WHERE message_id = :id"
                : "UPDATE notify.sms_message SET status = 'FAILED', failed_at = clock_timestamp(), last_error = :e WHERE message_id = :id")
            .params(delivered ? Map.of("id", messageId) : Map.of("id", messageId, "e", why)).update();
        return view(messageId);
    }

    // ------------------------------------------------------------------ staff

    @Transactional(readOnly = true)
    public MemberSms member(UUID tenantId, String customerNumber) {
        session.bind(tenantId);
        record C(UUID id, String phone) { }
        C c = customer(customerNumber, (rs, i) -> new C(rs.getObject(1, UUID.class), rs.getString(2)));
        SmsPreferences p = jdbc.sql("SELECT language, payment_alerts, reminders, updated_by, updated_at FROM notify.member_preference WHERE customer_id = :c")
            .param("c", c.id())
            .query((rs, i) -> new SmsPreferences(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3), rs.getString(4),
                    rs.getObject(5, OffsetDateTime.class)))
            .optional().orElse(new SmsPreferences("en", true, true, null, null));
        boolean enabled = jdbc.sql("SELECT EXISTS (SELECT 1 FROM notify.sms_settings WHERE enabled)").query(Boolean.class).single();
        List<SmsView> messages = jdbc.sql(VIEW + " WHERE m.customer_id = :c ORDER BY m.created_at DESC LIMIT 30")
            .param("c", c.id()).query((rs, i) -> view(rs)).list();
        return new MemberSms(customerNumber, maskPhone(c.phone()), p, enabled, messages);
    }

    @Transactional
    public MemberSms setPreferences(UUID tenantId, String customerNumber, String language, boolean paymentAlerts, boolean reminders,
                                    String actor) {
        session.bind(tenantId);
        if (!"en".equals(language) && !"sw".equals(language)) {
            throw new IllegalArgumentException("language must be en (English) or sw (Swahili)");
        }
        UUID c = customer(customerNumber, (rs, i) -> rs.getObject(1, UUID.class));
        jdbc.sql("""
                INSERT INTO notify.member_preference (tenant_id, customer_id, language, payment_alerts, reminders, updated_by)
                VALUES (tenancy.current_tenant(), :c, :l, :pay, :rem, :actor)
                ON CONFLICT (tenant_id, customer_id) DO UPDATE SET language = EXCLUDED.language,
                    payment_alerts = EXCLUDED.payment_alerts, reminders = EXCLUDED.reminders,
                    updated_by = EXCLUDED.updated_by, updated_at = clock_timestamp()
                """).param("c", c).param("l", language).param("pay", paymentAlerts).param("rem", reminders).param("actor", actor)
            .update();
        return member(tenantId, customerNumber);
    }

    @Transactional(readOnly = true)
    public SmsSettings settings(UUID tenantId) {
        session.bind(tenantId);
        return jdbc.sql("SELECT enabled, signature, sender_id, ussd_code, reminder_hour, updated_by, updated_at FROM notify.sms_settings")
            .query((rs, i) -> new SmsSettings(rs.getBoolean(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                    rs.getString(6), rs.getObject(7, OffsetDateTime.class)))
            .optional().orElse(new SmsSettings(false, null, null, null, 8, null, null));
    }

    @Transactional
    public SmsSettings saveSettings(UUID tenantId, boolean enabled, String signature, String senderId, String ussdCode,
                                   int reminderHour, String actor) {
        session.bind(tenantId);
        if (signature == null || !signature.strip().matches("^[A-Za-z0-9][A-Za-z0-9 .&-]{1,19}$")) {
            throw new IllegalArgumentException("signature: 2 to 20 letters, digits, spaces or . & - (it starts every SMS)");
        }
        String sender = senderId == null || senderId.isBlank() ? null : senderId.strip();
        if (sender != null && !sender.matches("^[A-Za-z0-9][A-Za-z0-9 -]{0,10}$")) {
            throw new IllegalArgumentException("sender ID: up to 11 letters, digits, spaces or -, as registered with the SMS provider");
        }
        String ussd = ussdCode == null || ussdCode.isBlank() ? null : ussdCode.strip();
        if (ussd != null && !ussd.matches("^\\*[0-9*]{1,17}#$")) {
            throw new IllegalArgumentException("USSD code like *483*1#");
        }
        if (reminderHour < 7 || reminderHour > 19) {
            throw new IllegalArgumentException("reminders are sent between 07:00 and 19:00");
        }
        jdbc.sql("""
                INSERT INTO notify.sms_settings (tenant_id, enabled, signature, sender_id, ussd_code, reminder_hour, updated_by)
                VALUES (tenancy.current_tenant(), :en, :sig, :sender, :ussd, :hour, :actor)
                ON CONFLICT (tenant_id) DO UPDATE SET enabled = EXCLUDED.enabled, signature = EXCLUDED.signature,
                    sender_id = EXCLUDED.sender_id, ussd_code = EXCLUDED.ussd_code, reminder_hour = EXCLUDED.reminder_hour,
                    updated_by = EXCLUDED.updated_by, updated_at = clock_timestamp()
                """).param("en", enabled).param("sig", signature.strip()).param("sender", sender).param("ussd", ussd)
            .param("hour", reminderHour).param("actor", actor).update();
        return settings(tenantId);
    }

    /** Counts by status over the last seven days, and the messages in one status (newest first). */
    @Transactional(readOnly = true)
    public SmsOverview overview(UUID tenantId, String status) {
        session.bind(tenantId);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String s : List.of("QUEUED", "DISPATCHED", "SENT", "DELIVERED", "FAILED", "SUPPRESSED")) {
            counts.put(s, 0);
        }
        record Count(String status, int n) { }
        jdbc.sql("SELECT status, count(*) FROM notify.sms_message WHERE created_at > clock_timestamp() - interval '7 days' GROUP BY status")
            .query((rs, i) -> new Count(rs.getString(1), rs.getInt(2))).list()
            .forEach(c -> counts.put(c.status(), c.n()));
        String wanted = status == null || status.isBlank() ? "FAILED" : status.strip().toUpperCase(java.util.Locale.ROOT);
        if (!counts.containsKey(wanted)) {
            throw new IllegalArgumentException("status must be one of " + counts.keySet());
        }
        List<SmsView> messages = jdbc.sql(VIEW + " WHERE m.status = :s ORDER BY m.created_at DESC LIMIT 100")
            .param("s", wanted).query((rs, i) -> view(rs)).list();
        return new SmsOverview(counts, messages);
    }

    // ------------------------------------------------------------------

    private record Row(String status, int attempts, String providerMessageId) { }

    private Row row(UUID messageId) {
        return jdbc.sql("SELECT status, attempts, provider_message_id FROM notify.sms_message WHERE message_id = :id FOR UPDATE")
            .param("id", messageId).query((rs, i) -> new Row(rs.getString(1), rs.getInt(2), rs.getString(3)))
            .optional().orElseThrow(() -> new NotFoundException("SMS message not found"));
    }

    private SmsView view(UUID messageId) {
        return jdbc.sql(VIEW + " WHERE m.message_id = :id").param("id", messageId).query((rs, i) -> view(rs)).single();
    }

    private static SmsView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SmsView(rs.getObject(1, UUID.class), rs.getObject(2, OffsetDateTime.class), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getInt(10),
                rs.getObject(11, OffsetDateTime.class), rs.getObject(12, OffsetDateTime.class), rs.getObject(13, OffsetDateTime.class),
                rs.getString(14));
    }

    private <T> T customer(String customerNumber, org.springframework.jdbc.core.RowMapper<T> mapper) {
        return jdbc.sql("SELECT customer_id, msisdn FROM customer.customer WHERE customer_number = :n")
            .param("n", customerNumber == null ? "" : customerNumber.strip())
            .query(mapper).optional().orElseThrow(() -> new NotFoundException("customer not found"));
    }

    private static void requireRef(String providerMessageId) {
        if (providerMessageId == null || providerMessageId.isBlank() || providerMessageId.length() > 100) {
            throw new IllegalArgumentException("providerMessageId (up to 100 characters) is required");
        }
    }

    private static String maskPhone(String msisdn) {
        return msisdn == null || msisdn.length() < 7 ? msisdn : msisdn.substring(0, 4) + "*****" + msisdn.substring(msisdn.length() - 3);
    }
}
