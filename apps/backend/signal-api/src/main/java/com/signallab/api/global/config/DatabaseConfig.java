package com.signallab.api.global.config;

import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@EnableConfigurationProperties(DataSourceProperties.class)
public class DatabaseConfig implements InitializingBean {

    private final DataSourceProperties dataSourceProperties;

    public DatabaseConfig(DataSourceProperties dataSourceProperties) {
        this.dataSourceProperties = dataSourceProperties;
    }

    @Override
    public void afterPropertiesSet() {
        String databaseUrl = dataSourceProperties.determineUrl();
        if (databaseUrl == null || databaseUrl.isBlank()) {
            throw new IllegalStateException("DATABASE_URL is required for PostgreSQL");
        }
        if (dataSourceProperties.determineUsername() == null || dataSourceProperties.determineUsername().isBlank()) {
            throw new IllegalStateException("DATABASE_USERNAME is required for PostgreSQL");
        }
        if (dataSourceProperties.determinePassword() == null || dataSourceProperties.determinePassword().isBlank()) {
            throw new IllegalStateException("DATABASE_PASSWORD is required for PostgreSQL");
        }
    }

    @Bean
    DataSource dataSource() {
        String jdbcUrl = normalizeJdbcUrl(dataSourceProperties.determineUrl());
        return DataSourceBuilder.create()
            .driverClassName("org.postgresql.Driver")
            .url(jdbcUrl)
            .username(dataSourceProperties.determineUsername())
            .password(dataSourceProperties.determinePassword())
            .build();
    }

    private static String normalizeJdbcUrl(String url) {
        if (url == null) {
            return null;
        }
        if (url.startsWith("postgresql://")) {
            return "jdbc:" + url;
        }
        return url;
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
