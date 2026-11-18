package ke.co.technopick.mfi.core.payments.application;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ke.co.technopick.mfi.canonical.AccountRef;
import ke.co.technopick.mfi.canonical.Money;
import ke.co.technopick.mfi.canonical.ResponseReason;
import ke.co.technopick.mfi.canonical.TransactionRequest;
import ke.co.technopick.mfi.core.accounting.domain.JournalEntry;
import ke.co.technopick.mfi.core.accounting.domain.JournalLine;
import ke.co.technopick.mfi.core.accounting.domain.Side;
import ke.co.technopick.mfi.core.accounting.persistence.JdbcJournal;
import ke.co.technopick.mfi.core.common.ConflictException;
import ke.co.technopick.mfi.core.common.NotFoundException;
import ke.co.technopick.mfi.core.payments.domain.AuthorizationDecider;
import ke.co.technopick.mfi.core.payments.domain.AuthorizationDecision;
import ke.co.technopick.mfi.core.payments.persistence.JdbcChannelConfig;
import ke.co.technopick.mfi.core.payments.persistence.JdbcChannelConfig.ChannelConfig;
import ke.co.technopick.mfi.core.savings.domain.AccountHold;
import ke.co.technopick.mfi.core.savings.domain.AccountSnapshot;
import ke.co.technopick.mfi.core.savings.persistence.JdbcDepositAccounts;
import ke.co.technopick.mfi.core.savings.persistence.JdbcDepositAccounts.AccountRow;
import ke.co.technopick.mfi.core.savings.persistence.JdbcHolds;
import ke.co.technopick.mfi.core.tenancy.persistence.BusinessDates;
import ke.co.technopick.mfi.core.tenancy.persistence.TenantSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authorization port used by the switch (design section 6): authorize places a hold,
 * completion posts the debit and captures the hold, reversal releases or reverses.
 * Each call is one database transaction holding the account lock, so the decision, the hold
 * and the posting can never disagree. All three are idempotent on their keys.
 */
@Service
public class AuthorizationService {

    public record AuthorizationResult(ResponseReason reason, UUID holdId, Money holdAmount, Money availableBalance,
                                      boolean replayed) { }

    public record CompletionResult(UUID holdId, UUID entryId, boolean replayed) { }

    public enum ReversalOutcome { RELEASED, REVERSED, ALREADY_RELEASED }

    public record ReversalResult(UUID holdId, ReversalOutcome outcome, UUID reversalEntryId, boolean replayed) { }

    private final TenantSession session;
    private final BusinessDates businessDates;
    private final JdbcDepositAccounts accounts;
    private final JdbcHolds holds;
    private final JdbcChannelConfig channels;
    private final JdbcJournal journal;

    public AuthorizationService(TenantSession session, BusinessDates businessDates, JdbcDepositAccounts accounts,
                                JdbcHolds holds, JdbcChannelConfig channels, JdbcJournal journal) {
        this.session = session;
        this.businessDates = businessDates;
        this.accounts = accounts;
        this.holds = holds;
        this.channels = channels;
        this.journal = journal;
    }

