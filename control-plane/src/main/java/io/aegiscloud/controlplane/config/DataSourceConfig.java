package io.aegiscloud.controlplane.config;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Builds the PostgreSQL pool from {@code DATABASE_URL}.
 *
 * <p>PostgreSQL is a hard dependency: unlike the cache, there is no meaningful
 * degraded mode for a platform whose entire fleet inventory lives in it. The pool
 * is sized to match the Go implementation (10 connections, one-hour maximum
 * lifetime) so behaviour under load is comparable across the port.
 */
@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    @Bean
    public DataSource dataSource(
            @Value("${DATABASE_URL:postgres://aegiscloud:aegiscloud@localhost:5432/aegiscloud?sslmode=disable}")
            String databaseUrl) {

        DatabaseUrl parsed = DatabaseUrl.parse(databaseUrl);

        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(parsed.jdbcUrl());
        if (parsed.username() != null) {
            ds.setUsername(parsed.username());
        }
        if (parsed.password() != null) {
            ds.setPassword(parsed.password());
        }
        ds.setMaximumPoolSize(10);
        ds.setMaxLifetime(3_600_000);

        // Compose starts the database and the control plane together, so the first
        // connection attempts legitimately fail while PostgreSQL finishes booting.
        // A generous timeout turns that into a slow start rather than a crash loop.
        ds.setInitializationFailTimeout(60_000);
        ds.setConnectionTimeout(10_000);
        ds.setPoolName("aegiscloud-pg");

        log.info("postgres pool configured for {}", parsed.jdbcUrl());
        return ds;
    }
}
