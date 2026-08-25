package com.signallab.api.domain.signal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.signallab.api.global.config.SignalProperties;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TestTop30UniverseService {
    private static final String DEFINITION_NAME = "[테스트] KOSPI Top 10 + 합성 20";
    private static final Set<String> INDICATORS = Set.of(
        "SMA", "EMA", "RSI", "MACD", "BOLLINGER", "VOLUME_SPIKE", "STOCHASTIC", "ATR", "ADX", "OBV"
    );
    private final JdbcTemplate jdbc;
    private final SignalProperties properties;
    private final ObjectMapper objectMapper;

    public TestTop30UniverseService(JdbcTemplate jdbc, SignalProperties properties, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> status() {
        List<Map<String, Object>> top10 = latestTop10();
        List<Map<String, Object>> fixtures = jdbc.query("""
            SELECT symbol,name_ko,provider_refs->'fixtureIndicators' indicators,
                   provider_refs->>'fixtureIndicator' legacy_indicator
            FROM instruments WHERE provider_refs->>'top30Fixture'='true' ORDER BY symbol
            """, (rs, row) -> {
                List<String> indicatorIds = parseIndicators(rs.getString("indicators"));
                String legacy = rs.getString("legacy_indicator");
                if (indicatorIds.isEmpty() && legacy != null && !legacy.isBlank()) indicatorIds = List.of(legacy);
                return Map.of("symbol", rs.getString("symbol"), "name", rs.getString("name_ko"), "indicatorIds", indicatorIds);
            });
        Map<String, Object> version = jdbc.query("""
            SELECT uv.id::text,uv.version,uv.effective_from,count(um.id) member_count
            FROM universe_versions uv JOIN universe_definitions ud ON ud.id=uv.universe_definition_id
            LEFT JOIN universe_memberships um ON um.universe_version_id=uv.id
            WHERE ud.kind='DEMO_TOP_30'::universe_kind AND uv.finalized_at IS NOT NULL
            GROUP BY uv.id ORDER BY uv.version DESC LIMIT 1
            """, rs -> rs.next() ? Map.of(
                "id", rs.getString(1), "version", rs.getInt(2),
                "effectiveFrom", rs.getObject(3).toString(), "memberCount", rs.getInt(4)
            ) : Map.of());
        return Map.of("top10", top10, "fixtures", fixtures, "universe", version, "ready", top10.size() == 10 && fixtures.size() == 20);
    }

    @Transactional
    public Map<String, Object> configure(List<FixtureInput> inputs) {
        requireEnabled();
        validate(inputs);
        List<Map<String, Object>> top10 = latestTop10();
        if (top10.size() != 10) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "KOSPI Top 10을 먼저 준비하세요.");
        }
        LocalDate through = latestCompletedWeekday();
        List<UUID> fixtureIds = new ArrayList<>();
        for (int index = 0; index < inputs.size(); index++) {
            FixtureInput input = inputs.get(index);
            String symbol = "TST" + String.format("%03d", index + 1);
            UUID instrumentId = ensureFixtureInstrument(symbol, input.name().trim(), input.indicatorIds());
            replaceFixtureCandles(instrumentId, input.indicatorIds(), through);
            fixtureIds.add(instrumentId);
        }
        UUID definitionId = ensureDefinition();
        Integer nextVersion = jdbc.queryForObject(
            "SELECT coalesce(max(version),0)+1 FROM universe_versions WHERE universe_definition_id=?", Integer.class, definitionId);
        UUID versionId = jdbc.queryForObject("""
            INSERT INTO universe_versions(universe_definition_id,version,effective_from,inclusion_policy,source,source_revision)
            VALUES (?,?,?,jsonb_build_object('realTop10',10,'syntheticFixtures',20),'top30-test-fixture',?) RETURNING id
            """, UUID.class, definitionId, nextVersion == null ? 1 : nextVersion, through.minusYears(1),
            "top30-test-fixture:" + through + ":v" + (nextVersion == null ? 1 : nextVersion));
        for (Map<String, Object> member : top10) insertMembership(versionId, UUID.fromString(member.get("id").toString()), through.minusYears(1));
        for (UUID member : fixtureIds) insertMembership(versionId, member, through.minusYears(1));
        jdbc.update("UPDATE universe_versions SET finalized_at=now() WHERE id=?", versionId);
        return status();
    }

    private List<Map<String, Object>> latestTop10() {
        return jdbc.query("""
            SELECT i.id::text,i.symbol,i.name_ko
            FROM universe_memberships um JOIN instruments i ON i.id=um.instrument_id
            WHERE um.universe_version_id=(
              SELECT uv.id FROM universe_versions uv JOIN universe_definitions ud ON ud.id=uv.universe_definition_id
              WHERE ud.kind='KOSPI_TOP_10'::universe_kind AND uv.finalized_at IS NOT NULL
              ORDER BY uv.effective_from DESC,uv.version DESC LIMIT 1)
            ORDER BY i.symbol
            """, (rs, row) -> Map.of("id", rs.getString("id"), "symbol", rs.getString("symbol"), "name", rs.getString("name_ko")));
    }

    private void validate(List<FixtureInput> inputs) {
        if (inputs == null || inputs.size() != 20) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "합성 종목은 정확히 20개여야 합니다.");
        Set<Integer> slots = new HashSet<>();
        for (FixtureInput input : inputs) {
            if (input == null || input.slot() < 1 || input.slot() > 20 || !slots.add(input.slot()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "합성 종목 슬롯 1~20을 중복 없이 지정하세요.");
            if (input.name() == null || input.name().trim().isEmpty() || input.name().trim().length() > 40)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "합성 종목 이름은 1~40자로 입력하세요.");
            if (input.indicatorIds() == null || input.indicatorIds().stream().anyMatch(value -> value == null || !INDICATORS.contains(value.toUpperCase())))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 테스트 지표가 포함되어 있습니다.");
            if (input.indicatorIds().stream().map(String::toUpperCase).distinct().count() != input.indicatorIds().size())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "같은 지표를 중복 선택할 수 없습니다.");
        }
        inputs.sort(java.util.Comparator.comparingInt(FixtureInput::slot));
    }

    private UUID ensureFixtureInstrument(String symbol, String name, List<String> indicators) {
        List<String> codes = indicators.stream().map(String::toUpperCase).toList();
        return jdbc.queryForObject("""
            INSERT INTO instruments(symbol,name_ko,market,kind,listed_on,provider_refs)
            VALUES (?,?,'KOSPI','OTHER',current_date-3650,
              jsonb_build_object('testFixture',true,'top30Fixture',true,'fixtureIndicators',?::jsonb))
            ON CONFLICT(symbol) DO UPDATE SET name_ko=excluded.name_ko,provider_refs=excluded.provider_refs,
              is_trade_suspended=false,delisted_on=null,updated_at=now() RETURNING id
            """, UUID.class, symbol, name, toJson(codes));
    }

    private UUID ensureDefinition() {
        UUID existing = jdbc.query("SELECT id FROM universe_definitions WHERE kind='DEMO_TOP_30'::universe_kind AND user_id IS NULL",
            rs -> rs.next() ? UUID.fromString(rs.getString(1)) : null);
        if (existing != null) return existing;
        return jdbc.queryForObject("""
            INSERT INTO universe_definitions(kind,name_ko,description)
            VALUES ('DEMO_TOP_30',?,'키움 KOSPI Top 10 실제 일봉과 지표별 합성 20종목') RETURNING id
            """, UUID.class, DEFINITION_NAME);
    }

    private void insertMembership(UUID versionId, UUID instrumentId, LocalDate effectiveFrom) {
        jdbc.update("""
            INSERT INTO universe_memberships(universe_version_id,instrument_id,effective_from)
            VALUES (?,?,?) ON CONFLICT(universe_version_id,instrument_id,effective_from) DO NOTHING
            """, versionId, instrumentId, effectiveFrom);
    }

    private void replaceFixtureCandles(UUID instrumentId, List<String> indicators, LocalDate through) {
        jdbc.update("DELETE FROM candles WHERE instrument_id=? AND provider='top30-test-fixture'", instrumentId);
        Set<String> codes = indicators.stream().map(String::toUpperCase).collect(java.util.stream.Collectors.toSet());
        List<LocalDate> sessions = sessions(through, 260);
        for (int index = 0; index < sessions.size(); index++) {
            CandleValue value = fixtureValue(codes, index, sessions.size());
            LocalDate date = sessions.get(index);
            OffsetDateTime openAt = date.atTime(9, 0).atOffset(ZoneOffset.ofHours(9));
            OffsetDateTime closeAt = date.atTime(15, 30).atOffset(ZoneOffset.ofHours(9));
            jdbc.update("""
                INSERT INTO candles(instrument_id,session_date,open_at,close_at,open,high,low,close,adjusted_close,volume,
                  is_final,is_stale,provider,provider_revision,dataset_version)
                VALUES (?,?,?,?,?,?,?,?,?,?,true,false,'top30-test-fixture',?,'top30-fixture-v1')
                ON CONFLICT(instrument_id,timeframe,close_at) DO UPDATE SET open=excluded.open,high=excluded.high,
                  low=excluded.low,close=excluded.close,adjusted_close=excluded.adjusted_close,volume=excluded.volume,
                  provider=excluded.provider,provider_revision=excluded.provider_revision,dataset_version=excluded.dataset_version
                """, instrumentId, date, openAt, closeAt, value.open(), value.high(), value.low(), value.close(), value.close(),
                value.volume(), "fixture:" + String.join("+", codes) + ":" + date);
        }
    }

    private CandleValue fixtureValue(Set<String> indicators, int index, int size) {
        double close = 100 + Math.sin(index / 8d) * 2;
        double range = 2;
        long volume = 1_000;
        int remaining = size - index;
        if (indicators.stream().anyMatch(Set.of("STOCHASTIC", "ADX", "OBV")::contains)) close = 100;
        if (indicators.contains("RSI")) close = index < size - 1 ? 150 - index * 0.22 : 125;
        if (indicators.stream().anyMatch(Set.of("SMA", "EMA", "MACD", "STOCHASTIC")::contains) && remaining <= 2) close = remaining == 2 ? 75 : 130;
        if (indicators.contains("STOCHASTIC") && remaining <= 5) close = remaining == 1 ? 130 : 60 + remaining * 10;
        if (indicators.contains("BOLLINGER") && remaining <= 2) close = remaining == 2 ? 70 : 101;
        if (indicators.contains("VOLUME_SPIKE") && remaining == 1) volume = 10_000;
        if (indicators.contains("ATR") && remaining == 1) range = 30;
        if (indicators.contains("ADX") && remaining <= 4) close = 100 + (5 - remaining) * 5;
        if (indicators.contains("OBV") && remaining == 2) { close = 95; volume = 2_000; }
        if (indicators.contains("OBV") && remaining == 1) { close = 110; volume = 10_000; }
        double open = close - Math.min(1, range / 3);
        return new CandleValue(BigDecimal.valueOf(open), BigDecimal.valueOf(close + range),
            BigDecimal.valueOf(close - range), BigDecimal.valueOf(close), volume);
    }

    private List<LocalDate> sessions(LocalDate through, int count) {
        List<LocalDate> reversed = new ArrayList<>();
        LocalDate cursor = through;
        while (reversed.size() < count) {
            if (cursor.getDayOfWeek() != DayOfWeek.SATURDAY && cursor.getDayOfWeek() != DayOfWeek.SUNDAY) reversed.add(cursor);
            cursor = cursor.minusDays(1);
        }
        java.util.Collections.reverse(reversed);
        return reversed;
    }

    private LocalDate latestWeekday(LocalDate value) {
        LocalDate result = value;
        while (result.getDayOfWeek() == DayOfWeek.SATURDAY || result.getDayOfWeek() == DayOfWeek.SUNDAY) result = result.minusDays(1);
        return result;
    }

    private LocalDate latestCompletedWeekday() {
        var now = java.time.ZonedDateTime.now(ZoneId.of("Asia/Seoul"));
        LocalDate candidate = now.toLocalTime().isBefore(LocalTime.of(15, 30)) ? now.toLocalDate().minusDays(1) : now.toLocalDate();
        return latestWeekday(candidate);
    }

    private void requireEnabled() {
        if (!properties.isSignalTestFixtureEnabled())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "테스트 유니버스 기능은 로컬 개발 환경에서만 사용할 수 있습니다.");
    }

    private List<String> parseIndicators(String value) {
        if (value == null || value.isBlank()) return List.of();
        try { return objectMapper.readValue(value, new TypeReference<>() {}); }
        catch (JsonProcessingException exception) { return List.of(); }
    }

    private String toJson(List<String> value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("테스트 지표 설정을 저장할 수 없습니다.", exception); }
    }

    public record FixtureInput(int slot, String name, List<String> indicatorIds) {}
    private record CandleValue(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, long volume) {}
}
