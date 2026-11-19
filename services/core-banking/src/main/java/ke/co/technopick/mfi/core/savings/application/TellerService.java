package ke.co.technopick.mfi.core.savings.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
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
import ke.co.technopick.mfi.core.customer.application.CustomerService;
import ke.co.technopick.mfi.core.messaging.persistence.Outbox;
import ke.co.technopick.mfi.core.savings.persistence.JdbcDepositAccounts;
import ke.co.technopick.mfi.core.tenancy.persistence.BusinessDates;
import ke.co.technopick.mfi.core.tenancy.persistence.TenantSession;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Teller cash deposits and withdrawals (ADR-0017, ADR-0018). Each posts Dr/Cr the teller's till against
 * the account's savings control (with the account as subledger) in one transaction. Amounts above
 * the tenant's policy wait for a second person; the database decides that, and refuses an
 * overdrawing withdrawal at commit whatever the application does.
 */
@Service
public class TellerService {

    public enum Kind { DEPOSIT, WITHDRAWAL }

    public record TellerTransactionView(UUID txnId, String accountNumber, String customerNumber, Kind kind, String amount,
                                        String currency, String narrative, String status, boolean requiresApproval,
                                        String requestedBy, OffsetDateTime requestedAt, String decidedBy,
                                        String decisionReason, UUID journalEntryId, boolean replayed) { }

    private record Txn(UUID id, UUID accountId, Kind kind, BigDecimal amount, String currency, String branch,
                       String narrative, String status, String requestedBy, UUID sessionId) { }

    private final JdbcClient jdbc;
    private final TenantSession session;
    private final BusinessDates businessDates;
    private final JdbcDepositAccounts accounts;
    private final CustomerService customers;
    private final JournalPosting journal;
    private final Outbox outbox;
    private final TillService tills;

    public TellerService(JdbcClient jdbc, TenantSession session, BusinessDates businessDates, JdbcDepositAccounts accounts,
                         CustomerService customers, JournalPosting journal, Outbox outbox, TillService tills) {
        this.tills = tills;
        this.jdbc = jdbc;
        this.session = session;
        this.businessDates = businessDates;
        this.accounts = accounts;
        this.customers = customers;
        this.journal = journal;
        this.outbox = outbox;
    }

