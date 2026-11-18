package ke.co.technopick.mfi.core.payments.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import ke.co.technopick.mfi.canonical.Money;
import ke.co.technopick.mfi.core.accounting.domain.JournalEntry;
import ke.co.technopick.mfi.core.accounting.domain.JournalLine;
import ke.co.technopick.mfi.core.accounting.domain.Side;
import ke.co.technopick.mfi.core.accounting.persistence.JdbcJournal;
import ke.co.technopick.mfi.core.messaging.persistence.Outbox;
import ke.co.technopick.mfi.core.tenancy.persistence.BusinessDates;
import ke.co.technopick.mfi.core.tenancy.persistence.TenantSession;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M-Pesa C2B (paybill) in core banking.
 *
 * <p>Validation answers Daraja's "may this payment proceed" question. Application posts a
 * confirmed payment exactly once (unique per tenant + TransID): to the customer's deposit account
 * when the account reference matches an account that accepts credits, otherwise to suspense as an
 * UNMATCHED item for maker-checker resolution. Either way the money is always on the ledger.
 *
 * <p>Account references are matched to deposit account numbers after trimming and upper-casing.
 * Loan repayments by loan number are added with the loan servicing API.
 */
@Service
public class MpesaC2bService {

    /** Daraja validation result codes (C2B00012 invalid account, C2B00013 invalid amount, C2B00015 short code). */
    public record Validation(String resultCode, String resultDesc) {
        public static Validation accept() {
            return new Validation("0", "Accepted");
        }
    }

    public record C2bPayment(UUID tenantId, String transId, String shortCode, Money amount, String billRef,
                             String msisdnMasked, OffsetDateTime transTime, String correlationId) { }

    public enum Outcome { APPLIED, UNMATCHED }

    public record Applied(UUID paymentId, Outcome outcome, UUID journalEntryId, String accountNumber, boolean replayed) { }

    private record Paybill(String shortCode, Currency currency, String branchCode, String glClearing, String glSuspense,
                           BigDecimal maxAmount) { }

    private record Account(UUID accountId, String accountNumber, String status, String glControl, String branchCode,
                           String currency) { }

    private final JdbcClient jdbc;
    private final TenantSession session;
    private final BusinessDates businessDates;
    private final JdbcJournal journal;
    private final Outbox outbox;

    public MpesaC2bService(JdbcClient jdbc, TenantSession session, BusinessDates businessDates, JdbcJournal journal,
                           Outbox outbox) {
        this.jdbc = jdbc;
        this.session = session;
        this.businessDates = businessDates;
        this.journal = journal;
        this.outbox = outbox;
    }

    @Transactional(readOnly = true)
    public Validation validate(UUID tenantId, String shortCode, String billRef, Money amount) {
        session.bind(tenantId);
        Optional<Paybill> paybill = paybill(shortCode);
        if (paybill.isEmpty()) {
            return new Validation("C2B00015", "Invalid short code");
        }
        if (!amount.currency().equals(paybill.get().currency()) || !amount.isPositive()
                || amount.amount().compareTo(paybill.get().maxAmount()) > 0) {
            return new Validation("C2B00013", "Invalid amount");
        }
        Optional<Account> account = account(billRef);
        if (account.isEmpty() || !acceptsCredits(account.get())) {
            return new Validation("C2B00012", "Invalid account number");
        }
        return Validation.accept();
    }

