package ke.co.technopick.mfi.core.identity.api;

import java.util.Collection;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless OAuth2 resource server. Every API call needs a valid JWT from the platform's OIDC
 * provider (Keycloak); roles come from the configured claim and are enforced per method with
 * {@code @PreAuthorize}. Only health probes are anonymous.
 *
 * <p>Member self-service (ADR-0033): a token with the MEMBER role is a member's token, whatever
 * else it says. It is given only ROLE_MEMBER, and it may call only {@code /api/v1/member/**}; every
 * other API path refuses it here, before any controller, so a staff endpoint can never be reached
 * by a member even if its own role check were missing.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    public static final String MEMBER = "MEMBER";

    private static final AuthenticationTrustResolver TRUST = new AuthenticationTrustResolverImpl();

    static boolean isMember(Authentication a) {
        return a.getAuthorities().stream().anyMatch(g -> ("ROLE_" + MEMBER).equals(g.getAuthority()));
    }

    @Bean
    SecurityFilterChain api(HttpSecurity http, Converter<Jwt, AbstractAuthenticationToken> jwtConverter,
                            java.util.List<AuthenticatedRequestFilter> authenticatedFilters) throws Exception {
        for (AuthenticatedRequestFilter f : authenticatedFilters) {
            http.addFilterAfter(f, org.springframework.security.web.access.intercept.AuthorizationFilter.class);
        }
        http
            .csrf(csrf -> csrf.disable()) // bearer tokens only, no cookies or browser sessions
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/v3/api-docs", "/v3/api-docs/**").hasRole("PLATFORM_ADMIN")
                .requestMatchers("/api/v1/member/**").hasRole(MEMBER)
                .requestMatchers("/api/**").access((authentication, context) -> {
                    Authentication a = authentication.get();
                    return new AuthorizationDecision(TRUST.isAuthenticated(a) && !isMember(a));
                })
                .anyRequest().denyAll())
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtConverter)));
        return http.build();
    }

    /** Maps the roles claim (list of strings) to ROLE_* authorities. */
    @Bean
    Converter<Jwt, AbstractAuthenticationToken> jwtConverter(@Value("${mfi.security.roles-claim}") String rolesClaim) {
        return jwt -> {
            List<String> roles = jwt.getClaimAsStringList(rolesClaim);
            Collection<GrantedAuthority> authorities = roles == null ? List.of()
                    : roles.contains(MEMBER) ? List.of(new SimpleGrantedAuthority("ROLE_" + MEMBER)) // never staff roles too
                    : roles.stream().<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
            return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
        };
    }
}
