package com.signallab.api.domain.profile.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProfileSettingsService {
    private final JdbcTemplate jdbc;

    public ProfileSettingsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Map<String, Object> update(UUID userId, SettingsRequest request) {
        String nickname = request.nickname() == null ? "" : request.nickname().trim();
        if (nickname.length() < 2 || nickname.length() > 24) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "닉네임은 2~24자로 입력하세요.");
        }
        boolean disclose = request.isPublic() && request.discloseOpenPositions();
        try {
            int updated = jdbc.update("""
                UPDATE profiles SET nickname=?,is_public=?,disclose_open_positions=?,updated_at=now()
                WHERE user_id=? AND deleted_at IS NULL
                """, nickname, request.isPublic(), disclose, userId);
            if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "프로필을 찾을 수 없습니다.");
        } catch (DataIntegrityViolationException error) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다.");
        }
        return settings(userId);
    }

    public Map<String, Object> selectUniverse(UUID userId, UniversePreferenceRequest request) {
        UUID universeId;
        try { universeId = UUID.fromString(request.universeVersionId()); }
        catch (Exception error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "종목군 버전 ID가 올바르지 않습니다."); }
        int updated = jdbc.update("""
            UPDATE profiles p SET selected_universe_version_id=?,updated_at=now()
            WHERE p.user_id=? AND p.deleted_at IS NULL AND EXISTS (
              SELECT 1 FROM universe_versions uv WHERE uv.id=? AND uv.finalized_at IS NOT NULL
                AND (uv.user_id IS NULL OR uv.user_id=p.user_id)
            )
            """, universeId, userId, universeId);
        if (updated == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "사용할 수 없는 종목군 버전입니다.");
        return settings(userId);
    }

    public Map<String, Object> settings(UUID userId) {
        Map<String, Object> result = jdbc.query("""
            SELECT p.nickname::text,p.is_public,p.disclose_open_positions,p.selected_universe_version_id,
                   ud.kind::text universe_kind
            FROM profiles p LEFT JOIN universe_versions uv ON uv.id=p.selected_universe_version_id
            LEFT JOIN universe_definitions ud ON ud.id=uv.universe_definition_id
            WHERE p.user_id=? AND p.deleted_at IS NULL
            """, rs -> {
                if (!rs.next()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "프로필을 찾을 수 없습니다.");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("nickname", rs.getString("nickname")); row.put("isPublic", rs.getBoolean("is_public"));
                row.put("discloseOpenPositions", rs.getBoolean("disclose_open_positions"));
                row.put("selectedUniverseVersionId", rs.getString("selected_universe_version_id"));
                row.put("selectedUniverseKind", rs.getString("universe_kind"));
                return row;
            }, userId);
        return result;
    }

    public record SettingsRequest(String nickname, boolean isPublic, boolean discloseOpenPositions) {}
    public record UniversePreferenceRequest(String universeVersionId) {}
}
