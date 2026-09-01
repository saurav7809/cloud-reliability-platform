package io.aegiscloud.controlplane.auth;

/**
 * Access levels, matching the {@code role} CHECK constraint on {@code app_user}.
 *
 * <p>VIEWER is strictly read-only. Anything that changes platform state — starting
 * with alert acknowledgement, and later every autonomous action — requires OPERATOR
 * or above.
 */
public enum Role {
    ADMIN,
    OPERATOR,
    VIEWER
}
