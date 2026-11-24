package ke.co.technopick.mfi.core.member.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import ke.co.technopick.mfi.core.common.TooManyRequestsException;
import ke.co.technopick.mfi.core.notification.application.NotificationService;
import ke.co.technopick.mfi.core.member.application.MemberService.MemberLoan;
import ke.co.technopick.mfi.core.member.application.MemberService.MemberOverview;
import ke.co.technopick.mfi.core.member.application.MemberService.MemberPaymentRequest;
import ke.co.technopick.mfi.core.savings.application.SavingsAccountService.SavingsAccountView;
import ke.co.technopick.mfi.core.savings.application.SavingsAccountService.SavingsStatement;
import ke.co.technopick.mfi.core.savings.application.SavingsAccountService.StatementLine;
import ke.co.technopick.mfi.core.tenancy.persistence.TenantSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * USSD menus for members (ADR-0034). The aggregator sends every key the member typed in this
 * session, joined with '*', on each request; the answer starts with "CON " (wait for more input)
 * or "END " (close the session). The member is identified by the phone the aggregator reports and
 * proves it with a PIN once per session; everything they see or pay goes through
 * {@link MemberService}, with the same ownership rules as the member API.
 *
 * <p>The typed text contains the PIN and ID number, so it is never logged, stored or echoed.
 */
@Service
public class UssdService {

    public record UssdRequest(UUID tenantId, String sessionId, String serviceCode, String phoneNumber, String text) { }

    public record UssdReply(String response) {
        public boolean ends() {
            return response.startsWith("END ");
        }
    }

    /** USSD screens are limited to 182 characters. */
    static final int MAX_SCREEN = 182;
    private static final Logger LOG = LoggerFactory.getLogger(UssdService.class);
    private static final Pattern SESSION = Pattern.compile("^[A-Za-z0-9_.:-]{1,54}$");
    private static final Pattern PIN = Pattern.compile("^[0-9]{4}$");
    private static final Pattern AMOUNT = Pattern.compile("^[1-9][0-9]{0,6}$");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String VISIT = "Please visit your branch.";
    private static final String MENU = "1. Balances\n2. Mini statement\n3. My loans\n4. Pay loan\n5. Deposit to savings\n"
            + "6. Change PIN\n0. Exit";

    private final JdbcClient jdbc;
    private final TenantSession session;
    private final TransactionTemplate tx;
    private final MemberService members;
    private final NotificationService notifications;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(10);
    private final int maxAttempts;
    private final int sessionSeconds;

    public UssdService(JdbcClient jdbc, TenantSession session, PlatformTransactionManager transactions, MemberService members,
                       NotificationService notifications,
                       @Value("${mfi.ussd.max-pin-attempts:3}") int maxAttempts,
                       @Value("${mfi.ussd.session-max-seconds:180}") int sessionSeconds) {
        this.jdbc = jdbc;
        this.session = session;
        this.tx = new TransactionTemplate(transactions);
        this.members = members;
        this.notifications = notifications;
        this.maxAttempts = maxAttempts;
        this.sessionSeconds = sessionSeconds;
    }

    /** Who is dialling, once the PIN (or nothing yet) has been dealt with. */
    private record Caller(UUID customerId, String customerNumber, String firstName, UssdReply reply) { }

    public UssdReply handle(UssdRequest r) {
        if (r == null || r.tenantId() == null || r.sessionId() == null || !SESSION.matcher(r.sessionId()).matches()
                || r.serviceCode() == null || r.serviceCode().isBlank() || r.serviceCode().length() > 20) {
            throw new IllegalArgumentException("tenantId, sessionId (up to 54 letters, digits, _.:-) and serviceCode are required");
        }
        String msisdn = msisdn(r.phoneNumber());
        String text = r.text() == null ? "" : r.text();
        if (msisdn == null || text.length() > 300) {
            throw new IllegalArgumentException("phoneNumber must be a Kenyan mobile number and text at most 300 characters");
        }
        List<String> inputs = text.isEmpty() ? List.of() : Arrays.asList(text.split("\\*", -1));
        Caller caller = tx.execute(status -> start(r, msisdn, inputs));
        if (caller.reply() != null) {
            return caller.reply();
        }
        return menu(r, caller, inputs.subList(1, inputs.size()));
    }

