package ke.co.technopick.mfi.core.identity.api;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Who is calling, derived only from the validated token. A tenant ID supplied in a request
 * body or header is never used for staff or customer calls.
 */
@Component
public class CurrentCaller {

    private final String tenantClaim;

    public CurrentCaller(@Value("${mfi.security.tenant-claim}") String tenantClaim) {
        this.tenantClaim = tenantClaim;
    }

    public UUID tenantId() {
        String value = token().getToken().getClaimAsString(tenantClaim);
        if (value == null) {
            throw new AccessDeniedException("token carries no tenant");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new AccessDeniedException("token tenant is not a valid id");
        }
    }

    /** Stable principal for audit columns, e.g. "user:3f2c..." or "client:switch". */
    public String actor() {
        JwtAuthenticationToken t = token();
        String clientId = t.getToken().getClaimAsString("azp");
        String subject = t.getToken().getSubject();
        return subject != null ? "user:" + subject : "client:" + clientId;
    }

    private static JwtAuthenticationToken token() {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken t) {
            return t;
        }
        throw new AccessDeniedException("no authenticated token");
    }
}
