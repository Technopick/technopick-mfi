package ke.co.technopick.mfi.core.lending.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ke.co.technopick.mfi.canonical.Money;
import ke.co.technopick.mfi.core.accounting.application.JournalPosting;
import ke.co.technopick.mfi.core.accounting.domain.JournalEntry;
import ke.co.technopick.mfi.core.accounting.domain.JournalLine;
import ke.co.technopick.mfi.core.accounting.domain.Side;
import ke.co.technopick.mfi.core.common.ConflictException;
import ke.co.technopick.mfi.core.common.NotFoundException;
import ke.co.technopick.mfi.core.tenancy.persistence.BusinessDates;
import ke.co.technopick.mfi.core.tenancy.persistence.TenantSession;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Calling on guarantors when a loan defaults, and lowering guarantee locks as a loan is repaid
 * (ADR-0044). A call shares the amount among the loan's open guarantees in proportion to their
 * locks; approval moves each share from the guarantor's savings through the recoveries clearing
 * account into the loan as a repayment. The database re-checks every rule (V41).
 */
@Service
public class GuarantorCallService {

    public record GuaranteeSettingsView(String recoveryClearingAccount, int minDaysPastDue, boolean releaseAsRepaid, String updatedBy,
                                        OffsetDateTime updatedAt) { }

    public record LoanGuaranteeLine(UUID guaranteeId, String guarantorNumber, String guarantorName, String accountNumber,
                                    String pledged, String locked, String recovered, String status) { }

    /** A loan's guarantees, how far past due it is and what may be called. */
    public record LoanGuarantees(String loanNumber, String borrowerNumber, String loanStatus, int daysPastDue, int minDaysPastDue,
                                 String owed, String locked, String callable, boolean callAllowed, List<LoanGuaranteeLine> guarantees) { }

    public record GuarantorCallLineView(UUID guaranteeId, String guarantorNumber, String guarantorName, String accountNumber,
                                        String amount, boolean applied) { }

    public record GuarantorCallView(UUID callId, String loanNumber, String borrowerNumber, int daysPastDue, String amount, String reason,
                                    String status, String requestedBy, OffsetDateTime requestedAt, String decidedBy,
                                    OffsetDateTime decidedAt, String decisionReason, List<GuarantorCallLineView> lines) { }

    record Open(UUID id, UUID accountId, String control, String branch, String currency, BigDecimal amount, BigDecimal locked,
                BigDecimal recovered, UUID hold) { }

    private final JdbcClient jdbc;
    private final TenantSession session;
    private final BusinessDates businessDates;
    private final JournalPosting journal;
    private final LoanRepaymentService repayments;

    public GuarantorCallService(JdbcClient jdbc, TenantSession session, BusinessDates businessDates, JournalPosting journal,
                                LoanRepaymentService repayments) {
        this.jdbc = jdbc;
        this.session = session;
        this.businessDates = businessDates;
        this.journal = journal;
        this.repayments = repayments;
    }

    // ------------------------------------------------------------------ settings

    @Transactional(readOnly = true)
    public Optional<GuaranteeSettingsView> settings(UUID tenantId) {
        session.bind(tenantId);
        return settingsView();
    }

    @Transactional
    public GuaranteeSettingsView saveSettings(UUID tenantId, String clearing, int minDays, boolean releaseAsRepaid, String actor) {
        session.bind(tenantId);
        jdbc.sql("""
                INSERT INTO lending.guarantee_settings (tenant_id, recovery_clearing, min_days_past_due, release_as_repaid, updated_by)
                VALUES (tenancy.current_tenant(), :c, :d, :r, :a)
                ON CONFLICT (tenant_id) DO UPDATE SET recovery_clearing = EXCLUDED.recovery_clearing,
                       min_days_past_due = EXCLUDED.min_days_past_due, release_as_repaid = EXCLUDED.release_as_repaid,
                       updated_by = EXCLUDED.updated_by, updated_at = clock_timestamp()
                """).param("c", clearing).param("d", minDays).param("r", releaseAsRepaid).param("a", actor).update();
        return settingsView().orElseThrow();
    }

    private Optional<GuaranteeSettingsView> settingsView() {
        return jdbc.sql("SELECT recovery_clearing, min_days_past_due, release_as_repaid, updated_by, updated_at FROM lending.guarantee_settings")
            .query((rs, n) -> new GuaranteeSettingsView(rs.getString(1), rs.getInt(2), rs.getBoolean(3), rs.getString(4),
                    rs.getObject(5, OffsetDateTime.class)))
            .optional();
    }

    // ------------------------------------------------------------------ a loan's guarantees

