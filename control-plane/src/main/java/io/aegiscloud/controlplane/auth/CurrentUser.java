package io.aegiscloud.controlplane.auth;

import io.aegiscloud.controlplane.web.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Reads the identity {@code JwtAuthFilter} placed in the security context. */
public final class CurrentUser {

    private CurrentUser() {
    }

    /**
     * Returns the authenticated caller.
     *
     * <p>Throws 401 rather than returning null: every call site sits behind an
     * {@code authenticated()} rule, so an absent principal means the filter chain is
     * misconfigured, and failing loudly is better than serving a request with no
     * idea who made it.
     */
    public static AuthenticatedUser get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return user;
        }
        throw ApiException.unauthorized("missing claims");
    }
}
