package com.signallab.api.domain.profile.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProfileReportService {

    private final JdbcTemplate jdbcTemplate;

    public ProfileReportService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> report(UUID reporterId, UUID targetUserId, ReportRequest request) {
        String reason = request == null || request.reason() == null ? "" : request.reason().trim();
        if (reason.length() < 2 || reason.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "신고 사유를 2~200자로 입력하세요.");
        }
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        UUID reportId = UUID.randomUUID();
        UUID publicProfileId = jdbcTemplate.query(
                "SELECT public_profile_id FROM profiles WHERE user_id = ? AND is_public = true AND deleted_at IS NULL",
                rs -> rs.next() ? UUID.fromString(rs.getString(1)) : null,
                targetUserId
            );
            if (publicProfileId == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "공개 프로필을 찾을 수 없습니다.");
            }
            ReportRow row = jdbcTemplate.queryForObject(
                """
                INSERT INTO community_reports (user_id, target_public_profile_id, reason_code, details)
                VALUES (?, ?, 'USER_REPORT', ?) RETURNING id, created_at
                """,
                (rs, rowNum) -> new ReportRow(
                    UUID.fromString(rs.getString("id")),
                    rs.getTimestamp("created_at").toInstant().atOffset(ZoneOffset.UTC)
                ),
                reporterId, publicProfileId, reason
            );
        reportId = row.id();
        createdAt = row.createdAt();
        return Map.of(
            "id", reportId,
            "reporterId", reporterId,
            "targetUserId", targetUserId,
            "reason", reason,
            "createdAt", createdAt
        );
    }

    public record ReportRequest(String reason) {}
    private record ReportRow(UUID id, OffsetDateTime createdAt) {}
}
