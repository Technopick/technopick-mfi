package ke.co.technopick.mfi.core.identity.api;

import jakarta.servlet.Filter;

/**
 * A filter that must run inside the security filter chain, after authentication and URL
 * authorization, so the caller is known for the whole request (e.g. the staff audit trail).
 * Such filters are added to the chain by {@link SecurityConfig} and are not registered as
 * ordinary servlet filters.
 */
public interface AuthenticatedRequestFilter extends Filter {
}
