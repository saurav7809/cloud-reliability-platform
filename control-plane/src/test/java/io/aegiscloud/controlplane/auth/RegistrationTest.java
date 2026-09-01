package io.aegiscloud.controlplane.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** What the platform accepts as a new account. */
class RegistrationTest {

    private static Registration.Verdict validate(String email, String password) {
        return Registration.validate(email, password, "Acme");
    }

    @Test
    @DisplayName("a reasonable registration is accepted")
    void validRegistration() {
        assertThat(validate("sam@example.com", "correct horse battery").valid()).isTrue();
    }

    @Test
    @DisplayName("a malformed address is rejected")
    void malformedEmail() {
        assertThat(validate("not-an-email", "correct horse battery").valid()).isFalse();
        assertThat(validate("no@domain", "correct horse battery").valid()).isFalse();
        assertThat(validate("two@@example.com", "correct horse battery").valid()).isFalse();
    }

    @Test
    @DisplayName("unusual but valid addresses are accepted, because syntax is not a security control")
    void unusualAddressesAreAllowed() {
        assertThat(validate("sam+aegis@example.co.uk", "correct horse battery").valid()).isTrue();
        assertThat(validate("s.a.m_1@sub.example.org", "correct horse battery").valid()).isTrue();
    }

    @Test
    @DisplayName("a short password is rejected, and the message says length is what matters")
    void shortPassword() {
        Registration.Verdict verdict = validate("sam@example.com", "Passw0rd!");

        assertThat(verdict.valid()).isFalse();
        assertThat(verdict.reason()).contains("at least 12");
        assertThat(verdict.reason()).contains("memorable phrase");
    }

    @Test
    @DisplayName("a long ordinary phrase is accepted with no composition rules")
    void lengthBeatsComposition() {
        // No uppercase, no digit, no symbol - and far stronger than "Passw0rd!".
        assertThat(validate("sam@example.com", "these are the days of miracle").valid()).isTrue();
    }

    @Test
    @DisplayName("a password containing the email local part is rejected")
    void passwordContainingEmailIsRejected() {
        Registration.Verdict verdict = validate("samantha@example.com", "samantha1234567");

        assertThat(verdict.valid()).isFalse();
        assertThat(verdict.reason()).contains("must not contain your email");
    }

    @Test
    @DisplayName("a very short local part does not trip the email-in-password rule")
    void shortLocalPartIsNotSubstringChecked() {
        // "jo" appears inside plenty of good passphrases; rejecting those would be
        // a rule that annoys without protecting.
        assertThat(validate("jo@example.com", "a journey of one thousand").valid()).isTrue();
    }

    @Test
    @DisplayName("whitespace is not a password")
    void whitespacePassword() {
        assertThat(validate("sam@example.com", "                ").valid()).isFalse();
    }

    @Test
    @DisplayName("an organisation name is required and bounded")
    void organisationName() {
        assertThat(Registration.validate("sam@example.com", "correct horse battery", "").valid())
                .isFalse();
        assertThat(Registration.validate("sam@example.com", "correct horse battery", "   ").valid())
                .isFalse();
        assertThat(Registration.validate("sam@example.com", "correct horse battery",
                "x".repeat(200)).valid()).isFalse();
    }

    @Test
    @DisplayName("addresses are normalised so one person cannot hold two accounts")
    void emailIsNormalised() {
        assertThat(Registration.normaliseEmail("  SAM@Example.COM ")).isEqualTo("sam@example.com");
    }
}
