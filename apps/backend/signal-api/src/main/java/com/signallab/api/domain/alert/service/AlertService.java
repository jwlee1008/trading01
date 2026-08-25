package com.signallab.api.domain.alert.service;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AlertService {

    private final JdbcTemplate jdbcTemplate;

    public AlertService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Alert> findFor(String userId) {
        return jdbcTemplate.query(
            """
            select audit.id, audit.entity_id, audit.after_redacted, audit.occurred_at
            from audit_logs audit
            join signals signal on signal.id = audit.entity_id and signal.user_id = audit.user_id
            join strategy_versions sv on sv.id = signal.strategy_version_id
            join strategies strategy on strategy.id = sv.strategy_id
            where audit.user_id = ? and audit.action = 'SIGNAL_CREATED'
              and strategy.archived_at is null
              and not exists (
                select 1 from strategy_versions newer
                where newer.strategy_id = sv.strategy_id
                  and newer.finalized_at is not null
                  and newer.version > sv.version
              )
            order by audit.occurred_at desc
            limit 50
            """,
            (rs, rowNum) -> {
                UUID signalId = rs.getObject("entity_id", UUID.class);
                return new Alert(
                    rs.getObject("id", UUID.class).toString(),
                    signalId == null ? null : signalId.toString(),
                    "사용자 설정 조건 충족 신호",
                    bodyFromPayload(rs.getString("after_redacted")),
                    false,
                    rs.getTimestamp("occurred_at").toInstant().toString()
                );
            },
            UUID.fromString(userId)
        );
    }

    private static String bodyFromPayload(String payload) {
        if (payload == null || payload.isBlank()) return "완성 일봉 기준";
        int key = payload.indexOf("\"body\"");
        if (key < 0) return "완성 일봉 기준";
        int colon = payload.indexOf(':', key);
        int firstQuote = colon < 0 ? -1 : payload.indexOf('"', colon);
        int secondQuote = firstQuote < 0 ? -1 : payload.indexOf('"', firstQuote + 1);
        return firstQuote >= 0 && secondQuote > firstQuote
            ? payload.substring(firstQuote + 1, secondQuote)
            : "완성 일봉 기준";
    }

    public record Alert(String id, String signalId, String title, String body, boolean read, String createdAt) {}
}
