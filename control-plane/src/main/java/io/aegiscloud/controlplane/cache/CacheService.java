package io.aegiscloud.controlplane.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Wraps Redis.
 *
 * <p>The fleet overview aggregates across every target on every request — the most
 * expensive read in the API and the one the dashboard hits first. Caching it keeps
 * that cost off PostgreSQL.
 *
 * <p>Redis is treated as strictly optional: if it is unavailable the platform runs
 * correctly, just without the cache. A reliability platform that falls over because
 * its cache is down would be an awkward thing to ship. Every operation therefore
 * swallows its failures — a cache miss is always a valid outcome, so there is no
 * error here a caller could usefully handle.
 */
@Service
public class CacheService {

    private static final Logger log = LoggerFactory.getLogger(CacheService.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private volatile boolean enabled;

    public CacheService(ObjectProvider<StringRedisTemplate> redisProvider, ObjectMapper mapper) {
        this.redis = redisProvider.getIfAvailable();
        this.mapper = mapper;
    }

    /**
     * Pings Redis once at startup. A failure here disables the cache rather than
     * failing the boot, matching how the platform is expected to survive a cache
     * outage in production.
     */
    @PostConstruct
    void probe() {
        if (redis == null) {
            enabled = false;
            log.info("redis not configured — caching disabled");
            return;
        }
        try {
            redis.getConnectionFactory().getConnection().ping();
            enabled = true;
            log.info("connected to redis");
        } catch (Exception e) {
            enabled = false;
            log.warn("redis unreachable, continuing without cache: {}", e.getMessage());
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Reads and deserialises a cached value. Returns null on a miss, on any error,
     * or when the cache is disabled — callers treat all three identically and fall
     * through to the database.
     */
    public <T> T get(String key, Class<T> type) {
        if (!enabled) {
            return null;
        }
        try {
            String raw = redis.opsForValue().get(key);
            if (raw == null) {
                return null;
            }
            return mapper.readValue(raw, type);
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            // A value we cannot decode is worse than no value — drop it so the next
            // read repopulates cleanly.
            log.warn("dropping undecodable cache entry {}: {}", key, e.getMessage());
            delete(key);
            return null;
        } catch (Exception e) {
            degrade(e);
            return null;
        }
    }

    /**
     * Stores a value with a TTL. Failures are ignored: a write that does not land
     * costs a cache miss, nothing more.
     */
    public void set(String key, Object value, Duration ttl) {
        if (!enabled) {
            return;
        }
        try {
            redis.opsForValue().set(key, mapper.writeValueAsString(value), ttl);
        } catch (Exception e) {
            degrade(e);
        }
    }

    /**
     * Drops keys whose underlying data just changed — called after mutations so the
     * dashboard never shows a stale rollup.
     */
    public void invalidate(String... keys) {
        if (!enabled || keys.length == 0) {
            return;
        }
        try {
            redis.delete(List.of(keys));
        } catch (Exception e) {
            degrade(e);
        }
    }

    private void delete(String key) {
        try {
            redis.delete(key);
        } catch (Exception e) {
            degrade(e);
        }
    }

    /**
     * Marks the cache unusable after a runtime failure. The startup ping cannot
     * catch a Redis that dies later, so the first failing operation flips the flag
     * and every subsequent request goes straight to PostgreSQL instead of paying a
     * timeout to rediscover the same outage.
     */
    private void degrade(Exception e) {
        if (enabled) {
            enabled = false;
            log.warn("redis operation failed, disabling cache for this process: {}", e.getMessage());
        }
    }
}
