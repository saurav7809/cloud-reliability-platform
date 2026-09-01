package io.aegiscloud.controlplane.web;

import io.aegiscloud.controlplane.cache.CacheService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dependency health, used by Compose and any external probe.
 *
 * <p>PostgreSQL is required, so losing it makes the service unhealthy (503). Redis
 * is optional — its absence is reported but does not fail the check, matching how
 * the cache actually degrades.
 */
@RestController
public class HealthController {

    /** Identifies the running build. Becomes a build-stamped value once CI produces release artifacts. */
    private static final String VERSION = "v0.3.0";

    private final JdbcTemplate jdbc;
    private final CacheService cache;

    public HealthController(JdbcTemplate jdbc, CacheService cache) {
        this.jdbc = jdbc;
        this.cache = cache;
    }

    @GetMapping("/healthz")
    public ResponseEntity<Map<String, Object>> health() {
        boolean postgresUp;
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            postgresUp = true;
        } catch (Exception e) {
            postgresUp = false;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", postgresUp ? "ok" : "degraded");
        body.put("service", "aegiscloud-control-plane");
        body.put("version", VERSION);
        body.put("time", Instant.now());
        body.put("dependencies", Map.of(
                "postgres", postgresUp ? "up" : "down",
                "redis", cache.isEnabled() ? "up" : "disabled"));

        return ResponseEntity
                .status(postgresUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(body);
    }
}
