package ke.co.technopick.mfi.core.payments.persistence;

import java.math.RoundingMode;
import java.util.Currency;
import java.util.Optional;
import ke.co.technopick.mfi.canonical.Money;
import ke.co.technopick.mfi.core.payments.domain.ChannelLimits;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Per-tenant channel configuration (V5): limits, settlement and fee GL accounts, hold lifetime. */
@Repository
public class JdbcChannelConfig {

    public record ChannelConfig(String channel, ChannelLimits limits, String glSettlementAccount, String glFeeIncome,
                                int holdTtlMinutes) { }

    private final JdbcClient jdbc;

    public JdbcChannelConfig(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ChannelConfig> find(String channel, Currency currency) {
        return jdbc.sql("""
                SELECT channel_code, per_txn_max, daily_max, gl_settlement_account, gl_fee_income, hold_ttl_minutes
                  FROM payments.channel_config WHERE channel_code = :channel AND currency = :ccy AND active
                """)
            .param("channel", channel)
            .param("ccy", currency.getCurrencyCode())
            .query((rs, n) -> new ChannelConfig(rs.getString("channel_code"),
                    new ChannelLimits(
                            Money.of(rs.getBigDecimal("per_txn_max").setScale(currency.getDefaultFractionDigits(), RoundingMode.UNNECESSARY), currency),
                            Money.of(rs.getBigDecimal("daily_max").setScale(currency.getDefaultFractionDigits(), RoundingMode.UNNECESSARY), currency)),
                    rs.getString("gl_settlement_account"), rs.getString("gl_fee_income"), rs.getInt("hold_ttl_minutes")))
            .optional();
    }
}