    @Transactional
    public AuthorizationResult authorize(String channel, TransactionRequest request) {
        session.bind(request.tenantId());
        if (request.sourceAccount().kind() != AccountRef.Kind.INTERNAL_ACCOUNT) {
            return decline(ResponseReason.INVALID_TRANSACTION);
        }
        Optional<AccountRow> row = accounts.lockByNumber(request.sourceAccount().value());
        if (row.isEmpty()) {
            return decline(ResponseReason.ACCOUNT_NOT_FOUND);
        }
        Optional<ChannelConfig> config = channels.find(channel, request.amount().currency());
        if (config.isEmpty()) {
            return decline(ResponseReason.INVALID_TRANSACTION);
        }
        LocalDate businessDate = businessDates.current();
        String key = "auth:" + request.duplicateKey().value();
        // Checked after taking the account lock, so concurrent duplicates cannot both pass.
        Optional<AccountHold> existing = holds.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            AccountSnapshot now = accounts.snapshot(row.get(), businessDate);
            return new AuthorizationResult(ResponseReason.APPROVED, existing.get().holdId(), existing.get().amount(),
                    now.available(), true);
        }
        AccountSnapshot snapshot = accounts.snapshot(row.get(), businessDate);
        AuthorizationDecision decision = AuthorizationDecider.decide(request, snapshot, config.get().limits());
        if (!decision.approved() || decision.hold().isEmpty()) {
            return new AuthorizationResult(decision.reason(), null, null, decision.balanceAfter(), false);
        }
        String reference = request.keys().rrnValue().orElse(request.keys().stan());
        AccountHold hold = AccountHold.place(request.tenantId(), row.get().accountId(), AccountHold.Kind.AUTHORIZATION,
                decision.holdAmount(), reference, key,
                Instant.now().plus(Duration.ofMinutes(config.get().holdTtlMinutes())));
        holds.insert(hold);
        return new AuthorizationResult(ResponseReason.APPROVED, hold.holdId(), hold.amount(), decision.balanceAfter(), false);
    }

    @Transactional
    public CompletionResult complete(UUID tenantId, UUID holdId, String channel, Money amount, Money fee,
                                     String actor, String correlationId) {
        session.bind(tenantId);
        AccountHold hold = holds.lock(holdId).orElseThrow(() -> new NotFoundException("hold not found"));
        if (hold.status() == AccountHold.Status.CAPTURED) {
            return new CompletionResult(holdId, hold.capturedByEntryId(), true);
        }
        if (!hold.isActive()) {
            throw new ConflictException("hold is " + hold.status());
        }
        if (!amount.plus(fee).equals(hold.amount())) {
            throw new IllegalArgumentException("amount + fee must equal the held amount " + hold.amount());
        }
        AccountRow account = accounts.lockById(hold.accountId()).orElseThrow(() -> new NotFoundException("account not found"));
        ChannelConfig config = channels.find(channel, amount.currency())
                .orElseThrow(() -> new IllegalArgumentException("channel not configured: " + channel));
        String key = "complete:" + holdId;
        List<JournalLine> lines = new ArrayList<>();
        lines.add(JournalLine.onAccount(account.glControlAccount(), Side.DEBIT, hold.amount(), account.branchCode(),
                account.accountId()));
        lines.add(JournalLine.credit(config.glSettlementAccount(), amount, account.branchCode()));
        if (fee.isPositive()) {
            lines.add(JournalLine.credit(config.glFeeIncome(), fee, account.branchCode()));
        }
        JournalEntry entry = new JournalEntry(deterministicId(tenantId, key), tenantId, key, businessDates.current(),
                "SWITCH", correlationId, hold.reference(), null, lines);
        journal.post(entry, actor);
        holds.capture(holdId, entry.entryId());
        return new CompletionResult(holdId, entry.entryId(), false);
    }

    @Transactional
    public ReversalResult reverse(UUID tenantId, UUID holdId, String actor, String correlationId) {
        session.bind(tenantId);
        AccountHold hold = holds.lock(holdId).orElseThrow(() -> new NotFoundException("hold not found"));
        switch (hold.status()) {
            case ACTIVE -> {
                holds.release(holdId);
                return new ReversalResult(holdId, ReversalOutcome.RELEASED, null, false);
            }
            case RELEASED, EXPIRED -> {
                return new ReversalResult(holdId, ReversalOutcome.ALREADY_RELEASED, null, true);
            }
            case CAPTURED -> {
                String key = "reverse:" + holdId;
                UUID reversalId = deterministicId(tenantId, key);
                if (journal.find(reversalId).isPresent()) {
                    return new ReversalResult(holdId, ReversalOutcome.REVERSED, reversalId, true);
                }
                JournalEntry original = journal.find(hold.capturedByEntryId())
                        .orElseThrow(() -> new IllegalStateException("captured entry missing"));
                JournalEntry reversal = original.reversal(reversalId, key, businessDates.current(), correlationId);
                journal.post(reversal, actor);
                return new ReversalResult(holdId, ReversalOutcome.REVERSED, reversalId, false);
            }
            default -> throw new IllegalStateException("unknown hold status " + hold.status());
        }
    }

    private static AuthorizationResult decline(ResponseReason reason) {
        return new AuthorizationResult(reason, null, null, null, false);
    }

    /** Same tenant + key always gives the same entry id, so a replay can never post a second entry. */
    static UUID deterministicId(UUID tenantId, String key) {
        return UUID.nameUUIDFromBytes((tenantId + "|" + key).getBytes(StandardCharsets.UTF_8));
    }
}
