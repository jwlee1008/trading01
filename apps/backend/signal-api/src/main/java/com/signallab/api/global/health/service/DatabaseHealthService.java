package com.signallab.api.global.health.service;

import com.signallab.api.global.config.DataStoreMode;
import com.signallab.api.global.config.SignalProperties;
import java.util.Map;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DatabaseHealthService {

    private final SignalProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final DataSourceProperties dataSourceProperties;

    public DatabaseHealthService(
        SignalProperties properties,
        JdbcTemplate jdbcTemplate,
        DataSourceProperties dataSourceProperties
    ) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.dataSourceProperties = dataSourceProperties;
    }

    public Map<String, Object> health() {
        if (properties.resolvedDataStore() == DataStoreMode.MOCK) {
            return Map.of(
                "provider", "mock",
                "database", "memory",
                "ping", "ok"
            );
        }

        String databaseUrl = dataSourceProperties.determineUrl();
        if (databaseUrl == null || databaseUrl.isBlank()) {
            throw new IllegalStateException("DATABASE_URL is required when DATA_STORE=postgres");
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

    public boolean isMockMode() {
        return properties.resolvedDataStore() == DataStoreMode.MOCK;
    }
}
