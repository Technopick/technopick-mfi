package ke.co.technopick.mfi.integration.sms;

/**
 * One SMS provider (ADR-0035). The provider is not chosen yet; {@link HttpSmsGateway} speaks a
 * small JSON contract that the TEST-ONLY simulator implements, and a thin adapter for the chosen
 * provider replaces it.
 */
public interface SmsGateway {

    enum Kind { ACCEPTED, REJECTED, RETRY }

    /** ACCEPTED with the provider's message id; REJECTED (will not work if repeated) or RETRY with a reason. */
    record Result(Kind kind, String providerMessageId, String code, String description) {
        static Result accepted(String id) {
            return new Result(Kind.ACCEPTED, id, null, null);
        }

        static Result failed(Kind kind, String code, String description) {
            return new Result(kind, null, code, description);
        }
    }

    Result send(String msisdn, String senderId, String text, String deliveryCallbackUrl);
}
