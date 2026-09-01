package io.aegiscloud.controlplane.auth;

/** The identity carried by a verified JWT, attached to the request by {@code JwtAuthFilter}. */
public record AuthenticatedUser(String id, String email, Role role) {
}
