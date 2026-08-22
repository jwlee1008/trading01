package com.signallab.api.domain.alert.service;

import com.signallab.api.global.config.DataStoreMode;
import com.signallab.api.global.config.SignalProperties;
import java.sql.Time;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AlertSettingsService {

    private static final Settings DEFAULT_SETTINGS = new Settings(true, false, "22:00", "07:00", false);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final SignalProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final Map<String, Settings> mockSettings = new ConcurrentHashMap<>();

    public AlertSettingsService(SignalProperties properties, JdbcTemplate jdbcTemplate) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
    }

    public Settings findFor(String userId) {
        if (properties.resolvedDataStore() == DataStoreMode.MOCK) {
            return mockSettings.getOrDefault(userId, DEFAULT_SETTINGS);
        }

        UUID id = UUID.fromString(userId);
        return jdbcTemplate.query(
            """
            select enabled, quiet_start, quiet_end
            from alert_settings
            where user_id = ? and strategy_id is null and instrument_id is null
            order by updated_at desc
            limit 1
            """,
            (rs, rowNum) -> {
                Time quietStart = rs.getTime("quiet_start");
                Time quietEnd = rs.getTime("quiet_end");
                boolean quietHoursEnabled = quietStart != null && quietEnd != null;
                return new Settings(
                    rs.getBoolean("enabled"),
                    quietHoursEnabled,
                    quietHoursEnabled ? quietStart.toLocalTime().format(TIME_FORMAT) : DEFAULT_SETTINGS.quietStart(),
                    quietHoursEnabled ? quietEnd.toLocalTime().format(TIME_FORMAT) : DEFAULT_SETTINGS.quietEnd(),
                    false
                );
            },
            id
        ).stream().findFirst().orElse(DEFAULT_SETTINGS);
    }

    public Settings update(String userId, Settings input) {
        validate(input);
        if (properties.resolvedDataStore() == DataStoreMode.MOCK) {
            mockSettings.put(userId, input);
            return input;
        }

        UUID id = UUID.fromString(userId);
        Time quietStart = input.quietHoursEnabled() ? Time.valueOf(LocalTime.parse(input.quietStart())) : null;
        Time quietEnd = input.quietHoursEnabled() ? Time.valueOf(LocalTime.parse(input.quietEnd())) : null;
        int updated = jdbcTemplate.update(
            """
            update alert_settings
            set enabled = ?, quiet_start = ?, quiet_end = ?, updated_at = now()
            where user_id = ? and strategy_id is null and instrument_id is null
            """,
            input.enabled(), quietStart, quietEnd, id
        );
        if (updated == 0) {
            jdbcTemplate.update(
                """
                insert into alert_settings (user_id, enabled, quiet_start, quiet_end)
                values (?, ?, ?, ?)
                """,
                id, input.enabled(), quietStart, quietEnd
            );
        }
        jdbcTemplate.update(
            """
            insert into audit_logs (user_id, actor_user_id, action, entity_type, after_redacted)
            values (?, ?, 'ALERT_SETTINGS_CHANGED', 'alert_settings', ?::jsonb)
            """,
            id, id, settingsJson(input)
        );
        return input;
    }

    private static void validate(Settings input) {
        if (input == null || input.quietStart() == null || input.quietEnd() == null) {
            throw new IllegalArgumentException("알림 설정 값이 필요합니다.");
        }
        if (!input.quietStart().matches("\\d{2}:\\d{2}") || !input.quietEnd().matches("\\d{2}:\\d{2}")) {
            throw new IllegalArgumentException("quietStart와 quietEnd는 HH:mm 형식이어야 합니다.");
        }
        try {
            LocalTime.parse(input.quietStart());
            LocalTime.parse(input.quietEnd());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("quietStart와 quietEnd는 HH:mm 형식이어야 합니다.", exception);
        }
    }

    private static String settingsJson(Settings input) {
        return "{\"enabled\":" + input.enabled()
            + ",\"quietHoursEnabled\":" + input.quietHoursEnabled()
            + ",\"quietStart\":\"" + input.quietStart()
            + "\",\"quietEnd\":\"" + input.quietEnd()
            + "\",\"showPriceOnLockScreen\":" + input.showPriceOnLockScreen() + "}";
    }

    public record Settings(
        boolean enabled,
        boolean quietHoursEnabled,
        String quietStart,
        String quietEnd,
        boolean showPriceOnLockScreen
    ) {}
}
