package io.aegiscloud.controlplane.auth;

import io.aegiscloud.controlplane.audit.AuditLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Creating accounts: self-service sign-up, and inviting colleagues.
 *
 * <p><b>Why open sign-up is safe here, and would not have been before Phase 10.</b>
 * Signing up creates a <em>new organisation</em>, and tenant isolation is enforced in
 * every query: a stranger who registers gets an empty organisation with no clusters,
 * no services and no visibility of anyone else's. Verified, not assumed — tenant B
 * holding tenant A's genuine ids gets 404 on every one of them. Before that boundary
 * existed, this endpoint would have handed any passer-by a view of the whole fleet,
 * and it would have been irresponsible to add.
 *
 * <p>It can still be switched off. A platform deployed inside one company usually
 * wants accounts created by an administrator rather than by anyone who can reach the
 * URL, so {@code aegiscloud.signup.enabled} exists and the endpoint answers 403 with
 * a reason when it is false.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final AuditLog audit;
    private final boolean signupEnabled;

    public AccountService(JdbcTemplate jdbc, PasswordEncoder encoder, AuditLog audit,
                          @Value("${aegiscloud.signup.enabled:true}") boolean signupEnabled) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.audit = audit;
        this.signupEnabled = signupEnabled;
    }

    public boolean signupEnabled() {
        return signupEnabled;
    }

    /** Raised for every rejected registration; the message is safe to show a caller. */
    public static class RegistrationRefused extends RuntimeException {
        public RegistrationRefused(String message) {
            super(message);
        }
    }

    /**
     * Creates an organisation and its first administrator.
     *
     * <p>The first account in an organisation is necessarily an ADMIN: somebody has
     * to be able to invite the second one, and an organisation whose only user cannot
     * administer it is a support ticket by construction.
     */
    @Transactional
    public AuthenticatedUser signUp(String email, String password, String organisationName) {
        if (!signupEnabled) {
            throw new RegistrationRefused(
                    "self-service sign-up is disabled on this deployment; "
                            + "ask an administrator to create your account");
        }

        Registration.Verdict verdict = Registration.validate(email, password, organisationName);
        if (!verdict.valid()) {
            throw new RegistrationRefused(verdict.reason());
        }

        String normalised = Registration.normaliseEmail(email);

        UUID orgId = jdbc.queryForObject(
                "INSERT INTO organization (name) VALUES (?) RETURNING id",
                UUID.class, organisationName.trim());

        UUID userId;
        try {
            userId = jdbc.queryForObject("""
                    INSERT INTO app_user (org_id, email, password_hash, role)
                    VALUES (?, ?, ?, 'ADMIN') RETURNING id
                    """, UUID.class, orgId, normalised, encoder.encode(password));
        } catch (DuplicateKeyException e) {
            // The email is globally unique, so this is the one case where sign-up
            // has to admit an address is taken. Enumeration is unavoidable at the
            // registration step - the alternative is accepting a registration that
            // silently did nothing - so the message is plain rather than evasive.
            // The transaction rolls back, taking the half-created organisation
            // with it.
            throw new RegistrationRefused("an account already exists for " + normalised);
        }

        // Recorded as an ENGINE action: there is no authenticated caller during
        // sign-up, and attributing it to the user being created would claim they
        // authorised something before they existed.
        audit.recordEngineAction(orgId, "SIGN_UP", "app_user", userId.toString(),
                Map.of("email", normalised, "organisation", organisationName.trim(),
                        "role", "ADMIN"));

        log.info("new organisation '{}' registered by {}", organisationName.trim(), normalised);

        return new AuthenticatedUser(userId.toString(), normalised, Role.ADMIN, orgId);
    }

    /**
     * Adds a user to the caller's organisation.
     *
     * <p>The organisation comes from the caller's token, never from the request. A
     * request-supplied organisation id would let one administrator create accounts
     * inside another tenant, which is the tenancy boundary undone by a single
     * parameter.
     */
    @Transactional
    public AuthenticatedUser invite(UUID orgId, String email, String password, Role role) {
        Registration.Verdict verdict = Registration.validate(email, password, "placeholder");
        if (!verdict.valid()) {
            throw new RegistrationRefused(verdict.reason());
        }

        String normalised = Registration.normaliseEmail(email);

        UUID userId;
        try {
            userId = jdbc.queryForObject("""
                    INSERT INTO app_user (org_id, email, password_hash, role)
                    VALUES (?, ?, ?, ?) RETURNING id
                    """, UUID.class, orgId, normalised, encoder.encode(password), role.name());
        } catch (DuplicateKeyException e) {
            throw new RegistrationRefused("an account already exists for " + normalised);
        }

        audit.recordUserAction("CREATE_USER", "app_user", userId.toString(),
                Map.of("email", normalised, "role", role.name()));

        return new AuthenticatedUser(userId.toString(), normalised, role, orgId);
    }

    public record Member(String id, String email, String role, Instant createdAt) {
    }

    /** The organisation's members. Password hashes are never selected, let alone returned. */
    public List<Member> members(UUID orgId) {
        return jdbc.query("""
                SELECT id::text, email, role, created_at FROM app_user
                WHERE org_id = ? ORDER BY created_at
                """, (rs, i) -> new Member(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getTimestamp(4).toInstant()), orgId);
    }

    /**
     * Changes a member's role.
     *
     * <p>Refuses to remove the last administrator. An organisation with no ADMIN
     * cannot invite anyone, change its own policy or set autonomy levels — it is
     * locked out of itself, and no amount of OPERATOR access recovers from it.
     */
    @Transactional
    public void changeRole(UUID orgId, UUID userId, Role role) {
        String current = jdbc.queryForList(
                        "SELECT role FROM app_user WHERE id = ? AND org_id = ?",
                        String.class, userId, orgId)
                .stream().findFirst()
                .orElseThrow(() -> new RegistrationRefused("no such user in this organisation"));

        if (Role.ADMIN.name().equals(current) && role != Role.ADMIN) {
            Integer admins = jdbc.queryForObject(
                    "SELECT count(*) FROM app_user WHERE org_id = ? AND role = 'ADMIN'",
                    Integer.class, orgId);

            if (admins != null && admins <= 1) {
                throw new RegistrationRefused(
                        "this is the organisation's only administrator; promote someone else first");
            }
        }

        jdbc.update("UPDATE app_user SET role = ? WHERE id = ? AND org_id = ?",
                role.name(), userId, orgId);

        audit.recordChange("CHANGE_ROLE", "app_user", userId.toString(),
                Map.of("role", current), Map.of("role", role.name()));
    }
}
