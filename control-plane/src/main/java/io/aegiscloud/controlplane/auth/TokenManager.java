package io.aegiscloud.controlplane.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/** Issues and verifies the HS256 bearer tokens the dashboard authenticates with. */
@Component
public class TokenManager {

    private static final Logger log = LoggerFactory.getLogger(TokenManager.class);
    private static final Duration TTL = Duration.ofHours(24);

    /** Below this, HMAC-SHA256 keys carry less entropy than the digest they feed. */
    private static final int RECOMMENDED_SECRET_BYTES = 32;

    private final SecretKey key;

    public TokenManager(@Value("${AEGISCLOUD_JWT_SECRET:dev-secret-change-me}") String secret) {
        byte[] raw = secret.getBytes(StandardCharsets.UTF_8);

        // AEGISCLOUD_JWT_SECRET is a plain string of any length — that is the
        // deployment contract, and the documented development default is only 20
        // bytes. HS256 requires a 256-bit key, so the configured value is hashed to
        // exactly that width rather than used verbatim.
        //
        // SHA-256 is a fixed, deterministic widening, so tokens stay valid across
        // restarts. It adds no entropy: a guessable secret produces a guessable key,
        // which is why a short one is still reported below.
        if (raw.length < RECOMMENDED_SECRET_BYTES) {
            log.warn("AEGISCLOUD_JWT_SECRET is {} bytes; use at least {} in any non-local deployment",
                    raw.length, RECOMMENDED_SECRET_BYTES);
        }

        try {
            byte[] widened = MessageDigest.getInstance("SHA-256").digest(raw);
            this.key = new SecretKeySpec(widened, "HmacSHA256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every conformant JVM; its absence is not a
            // condition the platform can meaningfully continue past.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public String issue(AuthenticatedUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.id())
                .claim("email", user.email())
                .claim("role", user.role().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(TTL)))
                .signWith(key)
                .compact();
    }

    /**
     * Verifies a token and returns its identity, or null if the token is absent,
     * malformed, expired, or signed with the wrong key. The caller cannot act
     * differently on those cases, so they are not distinguished.
     */
    public AuthenticatedUser parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String role = claims.get("role", String.class);
            String email = claims.get("email", String.class);
            if (role == null || email == null) {
                return null;
            }
            return new AuthenticatedUser(claims.getSubject(), email, Role.valueOf(role));
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}