    // ------------------------------------------------------------------ session, identity and PIN

    private Caller start(UssdRequest r, String msisdn, List<String> inputs) {
        session.bind(r.tenantId());
        jdbc.sql("""
                INSERT INTO member.ussd_session (tenant_id, session_id, service_code, msisdn)
                VALUES (tenancy.current_tenant(), :s, :code, :m) ON CONFLICT DO NOTHING
                """).param("s", r.sessionId()).param("code", r.serviceCode()).param("m", msisdn).update();
        record S(String msisdn, UUID customerId, boolean verified, boolean expired, int requests) { }
        S s = jdbc.sql("""
                SELECT msisdn, customer_id, pin_verified, started_at < clock_timestamp() - make_interval(secs => :max), requests
                  FROM member.ussd_session WHERE session_id = :s FOR UPDATE
                """).param("s", r.sessionId()).param("max", sessionSeconds)
            .query((rs, i) -> new S(rs.getString(1), rs.getObject(2, UUID.class), rs.getBoolean(3), rs.getBoolean(4), rs.getInt(5)))
            .single();
        if (!s.msisdn().equals(msisdn)) {
            LOG.warn("USSD session {} reported with a different phone; refused", r.sessionId());
            return end("Session error. Please dial again.");
        }
        if (s.expired() || s.requests() >= 200) {
            event(r.sessionId(), s.customerId(), "SESSION_EXPIRED", null);
            return end("Your session has expired. Please dial again.");
        }
        jdbc.sql("UPDATE member.ussd_session SET requests = requests + 1, last_seen_at = clock_timestamp() WHERE session_id = :s")
            .param("s", r.sessionId()).update();

        record C(UUID id, String number, String firstName, String status, String idNumber, LocalDate born) { }
        List<C> found = jdbc.sql("""
                SELECT customer_id, customer_number, first_name, status, id_number, date_of_birth
                  FROM customer.customer WHERE msisdn = :m ORDER BY customer_number
                """).param("m", msisdn)
            .query((rs, i) -> new C(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getObject(6, LocalDate.class)))
            .list();
        List<C> active = found.stream().filter(c -> "ACTIVE".equals(c.status())).toList();
        if (active.size() != 1) {
            boolean inactive = active.isEmpty() && !found.isEmpty();
            event(r.sessionId(), inactive ? found.getFirst().id() : null, inactive ? "NOT_ACTIVE" : "NOT_REGISTERED",
                    active.size() > 1 ? "phone shared by " + active.size() + " members" : null);
            return end(inactive ? "Your membership is not active. " + VISIT
                    : "This phone number is not registered for mobile banking. " + VISIT);
        }
        C c = active.getFirst();
        if (s.customerId() == null) {
            jdbc.sql("UPDATE member.ussd_session SET customer_id = :c WHERE session_id = :s").param("c", c.id())
                .param("s", r.sessionId()).update();
        } else if (!s.customerId().equals(c.id())) {
            return end("Session error. Please dial again.");
        }

        record P(String status, String hash, int failed, boolean expired) { }
        P p = jdbc.sql("""
                SELECT status, pin_hash, failed_attempts, setup_expires_at <= clock_timestamp()
                  FROM member.pin WHERE customer_id = :c FOR UPDATE
                """).param("c", c.id())
            .query((rs, i) -> new P(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getBoolean(4))).optional().orElse(null);
        if (p == null) {
            event(r.sessionId(), c.id(), "NO_PIN", null);
            return end("Mobile banking is not set up for you yet. " + VISIT);
        }
        if ("LOCKED".equals(p.status())) {
            event(r.sessionId(), c.id(), "PIN_LOCKED", null);
            return end("Your PIN is locked after too many wrong attempts. " + VISIT);
        }
        if ("SETUP_OPEN".equals(p.status())) {
            if (p.expired()) {
                event(r.sessionId(), c.id(), "SETUP_EXPIRED", null);
                return end("Your PIN setup has expired. " + VISIT);
            }
            return setup(r.sessionId(), c.id(), c.idNumber(), c.born(), msisdn, p.failed(), inputs);
        }
        // ACTIVE
        if (inputs.isEmpty()) {
            return con("Welcome " + c.firstName() + ".\nEnter your PIN:");
        }
        if (!s.verified()) {
            if (!PIN.matcher(inputs.getFirst()).matches() || !bcrypt.matches(inputs.getFirst(), p.hash())) {
                return wrong(r.sessionId(), c.id(), p.failed(), "PIN_WRONG", "Wrong PIN.");
            }
            jdbc.sql("UPDATE member.pin SET failed_attempts = 0 WHERE customer_id = :c AND failed_attempts <> 0")
                .param("c", c.id()).update();
            jdbc.sql("UPDATE member.ussd_session SET pin_verified = true WHERE session_id = :s").param("s", r.sessionId()).update();
            event(r.sessionId(), c.id(), "PIN_OK", null);
        }
        return new Caller(c.id(), c.number(), c.firstName(), null);
    }

