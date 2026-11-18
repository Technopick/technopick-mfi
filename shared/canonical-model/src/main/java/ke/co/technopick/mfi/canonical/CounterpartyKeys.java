package ke.co.technopick.mfi.canonical;

import java.time.Instant;
import java.util.Optional;

/**
 * Identifiers supplied by the counterparty, kept verbatim for matching, duplicate
 * detection, reversal lookup and reconciliation.
 *
 * <p>Which of these form the uniqueness key is configured per counterparty
 * (see docs/switching/duplicate-keys.md); {@link DuplicateKey} builds the default.
 *
 * @param acquirerId           ISO 8583 field 32, or the sending participant for ISO 20022
 * @param terminalId           ISO 8583 field 41 (may be empty for ISO 20022)
 * @param stan                 ISO 8583 field 11
 * @param rrn                  ISO 8583 field 37
 * @param transmissionDateTime ISO 8583 field 7, as UTC instant
 * @param messageId            ISO 20022 MsgId
 * @param endToEndId           ISO 20022 EndToEndId
 * @param uetr                 ISO 20022 UETR where used
 */
public record CounterpartyKeys(
        String acquirerId,
        String terminalId,
        String stan,
        String rrn,
        Instant transmissionDateTime,
        String messageId,
        String endToEndId,
        String uetr) {

    public static CounterpartyKeys iso8583(
            String acquirerId, String terminalId, String stan, String rrn, Instant transmissionDateTime) {
        requireText(acquirerId, "acquirerId");
        requireText(stan, "stan");
        if (!stan.matches("\\d{6}")) {
            throw new IllegalArgumentException("STAN must be exactly 6 digits");
        }
        if (transmissionDateTime == null) {
            throw new IllegalArgumentException("transmissionDateTime is required");
        }
        return new CounterpartyKeys(acquirerId, terminalId, stan, rrn, transmissionDateTime, null, null, null);
    }

    public static CounterpartyKeys iso20022(String sendingParticipant, String messageId, String endToEndId, String uetr) {
        requireText(sendingParticipant, "sendingParticipant");
        requireText(messageId, "messageId");
        requireText(endToEndId, "endToEndId");
        return new CounterpartyKeys(sendingParticipant, null, null, null, null, messageId, endToEndId, uetr);
    }

    public Optional<String> rrnValue() {
        return Optional.ofNullable(rrn);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
