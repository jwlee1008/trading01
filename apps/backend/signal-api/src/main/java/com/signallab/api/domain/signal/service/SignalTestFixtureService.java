package com.signallab.api.domain.signal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.signallab.api.domain.strategy.dto.StrategyRequest;
import com.signallab.api.domain.strategy.dto.StrategyVersionResponse;
import com.signallab.api.domain.strategy.service.StrategyService;
import com.signallab.api.domain.worker.service.WorkerCycleService;
import com.signallab.api.global.config.SignalProperties;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SignalTestFixtureService {
    private static final String SYMBOL = "TST001";
    private static final String STRATEGY_NAME = "[테스트] SMA 반복 신호";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final StrategyService strategyService;
    private final WorkerCycleService workerCycleService;
    private final SignalProperties properties;

    public SignalTestFixtureService(JdbcTemplate jdbc, ObjectMapper mapper, StrategyService strategyService,
                                    WorkerCycleService workerCycleService, SignalProperties properties) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.strategyService = strategyService;
        this.workerCycleService = workerCycleService;
        this.properties = properties;
    }

    @Transactional
    public Map<String, Object> createSignalScenario(UUID userId) {
        if (!properties.isSignalTestFixtureEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "테스트 신호 기능은 로컬 개발 환경에서만 사용할 수 있습니다.");
        }
        UUID instrumentId = ensureInstrument();
        UUID universeVersionId = ensureUniverse(userId, instrumentId);
        UUID strategyVersionId = ensureStrategy(userId, universeVersionId);
        LocalDate signalDate = appendFalseToTrueCandles(instrumentId);
        Map<String, Object> request = workerCycleService.request("signal", userId);
        return Map.of(
            "symbol", SYMBOL,
            "instrumentName", "[테스트] 반복 신호 종목",
            "strategyVersionId", strategyVersionId.toString(),
            "signalDate", signalDate.toString(),
            "workerRequest", request,
            "message", "SMA(2) false→true 테스트 일봉을 만들었습니다. Worker 처리 후 신호에 표시됩니다."
        );
    }

    private UUID ensureInstrument() {
        return jdbc.queryForObject("""
            INSERT INTO instruments(symbol,name_ko,market,kind,listed_on,provider_refs)
            VALUES (?,'[테스트] 반복 신호 종목','KOSPI','OTHER',current_date-3650,'{"testFixture":true}'::jsonb)
            ON CONFLICT(symbol) DO UPDATE SET name_ko=excluded.name_ko,provider_refs=excluded.provider_refs,updated_at=now()
            RETURNING id
            """, UUID.class, SYMBOL);
    }

    private UUID ensureUniverse(UUID userId, UUID instrumentId) {
        UUID definitionId = jdbc.query("""
            SELECT id FROM universe_definitions WHERE user_id=? AND name_ko='[테스트] 신호 확인용' LIMIT 1
            """, rs -> rs.next() ? UUID.fromString(rs.getString(1)) : null, userId);
        if (definitionId == null) definitionId = jdbc.queryForObject("""
            INSERT INTO universe_definitions(user_id,kind,name_ko,description)
            VALUES (?,'CUSTOM','[테스트] 신호 확인용','로컬 신호 기능 검증 전용') RETURNING id
            """, UUID.class, userId);
        UUID versionId = jdbc.query("""
            SELECT id FROM universe_versions WHERE universe_definition_id=? AND source='local-test-fixture' LIMIT 1
            """, rs -> rs.next() ? UUID.fromString(rs.getString(1)) : null, definitionId);
        boolean createdVersion = versionId == null;
        if (versionId == null) {
            versionId = jdbc.queryForObject("""
            INSERT INTO universe_versions(user_id,universe_definition_id,version,effective_from,inclusion_policy,source,source_revision,finalized_at)
            VALUES (?,?,1,current_date-3650,'{"testFixture":true}'::jsonb,'local-test-fixture','local-test-fixture-v1',null) RETURNING id
            """, UUID.class, userId, definitionId);
        }
        jdbc.update("""
            INSERT INTO universe_memberships(user_id,universe_version_id,instrument_id,effective_from)
            VALUES (?,?,?,current_date-3650) ON CONFLICT(universe_version_id,instrument_id,effective_from) DO NOTHING
            """, userId, versionId, instrumentId);
        if (createdVersion) jdbc.update("UPDATE universe_versions SET finalized_at=now() WHERE id=?", versionId);
        return versionId;
    }

    private UUID ensureStrategy(UUID userId, UUID universeVersionId) {
        UUID existing = jdbc.query("""
            SELECT sv.id FROM strategy_versions sv JOIN strategies s ON s.id=sv.strategy_id
            WHERE sv.user_id=? AND s.name=? ORDER BY sv.version DESC LIMIT 1
            """, rs -> rs.next() ? UUID.fromString(rs.getString(1)) : null, userId, STRATEGY_NAME);
        if (existing != null) {
            jdbc.update("""
                UPDATE strategies SET archived_at=null,updated_at=now() WHERE user_id=? AND name=?
                """, userId, STRATEGY_NAME);
            jdbc.update("UPDATE strategy_versions SET notifications_enabled=true,finalized_at=COALESCE(finalized_at,now()) WHERE id=?", existing);
            return existing;
        }
        ObjectNode rule = mapper.createObjectNode();
        rule.putObject("left").put("kind", "CLOSE");
        rule.put("operator", "CROSSES_ABOVE");
        ObjectNode right = rule.putObject("right");
        right.put("kind", "INDICATOR"); right.put("indicatorId", "SMA"); right.put("outputKey", "sma");
        right.putObject("params").put("period", 2);
        StrategyVersionResponse created = strategyService.create(userId,
            new StrategyRequest(STRATEGY_NAME, universeVersionId.toString(), "AND", List.of(rule), true, false));
        return created.id();
    }

    private LocalDate appendFalseToTrueCandles(UUID instrumentId) {
        LocalDate latest = jdbc.query("SELECT max(session_date) FROM candles WHERE instrument_id=?",
            rs -> rs.next() ? rs.getObject(1, LocalDate.class) : null, instrumentId);
        LocalDate baselineDate;
        if (latest == null) {
            LocalDate seedDate = LocalDate.now().minusYears(10);
            insertCandle(instrumentId, seedDate, 120, 1_000);
            baselineDate = seedDate.plusDays(1);
        } else baselineDate = latest.plusDays(1);
        LocalDate signalDate = baselineDate.plusDays(1);
        if (signalDate.isAfter(LocalDate.now())) throw new ResponseStatusException(HttpStatus.CONFLICT, "테스트 일봉 날짜 공간을 모두 사용했습니다.");
        insertCandle(instrumentId, baselineDate, 80, 1_000);
        insertCandle(instrumentId, signalDate, 120, 10_000);
        return signalDate;
    }

    private void insertCandle(UUID instrumentId, LocalDate date, long close, long volume) {
        OffsetDateTime openAt = date.atTime(9, 0).atOffset(ZoneOffset.ofHours(9));
        OffsetDateTime closeAt = date.atTime(15, 30).atOffset(ZoneOffset.ofHours(9));
        jdbc.update("""
            INSERT INTO candles(instrument_id,session_date,open_at,close_at,open,high,low,close,adjusted_close,volume,
              is_final,is_stale,provider,provider_revision,dataset_version)
            VALUES (?,?,?,?,?,?,?,?,?,?,true,false,'local-test-fixture',?,'local-test-fixture-v1')
            ON CONFLICT(instrument_id,timeframe,close_at) DO NOTHING
            """, instrumentId, date, openAt, closeAt, close, close, close, close, close, volume, "fixture:" + date);
    }
}
