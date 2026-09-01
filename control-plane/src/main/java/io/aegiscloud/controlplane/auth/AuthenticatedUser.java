package io.aegiscloud.controlplane.auth;

/**
 * The identity carried by a verified JWT, attached to the request by
 * {@code JwtAuthFilter}.
 *
 * <p>The organisation is part of the identity, not something looked up later. Every
 * query that reads tenant data scopes by this value, so a request cannot reach
 * another organisation's rows even if a handler forgets to think about it — the
 * boundary lives in the token rather than in the discipline of each call site.
 */
public record AuthenticatedUser(String id, String email, Role role, java.util.UUID orgId) {
}
