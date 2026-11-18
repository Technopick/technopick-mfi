package ke.co.technopick.mfi.core.savings.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import ke.co.technopick.mfi.canonical.Money;
import ke.co.technopick.mfi.core.savings.domain.AccountSnapshot;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Deposit accounts and their balances. All queries run under the bound tenant (RLS). */
@Repository
public class JdbcDepositAccounts {

    /** Static attributes needed to post against an account. */
    public record AccountRow(UUID accountId, UUID tenantId, String accountNumber, String status, Currency currency,
                             String branchCode, String glControlAccount, BigDecimal overdraftLimit) { }

    private final JdbcClient jdbc;

    public JdbcDepositAccounts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Locks the account row for the rest of the transaction ({@code FOR NO KEY UPDATE}), so
     * concurrent authorizations and debits on the same account are serialised.
     */
    public Optional<AccountRow> lockByNumber(String accountNumber) {
        return jdbc.sql("""
                SELECT account_id, tenant_id, account_number, status, currency, branch_code, gl_control_account,
                       overdraft_limit
                  FROM savings.deposit_account WHERE account_number = :number FOR NO KEY UPDATE
                """)
            .param("number", accountNumber)
            .query((rs, n) -> new AccountRow(rs.getObject("account_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                    rs.getString("account_number"), rs.getString("status"), Currency.getInstance(rs.getString("currency")),
                    rs.getString("branch_code"), rs.getString("gl_control_account"), rs.getBigDecimal("overdraft_limit")))
            .optional();
    }

    public Optional<AccountRow> lockById(UUID accountId) {
        return jdbc.sql("""
                SELECT account_id, tenant_id, account_number, status, currency, branch_code, gl_control_account,
                       overdraft_limit
                  FROM savings.deposit_account WHERE account_id = :id FOR NO KEY UPDATE
                """)
            .param("id", accountId)
            .query((rs, n) -> new AccountRow(rs.getObject("account_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                    rs.getString("account_number"), rs.getString("status"), Currency.getInstance(rs.getString("currency")),
                    rs.getString("branch_code"), rs.getString("gl_control_account"), rs.getBigDecimal("overdraft_limit")))
            .optional();
    }

    /**
     * Snapshot for authorization. withdrawnToday = today's posted debits on the account plus
     * active authorization holds placed today (both count against the daily channel limit).
     */
    public AccountSnapshot snapshot(AccountRow a, LocalDate businessDate) {
        record Bal(BigDecimal ledger, BigDecimal holds) { }
        Bal bal = jdbc.sql("SELECT ledger_balance, active_holds FROM savings.account_balance WHERE account_id = :id")
            .param("id", a.accountId())
            .query((rs, n) -> new Bal(rs.getBigDecimal("ledger_balance"), rs.getBigDecimal("active_holds")))
            .single();
        BigDecimal today = jdbc.sql("""
                SELECT COALESCE((SELECT sum(l.amount) FROM ledger.journal_line l
                                   JOIN ledger.journal_entry e ON e.tenant_id = l.tenant_id AND e.entry_id = l.entry_id
                                  WHERE l.subledger_account_id = :id AND l.side = 'D' AND e.business_date = :date), 0)
                     + COALESCE((SELECT sum(amount) FROM savings.account_hold
                                  WHERE account_id = :id AND status = 'ACTIVE' AND kind = 'AUTHORIZATION'), 0)
                """)
            .param("id", a.accountId())
            .param("date", businessDate)
            .query(BigDecimal.class)
            .single();
        Currency c = a.currency();
        return new AccountSnapshot(a.accountId(), a.tenantId(), AccountSnapshot.Status.valueOf(a.status()), c,
                money(bal.ledger(), c), money(bal.holds(), c), money(a.overdraftLimit(), c), money(today, c));
    }

    public Optional<BigDecimal[]> balances(String accountNumber) {
        return jdbc.sql("""
                SELECT ledger_balance, active_holds, available_balance FROM savings.account_balance
                 WHERE account_number = :number
                """)
            .param("number", accountNumber)
            .query((rs, n) -> new BigDecimal[] {rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3)})
            .optional();
    }

    /** numeric(19,4) values arrive at scale 4; KES amounts are whole cents, so this is exact. */
    static Money money(BigDecimal value, Currency currency) {
        return Money.of(value.setScale(currency.getDefaultFractionDigits(), java.math.RoundingMode.UNNECESSARY), currency);
    }
}