    /** First PIN or reset: the member confirms their ID number, then chooses a PIN twice. */
    private Caller setup(String sessionId, UUID customerId, String idNumber, LocalDate born, String msisdn, int failed,
                         List<String> inputs) {
        if (inputs.isEmpty()) {
            return con("Welcome to mobile banking.\nTo set your PIN, enter your ID number:");
        }
        if (!sameId(inputs.getFirst(), idNumber)) {
            return wrong(sessionId, customerId, failed, "ID_WRONG", "The ID number does not match our records.");
        }
        if (inputs.size() == 1) {
            return con("Choose a 4-digit PIN:");
        }
        String pin = inputs.get(1);
        String weak = weakness(pin, born, msisdn);
        if (weak != null) {
            return end(weak + " Please dial again.");
        }
        if (inputs.size() == 2) {
            return con("Enter the PIN again:");
        }
        if (!pin.equals(inputs.get(2))) {
            return end("The PINs do not match. Please dial again.");
        }
        jdbc.sql("""
                UPDATE member.pin SET status = 'ACTIVE', pin_hash = :h, pin_set_at = clock_timestamp(), failed_attempts = 0
                 WHERE customer_id = :c
                """).param("h", bcrypt.encode(pin)).param("c", customerId).update();
        event(sessionId, customerId, "PIN_SET", null);
        notifications.pinEvent(customerId, "PIN_SET", sessionId);
        return end("Your PIN is set. Dial again to use mobile banking. Never share your PIN.");
    }

    private Caller wrong(String sessionId, UUID customerId, int failed, String event, String message) {
        int now = failed + 1;
        if (now >= maxAttempts) {
            jdbc.sql("UPDATE member.pin SET failed_attempts = :f, status = 'LOCKED', locked_at = clock_timestamp() WHERE customer_id = :c")
                .param("f", Math.min(now, 10)).param("c", customerId).update();
            event(sessionId, customerId, event, null);
            event(sessionId, customerId, "PIN_LOCKED", null);
            notifications.pinEvent(customerId, "PIN_LOCKED", sessionId);
            return end(message + " Your PIN is now locked. " + VISIT);
        }
        jdbc.sql("UPDATE member.pin SET failed_attempts = :f WHERE customer_id = :c").param("f", now).param("c", customerId).update();
        event(sessionId, customerId, event, null);
        int left = maxAttempts - now;
        return end(message + " " + left + (left == 1 ? " attempt" : " attempts") + " left before your PIN is locked.");
    }

