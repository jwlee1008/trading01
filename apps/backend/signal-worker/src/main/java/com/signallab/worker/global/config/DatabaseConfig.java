package com.signallab.worker.global.config;

import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DataSourceProperties.class)
public class DatabaseConfig implements InitializingBean {
    private final DataSourceProperties properties;

    public DatabaseConfig(DataSourceProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        if (properties.determineUrl() == null || properties.determineUrl().isBlank()) {
            throw new IllegalStateException("DATABASE_URL is required");
        }
        if (properties.determineUsername() == null || properties.determineUsername().isBlank()) {
            throw new IllegalStateException("DATABASE_USERNAME is required");
        }
        if (properties.determinePassword() == null || properties.determinePassword().isBlank()) {
            throw new IllegalStateException("DATABASE_PASSWORD is required");
        }
    }

    @Bean
    DataSource dataSource() {
        return DataSourceBuilder.create()
            .driverClassName("org.postgresql.Driver")
            .url(normalize(properties.determineUrl()))
            .username(properties.determineUsername())
            .password(properties.determinePassword())
            .build();
    }

    private String normalize(String url) {
        return url.startsWith("postgresql://") ? "jdbc:" + url : url;
    }
}
