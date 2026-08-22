package com.signallab.api.domain.account.service;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AccountService {

    private final JdbcTemplate jdbcTemplate;

    public AccountService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean isAccountDeleted(String userId) {
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

    public void delete(String userId) {
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
