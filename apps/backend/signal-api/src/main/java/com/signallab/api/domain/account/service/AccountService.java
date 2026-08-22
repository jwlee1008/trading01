package com.signallab.api.domain.account.service;

import com.signallab.api.global.config.DataStoreMode;
import com.signallab.api.global.config.SignalProperties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AccountService {

    private final SignalProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final Set<String> deletedMockUsers = ConcurrentHashMap.newKeySet();

    public AccountService(SignalProperties properties, JdbcTemplate jdbcTemplate) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean isAccountDeleted(String userId) {
        if (properties.resolvedDataStore() == DataStoreMode.MOCK) {
            return deletedMockUsers.contains(userId);
        }

        if (jdbcTemplate == null) {
            return false;
        }

        try {
            Boolean deleted = jdbcTemplate.queryForObject(
                """
                select deleted_at is not null
                from profiles
                where user_id = ?::uuid
                """,
                Boolean.class,
                userId
            );
            return Boolean.TRUE.equals(deleted);
        } catch (EmptyResultDataAccessException ignored) {
            return false;
        }
    }

    public void markDeletedInMock(String userId) {
        deletedMockUsers.add(userId);
    }

    public void delete(String userId) {
        if (properties.resolvedDataStore() == DataStoreMode.MOCK) {
            if (!deletedMockUsers.add(userId)) {
                throw new IllegalStateException("삭제된 계정입니다.");
            }
            return;
        }

        int updated = jdbcTemplate.update(
            """
            update profiles
            set deleted_at = now(), is_public = false, updated_at = now()
            where user_id = ?::uuid and deleted_at is null
            """,
            userId
        );
        if (updated == 0) {
            throw new IllegalStateException("삭제된 계정입니다.");
        }
    }
}
