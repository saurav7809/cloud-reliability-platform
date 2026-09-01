package io.aegiscloud.controlplane.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Authenticates users against the {@code app_user} table. */
@Repository
public class UserStore {

    /**
     * A valid bcrypt digest of an unrelated value, compared against when no user
     * matches so that a missing account and a wrong password cost the same time.
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public UserStore(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    /**
     * Verifies an email/password pair, returning null when either is wrong.
     *
     * <p>A missing user and a wrong password return the same result, and the bcrypt
     * comparison runs either way, so response timing does not reveal which accounts
     * exist.
     */
    public AuthenticatedUser authenticate(String email, String password) {
        if (email == null || password == null) {
            encoder.matches(password == null ? "" : password, DUMMY_HASH);
            return null;
        }

        List<Row> rows = jdbc.query(
                "SELECT id::text, password_hash, role FROM app_user WHERE lower(email) = lower(?)",
                (rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3)),
                email);

        if (rows.isEmpty()) {
            encoder.matches(password, DUMMY_HASH);
            return null;
        }

        Row row = rows.get(0);
        if (!encoder.matches(password, row.hash())) {
            return null;
        }
        return new AuthenticatedUser(row.id(), email, Role.valueOf(row.role()));
    }

    private record Row(String id, String hash, String role) {
    }
}
