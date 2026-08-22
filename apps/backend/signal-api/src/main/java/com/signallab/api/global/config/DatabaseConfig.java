package com.signallab.api.global.config;

import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@EnableConfigurationProperties(DataSourceProperties.class)
public class DatabaseConfig implements InitializingBean {

    private final SignalProperties signalProperties;
    private final DataSourceProperties dataSourceProperties;

    public DatabaseConfig(SignalProperties signalProperties, DataSourceProperties dataSourceProperties) {
        this.signalProperties = signalProperties;
        this.dataSourceProperties = dataSourceProperties;
    }

    @Override
    public void afterPropertiesSet() {
        if (signalProperties.resolvedDataStore() != DataStoreMode.POSTGRES) {
            return;
        }
        String databaseUrl = dataSourceProperties.determineUrl();
        if (databaseUrl == null || databaseUrl.isBlank()) {
            throw new IllegalStateException("DATABASE_URL is required when DATA_STORE=postgres");
        }
    }

    @Bean
    DataSource dataSource() {
        if (signalProperties.resolvedDataStore() != DataStoreMode.POSTGRES) {
            return new NoopDataSource();
        }
        String jdbcUrl = normalizeJdbcUrl(dataSourceProperties.determineUrl());
        return dataSourceProperties.initializeDataSourceBuilder().url(jdbcUrl).build();
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