    @Transactional(readOnly = true)
    public LoanGuarantees loan(UUID tenantId, String loanNumber) {
        session.bind(tenantId);
        record L(UUID id, String borrower, String status, int dpd, BigDecimal owed) { }
        L l = jdbc.sql("""
                SELECT loan_id, customer_ref, status, lending.guarantor_call_due(tenant_id, loan_id),
                       lending.loan_unpaid(tenant_id, loan_id) - lending.loan_credit(tenant_id, loan_id)
                  FROM lending.loan WHERE loan_number = :n
                """).param("n", loanNumber)
            .query((rs, n) -> new L(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getBigDecimal(5)))
            .optional().orElseThrow(() -> new NotFoundException("loan " + loanNumber + " not found"));
        List<LoanGuaranteeLine> lines = jdbc.sql("""
                SELECT g.guarantee_id, c.customer_number, c.first_name || ' ' || c.last_name, d.account_number, g.amount, g.locked_amount,
                       g.recovered, g.status
                  FROM lending.loan_guarantee g
                  JOIN lending.loan_application a ON a.tenant_id = g.tenant_id AND a.application_id = g.application_id
                  JOIN customer.customer c ON c.tenant_id = g.tenant_id AND c.customer_id = g.guarantor_customer_id
                  JOIN savings.deposit_account d ON d.tenant_id = g.tenant_id AND d.account_id = g.savings_account_id
                 WHERE a.loan_id = :l AND g.status IN ('PLEDGED', 'CALLED', 'RELEASED')
                 ORDER BY c.customer_number
                """).param("l", l.id())
            .query((rs, n) -> new LoanGuaranteeLine(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                    m(rs.getBigDecimal(5)), m(rs.getBigDecimal(6)), m(rs.getBigDecimal(7)), rs.getString(8)))
            .list();
        BigDecimal locked = lines.stream().filter(g -> "PLEDGED".equals(g.status())).map(g -> new BigDecimal(g.locked()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal owed = l.owed().max(BigDecimal.ZERO);
        Optional<GuaranteeSettingsView> s = settingsView();
        int min = s.map(GuaranteeSettingsView::minDaysPastDue).orElse(0);
        boolean allowed = s.isPresent() && "ACTIVE".equals(l.status()) && l.dpd() >= min && locked.signum() > 0 && owed.signum() > 0;
        return new LoanGuarantees(loanNumber, l.borrower(), l.status(), l.dpd(), min, m(owed), m(locked), m(locked.min(owed)), allowed,
                lines);
    }

    // ------------------------------------------------------------------ calls

    /** Asks to call on the loan's guarantors for {@code amount} (all that is callable when null), shared by lock. */
    @Transactional
    public GuarantorCallView request(UUID tenantId, String idempotencyKey, String loanNumber, BigDecimal amount, String reason,
                                     String actor) {
        session.bind(tenantId);
        Optional<UUID> replay = jdbc.sql("SELECT call_id FROM lending.guarantor_call WHERE idempotency_key = :k").param("k", idempotencyKey)
            .query(UUID.class).optional();
        if (replay.isPresent()) {
            return call(replay.get());
        }
        GuaranteeSettingsView s = settingsView()
            .orElseThrow(() -> new ConflictException("guarantor calls are not set up; finance must set the recoveries clearing account"));
        LoanGuarantees g = loan(tenantId, loanNumber);
        if (!"ACTIVE".equals(g.loanStatus())) {
            throw new ConflictException("loan " + loanNumber + " is " + g.loanStatus());
        }
        if (g.daysPastDue() < s.minDaysPastDue()) {
            throw new ConflictException("loan " + loanNumber + " is " + g.daysPastDue() + " days past due at the last close; guarantors are"
                    + " called from " + s.minDaysPastDue() + " days");
        }
        UUID loanId = jdbc.sql("SELECT loan_id FROM lending.loan WHERE loan_number = :n FOR UPDATE").param("n", loanNumber)
            .query(UUID.class).single();
        if (jdbc.sql("SELECT EXISTS (SELECT 1 FROM lending.guarantor_call WHERE loan_id = :l AND status = 'REQUESTED')")
                .param("l", loanId).query(Boolean.class).single()) {
            throw new ConflictException("a call on loan " + loanNumber + "'s guarantors is already waiting for a decision");
        }
        BigDecimal callable = new BigDecimal(g.callable());
        BigDecimal total = amount == null ? callable : amount;
        if (total.signum() <= 0) {
            throw new ConflictException("nothing can be called: " + g.locked() + " locked, " + g.owed() + " owed");
        }
        if (total.compareTo(callable) > 0) {
            throw new IllegalArgumentException("at most " + g.callable() + " can be called (" + g.locked() + " locked, " + g.owed() + " owed)");
        }
        List<LoanGuaranteeLine> open = g.guarantees().stream().filter(x -> "PLEDGED".equals(x.status()) && new BigDecimal(x.locked()).signum() > 0)
            .toList();
        BigDecimal locked = new BigDecimal(g.locked());
        UUID callId = UUID.nameUUIDFromBytes((tenantId + "|guarantor-call|" + idempotencyKey).getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO lending.guarantor_call (tenant_id, call_id, idempotency_key, loan_id, days_past_due, amount, reason, requested_by)
                VALUES (tenancy.current_tenant(), :id, :k, :l, :d, :a, :r, :by)
                """).param("id", callId).param("k", idempotencyKey).param("l", loanId).param("d", g.daysPastDue()).param("a", total)
            .param("r", reason.strip()).param("by", actor).update();
        // shares by lock, rounded to cents; the last share takes the remainder (never above its lock)
        BigDecimal given = BigDecimal.ZERO;
        for (int i = 0; i < open.size(); i++) {
            LoanGuaranteeLine x = open.get(i);
            BigDecimal lock = new BigDecimal(x.locked());
            BigDecimal share = i == open.size() - 1 ? total.subtract(given)
                    : total.multiply(lock).divide(locked, 2, RoundingMode.HALF_UP);
            share = share.min(lock);
            given = given.add(share);
            if (share.signum() > 0) {
                jdbc.sql("""
                        INSERT INTO lending.guarantor_call_line (tenant_id, call_id, guarantee_id, amount)
                        VALUES (tenancy.current_tenant(), :c, :g, :a)
                        """).param("c", callId).param("g", x.guaranteeId()).param("a", share).update();
            }
        }
        return call(callId);
    }

    @Transactional
    public GuarantorCallView decide(UUID tenantId, UUID callId, boolean approve, String reason, String actor) {
        session.bind(tenantId);
        record C(UUID loan, String status, String requestedBy, BigDecimal amount) { }
        C c = jdbc.sql("SELECT loan_id, status, requested_by, amount FROM lending.guarantor_call WHERE call_id = :id FOR UPDATE")
            .param("id", callId).query((rs, n) -> new C(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getBigDecimal(4)))
            .optional().orElseThrow(() -> new NotFoundException("guarantor call not found"));
        if (!"REQUESTED".equals(c.status())) {
            throw new ConflictException("the call is already " + c.status());
        }
        if (c.requestedBy().equals(actor)) {
            throw new AccessDeniedException("the person who asked for the call cannot decide it");
        }
        String why = reason == null || reason.isBlank() ? null : reason.strip();
        if (!approve) {
            if (why == null || why.length() < 3) {
                throw new IllegalArgumentException("a rejection needs a reason");
            }
            jdbc.sql("""
                    UPDATE lending.guarantor_call SET status = 'REJECTED', decided_by = :a, decided_at = clock_timestamp(), decision_reason = :r
                     WHERE call_id = :id
                    """).param("a", actor).param("r", why).param("id", callId).update();
            return call(callId);
        }
        String loanStatus = jdbc.sql("SELECT status FROM lending.loan WHERE loan_id = :l FOR UPDATE").param("l", c.loan())
            .query(String.class).single();
        if (!"ACTIVE".equals(loanStatus)) {
            throw new ConflictException("the loan is " + loanStatus + "; reject the call");
        }
        BigDecimal owed = jdbc.sql("SELECT lending.loan_unpaid(tenant_id, loan_id) - lending.loan_credit(tenant_id, loan_id) FROM lending.loan WHERE loan_id = :l")
            .param("l", c.loan()).query(BigDecimal.class).single();
        if (c.amount().compareTo(owed) > 0) {
            throw new ConflictException("only " + m(owed) + " is now owed; reject this call of " + m(c.amount()) + " and ask again");
        }
        GuaranteeSettingsView s = settingsView().orElseThrow();
        LocalDate today = businessDates.current();
        jdbc.sql("""
                UPDATE lending.guarantor_call SET status = 'APPROVED', decided_by = :a, decided_at = clock_timestamp(), decision_reason = :r,
                       business_date = :d WHERE call_id = :id
                """).param("a", actor).param("r", why).param("d", today).param("id", callId).update();
        record Line(UUID guarantee, BigDecimal amount) { }
        List<Line> lines = jdbc.sql("SELECT guarantee_id, amount FROM lending.guarantor_call_line WHERE call_id = :c ORDER BY guarantee_id")
            .param("c", callId).query((rs, n) -> new Line(rs.getObject(1, UUID.class), rs.getBigDecimal(2))).list();
        String loanNumber = jdbc.sql("SELECT loan_number FROM lending.loan WHERE loan_id = :l").param("l", c.loan()).query(String.class).single();
        for (Line line : lines) {
            Open g = open(line.guarantee(), true);
            if (line.amount().compareTo(g.locked()) > 0) {
                throw new ConflictException("a guarantee's lock is now " + m(g.locked()) + ", less than its share of " + m(line.amount())
                        + "; reject the call and ask again");
            }
            // the guarantee first (its lock moves before the loan can close and release the others)
            relock(tenantId, g, g.locked().subtract(line.amount()), line.amount(), "call:" + callId);
            Money money = money(line.amount(), g.currency());
            String key = "guarantor-call:" + callId + ":" + line.guarantee();
            UUID entryId = UUID.nameUUIDFromBytes((tenantId + "|" + key).getBytes(StandardCharsets.UTF_8));
            journal.post(new JournalEntry(entryId, tenantId, key, today, "LOAN", "guarantor-call:" + loanNumber, null, null, List.of(
                    JournalLine.onAccount(g.control(), Side.DEBIT, money, g.branch(), g.accountId()),
                    JournalLine.credit(s.recoveryClearingAccount(), money, g.branch()))), actor);
            LoanRepaymentService.Result paid = repayments.apply(tenantId, c.loan(), money, "INTERNAL",
                    "GUARANTOR:" + callId + ":" + line.guarantee(), s.recoveryClearingAccount(), today, "guarantor-call:" + callId, actor);
            jdbc.sql("""
                    UPDATE lending.guarantor_call_line SET applied = true, transfer_entry_id = :e, repayment_id = :r
                     WHERE call_id = :c AND guarantee_id = :g
                    """).param("e", entryId).param("r", paid.repaymentId()).param("c", callId).param("g", line.guarantee()).update();
        }
        return call(callId);
    }

    @Transactional(readOnly = true)
    public List<GuarantorCallView> calls(UUID tenantId, String status) {
        session.bind(tenantId);
        return jdbc.sql(CALL + " WHERE (CAST(:s AS text) IS NULL OR c.status = :s) ORDER BY c.requested_at DESC LIMIT 200")
            .param("s", status).query((rs, n) -> callRow(rs)).list().stream().map(this::withLines).toList();
    }

    private GuarantorCallView call(UUID id) {
        return jdbc.sql(CALL + " WHERE c.call_id = :id").param("id", id).query((rs, n) -> callRow(rs)).optional().map(this::withLines)
            .orElseThrow(() -> new NotFoundException("guarantor call not found"));
    }

    // ------------------------------------------------------------------ release as repaid (end of day)

    /** Lowers each open lock to the pledge's share of the principal still outstanding, less what was recovered. */
    @Transactional
    public int releaseAsRepaid(UUID tenantId, LocalDate date, String actor) {
        session.bind(tenantId);
        if (!settingsView().map(GuaranteeSettingsView::releaseAsRepaid).orElse(false)) {
            return 0;
        }
        record T(UUID guarantee, BigDecimal target) { }
        List<T> due = jdbc.sql("""
                SELECT g.guarantee_id,
                       GREATEST(round(g.amount * lending.loan_principal_outstanding(l.tenant_id, l.loan_id) / l.principal, 2) - g.recovered, 0)
                  FROM lending.loan_guarantee g
                  JOIN lending.loan_application a ON a.tenant_id = g.tenant_id AND a.application_id = g.application_id
                  JOIN lending.loan l ON l.tenant_id = a.tenant_id AND l.loan_id = a.loan_id
                 WHERE g.status = 'PLEDGED' AND l.status = 'ACTIVE' AND g.locked_amount > 0
                   AND GREATEST(round(g.amount * lending.loan_principal_outstanding(l.tenant_id, l.loan_id) / l.principal, 2) - g.recovered, 0)
                       < g.locked_amount
                """).query((rs, n) -> new T(rs.getObject(1, UUID.class), rs.getBigDecimal(2))).list();
        for (T t : due) {
            relock(tenantId, open(t.guarantee(), true), t.target(), BigDecimal.ZERO, "repaid:" + date);
        }
        return due.size();
    }

    // ------------------------------------------------------------------ helpers

    /** Moves a guarantee's lock to {@code newLock} on a new hold (none when nothing stays locked). */
    private void relock(UUID tenantId, Open g, BigDecimal newLock, BigDecimal recoveredNow, String why) {
        UUID hold = g.hold();
        if (newLock.signum() > 0) {
            hold = UUID.nameUUIDFromBytes((tenantId + "|guarantee-lock|" + g.id() + "|" + why).getBytes(StandardCharsets.UTF_8));
            jdbc.sql("""
                    INSERT INTO savings.account_hold (tenant_id, hold_id, account_id, kind, amount, currency, reference, idempotency_key)
                    VALUES (tenancy.current_tenant(), :id, :acc, 'EARMARK', :amt, :ccy, :ref, :key)
                    """).param("id", hold).param("acc", g.accountId()).param("amt", newLock).param("ccy", g.currency())
                .param("ref", "guarantee:" + g.id()).param("key", "guarantee-lock:" + g.id() + ":" + why).update();
        }
        boolean usedUp = newLock.signum() == 0 && recoveredNow.signum() > 0;
        jdbc.sql("""
                UPDATE lending.loan_guarantee SET locked_amount = :l, recovered = recovered + :r, current_hold_id = :h, status = :s,
                       ended_by = CASE WHEN :s = 'CALLED' THEN 'system' END,
                       end_reason = CASE WHEN :s = 'CALLED' THEN 'Called to repay the loan' END
                 WHERE guarantee_id = :g
                """).param("l", newLock).param("r", recoveredNow).param("h", hold).param("s", usedUp ? "CALLED" : "PLEDGED")
            .param("g", g.id()).update();
    }

    private Open open(UUID guaranteeId, boolean lock) {
        return jdbc.sql("""
                SELECT g.guarantee_id, g.savings_account_id, d.gl_control_account, d.branch_code, d.currency, g.amount, g.locked_amount,
                       g.recovered, g.current_hold_id
                  FROM lending.loan_guarantee g
                  JOIN savings.deposit_account d ON d.tenant_id = g.tenant_id AND d.account_id = g.savings_account_id
                 WHERE g.guarantee_id = :id AND g.status = 'PLEDGED'
                """ + (lock ? " FOR UPDATE OF g" : "")).param("id", guaranteeId)
            .query((rs, n) -> new Open(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getObject(9, UUID.class)))
            .optional().orElseThrow(() -> new ConflictException("the guarantee is no longer open"));
    }

    private static final String CALL = """
            SELECT c.*, l.loan_number, l.customer_ref FROM lending.guarantor_call c
              JOIN lending.loan l ON l.tenant_id = c.tenant_id AND l.loan_id = c.loan_id
            """;

    private static GuarantorCallView callRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new GuarantorCallView(rs.getObject("call_id", UUID.class), rs.getString("loan_number"), rs.getString("customer_ref"),
                rs.getInt("days_past_due"), m(rs.getBigDecimal("amount")), rs.getString("reason"), rs.getString("status"),
                rs.getString("requested_by"), rs.getObject("requested_at", OffsetDateTime.class), rs.getString("decided_by"),
                rs.getObject("decided_at", OffsetDateTime.class), rs.getString("decision_reason"), List.of());
    }

    private GuarantorCallView withLines(GuarantorCallView v) {
        List<GuarantorCallLineView> lines = new ArrayList<>(jdbc.sql("""
                SELECT l.guarantee_id, c.customer_number, c.first_name || ' ' || c.last_name, d.account_number, l.amount, l.applied
                  FROM lending.guarantor_call_line l
                  JOIN lending.loan_guarantee g ON g.tenant_id = l.tenant_id AND g.guarantee_id = l.guarantee_id
                  JOIN customer.customer c ON c.tenant_id = g.tenant_id AND c.customer_id = g.guarantor_customer_id
                  JOIN savings.deposit_account d ON d.tenant_id = g.tenant_id AND d.account_id = g.savings_account_id
                 WHERE l.call_id = :id ORDER BY c.customer_number
                """).param("id", v.callId())
            .query((rs, n) -> new GuarantorCallLineView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                    m(rs.getBigDecimal(5)), rs.getBoolean(6))).list());
        return new GuarantorCallView(v.callId(), v.loanNumber(), v.borrowerNumber(), v.daysPastDue(), v.amount(), v.reason(), v.status(),
                v.requestedBy(), v.requestedAt(), v.decidedBy(), v.decidedAt(), v.decisionReason(), lines);
    }

    private static Money money(BigDecimal amount, String currency) {
        Currency ccy = Currency.getInstance(currency);
        return Money.of(amount.setScale(ccy.getDefaultFractionDigits(), RoundingMode.UNNECESSARY), ccy);
    }

    private static String m(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
