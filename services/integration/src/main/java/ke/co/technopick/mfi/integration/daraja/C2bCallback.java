package ke.co.technopick.mfi.integration.daraja;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/**
 * A C2B validation or confirmation callback, after the tenant has been derived from the
 * authenticated path token. Field names follow Daraja's payload (TransID, TransAmount, ...).
 */
public record C2bCallback(UUID tenantId, String transId, String transTime, String amount, String shortCode,
                          String billRef, String msisdn, String rawJson) {

    private static final DateTimeFormatter DARAJA_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    /** Daraja timestamps are East Africa Time without an offset. */
    private static final ZoneOffset EAT = ZoneOffset.ofHours(3);

    static C2bCallback from(UUID tenantId, Map<String, Object> body, String rawJson) {
        return new C2bCallback(tenantId, text(body, "TransID"), text(body, "TransTime"), text(body, "TransAmount"),
                text(body, "BusinessShortCode"), text(body, "BillRefNumber"), text(body, "MSISDN"), rawJson);
    }

    private static String text(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v).strip();
    }

    public OffsetDateTime transTimeWithOffset() {
        return LocalDateTime.parse(transTime, DARAJA_TIME).atOffset(EAT);
    }

    /** Keeps only the first 4 and last 3 characters (Daraja may already hash or mask it). */
    public String maskedMsisdn() {
        if (msisdn == null || msisdn.length() < 8) {
            return null;
        }
        return msisdn.substring(0, 4) + "*".repeat(msisdn.length() - 7) + msisdn.substring(msisdn.length() - 3);
    }

    /** Structural checks before anything is stored or forwarded. */
    public boolean wellFormed() {
        return transId != null && transId.matches("^[A-Z0-9]{8,20}$")
                && amount != null && amount.matches("^\\d{1,13}(\\.\\d{1,2})?$")
                && shortCode != null && shortCode.matches("^[0-9]{5,10}$")
                && transTime != null && transTime.matches("^\\d{14}$");
    }
}
