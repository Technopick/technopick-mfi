package ke.co.technopick.mfi.core.member.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import ke.co.technopick.mfi.canonical.Money;
import ke.co.technopick.mfi.core.common.NotFoundException;
import ke.co.technopick.mfi.core.common.TooManyRequestsException;
import ke.co.technopick.mfi.core.lending.application.LoanOriginationService;
import ke.co.technopick.mfi.core.lending.application.LoanOriginationService.LoanScheduleView;
import ke.co.technopick.mfi.core.lending.application.LoanSettlementService;
import ke.co.technopick.mfi.core.lending.application.LoanSettlementService.SettlementQuote;
import ke.co.technopick.mfi.core.payments.application.MpesaStkService;
import ke.co.technopick.mfi.core.savings.application.SavingsAccountService;
import ke.co.technopick.mfi.core.savings.application.SavingsAccountService.SavingsAccountView;
import ke.co.technopick.mfi.core.savings.application.SavingsAccountService.SavingsStatement;
import ke.co.technopick.mfi.core.tenancy.persistence.TenantSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Member self-service (ADR-0033): a member sees and pays only into their own accounts and loans.
 * Every method starts from the member's identity in the token and checks ownership before using
 * the staff services underneath; something that is not the member's answers "not found", so a
 * member cannot even learn that another member's account number exists.
 */
@Service
public class MemberService {

    public record MemberLoan(String loanNumber, String status, String currency, String principal, String principalOutstanding,
                             LocalDate nextDueDate, String nextDueAmount) { }

    public record MemberOverview(String customerNumber, String firstName, List<SavingsAccountView> accounts, List<MemberLoan> loans) { }

    public record MemberLoanDetail(LoanScheduleView schedule, SettlementQuote payoff) { }

    public record MemberPaymentRequest(String idempotencyKey, String target, String amount) { }

    public record MemberPayment(UUID requestId, String target, String amount, String currency, String phone, String status,
                                String resultDesc, boolean replayed) { }

    private record Member(UUID customerId, String number, String firstName, String msisdn) { }

    private final JdbcClient jdbc;
    private final TenantSession session;
    private final SavingsAccountService savings;
    private final LoanOriginationService loans;
    private final LoanSettlementService settlements;
    private final MpesaStkService stk;
    private final int promptsPerWindow;
    private final int windowMinutes;

    public MemberService(JdbcClient jdbc, TenantSession session, SavingsAccountService savings, LoanOriginationService loans,
                         LoanSettlementService settlements, MpesaStkService stk,
                         @Value("${mfi.member.payment-prompts-per-window:3}") int promptsPerWindow,
                         @Value("${mfi.member.payment-prompt-window-minutes:10}") int windowMinutes) {
        this.jdbc = jdbc;
        this.session = session;
        this.savings = savings;
        this.loans = loans;
        this.settlements = settlements;
        this.stk = stk;
        this.promptsPerWindow = promptsPerWindow;
        this.windowMinutes = windowMinutes;
    }

