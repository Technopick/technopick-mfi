package ke.co.technopick.mfi.core.notification.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import ke.co.technopick.mfi.core.notification.application.SmsTemplates.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Queues SMS messages to members (ADR-0035) in the caller's transaction, so a message exists if
 * and only if the event it reports was committed. Queueing runs in a savepoint: a fault here is
 * logged and never undoes the payment, end of day or PIN change that called it. The tenant must
 * already be bound by the caller.
 */
@Service
public class NotificationService {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationService.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private record Settings(String signature, String ussdCode, int reminderHour, ZoneId zone) { }

    private record Member(String msisdn, String status, String language, boolean paymentAlerts, boolean reminders) { }

    private final JdbcClient jdbc;
    private final TransactionTemplate savepoint;

    public NotificationService(JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.savepoint = new TransactionTemplate(transactions);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    /** An M-Pesa receipt applied to a member's savings account or loan. */
    public void paymentReceived(UUID accountId, UUID loanId, BigDecimal amount, String receipt, LocalDate date) {
        safely("payment " + receipt, () -> {
            record Target(UUID customerId, String number) { }
            Target t = accountId != null
                ? jdbc.sql("SELECT customer_id, account_number FROM savings.deposit_account WHERE account_id = :id")
                    .param("id", accountId).query((rs, i) -> new Target(rs.getObject(1, UUID.class), rs.getString(2))).optional().orElse(null)
                : jdbc.sql("SELECT customer_id, loan_number FROM lending.loan WHERE loan_id = :id")
                    .param("id", loanId).query((rs, i) -> new Target(rs.getObject(1, UUID.class), rs.getString(2))).optional().orElse(null);
            if (t == null || t.customerId() == null) {
                return;
            }
            Map<String, String> p = new HashMap<>();
            p.put("amount", money(amount));
            p.put("date", date.format(DATE));
            p.put("ref", receipt);
            if (accountId != null) {
                p.put("account", mask(t.number()));
            } else {
                p.put("loan", t.number());
            }
            queue(t.customerId(), accountId != null ? "PAYMENT_SAVINGS" : "PAYMENT_LOAN", p, "pay:" + receipt, null, "provider:mpesa");
        });
    }

    /**
     * At the end-of-day close of {@code closed}: instalments due in three days and instalments due
     * today that are still unpaid. Sent the next morning at the tenant's reminder hour.
     */
    public int loanReminders(LocalDate closed, String actor) {
        int[] queued = {0};
        safely("loan reminders " + closed, () -> {
            Settings s = settings();
            if (s == null) {
                return;
            }
            String paybill = jdbc.sql("SELECT short_code FROM payments.mpesa_paybill ORDER BY short_code LIMIT 1")
                .query(String.class).optional().orElse(null);
            if (paybill == null) {
                LOG.warn("loan reminders for {} not queued: no M-Pesa paybill configured", closed);
                return;
            }
            OffsetDateTime morning = closed.plusDays(1).atTime(LocalTime.of(s.reminderHour(), 0)).atZone(s.zone()).toOffsetDateTime();
            record Due(UUID customerId, String loan, LocalDate due, BigDecimal remaining) { }
            List<Due> due = jdbc.sql("""
                    SELECT l.customer_id, l.loan_number, s.due_date,
                           (s.principal + s.interest) - COALESCE((
                               SELECT sum(a.amount) FROM lending.repayment_allocation a
                                 JOIN lending.repayment r ON r.tenant_id = a.tenant_id AND r.repayment_id = a.repayment_id
                                WHERE r.loan_id = l.loan_id AND a.installment_no = s.installment_no
                                  AND a.component IN ('PRINCIPAL', 'INTEREST')), 0) AS remaining
                      FROM lending.schedule_installment s
                      JOIN lending.loan l ON l.tenant_id = s.tenant_id AND l.loan_id = s.loan_id
                     WHERE s.due_date IN (:d, :d3) AND l.status = 'ACTIVE' AND l.customer_id IS NOT NULL
                       AND s.schedule_version = (SELECT max(x.schedule_version) FROM lending.schedule_installment x
                                                  WHERE x.tenant_id = s.tenant_id AND x.loan_id = s.loan_id)
                    """).param("d", closed).param("d3", closed.plusDays(3))
                .query((rs, i) -> new Due(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, LocalDate.class),
                        rs.getBigDecimal(4)))
                .list();
            for (Due d : due) {
                if (d.remaining().setScale(2, RoundingMode.HALF_UP).signum() <= 0) {
                    continue;
                }
                boolean missed = d.due().equals(closed);
                Map<String, String> p = Map.of("loan", d.loan(), "amount", money(d.remaining()), "date", d.due().format(DATE),
                        "paybill", paybill);
                if (queue(d.customerId(), missed ? "LOAN_MISSED" : "LOAN_DUE_SOON", p,
                        (missed ? "missed:" : "due:") + d.loan() + ":" + d.due(), morning, actor)) {
                    queued[0]++;
                }
            }
        });
        return queued[0];
    }

