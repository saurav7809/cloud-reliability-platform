package io.aegiscloud.controlplane.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aegiscloud.controlplane.auth.JwtAuthFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.Map;

/**
 * The control plane's security model: stateless bearer tokens, no sessions.
 *
 * <p>Form login, HTTP Basic, CSRF and session creation are all switched off. This is
 * a token API consumed by a separate frontend — there is no browser session for CSRF
 * to protect, and a server-side session would break the horizontal scaling the
 * platform is meant to demonstrate.
 *
 * <p>Authorisation for individual operations is declared with {@code @PreAuthorize}
 * on the handler that performs them, rather than as path patterns here, so the rule
 * is visible in the method it governs.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final ObjectMapper mapper;

    public SecurityConfig(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                // Resolved by bean name rather than by type: Spring MVC's
                // mvcHandlerMappingIntrospector also implements
                // CorsConfigurationSource, so a by-type injection here is ambiguous.
                // withDefaults() looks up the bean named corsConfigurationSource,
                // which is the one WebConfig declares.
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Health and API documentation stay reachable without a token:
                        // a probe that needs credentials cannot report that credentials
                        // are broken.
                        .requestMatchers("/", "/healthz", "/actuator/health/**",
                                "/openapi.yaml", "/swagger", "/swagger-ui/**",
                                "/v3/api-docs/**").permitAll()
                        // Sign-up is public by necessity: there is no token to
                        // present before an account exists. It creates an empty
                        // organisation, and tenant scoping keeps it that way.
                        .requestMatchers("/api/v1/auth/login", "/api/v1/auth/signup",
                                "/api/v1/auth/signup/enabled").permitAll()
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler()))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Bcrypt, matching the digests already stored in {@code app_user}. Exposed as a
     * bean so the seeder and the login path cannot drift onto different encoders.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Renders 401s in the same {@code {"error","message"}} envelope as every other
     * failure. Spring Security's default is an empty body, which the dashboard's API
     * client would surface to the user as a blank error.
     *
     * @see HttpStatusEntryPoint the default this replaces
     */
    private AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, ex) -> {
            Object reason = request.getAttribute(JwtAuthFilter.AUTH_ERROR_ATTRIBUTE);
            write(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED",
                    reason instanceof String s ? s : "missing bearer token");
        };
    }

    private AccessDeniedHandler accessDeniedHandler() {
        return (request, response, ex) ->
                write(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "insufficient role");
    }

    private void write(HttpServletResponse response, int status, String code, String message)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), Map.of("error", code, "message", message));
    }
}
