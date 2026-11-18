package ke.co.technopick.mfi.canonical;

/** Role of a message within a transaction's life cycle. */
public enum MessageFunction {
    /** Needs a decision now (ISO 8583 x100/x200, ISO 20022 pacs.008 inbound). */
    REQUEST,
    /** Informs of a decision already taken elsewhere (x120/x220). */
    ADVICE,
    /** Undo of a prior request (x400/x420, camt.056 + pacs.004). */
    REVERSAL,
    /** Retransmission of an advice or reversal (x221/x421); must be idempotent. */
    REPEAT
}