    @Transactional
    public Applied apply(C2bPayment p) {
        session.bind(p.tenantId());
        Optional<Applied> existing = existing(p.transId());
        if (existing.isPresent()) {
            return existing.get();
        }
        Paybill paybill = paybill(p.shortCode())
                .orElseThrow(() -> new IllegalArgumentException("short code not configured for tenant"));
        if (!p.amount().currency().equals(paybill.currency())) {
            throw new IllegalArgumentException("currency does not match the paybill");
        }
        Optional<Account> account = account(p.billRef()).filter(MpesaC2bService::acceptsCredits);
        String key = "mpesa-c2b:" + p.transId();
        UUID entryId = UUID.nameUUIDFromBytes((p.tenantId() + "|" + key).getBytes(StandardCharsets.UTF_8));
        UUID paymentId = UUID.nameUUIDFromBytes((p.tenantId() + "|payment|" + key).getBytes(StandardCharsets.UTF_8));
        String branch = account.map(Account::branchCode).orElse(paybill.branchCode());
        JournalLine credit = account.isPresent()
                ? JournalLine.onAccount(account.get().glControl(), Side.CREDIT, p.amount(), branch, account.get().accountId())
                : JournalLine.credit(paybill.glSuspense(), p.amount(), branch);
        journal.post(new JournalEntry(entryId, p.tenantId(), key, businessDates.current(), "MPESA", p.correlationId(),
                p.transId(), null, List.of(JournalLine.debit(paybill.glClearing(), p.amount(), branch), credit)), "provider:mpesa");
        Outcome outcome = account.isPresent() ? Outcome.APPLIED : Outcome.UNMATCHED;
        jdbc.sql("""
                INSERT INTO payments.provider_payment (tenant_id, payment_id, provider, provider_ref, short_code, amount,
                    currency, bill_ref, msisdn_masked, provider_time, status, deposit_account_id, journal_entry_id)
                VALUES (:tenant, :id, 'MPESA_C2B', :ref, :shortCode, :amount, :ccy, :billRef, :msisdn, :time, :status,
                    :account, :entry)
                """)
            .param("tenant", p.tenantId())
            .param("id", paymentId)
            .param("ref", p.transId())
            .param("shortCode", p.shortCode())
            .param("amount", p.amount().amount())
            .param("ccy", p.amount().currency().getCurrencyCode())
            .param("billRef", p.billRef())
            .param("msisdn", p.msisdnMasked())
            .param("time", p.transTime())
            .param("status", outcome.name())
            .param("account", account.map(Account::accountId).orElse(null))
            .param("entry", entryId)
            .update();
        outbox.record(p.tenantId(), "MpesaPaymentReceived", "provider_payment", paymentId.toString(),
                "{\"paymentId\":\"%s\",\"transId\":\"%s\",\"outcome\":\"%s\",\"amount\":\"%s\",\"currency\":\"%s\"%s}"
                        .formatted(paymentId, p.transId(), outcome, p.amount().amount().toPlainString(),
                                p.amount().currency().getCurrencyCode(),
                                account.map(a -> ",\"accountId\":\"" + a.accountId() + "\"").orElse("")),
                p.correlationId());
        return new Applied(paymentId, outcome, entryId, account.map(Account::accountNumber).orElse(null), false);
    }

    private Optional<Applied> existing(String transId) {
        return jdbc.sql("""
                SELECT p.payment_id, p.status, p.journal_entry_id, a.account_number
                  FROM payments.provider_payment p
                  LEFT JOIN savings.deposit_account a ON a.tenant_id = p.tenant_id AND a.account_id = p.deposit_account_id
                 WHERE p.provider = 'MPESA_C2B' AND p.provider_ref = :ref
                """)
            .param("ref", transId)
            .query((rs, n) -> new Applied(rs.getObject("payment_id", UUID.class), Outcome.valueOf(rs.getString("status")),
                    rs.getObject("journal_entry_id", UUID.class), rs.getString("account_number"), true))
            .optional();
    }

    private Optional<Paybill> paybill(String shortCode) {
        return jdbc.sql("""
                SELECT short_code, currency, branch_code, gl_clearing, gl_suspense, max_amount
                  FROM payments.mpesa_paybill WHERE short_code = :code AND active
                """)
            .param("code", shortCode)
            .query((rs, n) -> new Paybill(rs.getString("short_code"), Currency.getInstance(rs.getString("currency")),
                    rs.getString("branch_code"), rs.getString("gl_clearing"), rs.getString("gl_suspense"),
                    rs.getBigDecimal("max_amount")))
            .optional();
    }

    private Optional<Account> account(String billRef) {
        if (billRef == null || billRef.isBlank()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                SELECT account_id, account_number, status, gl_control_account, branch_code, currency
                  FROM savings.deposit_account WHERE account_number = :number
                """)
            .param("number", billRef.strip().toUpperCase(Locale.ROOT))
            .query((rs, n) -> new Account(rs.getObject("account_id", UUID.class), rs.getString("account_number"),
                    rs.getString("status"), rs.getString("gl_control_account"), rs.getString("branch_code"),
                    rs.getString("currency")))
            .optional();
    }

    private static boolean acceptsCredits(Account a) {
        return !"CLOSED".equals(a.status()) && !"BLOCKED".equals(a.status());
    }

    /** M-Pesa amounts arrive as decimal strings such as "1500.00". */
    public static Money kes(String amount, Currency currency) {
        return Money.of(new BigDecimal(amount).setScale(currency.getDefaultFractionDigits(), RoundingMode.UNNECESSARY),
                currency);
    }
}
