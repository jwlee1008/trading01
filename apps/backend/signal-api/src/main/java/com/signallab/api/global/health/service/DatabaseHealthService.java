package com.signallab.api.global.health.service;

import java.util.Map;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DatabaseHealthService {

    private final JdbcTemplate jdbcTemplate;
    private final DataSourceProperties dataSourceProperties;

    public DatabaseHealthService(
        JdbcTemplate jdbcTemplate,
        DataSourceProperties dataSourceProperties
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSourceProperties = dataSourceProperties;
    }

    public Map<String, Object> health() {
        String databaseUrl = dataSourceProperties.determineUrl();
        if (databaseUrl == null || databaseUrl.isBlank()) {
            throw new IllegalStateException("DATABASE_URL is required for PostgreSQL");
        }

        if (jdbcTemplate == null) {
            throw new IllegalStateException("JdbcTemplate is unavailable in postgres mode.");
        }
        jdbcTemplate.queryForObject("select 1 as healthy", Integer.class);
        return Map.of(
            "provider", "postgres",
            "database", "postgres",
            "ping", "ok"
        );
    }

    public boolean isPostgres() {
        return true;
    }
}
