package com.signallab.api.domain.profile.repository;

import com.signallab.domain.profile.entity.Profile;
import com.signallab.domain.profile.repository.ProfileRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;
import java.time.ZoneOffset;

@Repository
public class JdbcProfileRepository implements ProfileRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcProfileRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<Profile> rowMapper = (rs, rowNum) -> new Profile(
            UUID.fromString(rs.getString("user_id")),
            UUID.fromString(rs.getString("public_profile_id")),
            rs.getString("nickname"),
            rs.getBoolean("is_public"),
            rs.getTimestamp("risk_disclosure_accepted_at") != null ? rs.getTimestamp("risk_disclosure_accepted_at").toInstant().atOffset(ZoneOffset.UTC) : null,
            rs.getString("terms_version"),
            rs.getString("privacy_version"),
            rs.getTimestamp("created_at").toInstant().atOffset(ZoneOffset.UTC),
            rs.getTimestamp("updated_at").toInstant().atOffset(ZoneOffset.UTC),
            rs.getTimestamp("deleted_at") != null ? rs.getTimestamp("deleted_at").toInstant().atOffset(ZoneOffset.UTC) : null
    );

    @Override
    public Optional<Profile> findByUserId(UUID userId) {
        String sql = "SELECT * FROM profiles WHERE user_id = ?";
        return jdbcTemplate.query(sql, rowMapper, userId).stream().findFirst();
    }

    @Override
    public void save(Profile profile) {
        String sql = """
            INSERT INTO profiles (user_id, public_profile_id, nickname, is_public, risk_disclosure_accepted_at, terms_version, privacy_version)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (user_id) DO UPDATE SET
                nickname = EXCLUDED.nickname,
                is_public = EXCLUDED.is_public,
                updated_at = NOW()
            """;
        jdbcTemplate.update(sql,
                profile.userId(),
                profile.publicProfileId(),
                profile.nickname(),
                profile.isPublic(),
                profile.riskDisclosureAcceptedAt() != null ? java.sql.Timestamp.from(profile.riskDisclosureAcceptedAt().toInstant()) : null,
                profile.termsVersion(),
                profile.privacyVersion()
        );
    }

    @Override
    public void updateVisibility(UUID userId, boolean isPublic) {
        String sql = "UPDATE profiles SET is_public = ?, updated_at = NOW() WHERE user_id = ?";
        jdbcTemplate.update(sql, isPublic, userId);
    }

    @Override
    public void delete(UUID userId) {
        String sql = "UPDATE profiles SET deleted_at = NOW() WHERE user_id = ?";
        jdbcTemplate.update(sql, userId);
    }
}
