package io.aegiscloud.controlplane;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AegisCloud control plane.
 *
 * <p>Redis auto-configuration is excluded deliberately. Spring Boot's default would
 * build a connection factory pointing at localhost whether or not Redis is actually
 * configured, which makes "cache disabled" indistinguishable from "cache pointed at
 * the wrong host". {@code RedisConfig} instead creates a factory only when
 * {@code REDIS_ADDR} is set, so an absent cache is an explicit, observable state —
 * see the class comment on {@code CacheService}.
 */
@SpringBootApplication(exclude = {
        RedisAutoConfiguration.class,
        RedisRepositoriesAutoConfiguration.class
})
@EnableScheduling
public class ControlPlaneApplication {

    public static void main(String[] args) {
        SpringApplication.run(ControlPlaneApplication.class, args);
    }
}
