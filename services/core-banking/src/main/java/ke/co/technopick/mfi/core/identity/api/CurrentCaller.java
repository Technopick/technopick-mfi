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
    private final String memberClaim;

    public CurrentCaller(@Value("${mfi.security.tenant-claim}") String tenantClaim,
                         @Value("${mfi.security.member-claim:customer_number}") String memberClaim) {
        this.tenantClaim = tenantClaim;
        this.memberClaim = memberClaim;
    }

    /** True for a member's own token (role MEMBER). */
    public boolean isMember() {
        return SecurityConfig.isMember(token());
    }

    /** The customer number a member's token was issued for; refused for any other caller. */
    public String memberCustomerNumber() {
        if (!isMember()) {
            throw new AccessDeniedException("not a member token");
        }
        String value = token().getToken().getClaimAsString(memberClaim);
        if (value == null || !value.matches("^CU[0-9]{2}-[0-9]{7}$")) {
            throw new AccessDeniedException("member token carries no valid customer number");
        }
        return value;
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

    /** Stable principal for audit columns, e.g. "user:3f2c...", "member:9a1b..." or "client:switch". */
    public String actor() {
        JwtAuthenticationToken t = token();
        String clientId = t.getToken().getClaimAsString("azp");
        String subject = t.getToken().getSubject();
        if (subject != null && SecurityConfig.isMember(t)) {
            return "member:" + subject;
        }
        return subject != null ? "user:" + subject : "client:" + clientId;
    }

    private static JwtAuthenticationToken token() {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken t) {
            return t;
        }
        throw new AccessDeniedException("no authenticated token");
    }
}