    /** Why a PIN is too easy to guess, or null. */
    static String weakness(String pin, LocalDate born, String msisdn) {
        if (!PIN.matcher(pin).matches()) {
            return "The PIN must be 4 digits.";
        }
        boolean same = pin.chars().distinct().count() == 1;
        boolean up = true;
        boolean down = true;
        for (int i = 1; i < 4; i++) {
            up &= pin.charAt(i) - pin.charAt(i - 1) == 1;
            down &= pin.charAt(i) - pin.charAt(i - 1) == -1;
        }
        if (same || up || down) {
            return "That PIN is too easy to guess.";
        }
        if ((born != null && pin.equals(String.valueOf(born.getYear()))) || msisdn.endsWith(pin)) {
            return "Do not use your birth year or phone number as a PIN.";
        }
        return null;
    }

    private static boolean sameId(String typed, String idNumber) {
        String a = typed.replace(" ", "").toUpperCase(Locale.ROOT);
        String b = idNumber == null ? "" : idNumber.replace(" ", "").toUpperCase(Locale.ROOT);
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ menus (after the PIN)

    private UssdReply menu(UssdRequest r, Caller c, List<String> m) {
        UUID t = r.tenantId();
        if (m.isEmpty()) {
            return reply("CON " + fit(MENU));
        }
        return switch (m.getFirst()) {
            case "1" -> balances(r, c);
            case "2" -> statement(r, c, m);
            case "3" -> loans(r, c);
            case "4" -> pay(r, c, m, activeLoans(members.overview(t, c.customerNumber())).stream().map(MemberLoan::loanNumber).toList(),
                    "loan");
            case "5" -> pay(r, c, m, activeAccounts(members.overview(t, c.customerNumber())).stream()
                    .map(SavingsAccountView::accountNumber).toList(), "savings account");
            case "6" -> changePin(r, c, m);
            case "0" -> reply("END Thank you for banking with us.");
            default -> reply("END Invalid choice. Please dial again.");
        };
    }

    private UssdReply balances(UssdRequest r, Caller c) {
        List<SavingsAccountView> accounts = activeAccounts(members.overview(r.tenantId(), c.customerNumber()));
        record(r, c, "BALANCES", null);
        if (accounts.isEmpty()) {
            return reply("END You have no savings accounts.");
        }
        StringBuilder b = new StringBuilder("Available balances:");
        for (SavingsAccountView a : accounts) {
            b.append('\n').append(a.productName()).append(' ').append(tail(a.accountNumber())).append(": ")
                .append(a.currency()).append(' ').append(money(a.availableBalance()));
        }
        return reply("END " + fit(b.toString()));
    }

    private UssdReply statement(UssdRequest r, Caller c, List<String> m) {
        List<SavingsAccountView> accounts = activeAccounts(members.overview(r.tenantId(), c.customerNumber()));
        if (accounts.isEmpty()) {
            return reply("END You have no savings accounts.");
        }
        String account;
        if (accounts.size() == 1) {
            account = accounts.getFirst().accountNumber();
        } else if (m.size() == 1) {
            return reply("CON " + fit(choose("Choose account:", accounts.stream().map(SavingsAccountView::accountNumber).toList())));
        } else {
            account = pick(accounts.stream().map(SavingsAccountView::accountNumber).toList(), m.get(1));
            if (account == null) {
                return reply("END Invalid choice. Please dial again.");
            }
        }
        SavingsStatement st = members.statement(r.tenantId(), c.customerNumber(), account, null, null);
        record(r, c, "STATEMENT", account);
        List<StatementLine> lines = st.lines();
        StringBuilder b = new StringBuilder("Last transactions ").append(tail(account)).append(':');
        for (StatementLine l : lines.subList(Math.max(0, lines.size() - 5), lines.size()).reversed()) {
            boolean credit = l.credit() != null && new BigDecimal(l.credit()).signum() > 0;
            b.append('\n').append(l.date().format(DAY)).append(credit ? " +" : " -").append(money(credit ? l.credit() : l.debit()));
        }
        if (lines.isEmpty()) {
            b.append("\nNone in the last 30 days.");
        }
        b.append("\nBalance ").append(st.currency()).append(' ').append(money(st.closingBalance()));
        return reply("END " + fit(b.toString()));
    }

    private UssdReply loans(UssdRequest r, Caller c) {
        List<MemberLoan> active = activeLoans(members.overview(r.tenantId(), c.customerNumber()));
        record(r, c, "LOANS", null);
        if (active.isEmpty()) {
            return reply("END You have no active loans.");
        }
        StringBuilder b = new StringBuilder();
        for (MemberLoan l : active) {
            var detail = members.loan(r.tenantId(), c.customerNumber(), l.loanNumber());
            b.append(b.isEmpty() ? "" : "\n").append(l.loanNumber());
            if (l.nextDueDate() != null) {
                b.append("\nNext: ").append(l.currency()).append(' ').append(money(l.nextDueAmount())).append(" by ")
                    .append(l.nextDueDate().format(DATE));
            }
            b.append("\nTo clear today: ").append(l.currency()).append(' ').append(money(detail.payoff().stillToPay()));
        }
        return reply("END " + fit(b.toString()));
    }

    /** Choose a target (if more than one), type an amount, confirm; then an M-Pesa prompt goes to the member's phone. */
    private UssdReply pay(UssdRequest r, Caller c, List<String> m, List<String> targets, String what) {
        if (targets.isEmpty()) {
            return reply("END You have no " + (what.equals("loan") ? "active loans" : "savings accounts") + " to pay into.");
        }
        int next = 1;
        String target;
        if (targets.size() == 1) {
            target = targets.getFirst();
        } else {
            if (m.size() == 1) {
                return reply("CON " + fit(choose("Choose " + what + ":", targets)));
            }
            target = pick(targets, m.get(1));
            if (target == null) {
                return reply("END Invalid choice. Please dial again.");
            }
            next = 2;
        }
        if (m.size() == next) {
            return reply("CON Enter amount to pay to " + target + " (KES):");
        }
        String amount = m.get(next);
        if (!AMOUNT.matcher(amount).matches()) {
            return reply("END Enter a whole amount in shillings, for example 500. Please dial again.");
        }
        if (m.size() == next + 1) {
            return reply("CON Pay KES " + money(amount) + " to " + target + " from M-Pesa?\n1. Confirm\n2. Cancel");
        }
        if (!"1".equals(m.get(next + 1))) {
            return reply("END Payment cancelled.");
        }
        try {
            // One payment per session: a repeated confirmation in the same session is the same prompt.
            var p = members.pay(r.tenantId(), c.customerNumber(), new MemberPaymentRequest("ussd:" + r.sessionId(), target, amount),
                    "ussd:" + c.customerNumber());
            record(r, c, "PAYMENT_PROMPT", target + " " + p.amount() + (p.replayed() ? " (repeat)" : ""));
            return reply("END " + fit("Enter your M-Pesa PIN on the prompt sent to your phone to pay KES " + money(amount) + " to "
                    + target + ". Your " + what + " is credited when M-Pesa confirms."));
        } catch (TooManyRequestsException e) {
            record(r, c, "PAYMENT_REFUSED", "too many prompts");
            return reply("END You have asked for several payments in a short time. Please try again in a few minutes.");
        } catch (RuntimeException e) {
            record(r, c, "PAYMENT_REFUSED", e.getClass().getSimpleName());
            LOG.warn("USSD payment prompt for {} refused: {}", target, e.getClass().getSimpleName());
            return reply("END We could not start the payment. Please try again later.");
        }
    }

    private UssdReply changePin(UssdRequest r, Caller c, List<String> m) {
        if (m.size() == 1) {
            return reply("CON Enter your new 4-digit PIN:");
        }
        String pin = m.get(1);
        String msisdn = msisdn(r.phoneNumber());
        LocalDate born = tx.execute(s -> {
            session.bind(r.tenantId());
            return jdbc.sql("SELECT date_of_birth FROM customer.customer WHERE customer_id = :c").param("c", c.customerId())
                .query(LocalDate.class).single();
        });
        String weak = weakness(pin, born, msisdn);
        if (weak != null) {
            return reply("END " + weak + " Please dial again.");
        }
        if (m.size() == 2) {
            return reply("CON Enter the new PIN again:");
        }
        if (!pin.equals(m.get(2))) {
            return reply("END The PINs do not match. Please dial again.");
        }
        tx.executeWithoutResult(s -> {
            session.bind(r.tenantId());
            int changed = jdbc.sql("""
                    UPDATE member.pin SET pin_hash = :h, pin_set_at = clock_timestamp() WHERE customer_id = :c AND status = 'ACTIVE'
                    """).param("h", bcrypt.encode(pin)).param("c", c.customerId()).update();
            if (changed != 1) {
                throw new IllegalStateException("PIN is no longer active");
            }
            event(r.sessionId(), c.customerId(), "PIN_CHANGED", null);
            notifications.pinEvent(c.customerId(), "PIN_CHANGED", r.sessionId());
        });
        return reply("END Your PIN has been changed. Never share your PIN.");
    }

    // ------------------------------------------------------------------ helpers

    private static List<SavingsAccountView> activeAccounts(MemberOverview o) {
        return o.accounts().stream().filter(a -> "ACTIVE".equals(a.status())).toList();
    }

    private static List<MemberLoan> activeLoans(MemberOverview o) {
        return o.loans().stream().filter(l -> "ACTIVE".equals(l.status())).toList();
    }

    private static String choose(String title, List<String> options) {
        StringBuilder b = new StringBuilder(title);
        for (int i = 0; i < options.size() && i < 9; i++) {
            b.append('\n').append(i + 1).append(". ").append(options.get(i));
        }
        return b.toString();
    }

    private static String pick(List<String> options, String typed) {
        if (typed.matches("^[1-9]$")) {
            int i = Integer.parseInt(typed) - 1;
            return i < options.size() ? options.get(i) : null;
        }
        return null;
    }

    private static String tail(String accountNumber) {
        return accountNumber.length() <= 4 ? accountNumber : "*" + accountNumber.substring(accountNumber.length() - 4);
    }

    static String money(String amount) {
        if (amount == null) {
            return "0.00";
        }
        return String.format(Locale.ENGLISH, "%,.2f", new BigDecimal(amount));
    }

    static String fit(String s) {
        return s.length() <= MAX_SCREEN - 4 ? s : s.substring(0, MAX_SCREEN - 7) + "...";
    }

    /** Kenyan mobile numbers as stored: 2547XXXXXXXX or 2541XXXXXXXX. */
    static String msisdn(String phone) {
        if (phone == null) {
            return null;
        }
        String p = phone.replaceAll("[\\s-]", "");
        if (p.startsWith("+")) {
            p = p.substring(1);
        }
        if (p.matches("^0[17][0-9]{8}$")) {
            p = "254" + p.substring(1);
        }
        return p.matches("^254[17][0-9]{8}$") ? p : null;
    }

    private void record(UssdRequest r, Caller c, String event, String detail) {
        tx.executeWithoutResult(s -> {
            session.bind(r.tenantId());
            event(r.sessionId(), c.customerId(), event, detail);
        });
    }

    private void event(String sessionId, UUID customerId, String event, String detail) {
        jdbc.sql("""
                INSERT INTO member.ussd_event (tenant_id, session_id, customer_id, event, detail)
                VALUES (tenancy.current_tenant(), :s, :c, :e, :d)
                """).param("s", sessionId).param("c", customerId).param("e", event)
            .param("d", detail == null ? null : detail.length() > 200 ? detail.substring(0, 200) : detail).update();
    }

    private static UssdReply reply(String s) {
        return new UssdReply(s);
    }

    private static Caller con(String s) {
        return new Caller(null, null, null, new UssdReply("CON " + fit(s)));
    }

    private static Caller end(String s) {
        return new Caller(null, null, null, new UssdReply("END " + fit(s)));
    }
}
