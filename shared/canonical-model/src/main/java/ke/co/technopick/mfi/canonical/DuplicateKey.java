package ke.co.technopick.mfi.canonical;

import java.time.Instant;
import java.util.Objects;

/**
 * Default duplicate-detection key for switched messages.
 *
 * <p>ISO 8583 default: (acquirer, terminal, STAN, transmission date-time, message function).
 * ISO 20022 default: (sending participant, End-to-End ID, message function).
 * RRN is deliberately not part of the default key because not every counterparty
 * guarantees its uniqueness; counterparties that do can add it via configuration.
 *
 * <p>The value is persisted in a unique index (switching.switch_txn.duplicate_key),
 * so the database, not this class, is the final guard against double processing.
 */
public record DuplicateKey(String value) {

    public DuplicateKey {
        Objects.requireNonNull(value, "value");
    }

    public static DuplicateKey of(CounterpartyKeys keys, MessageFunction function) {
        Objects.requireNonNull(keys, "keys");
        Objects.requireNonNull(function, "function");
        // A repeat is the same logical message as its original advice/reversal.
        MessageFunction logical = function == MessageFunction.REPEAT ? MessageFunction.ADVICE : function;
        if (keys.stan() != null) {
            return new DuplicateKey(String.join("|",
                    "8583",
                    keys.acquirerId(),
                    Objects.requireNonNullElse(keys.terminalId(), ""),
                    keys.stan(),
                    format(keys.transmissionDateTime()),
                    logical.name()));
        }
        return new DuplicateKey(String.join("|",
                "20022", keys.acquirerId(), keys.endToEndId(), logical.name()));
    }

    private static String format(Instant instant) {
        return Long.toString(instant.getEpochSecond());
    }
}
