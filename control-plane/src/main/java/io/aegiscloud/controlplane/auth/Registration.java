package io.aegiscloud.controlplane.auth;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What a new account has to satisfy before it exists.
 *
 * <p>Pure, so the rules can be read in one place and tested without a database. Every
 * rule here is a decision about who gets to reach a platform that can scale, restart
 * and deliberately break production workloads, so each says why it exists rather than
 * only what it rejects.
 */
public final class Registration {

    /**
     * Deliberately permissive. Address syntax is not a security control — the only
     * check that proves an address is real is sending mail to it, which this
     * platform does not do. Rejecting valid-but-unusual addresses would be strictness
     * that costs users and buys nothing.
     */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s.]+\\.[^@\\s]+$");

    /**
     * Twelve characters, and no composition rules.
     *
     * <p>Length beats character classes: "Passw0rd!" satisfies every upper-lower-digit
     * -symbol rule ever written and is on every cracking list, while a long ordinary
     * phrase is not. NIST dropped composition requirements for exactly this reason.
     */
    static final int MINIMUM_PASSWORD_LENGTH = 12;

    /** Long enough to be identifiable in an audit trail, short enough to fit a column. */
    static final int MAX_ORGANISATION_NAME = 120;

    private Registration() {
    }

    /** @param reason empty when the request is acceptable */
    public record Verdict(boolean valid, String reason) {

        static Verdict ok() {
            return new Verdict(true, "");
        }

        static Verdict reject(String reason) {
            return new Verdict(false, reason);
        }
    }

    public static Verdict validate(String email, String password, String organisationName) {
        if (email == null || !EMAIL.matcher(email.trim()).matches()) {
            return Verdict.reject("a valid email address is required");
        }

        if (password == null || password.length() < MINIMUM_PASSWORD_LENGTH) {
            return Verdict.reject("the password must be at least "
                    + MINIMUM_PASSWORD_LENGTH + " characters; length matters more than "
                    + "symbols, so a memorable phrase is fine");
        }

        if (password.trim().isEmpty()) {
            return Verdict.reject("the password must not be only whitespace");
        }

        // A password containing the email is the most common way a long password is
        // still a guessable one.
        String localPart = email.trim().toLowerCase(Locale.ROOT).split("@")[0];
        if (localPart.length() >= 4
                && password.toLowerCase(Locale.ROOT).contains(localPart)) {
            return Verdict.reject("the password must not contain your email address");
        }

        if (organisationName == null || organisationName.isBlank()) {
            return Verdict.reject("an organisation name is required");
        }

        if (organisationName.length() > MAX_ORGANISATION_NAME) {
            return Verdict.reject("the organisation name must be at most "
                    + MAX_ORGANISATION_NAME + " characters");
        }

        return Verdict.ok();
    }

    /** Addresses are compared and stored lowercased, so one person cannot hold two accounts. */
    public static String normaliseEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
