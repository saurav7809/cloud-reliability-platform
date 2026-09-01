package io.aegiscloud.controlplane.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Cross-origin access for the dashboard, which is served from a different port in
 * development and a different host in deployment.
 *
 * <p>Exposed as a {@link CorsConfigurationSource} bean rather than through
 * {@code WebMvcConfigurer}, because the security filter chain applies CORS before a
 * request reaches Spring MVC. Declaring it once here keeps preflight handling and
 * MVC agreeing on the same origin list.
 */
@Configuration
public class WebConfig {

    private static final Logger log = LoggerFactory.getLogger(WebConfig.class);

    private final List<String> origins;

    public WebConfig(@Value("${AEGISCLOUD_WEB_ORIGIN:http://localhost:5173}") String rawOrigins) {
        this.origins = Arrays.stream(rawOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        log.info("CORS origins: {}", origins);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // An explicit origin list, never a wildcard: credentialed requests and
        // "*" are mutually exclusive, and the dashboard sends an Authorization
        // header on every call.
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