    @Transactional
    public TellerTransactionView transact(UUID tenantId, String idempotencyKey, String accountNumber, Kind kind,
                                          BigDecimal amountValue, String narrative, boolean askApproval, String actor) {
        session.bind(tenantId);
        Optional<UUID> existing = jdbc.sql("SELECT txn_id FROM savings.teller_transaction WHERE idempotency_key = :k")
            .param("k", idempotencyKey).query(UUID.class).optional();
        if (existing.isPresent()) {
            TellerTransactionView v = view(tenantId, existing.get(), true);
            if (!v.accountNumber().equals(accountNumber.strip()) || v.kind() != kind
                    || new BigDecimal(v.amount()).compareTo(amountValue) != 0) {
                throw new ConflictException("idempotency key already used for a different transaction");
            }
            return v;
        }
        JdbcDepositAccounts.AccountRow account = accounts.lockByNumber(accountNumber.strip())
                .orElseThrow(() -> new IllegalArgumentException("account " + accountNumber + " not found"));
        Money amount = money(amountValue, account.currency());
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("amount must be positive");
        }
        checkAccountAllows(account, kind);
        TillService.OpenTill till = tills.openTillOf(actor)
                .orElseThrow(() -> new IllegalArgumentException("open your till before taking or paying out cash"));
        if (!till.branchCode().equals(account.branchCode()) || !till.currency().equals(account.currency().getCurrencyCode())) {
            throw new IllegalArgumentException("your till " + till.tillCode() + " is at branch " + till.branchCode()
                    + "; the account is held at " + account.branchCode());
        }
        if (kind == Kind.WITHDRAWAL) {
            BigDecimal available = accounts.balances(account.accountNumber()).map(b -> b[2]).orElse(BigDecimal.ZERO);
            if (available.compareTo(amount.amount()) < 0) {
                throw new IllegalArgumentException("insufficient available balance (" + plain(available) + ")");
            }
            BigDecimal inTill = tills.tillCash(till.glCash());
            if (inTill.compareTo(amount.amount()) < 0) {
                throw new IllegalArgumentException("your till holds only " + plain(inTill) + "; ask for cash from the vault");
            }
        }
        BigDecimal threshold = jdbc.sql("""
                SELECT CASE :kind WHEN 'WITHDRAWAL' THEN withdrawal_approval_above ELSE deposit_approval_above END
                  FROM savings.teller_policy WHERE currency = :ccy
                """)
            .param("kind", kind.name()).param("ccy", account.currency().getCurrencyCode()).query(BigDecimal.class)
            .optional().orElseThrow(() -> new IllegalStateException("no teller policy configured for "
                    + account.currency().getCurrencyCode()));
        boolean needsApproval = askApproval || amount.amount().compareTo(threshold) > 0;
        UUID txnId = UUID.randomUUID();
        UUID entryId = needsApproval ? null : post(tenantId, txnId, account, kind, amount, till.glCash(), actor);
        jdbc.sql("""
                INSERT INTO savings.teller_transaction (tenant_id, txn_id, idempotency_key, account_id, kind, amount, currency,
                    branch_code, narrative, status, requested_by, journal_entry_id, session_id)
                VALUES (:tenant, :id, :key, :account, :kind, :amount, :ccy, :branch, :narrative, :status, :actor, :entry,
                    :session)
                """)
            .param("tenant", tenantId).param("id", txnId).param("key", idempotencyKey).param("account", account.accountId())
            .param("kind", kind.name()).param("amount", amount.amount()).param("ccy", account.currency().getCurrencyCode())
            .param("branch", account.branchCode()).param("narrative", narrative(narrative, kind))
            .param("status", needsApproval ? "PENDING_APPROVAL" : "POSTED").param("actor", actor).param("entry", entryId)
            .param("session", till.sessionId())
            .update();
        return view(tenantId, txnId, false);
    }

    @Transactional
    public TellerTransactionView decide(UUID tenantId, UUID txnId, boolean approve, String reason, String actor) {
        session.bind(tenantId);
        Txn t = jdbc.sql("""
                SELECT txn_id, account_id, kind, amount, currency, branch_code, narrative, status, requested_by, session_id
                  FROM savings.teller_transaction WHERE txn_id = :id FOR UPDATE
                """)
            .param("id", txnId)
            .query((rs, i) -> new Txn(rs.getObject("txn_id", UUID.class), rs.getObject("account_id", UUID.class),
                    Kind.valueOf(rs.getString("kind")), rs.getBigDecimal("amount"), rs.getString("currency"),
                    rs.getString("branch_code"), rs.getString("narrative"), rs.getString("status"),
                    rs.getString("requested_by"), rs.getObject("session_id", UUID.class)))
            .optional().orElseThrow(() -> new NotFoundException("teller transaction not found"));
        if (!"PENDING_APPROVAL".equals(t.status())) {
            throw new ConflictException("transaction is already " + t.status());
        }
        if (t.requestedBy().equals(actor)) {
            throw new AccessDeniedException("the teller who captured the transaction cannot approve it");
        }
        if (!approve) {
            if (reason == null || reason.strip().length() < 3) {
                throw new IllegalArgumentException("a rejection needs a reason");
            }
            jdbc.sql("""
                    UPDATE savings.teller_transaction SET status = 'REJECTED', decided_by = :actor, decision_reason = :reason
                     WHERE txn_id = :id
                    """)
                .param("actor", actor).param("reason", reason.strip()).param("id", txnId).update();
            return view(tenantId, txnId, false);
        }
        JdbcDepositAccounts.AccountRow account = accounts.lockById(t.accountId())
                .orElseThrow(() -> new IllegalStateException("account missing"));
        checkAccountAllows(account, t.kind());
        String tillGl = tills.tillGlOfSession(t.sessionId());
        if (t.kind() == Kind.WITHDRAWAL && tills.tillCash(tillGl).compareTo(t.amount()) < 0) {
            throw new IllegalArgumentException("the teller's till holds only " + plain(tills.tillCash(tillGl)));
        }
        UUID entryId = post(tenantId, txnId, account, t.kind(), money(t.amount(), account.currency()), tillGl, actor);
        jdbc.sql("""
                UPDATE savings.teller_transaction SET status = 'POSTED', decided_by = :actor, decision_reason = :reason,
                       journal_entry_id = :entry
                 WHERE txn_id = :id
                """)
            .param("actor", actor).param("reason", reason == null || reason.isBlank() ? null : reason.strip())
            .param("entry", entryId).param("id", txnId).update();
        return view(tenantId, txnId, false);
    }

    @Transactional(readOnly = true)
    public List<TellerTransactionView> pending(UUID tenantId) {
        session.bind(tenantId);
        return jdbc.sql("SELECT txn_id FROM savings.teller_transaction WHERE status = 'PENDING_APPROVAL' ORDER BY requested_at")
            .query(UUID.class).list().stream().map(id -> view(tenantId, id, false)).toList();
    }

    @Transactional(readOnly = true)
    public TellerTransactionView view(UUID tenantId, UUID txnId, boolean replayed) {
        session.bind(tenantId);
        return jdbc.sql("""
                SELECT t.txn_id, a.account_number, a.customer_ref, t.kind, t.amount, t.currency, t.narrative, t.status,
                       t.requires_approval, t.requested_by, t.requested_at, t.decided_by, t.decision_reason, t.journal_entry_id
                  FROM savings.teller_transaction t
                  JOIN savings.deposit_account a ON a.tenant_id = t.tenant_id AND a.account_id = t.account_id
                 WHERE t.txn_id = :id
                """)
            .param("id", txnId)
            .query((rs, i) -> new TellerTransactionView(rs.getObject("txn_id", UUID.class), rs.getString("account_number"),
                    rs.getString("customer_ref"), Kind.valueOf(rs.getString("kind")), plain(rs.getBigDecimal("amount")),
                    rs.getString("currency"), rs.getString("narrative"), rs.getString("status"),
                    rs.getBoolean("requires_approval"), rs.getString("requested_by"),
                    rs.getObject("requested_at", OffsetDateTime.class), rs.getString("decided_by"),
                    rs.getString("decision_reason"), rs.getObject("journal_entry_id", UUID.class), replayed))
            .optional().orElseThrow(() -> new NotFoundException("teller transaction not found"));
    }

    private void checkAccountAllows(JdbcDepositAccounts.AccountRow account, Kind kind) {
        String status = account.status();
        boolean allowed = "ACTIVE".equals(status) || (kind == Kind.DEPOSIT && "DEBIT_BLOCKED".equals(status));
        if (!allowed) {
            throw new IllegalArgumentException("account " + account.accountNumber() + " is " + status
                    + "; " + kind.name().toLowerCase() + "s are not allowed");
        }
        String customerStatus = jdbc.sql("""
                SELECT c.status FROM savings.deposit_account a
                  JOIN customer.customer c ON c.tenant_id = a.tenant_id AND c.customer_id = a.customer_id
                 WHERE a.account_id = :id
                """)
            .param("id", account.accountId()).query(String.class).optional().orElse("ACTIVE");
        if (!"ACTIVE".equals(customerStatus)) {
            throw new IllegalArgumentException("the account holder is " + customerStatus);
        }
    }

    private UUID post(UUID tenantId, UUID txnId, JdbcDepositAccounts.AccountRow account, Kind kind, Money amount,
                      String cashGl, String actor) {
        String key = "teller:" + txnId;
        UUID entryId = UUID.nameUUIDFromBytes((tenantId + "|" + key).getBytes(StandardCharsets.UTF_8));
        JournalLine cash = kind == Kind.DEPOSIT ? JournalLine.debit(cashGl, amount, account.branchCode())
                : JournalLine.credit(cashGl, amount, account.branchCode());
        JournalLine customer = JournalLine.onAccount(account.glControlAccount(),
                kind == Kind.DEPOSIT ? Side.CREDIT : Side.DEBIT, amount, account.branchCode(), account.accountId());
        journal.post(new JournalEntry(entryId, tenantId, key, businessDates.current(), "TELLER", "teller:" + txnId, null, null,
                kind == Kind.DEPOSIT ? List.of(cash, customer) : List.of(customer, cash)), actor);
        outbox.record(tenantId, kind == Kind.DEPOSIT ? "TellerDepositPosted" : "TellerWithdrawalPosted", "teller_transaction",
                txnId.toString(), "{\"txnId\":\"%s\",\"accountId\":\"%s\",\"amount\":\"%s\",\"currency\":\"%s\"}"
                        .formatted(txnId, account.accountId(), amount.amount().toPlainString(),
                                account.currency().getCurrencyCode()),
                "teller:" + txnId);
        return entryId;
    }

    private static String narrative(String text, Kind kind) {
        String n = text == null || text.isBlank() ? (kind == Kind.DEPOSIT ? "Cash deposit" : "Cash withdrawal") : text.strip();
        return n.length() > 140 ? n.substring(0, 140) : n;
    }

    private static Money money(BigDecimal value, Currency currency) {
        if (value.stripTrailingZeros().scale() > currency.getDefaultFractionDigits()) {
            throw new IllegalArgumentException("amount has too many decimal places");
        }
        return Money.of(value.setScale(currency.getDefaultFractionDigits(), RoundingMode.UNNECESSARY), currency);
    }

    private static String plain(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
