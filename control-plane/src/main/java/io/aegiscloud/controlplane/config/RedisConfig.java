package io.aegiscloud.controlplane.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * Creates Redis plumbing only when {@code REDIS_ADDR} is set.
 *
 * <p>The condition sits on the whole configuration class rather than on individual
 * beans, so when the variable is absent no Redis beans exist at all and
 * {@code CacheService} simply receives nothing to inject. That is the intended way
 * to run without a cache: not a factory quietly retrying a host nobody configured,
 * but an absence the code can see and report.
 */
@Configuration
@ConditionalOnExpression("!'${REDIS_ADDR:}'.isBlank()")
public class RedisConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    @Bean
    public LettuceConnectionFactory redisConnectionFactory(@Value("${REDIS_ADDR:}") String redisAddr) {
        String host = redisAddr;
        int port = 6379;

        int split = redisAddr.lastIndexOf(':');
        if (split > 0) {
            String portPart = redisAddr.substring(split + 1);
            try {
                port = Integer.parseInt(portPart);
                host = redisAddr.substring(0, split);
            } catch (NumberFormatException e) {
                // Treat the whole value as a hostname rather than guessing: a
                // mangled port is a configuration error worth seeing in the log.
                log.warn("REDIS_ADDR '{}' has an unparseable port, using default 6379", redisAddr);
            }
        }

        // Timeouts are deliberately short. The cache exists to take load off
        // PostgreSQL; a slow cache that blocks request threads would defeat that
        // purpose more thoroughly than having no cache at all.
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(2))
                .shutdownTimeout(Duration.ofSeconds(1))
                .build();

        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port), clientConfig);

        // Without this, an unreachable Redis raises on context startup. The
        // platform must boot with its cache down, so validation is left to the
        // explicit ping in CacheService.
        factory.setValidateConnection(false);

        log.info("redis configured at {}:{}", host, port);
        return factory;
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }
}
