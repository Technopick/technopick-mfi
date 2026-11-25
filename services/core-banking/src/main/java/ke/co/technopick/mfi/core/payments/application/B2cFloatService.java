package ke.co.technopick.mfi.core.payments.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ke.co.technopick.mfi.core.common.ConflictException;
import ke.co.technopick.mfi.core.common.NotFoundException;
import ke.co.technopick.mfi.core.payments.domain.MpesaAccountBalance;
import ke.co.technopick.mfi.core.payments.domain.MpesaAccountBalance.SubAccount;
import ke.co.technopick.mfi.core.tenancy.persistence.TenantSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * B2C float (ADR-0039): the balance of each B2C short code's float account, asked of M-Pesa
 * (Account Balance API) once a day and on demand, compared with the ledger clearing account, the
 * finance threshold and the disbursements already approved but not yet paid.
 */
@Service
public class B2cFloatService {

    public record FloatSettings(String shortCode, String floatAccountName, String lowFloatThreshold, int dailyCheckHour,
                                String updatedBy, OffsetDateTime updatedAt) { }

    public record BalanceCheckDue(UUID checkId, String shortCode) { }

    public record FloatSubAccount(String name, String currency, String current, String available) { }

    public record BalanceCheckView(UUID checkId, String shortCode, String reason, String status, String requestedBy,
                                   OffsetDateTime requestedAt, OffsetDateTime answeredAt, String resultCode, String resultDesc,
                                   String floatAccount, String floatCurrent, String floatAvailable, String ledgerBalance,
                                   String difference, String committed, List<FloatSubAccount> accounts) { }

    public record FloatAccountView(String shortCode, String clearingAccount, FloatSettings settings, String committedNow,
                                   BalanceCheckView latest, List<String> alerts, List<BalanceCheckView> recent) { }

    private final JdbcClient jdbc;
    private final TenantSession session;
    private final Duration answerWait;
    private final Duration staleAfter;

    public B2cFloatService(JdbcClient jdbc, TenantSession session,
                           @Value("${mfi.b2c.float.answer-wait:PT15M}") Duration answerWait,
                           @Value("${mfi.b2c.float.stale-after:PT26H}") Duration staleAfter) {
        this.jdbc = jdbc;
        this.session = session;
        this.answerWait = answerWait;
        this.staleAfter = staleAfter;
    }

    @Transactional
    public FloatSettings saveSettings(UUID tenantId, String shortCode, String floatAccountName, BigDecimal threshold,
                                      int dailyCheckHour, String actor) {
        session.bind(tenantId);
        account(shortCode);
        jdbc.sql("""
                INSERT INTO payments.b2c_float_settings (tenant_id, short_code, float_account_name, low_float_threshold,
                                                         daily_check_hour, updated_by)
                VALUES (tenancy.current_tenant(), :s, :name, :t, :h, :actor)
                ON CONFLICT (tenant_id, short_code) DO UPDATE
                   SET float_account_name = EXCLUDED.float_account_name, low_float_threshold = EXCLUDED.low_float_threshold,
                       daily_check_hour = EXCLUDED.daily_check_hour, updated_by = EXCLUDED.updated_by,
                       updated_at = clock_timestamp()
                """)
            .param("s", shortCode).param("name", floatAccountName.strip()).param("t", threshold).param("h", dailyCheckHour)
            .param("actor", actor).update();
        return settings(shortCode);
    }