    /** An approved PIN setup: tell the member how to choose their PIN. */
    public void pinSetupReady(UUID customerId, UUID setupId, int hours, String actor) {
        safely("PIN setup " + setupId, () -> {
            Settings s = settings();
            if (s == null) {
                return;
            }
            if (s.ussdCode() == null) {
                LOG.warn("PIN setup SMS not queued: no USSD code in the SMS settings");
                return;
            }
            queue(customerId, "PIN_SETUP_READY", Map.of("ussd", s.ussdCode(), "hours", String.valueOf(hours)),
                    "pin:ready:" + setupId, null, actor);
        });
    }

    /** PIN_SET, PIN_CHANGED or PIN_LOCKED, once per USSD session. */
    public void pinEvent(UUID customerId, String template, String sessionId) {
        if (!List.of("PIN_SET", "PIN_CHANGED", "PIN_LOCKED").contains(template)) {
            throw new IllegalArgumentException("not a PIN template: " + template);
        }
        safely(template, () -> {
            Settings s = settings();
            if (s == null) {
                return;
            }
            String time = OffsetDateTime.now().atZoneSameInstant(s.zone()).format(TIME);
            queue(customerId, template, Map.of("time", time), "pin:" + template.toLowerCase(Locale.ROOT) + ":" + sessionId, null, "ussd");
        });
    }

    // ------------------------------------------------------------------

    /** True if a message row was written (queued or suppressed). */
    private boolean queue(UUID customerId, String template, Map<String, String> params, String dedupeKey, OffsetDateTime notBefore,
                          String actor) {
        Settings s = settings();
        if (s == null) {
            return false;
        }
        Member m = jdbc.sql("""
                SELECT c.msisdn, c.status, COALESCE(p.language, 'en'), COALESCE(p.payment_alerts, true), COALESCE(p.reminders, true)
                  FROM customer.customer c
                  LEFT JOIN notify.member_preference p ON p.tenant_id = c.tenant_id AND p.customer_id = c.customer_id
                 WHERE c.customer_id = :c
                """).param("c", customerId)
            .query((rs, i) -> new Member(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getBoolean(5)))
            .optional().orElse(null);
        if (m == null || m.msisdn() == null || !m.msisdn().matches("^254[17][0-9]{8}$")) {
            return false;
        }
        Category category = SmsTemplates.ALL.get(template).category();
        String suppressed = null;
        if (category == Category.TRANSACTIONAL && !m.paymentAlerts() || category == Category.REMINDER && !m.reminders()) {
            suppressed = "MEMBER_OPTED_OUT";
        } else if (category == Category.REMINDER && !"ACTIVE".equals(m.status())) {
            suppressed = "MEMBER_NOT_ACTIVE";
        }
        Map<String, String> all = new HashMap<>(params);
        all.put("sig", s.signature());
        String body = SmsTemplates.render(template, m.language(), all);
        int n = jdbc.sql("""
                INSERT INTO notify.sms_message (tenant_id, message_id, dedupe_key, customer_id, msisdn, template, category, language,
                    body, status, suppressed_reason, not_before, created_by)
                VALUES (tenancy.current_tenant(), :id, :key, :c, :msisdn, :template, :category, :lang, :body, :status, :why,
                    COALESCE(CAST(:notBefore AS timestamptz), clock_timestamp()), :actor)
                ON CONFLICT (tenant_id, dedupe_key) DO NOTHING
                """).param("id", UUID.randomUUID()).param("key", dedupeKey).param("c", customerId).param("msisdn", m.msisdn())
            .param("template", template).param("category", category.name()).param("lang", m.language()).param("body", body)
            .param("status", suppressed == null ? "QUEUED" : "SUPPRESSED").param("why", suppressed)
            .param("notBefore", notBefore).param("actor", actor).update();
        return n == 1;
    }

    /** Settings if SMS is switched on for the bound tenant, else null. */
    private Settings settings() {
        return jdbc.sql("""
                SELECT s.signature, s.ussd_code, s.reminder_hour, t.time_zone FROM notify.sms_settings s
                  JOIN tenancy.tenant t ON t.tenant_id = s.tenant_id WHERE s.enabled
                """)
            .query((rs, i) -> new Settings(rs.getString(1), rs.getString(2), rs.getInt(3), ZoneId.of(rs.getString(4))))
            .optional().orElse(null);
    }

    private void safely(String what, Runnable work) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("SMS messages are queued inside the business transaction");
        }
        try {
            savepoint.executeWithoutResult(status -> work.run());
        } catch (RuntimeException e) {
            LOG.error("SMS for {} not queued: {}", what, e.toString());
        }
    }

    static String money(BigDecimal amount) {
        return String.format(Locale.ENGLISH, "%,.2f", amount.setScale(2, RoundingMode.HALF_UP));
    }

    static String mask(String accountNumber) {
        return accountNumber.length() <= 4 ? accountNumber : "*" + accountNumber.substring(accountNumber.length() - 4);
    }
}
