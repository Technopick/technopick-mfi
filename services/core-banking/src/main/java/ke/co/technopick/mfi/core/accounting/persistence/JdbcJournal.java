package ke.co.technopick.mfi.core.accounting.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ke.co.technopick.mfi.canonical.Money;
import ke.co.technopick.mfi.core.accounting.domain.JournalEntry;
import ke.co.technopick.mfi.core.accounting.domain.JournalLine;
import ke.co.technopick.mfi.core.accounting.domain.Side;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The only writer of ledger tables. Posting happens inside the caller's transaction; the
 * database re-checks balance, periods, subledger rules and available balance at commit.
 */
@Repository
public class JdbcJournal implements ke.co.technopick.mfi.core.accounting.application.JournalPosting {

    private final JdbcClient jdbc;

    public JdbcJournal(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void post(JournalEntry entry, String actor) {
        jdbc.sql("""
                INSERT INTO ledger.journal_entry (tenant_id, entry_id, idempotency_key, business_date, source,
                    correlation_id, switch_reference, reverses_entry_id, actor)
                VALUES (:tenant, :entry, :key, :date, :source, :corr, :switchRef, :reverses, :actor)
                """)
            .param("tenant", entry.tenantId())
            .param("entry", entry.entryId())
            .param("key", entry.idempotencyKey())
            .param("date", entry.businessDate())
            .param("source", entry.source())
            .param("corr", entry.correlationId())
            .param("switchRef", entry.switchReference())
            .param("reverses", entry.reversesEntryId())
            .param("actor", actor)
            .update();
        int lineNo = 1;
        for (JournalLine line : entry.lines()) {
            jdbc.sql("""
                    INSERT INTO ledger.journal_line (tenant_id, entry_id, line_no, account_code, side, amount,
                        currency, branch_code, narrative, subledger_account_id)
                    VALUES (:tenant, :entry, :lineNo, :account, :side, :amount, :ccy, :branch, :narrative, :sub)
                    """)
                .param("tenant", entry.tenantId())
                .param("entry", entry.entryId())
                .param("lineNo", lineNo++)
                .param("account", line.glAccountCode())
                .param("side", line.side() == Side.DEBIT ? "D" : "C")
                .param("amount", line.amount().amount())
                .param("ccy", line.amount().currency().getCurrencyCode())
                .param("branch", line.branchCode())
                .param("narrative", line.narrative())
                .param("sub", line.subledgerAccountId())
                .update();
        }
    }

    /** Loads a posted entry (used to build exact reversals). */
    public Optional<JournalEntry> find(UUID entryId) {
        record Header(UUID tenantId, String idempotencyKey, LocalDate businessDate, String source,
                      String correlationId, String switchReference, UUID reversesEntryId) { }
        Optional<Header> header = jdbc.sql("""
                SELECT tenant_id, idempotency_key, business_date, source, correlation_id, switch_reference,
                       reverses_entry_id
                  FROM ledger.journal_entry WHERE entry_id = :entry
                """)
            .param("entry", entryId)
            .query((rs, n) -> new Header(rs.getObject("tenant_id", UUID.class), rs.getString("idempotency_key"),
                    rs.getObject("business_date", LocalDate.class), rs.getString("source"),
                    rs.getString("correlation_id"), rs.getString("switch_reference"),
                    rs.getObject("reverses_entry_id", UUID.class)))
            .optional();
        if (header.isEmpty()) {
            return Optional.empty();
        }
        List<JournalLine> lines = jdbc.sql("""
                SELECT account_code, side, amount, currency, branch_code, narrative, subledger_account_id
                  FROM ledger.journal_line WHERE entry_id = :entry ORDER BY line_no
                """)
            .param("entry", entryId)
            .query((rs, n) -> new JournalLine(rs.getString("account_code"),
                    "D".equals(rs.getString("side")) ? Side.DEBIT : Side.CREDIT,
                    money(rs.getBigDecimal("amount"), Currency.getInstance(rs.getString("currency"))),
                    rs.getString("branch_code"), rs.getString("narrative"),
                    rs.getObject("subledger_account_id", UUID.class)))
            .list();
        Header h = header.get();
        return Optional.of(new JournalEntry(entryId, h.tenantId(), h.idempotencyKey(), h.businessDate(), h.source(),
                h.correlationId(), h.switchReference(), h.reversesEntryId(), lines));
    }

    /** numeric(19,4) arrives at scale 4; ledger amounts are whole minor units, so this is exact. */
    private static Money money(BigDecimal value, Currency currency) {
        return Money.of(value.setScale(currency.getDefaultFractionDigits(), java.math.RoundingMode.UNNECESSARY), currency);
    }
}