    @Transactional
    public BalanceCheckView request(UUID tenantId, String shortCode, String actor) {
        session.bind(tenantId);
        account(shortCode);
        boolean open = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM payments.b2c_balance_check WHERE short_code = :s AND status IN ('PENDING', 'SENT'))
                """).param("s", shortCode).query(Boolean.class).single();
        if (open) {
            throw new ConflictException("a balance check for " + shortCode + " is already waiting for M-Pesa");
        }
        UUID id = UUID.randomUUID();
        insert(id, shortCode, "MANUAL", actor);
        return check(id);
    }

    /**
     * For the integration runtime: closes checks left unanswered, opens the daily check of every
     * active account once its hour has passed (tenant time), and returns the checks to send.
     */
    @Transactional
    public List<BalanceCheckDue> due(UUID tenantId) {
        session.bind(tenantId);
        jdbc.sql("""
                UPDATE payments.b2c_balance_check SET status = 'NO_ANSWER', result_desc = 'no answer within the wait time'
                 WHERE status IN ('PENDING', 'SENT')
                   AND COALESCE(sent_at, requested_at) <= clock_timestamp() - make_interval(secs => :wait)
                """).param("wait", answerWait.toMillis() / 1000.0).update();
        ZoneId zone = ZoneId.of(jdbc.sql("SELECT time_zone FROM tenancy.tenant").query(String.class).single());
        ZonedDateTime now = ZonedDateTime.now(zone);
        record Daily(String shortCode, int hour) { }
        List<Daily> accounts = jdbc.sql("""
                SELECT a.short_code, COALESCE(s.daily_check_hour, 7)
                  FROM payments.mpesa_b2c_account a
                  LEFT JOIN payments.b2c_float_settings s ON s.tenant_id = a.tenant_id AND s.short_code = a.short_code
                 WHERE a.active
                   AND NOT EXISTS (SELECT 1 FROM payments.b2c_balance_check c
                                    WHERE c.tenant_id = a.tenant_id AND c.short_code = a.short_code
                                      AND (c.status IN ('PENDING', 'SENT') OR (c.reason = 'DAILY'
                                           AND (c.requested_at AT TIME ZONE :zone)::date = :today)))
                """).param("zone", zone.getId()).param("today", now.toLocalDate())
            .query((rs, n) -> new Daily(rs.getString(1), rs.getInt(2))).list();
        for (Daily d : accounts) {
            if (now.getHour() >= d.hour()) {
                insert(UUID.randomUUID(), d.shortCode(), "DAILY", "system");
            }
        }
        return jdbc.sql("SELECT check_id, short_code FROM payments.b2c_balance_check WHERE status = 'PENDING' ORDER BY requested_at")
            .query((rs, n) -> new BalanceCheckDue(rs.getObject(1, UUID.class), rs.getString(2))).list();
    }

    @Transactional
    public BalanceCheckView sent(UUID tenantId, UUID checkId) {
        session.bind(tenantId);
        requireStatus(checkId, "PENDING");
        jdbc.sql("UPDATE payments.b2c_balance_check SET status = 'SENT', sent_at = clock_timestamp() WHERE check_id = :id")
            .param("id", checkId).update();
        return check(checkId);
    }

    @Transactional
    public BalanceCheckView sendFailed(UUID tenantId, UUID checkId, String description) {
        session.bind(tenantId);
        requireStatus(checkId, "PENDING");
        jdbc.sql("UPDATE payments.b2c_balance_check SET status = 'SEND_FAILED', result_desc = :d WHERE check_id = :id")
            .param("d", truncate(description, 200)).param("id", checkId).update();
        return check(checkId);
    }

    @Transactional
    public BalanceCheckView noAnswer(UUID tenantId, UUID checkId) {
        session.bind(tenantId);
        BalanceCheckView c = lock(checkId);
        if ("PENDING".equals(c.status()) || "SENT".equals(c.status())) {
            jdbc.sql("UPDATE payments.b2c_balance_check SET status = 'NO_ANSWER', result_desc = 'M-Pesa queue timeout' "
                    + "WHERE check_id = :id").param("id", checkId).update();
        }
        return check(checkId);
    }

    /** Records M-Pesa's answer once, with the ledger clearing balance and the approved-but-unpaid total at that moment. */
    @Transactional
    public BalanceCheckView answer(UUID tenantId, UUID checkId, String resultCode, String resultDesc, String accountBalance) {
        session.bind(tenantId);
        BalanceCheckView c = lock(checkId);
        if ("ANSWERED".equals(c.status())) {
            return c;
        }
        if (!"PENDING".equals(c.status()) && !"SENT".equals(c.status())) {
            throw new ConflictException("check is " + c.status());
        }
        if ("PENDING".equals(c.status())) {
            jdbc.sql("UPDATE payments.b2c_balance_check SET status = 'SENT', sent_at = clock_timestamp() WHERE check_id = :id")
                .param("id", checkId).update();
        }
        String code = truncate(resultCode, 20);
        String desc = truncate(resultDesc, 200);
        if (!"0".equals(code)) {
            jdbc.sql("""
                    UPDATE payments.b2c_balance_check SET status = 'ANSWERED', answered_at = clock_timestamp(), result_code = :c,
                           result_desc = :d WHERE check_id = :id
                    """).param("c", code).param("d", desc).param("id", checkId).update();
            return check(checkId);
        }
        String accountName = settings(c.shortCode()).floatAccountName();
        List<SubAccount> accounts;
        Optional<SubAccount> floatAccount;
        try {
            accounts = MpesaAccountBalance.parse(accountBalance);
            floatAccount = MpesaAccountBalance.find(accounts, accountName);
        } catch (IllegalArgumentException e) {
            accounts = List.of();
            floatAccount = Optional.empty();
            desc = truncate("unreadable balance: " + e.getMessage(), 200);
        }
        if (floatAccount.isEmpty()) {
            jdbc.sql("""
                    UPDATE payments.b2c_balance_check SET status = 'ANSWERED', answered_at = clock_timestamp(),
                           result_code = 'UNREADABLE', result_desc = :d WHERE check_id = :id
                    """).param("d", accounts.isEmpty() ? desc : truncate("no \"" + accountName + "\" in the answer", 200))
                .param("id", checkId).update();
            return check(checkId);
        }
        StringBuilder json = new StringBuilder("[");
        for (SubAccount a : accounts) {
            if (json.length() > 1) {
                json.append(',');
            }
            json.append("{\"name\":\"").append(a.name().replace("\\", "").replace("\"", "")).append("\",\"currency\":\"")
                .append(a.currency()).append("\",\"current\":\"").append(a.current().toPlainString())
                .append("\",\"available\":\"").append(a.available().toPlainString()).append("\"}");
        }
        json.append(']');
        jdbc.sql("""
                UPDATE payments.b2c_balance_check c
                   SET status = 'ANSWERED', answered_at = clock_timestamp(), result_code = '0', result_desc = :d,
                       float_account = :fa, float_current = :cur, float_available = :avail, accounts = CAST(:json AS jsonb),
                       ledger_balance = payments.clearing_balance(c.tenant_id, a.gl_clearing,
                                            (SELECT current_date_ FROM tenancy.business_date WHERE tenant_id = c.tenant_id)),
                       committed = (SELECT COALESCE(sum(d.amount), 0) FROM payments.b2c_disbursement d
                                     WHERE d.tenant_id = c.tenant_id AND d.short_code = c.short_code
                                       AND d.status IN ('APPROVED', 'DISPATCHED', 'SUBMITTED'))
                  FROM payments.mpesa_b2c_account a
                 WHERE a.tenant_id = c.tenant_id AND a.short_code = c.short_code AND c.check_id = :id
                """)
            .param("d", desc).param("fa", floatAccount.get().name()).param("cur", floatAccount.get().current())
            .param("avail", floatAccount.get().available()).param("json", json.toString()).param("id", checkId).update();
        return check(checkId);
    }

    @Transactional(readOnly = true)
    public List<FloatAccountView> overview(UUID tenantId) {
        session.bind(tenantId);
        record Account(String shortCode, String clearing, BigDecimal committed) { }
        List<Account> accounts = jdbc.sql("""
                SELECT a.short_code, a.gl_clearing,
                       (SELECT COALESCE(sum(d.amount), 0) FROM payments.b2c_disbursement d
                         WHERE d.tenant_id = a.tenant_id AND d.short_code = a.short_code
                           AND d.status IN ('APPROVED', 'DISPATCHED', 'SUBMITTED'))
                  FROM payments.mpesa_b2c_account a WHERE a.active ORDER BY a.short_code
                """).query((rs, n) -> new Account(rs.getString(1), rs.getString(2), rs.getBigDecimal(3))).list();
        List<FloatAccountView> views = new ArrayList<>();
        for (Account a : accounts) {
            FloatSettings s = settings(a.shortCode());
            List<BalanceCheckView> recent = jdbc.sql(SELECT + " WHERE short_code = :s ORDER BY requested_at DESC LIMIT 10")
                .param("s", a.shortCode()).query(B2cFloatService::row).list();
            BalanceCheckView latest = recent.stream().filter(c -> "ANSWERED".equals(c.status()) && "0".equals(c.resultCode()))
                .findFirst().orElse(null);
            views.add(new FloatAccountView(a.shortCode(), a.clearing(), s, money(a.committed()), latest,
                    alerts(s, latest, recent, a.committed(), staleAfter), recent));
        }
        return views;
    }

    /** What finance and operations should look at, most urgent first. */
    static List<String> alerts(FloatSettings s, BalanceCheckView latest, List<BalanceCheckView> recent, BigDecimal committed,
                               Duration staleAfter) {
        List<String> alerts = new ArrayList<>();
        Optional<BalanceCheckView> lastFinished = recent.stream()
            .filter(c -> !"PENDING".equals(c.status()) && !"SENT".equals(c.status())).findFirst();
        if (latest == null) {
            alerts.add("NO_BALANCE_YET");
        } else {
            BigDecimal available = new BigDecimal(latest.floatAvailable());
            if (committed.compareTo(available) > 0) {
                alerts.add("APPROVED_EXCEEDS_FLOAT");
            }
            BigDecimal threshold = new BigDecimal(s.lowFloatThreshold());
            if (threshold.signum() > 0 && available.compareTo(threshold) < 0) {
                alerts.add("LOW_FLOAT");
            }
            if (new BigDecimal(latest.difference()).signum() != 0) {
                alerts.add("LEDGER_DIFFERENCE");
            }
            if (latest.answeredAt().isBefore(OffsetDateTime.now().minus(staleAfter))) {
                alerts.add("BALANCE_OUT_OF_DATE");
            }
        }
        if (lastFinished.isPresent() && !("ANSWERED".equals(lastFinished.get().status())
                && "0".equals(lastFinished.get().resultCode()))) {
            alerts.add("LAST_CHECK_FAILED");
        }
        return alerts;
    }

    private void insert(UUID id, String shortCode, String reason, String actor) {
        jdbc.sql("""
                INSERT INTO payments.b2c_balance_check (tenant_id, check_id, short_code, reason, requested_by)
                VALUES (tenancy.current_tenant(), :id, :s, :r, :actor)
                """).param("id", id).param("s", shortCode).param("r", reason).param("actor", actor).update();
    }

    private FloatSettings settings(String shortCode) {
        return jdbc.sql("""
                SELECT short_code, float_account_name, low_float_threshold, daily_check_hour, updated_by, updated_at
                  FROM payments.b2c_float_settings WHERE short_code = :s
                """).param("s", shortCode)
            .query((rs, n) -> new FloatSettings(rs.getString(1), rs.getString(2), money(rs.getBigDecimal(3)), rs.getInt(4),
                    rs.getString(5), rs.getObject(6, OffsetDateTime.class)))
            .optional().orElse(new FloatSettings(shortCode, "Utility Account", "0.00", 7, null, null));
    }

    private void account(String shortCode) {
        boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM payments.mpesa_b2c_account WHERE short_code = :s)")
            .param("s", shortCode).query(Boolean.class).single();
        if (!exists) {
            throw new NotFoundException("no B2C account " + shortCode);
        }
    }

    private void requireStatus(UUID checkId, String status) {
        BalanceCheckView c = lock(checkId);
        if (!status.equals(c.status())) {
            throw new ConflictException("check is " + c.status());
        }
    }

    private BalanceCheckView lock(UUID checkId) {
        return jdbc.sql(SELECT + " WHERE check_id = :id FOR UPDATE").param("id", checkId).query(B2cFloatService::row)
            .optional().orElseThrow(() -> new NotFoundException("balance check not found"));
    }

    private BalanceCheckView check(UUID checkId) {
        return jdbc.sql(SELECT + " WHERE check_id = :id").param("id", checkId).query(B2cFloatService::row).single();
    }

    private static final String SELECT = "SELECT *, accounts::text AS accounts_text FROM payments.b2c_balance_check";

    private static final java.util.regex.Pattern SUB = java.util.regex.Pattern.compile(
            "\\{\"name\": ?\"([^\"]*)\", ?\"current\": ?\"([^\"]*)\", ?\"currency\": ?\"([^\"]*)\", ?\"available\": ?\"([^\"]*)\"\\}");

    private static BalanceCheckView row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        BigDecimal current = rs.getBigDecimal("float_current");
        BigDecimal ledger = rs.getBigDecimal("ledger_balance");
        List<FloatSubAccount> subs = new ArrayList<>();
        String text = rs.getString("accounts_text");
        if (text != null) {
            var m = SUB.matcher(text);
            while (m.find()) {
                subs.add(new FloatSubAccount(m.group(1), m.group(3), m.group(2), m.group(4)));
            }
        }
        return new BalanceCheckView(rs.getObject("check_id", UUID.class), rs.getString("short_code"), rs.getString("reason"),
                rs.getString("status"), rs.getString("requested_by"), rs.getObject("requested_at", OffsetDateTime.class),
                rs.getObject("answered_at", OffsetDateTime.class), rs.getString("result_code"), rs.getString("result_desc"),
                rs.getString("float_account"), money(current), money(rs.getBigDecimal("float_available")), money(ledger),
                current == null || ledger == null ? null : money(current.subtract(ledger)), money(rs.getBigDecimal("committed")),
                subs);
    }

    private static String money(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