    /** The member behind the token: must exist in the token's tenant and be ACTIVE (KYC verified, consent recorded). */
    private Member member(UUID tenantId, String customerNumber) {
        session.bind(tenantId);
        record C(UUID id, String number, String firstName, String msisdn, String status) { }
        C c = jdbc.sql("SELECT customer_id, customer_number, first_name, msisdn, status FROM customer.customer WHERE customer_number = :n")
            .param("n", customerNumber)
            .query((rs, i) -> new C(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)))
            .optional().orElseThrow(() -> new AccessDeniedException("no member for this token"));
        if (!"ACTIVE".equals(c.status())) {
            throw new AccessDeniedException("member is not active");
        }
        return new Member(c.id(), c.number(), c.firstName(), c.msisdn());
    }

    private boolean ownsAccount(Member m, String accountNumber) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM savings.deposit_account WHERE account_number = :a AND customer_id = :c)")
            .param("a", accountNumber).param("c", m.customerId()).query(Boolean.class).single();
    }

    private boolean ownsLoan(Member m, String loanNumber) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM lending.loan WHERE loan_number = :l AND customer_id = :c)")
            .param("l", loanNumber).param("c", m.customerId()).query(Boolean.class).single();
    }

    private static String m(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    @Transactional(readOnly = true)
    public MemberOverview overview(UUID tenantId, String customerNumber) {
        Member me = member(tenantId, customerNumber);
        List<MemberLoan> myLoans = jdbc.sql("""
                SELECT l.loan_number, l.status, l.currency, l.principal,
                       CASE WHEN l.status = 'ACTIVE' THEN lending.loan_principal_outstanding(l.tenant_id, l.loan_id) END,
                       n.due_date, n.amount
                  FROM lending.loan l
                  LEFT JOIN LATERAL (
                      SELECT s.due_date, s.principal + s.interest AS amount FROM lending.schedule_installment s
                       WHERE s.tenant_id = l.tenant_id AND s.loan_id = l.loan_id
                         AND s.schedule_version = (SELECT max(schedule_version) FROM lending.schedule_installment x
                                                    WHERE x.tenant_id = l.tenant_id AND x.loan_id = l.loan_id)
                         AND s.principal > COALESCE((SELECT sum(a.amount) FROM lending.repayment_allocation a
                                 JOIN lending.repayment r ON r.tenant_id = a.tenant_id AND r.repayment_id = a.repayment_id
                                WHERE r.loan_id = l.loan_id AND a.installment_no = s.installment_no AND a.component = 'PRINCIPAL'), 0)
                       ORDER BY s.due_date LIMIT 1) n ON l.status = 'ACTIVE'
                 WHERE l.customer_id = :c AND l.status IN ('ACTIVE', 'CLOSED', 'WRITTEN_OFF')
                 ORDER BY l.loan_number
                """).param("c", me.customerId())
            .query((rs, i) -> new MemberLoan(rs.getString(1), rs.getString(2), rs.getString(3), m(rs.getBigDecimal(4)),
                    m(rs.getBigDecimal(5)), rs.getObject(6, LocalDate.class), m(rs.getBigDecimal(7))))
            .list();
        return new MemberOverview(me.number(), me.firstName(), savings.ofCustomer(tenantId, me.number()), myLoans);
    }

    @Transactional(readOnly = true)
    public SavingsStatement statement(UUID tenantId, String customerNumber, String accountNumber, LocalDate from, LocalDate to) {
        Member me = member(tenantId, customerNumber);
        if (!ownsAccount(me, accountNumber)) {
            throw new NotFoundException("account not found");
        }
        return savings.statement(tenantId, accountNumber, from, to);
    }

    @Transactional(readOnly = true)
    public MemberLoanDetail loan(UUID tenantId, String customerNumber, String loanNumber) {
        Member me = member(tenantId, customerNumber);
        if (!ownsLoan(me, loanNumber)) {
            throw new NotFoundException("loan not found");
        }
        return new MemberLoanDetail(loans.schedule(tenantId, loanNumber), settlements.quote(tenantId, loanNumber));
    }

    /** An M-Pesa prompt to the member's own registered phone, paying into the member's own account or loan. */
    @Transactional
    public MemberPayment pay(UUID tenantId, String customerNumber, MemberPaymentRequest r, String actor) {
        if (r == null || r.idempotencyKey() == null || r.idempotencyKey().isBlank() || r.idempotencyKey().length() > 60
                || r.target() == null || r.amount() == null) {
            throw new IllegalArgumentException("idempotencyKey (up to 60 characters), target and amount are required");
        }
        if (!r.amount().matches("^[1-9][0-9]{0,8}$")) {
            throw new IllegalArgumentException("amount must be whole shillings, for example 500");
        }
        Member me = member(tenantId, customerNumber);
        String target = r.target().strip().toUpperCase(java.util.Locale.ROOT);
        if (!ownsAccount(me, target) && !ownsLoan(me, target)) {
            throw new NotFoundException("account or loan not found");
        }
        // The member's keys live in their own namespace, so they can never collide with or replay anyone else's.
        String key = "member:" + me.customerId() + ":" + r.idempotencyKey().strip();
        boolean replay = jdbc.sql("SELECT EXISTS (SELECT 1 FROM payments.stk_request WHERE idempotency_key = :k)")
            .param("k", key).query(Boolean.class).single();
        if (!replay) {
            int recent = jdbc.sql("""
                    SELECT count(*) FROM payments.stk_request
                     WHERE member_customer_id = :c AND requested_at > clock_timestamp() - make_interval(mins => :w)
                    """).param("c", me.customerId()).param("w", windowMinutes).query(Integer.class).single();
            if (recent >= promptsPerWindow) {
                throw new TooManyRequestsException("at most " + promptsPerWindow + " payment prompts in " + windowMinutes
                        + " minutes; please wait", 60L * windowMinutes);
            }
        }
        String currency = jdbc.sql("SELECT base_currency FROM tenancy.tenant").query(String.class).single();
        Money amount = Money.of(new BigDecimal(r.amount()).setScale(2), Currency.getInstance(currency));
        MpesaStkService.Created created = stk.request(tenantId, key, null, me.msisdn(), amount, target, actor, me.customerId());
        return payment(tenantId, me, created.requestId(), created.replayed());
    }

    @Transactional(readOnly = true)
    public MemberPayment payment(UUID tenantId, String customerNumber, UUID requestId) {
        Member me = member(tenantId, customerNumber);
        return payment(tenantId, me, requestId, false);
    }

    private MemberPayment payment(UUID tenantId, Member me, UUID requestId, boolean replayed) {
        boolean mine = jdbc.sql("SELECT EXISTS (SELECT 1 FROM payments.stk_request WHERE request_id = :id AND member_customer_id = :c)")
            .param("id", requestId).param("c", me.customerId()).query(Boolean.class).single();
        if (!mine) {
            throw new NotFoundException("payment not found");
        }
        MpesaStkService.View v = stk.view(tenantId, requestId);
        return new MemberPayment(v.requestId(), v.targetReference(), v.amount(), v.currency(), v.msisdnMasked(), v.status().name(),
                v.resultDesc(), replayed);
    }
}
