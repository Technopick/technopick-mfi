package ke.co.technopick.mfi.core.audit.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import ke.co.technopick.mfi.core.audit.application.AuditTrailService;
import ke.co.technopick.mfi.core.audit.application.AuditTrailService.AuditRecord;
import ke.co.technopick.mfi.core.identity.api.AuthenticatedRequestFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Records staff API requests in the audit trail (ADR-0027): every change (POST, PUT, PATCH,
 * DELETE) and every read of customer, account, loan or audit data, whatever the outcome
 * (refused and failed requests included). Runs inside the security filter chain, after
 * authorization, so the caller is known (it is added there by the security configuration and is
 * not registered as a plain servlet filter). Only requests whose token names a tenant are recorded; integration callbacks have their
 * own stored record (provider_callback). Request bodies are never recorded; query values are kept
 * only for non-identifying parameters (dates, status, branch).
 */
@Component
public class StaffAuditFilter extends OncePerRequestFilter implements AuthenticatedRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(StaffAuditFilter.class);
    private static final Set<String> READ_AUDITED = Set.of("/api/v1/customers", "/api/v1/savings/accounts",
            "/api/v1/savings/customers", "/api/v1/lending/loans", "/api/v1/audit", "/api/v1/member");
    private static final Set<String> KEPT_QUERY_PARAMS = Set.of("from", "to", "asOf", "businessDate", "status", "branchCode",
            "method", "refusedOnly");
    private static final Pattern REQUEST_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private final AuditTrailService audit;
    private final String tenantClaim;

    public StaffAuditFilter(AuditTrailService audit, @Value("${mfi.security.tenant-claim}") String tenantClaim) {
        this.audit = audit;
        this.tenantClaim = tenantClaim;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!path.startsWith("/api/") || "OPTIONS".equals(request.getMethod()) || "HEAD".equals(request.getMethod())) {
            return true;
        }
        return "GET".equals(request.getMethod()) && READ_AUDITED.stream().noneMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        OffsetDateTime at = OffsetDateTime.now();
        String header = request.getHeader("X-Request-Id");
        String requestId = header != null && REQUEST_ID.matcher(header).matches() ? header : UUID.randomUUID().toString();
        response.setHeader("X-Request-Id", requestId);
        int status = 500;
        try {
            chain.doFilter(request, response);
            status = response.getStatus();
        } finally {
            write(request, at, status, (int) ((System.nanoTime() - started) / 1_000_000), requestId);
        }
    }

    private void write(HttpServletRequest request, OffsetDateTime at, int status, int durationMs, String requestId) {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token)) {
            return;
        }
        String tenant = token.getToken().getClaimAsString(tenantClaim);
        UUID tenantId;
        try {
            tenantId = tenant == null ? null : UUID.fromString(tenant);
        } catch (IllegalArgumentException e) {
            tenantId = null;
        }
        if (tenantId == null) {
            return;
        }
        String subject = token.getToken().getSubject();
        // Members are recorded as "member:<sub>", the same actor the services write, so a member's trail is never mixed with staff.
        String actor = subject == null ? "client:" + token.getToken().getClaimAsString("azp")
            : (token.getAuthorities().stream().anyMatch(a -> "ROLE_MEMBER".equals(a.getAuthority())) ? "member:" : "user:") + subject;
        String roles = token.getAuthorities().stream().map(GrantedAuthority::getAuthority)
            .filter(a -> a.startsWith("ROLE_")).map(a -> a.substring(5)).sorted().collect(Collectors.joining(","));
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String path = request.getRequestURI().substring(request.getContextPath().length());
        try {
            audit.record(new AuditRecord(tenantId, at, actor, roles, request.getMethod(),
                    pattern == null ? "(no route)" : pattern.toString(), path, query(request), status, durationMs,
                    request.getRemoteAddr(), requestId));
        } catch (RuntimeException e) {
            // The response has already been sent; never hide the failure.
            LOG.error("AUDIT_WRITE_FAILED request {} {} {} by {}: {}", requestId, request.getMethod(), path, actor,
                    e.toString());
        }
    }

    private static String query(HttpServletRequest request) {
        if (request.getParameterMap().isEmpty()) { // JSON APIs: parameters come from the query string only
            return null;
        }
        return request.getParameterMap().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
            .map(e -> e.getKey() + "=" + (KEPT_QUERY_PARAMS.contains(e.getKey()) ? String.join(",", e.getValue()) : "***"))
            .collect(Collectors.joining("&"));
    }
}
