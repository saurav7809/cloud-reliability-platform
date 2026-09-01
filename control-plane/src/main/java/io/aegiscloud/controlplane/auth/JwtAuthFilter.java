package io.aegiscloud.controlplane.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Populates the security context from the bearer token, when one is present and valid.
 *
 * <p>The filter never rejects a request itself. An absent or unusable token simply
 * leaves the context unauthenticated, and Spring Security's entry point decides what
 * that means for the route being called — which is what keeps the public routes
 * (health, docs, login) working through the same chain as the protected ones.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    /**
     * Set when a token was supplied but could not be used, so the entry point can
     * distinguish "you sent nothing" from "what you sent is expired" instead of
     * reporting both as a missing token.
     */
    public static final String AUTH_ERROR_ATTRIBUTE = "aegiscloud.authError";

    private static final String BEARER = "Bearer ";

    private final TokenManager tokens;

    public JwtAuthFilter(TokenManager tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String presented = presentedToken(request);
        if (presented != null) {
            AuthenticatedUser user = tokens.parse(presented);

            if (user == null) {
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, "invalid or expired token");
            } else {
                // The ROLE_ prefix is what hasRole()/@PreAuthorize match on; the
                // stored role column holds the bare name.
                var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()));
                var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }

        chain.doFilter(request, response);
    }

    /**
     * The token from the Authorization header, or — for the live event stream only —
     * from a {@code token} query parameter.
     *
     * <p>The browser's EventSource cannot set request headers, so a stream consumed
     * by the dashboard has no other way to authenticate. The exception is deliberately
     * confined to {@code /stream} paths rather than being allowed everywhere: a token
     * in a URL ends up in access logs and referrers, which is an acceptable trade for
     * one long-lived read-only connection and not for the rest of the API.
     */
    private String presentedToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER)) {
            return header.substring(BEARER.length());
        }

        String query = request.getParameter("token");
        if (query != null && !query.isBlank() && request.getRequestURI().endsWith("/stream")) {
            return query;
        }

        return null;
    }
}
