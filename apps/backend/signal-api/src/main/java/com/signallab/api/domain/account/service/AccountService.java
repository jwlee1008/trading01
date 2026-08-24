package com.signallab.api.domain.account.service;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Transactional
    public void delete(String userId) {
        Boolean exists = jdbcTemplate.queryForObject(
            "select exists(select 1 from profiles where user_id=?::uuid and deleted_at is null)", Boolean.class, userId);
        if (!Boolean.TRUE.equals(exists)) {
            throw new IllegalStateException("삭제된 계정입니다.");
        }
        jdbcTemplate.queryForObject("select set_config('app.account_purge','on',true)", String.class);
        int deleted = jdbcTemplate.update("delete from auth.users where id=?::uuid", userId);
        if (deleted != 1) throw new IllegalStateException("인증 계정 삭제를 완료하지 못했습니다.");
    }
}
