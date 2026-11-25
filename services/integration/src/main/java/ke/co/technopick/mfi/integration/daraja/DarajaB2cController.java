package ke.co.technopick.mfi.integration.daraja;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * B2C result and queue-timeout callbacks: {@code /daraja/b2c/{token}/(result|timeout)/{disbursementId}}.
 * Authenticated like C2B/STK; the OriginatorConversationID in the body must be the disbursement id
 * in the URL (we set it when sending). Stored once per disbursement and type before acknowledging.
 */
@RestController
@RequestMapping(path = "/daraja/b2c/{token}", produces = "application/json")
public class DarajaB2cController {

    private static final Logger LOG = LoggerFactory.getLogger(DarajaB2cController.class);
    private static final Map<String, Object> ACK = Map.of("ResultCode", 0, "ResultDesc", "Accepted");

    private final CallbackGuard guard;
    private final JdbcClient jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    public DarajaB2cController(CallbackGuard guard, JdbcClient jdbc) {
        this.guard = guard;
        this.jdbc = jdbc;
    }

    @PostMapping("/result/{disbursementId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> result(@PathVariable String token, @PathVariable String disbursementId,
                                                      @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return store(token, disbursementId, body, request, "B2C_RESULT");
    }

    @PostMapping("/timeout/{disbursementId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> timeout(@PathVariable String token, @PathVariable String disbursementId,
                                                       @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return store(token, disbursementId, body, request, "B2C_TIMEOUT");
    }

    /** Transaction Status answers ({@code /status-result/{queryId}}) and their queue timeouts (ADR-0039). */
    @PostMapping("/status-result/{queryId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> statusResult(@PathVariable String token, @PathVariable String queryId,
                                                            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return storeAnswer(token, queryId, body, request, "B2C_STATUS");
    }

    @PostMapping("/status-timeout/{queryId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> statusTimeout(@PathVariable String token, @PathVariable String queryId,
                                                             @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return storeAnswer(token, queryId, body, request, "B2C_STATUS_TIMEOUT");
    }

    /** Account Balance answers ({@code /balance-result/{checkId}}) and their queue timeouts (ADR-0039). */
    @PostMapping("/balance-result/{checkId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> balanceResult(@PathVariable String token, @PathVariable String checkId,
                                                             @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return storeAnswer(token, checkId, body, request, "B2C_BALANCE");
    }

    @PostMapping("/balance-timeout/{checkId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> balanceTimeout(@PathVariable String token, @PathVariable String checkId,
                                                              @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return storeAnswer(token, checkId, body, request, "B2C_BALANCE_TIMEOUT");
    }

    /**
     * The OriginatorConversationID of these answers is the query's own, set by M-Pesa, so the
     * request id in the path (unguessable, created by core) routes the answer.
     */
    private ResponseEntity<Map<String, Object>> storeAnswer(String token, String requestId, Map<String, Object> body,
                                                            HttpServletRequest request, String type) {
        if (!guard.sourceAllowed(request.getRemoteAddr())) {
            LOG.warn("B2C {} callback from disallowed source {}", type, request.getRemoteAddr());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Optional<DarajaProperties.B2cAccount> account = guard.b2cAccountFor(token);
        if (account.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        UUID id;
        try {
            id = UUID.fromString(requestId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        if (!(body.get("Result") instanceof Map<?, ?> result) || result.get("ResultCode") == null) {
            LOG.warn("rejected malformed B2C {} callback for {}", type, id);
            return ResponseEntity.badRequest().build();
        }
        UUID callbackId = UUID.nameUUIDFromBytes(("MPESA|" + type + "|" + id).getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO integration.provider_callback (callback_id, provider, callback_type, provider_ref, tenant_id,
                    payload, route_ref)
                VALUES (:cid, 'MPESA', :type, :ref, :tenant, CAST(:payload AS jsonb), :ref)
                ON CONFLICT (provider, callback_type, provider_ref) DO NOTHING
                """)
            .param("cid", callbackId).param("type", type).param("ref", id.toString())
            .param("tenant", account.get().tenantId()).param("payload", json.writeValueAsString(body))
            .update();
        return ResponseEntity.ok(ACK);
    }

    private ResponseEntity<Map<String, Object>> store(String token, String disbursementId, Map<String, Object> body,
                                                      HttpServletRequest request, String type) {
        if (!guard.sourceAllowed(request.getRemoteAddr())) {
            LOG.warn("B2C callback from disallowed source {}", request.getRemoteAddr());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Optional<DarajaProperties.B2cAccount> account = guard.b2cAccountFor(token);
        if (account.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        UUID id;
        try {
            id = UUID.fromString(disbursementId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        if (!(body.get("Result") instanceof Map<?, ?> result)
                || !id.toString().equals(String.valueOf(result.get("OriginatorConversationID")))
                || result.get("ResultCode") == null) {
            LOG.warn("rejected malformed or misrouted B2C {} callback for {}", type, id);
            return ResponseEntity.badRequest().build();
        }
        UUID callbackId = UUID.nameUUIDFromBytes(("MPESA|" + type + "|" + id).getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO integration.provider_callback (callback_id, provider, callback_type, provider_ref, tenant_id,
                    payload, route_ref)
                VALUES (:cid, 'MPESA', :type, :ref, :tenant, CAST(:payload AS jsonb), :ref)
                ON CONFLICT (provider, callback_type, provider_ref) DO NOTHING
                """)
            .param("cid", callbackId).param("type", type).param("ref", id.toString())
            .param("tenant", account.get().tenantId()).param("payload", json.writeValueAsString(body))
            .update();
        return ResponseEntity.ok(ACK);
    }
}
