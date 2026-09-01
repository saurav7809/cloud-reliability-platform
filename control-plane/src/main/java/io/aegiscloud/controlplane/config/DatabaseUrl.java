package io.aegiscloud.controlplane.config;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Translates the libpq-style {@code DATABASE_URL} the platform is configured with
 * into the JDBC form the PostgreSQL driver expects.
 *
 * <p>The environment variable contract is shared with the Go implementation this
 * control plane replaces — {@code postgres://user:pass@host:port/db?sslmode=disable}
 * — and deployment configs, Compose files and developer shells all already carry
 * that form. Rather than force every caller to learn a second syntax, the
 * translation happens here, in one place. This mirrors {@code normalizeDSN} in the
 * Go {@code internal/db} package.
 */
public record DatabaseUrl(String jdbcUrl, String username, String password) {

    /**
     * Parses a {@code postgres://} or {@code postgresql://} URL. A value that is
     * already in {@code jdbc:} form is passed through untouched, so an operator who
     * prefers the JDBC syntax is not fought.
     *
     * @throws IllegalArgumentException if the URL cannot be understood — failing at
     *                                  startup with a clear message is better than
     *                                  connecting somewhere unintended.
     */
    public static DatabaseUrl parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("DATABASE_URL must not be empty");
        }
        String trimmed = raw.trim();

        if (trimmed.startsWith("jdbc:")) {
            return new DatabaseUrl(trimmed, null, null);
        }

        if (!trimmed.startsWith("postgres://") && !trimmed.startsWith("postgresql://")) {
            throw new IllegalArgumentException(
                    "DATABASE_URL must start with postgres://, postgresql:// or jdbc:, got: " + scrub(trimmed));
        }

        final URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("DATABASE_URL is not a valid URL: " + scrub(trimmed), e);
        }

        String host = uri.getHost();
        if (host == null) {
            throw new IllegalArgumentException("DATABASE_URL has no host: " + scrub(trimmed));
        }
        int port = uri.getPort() == -1 ? 5432 : uri.getPort();

        String database = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
        if (database.isBlank()) {
            throw new IllegalArgumentException("DATABASE_URL has no database name: " + scrub(trimmed));
        }

        String username = null;
        String password = null;
        String userInfo = uri.getUserInfo();
        if (userInfo != null && !userInfo.isBlank()) {
            int split = userInfo.indexOf(':');
            if (split >= 0) {
                username = userInfo.substring(0, split);
                password = userInfo.substring(split + 1);
            } else {
                username = userInfo;
            }
        }

        StringBuilder jdbc = new StringBuilder("jdbc:postgresql://")
                .append(host).append(':').append(port).append('/').append(database);
        if (uri.getQuery() != null && !uri.getQuery().isBlank()) {
            jdbc.append('?').append(uri.getQuery());
        }

        return new DatabaseUrl(jdbc.toString(), username, password);
    }

    /** Removes credentials so a malformed URL can be logged without leaking a password. */
    private static String scrub(String url) {
        return url.replaceAll("://[^@/]*@", "://***@");
    }
}
