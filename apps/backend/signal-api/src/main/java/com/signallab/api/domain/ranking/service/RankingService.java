package com.signallab.api.domain.ranking.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.signallab.api.global.config.DataStoreMode;
import com.signallab.api.global.config.SignalProperties;
import com.signallab.api.domain.strategy.dto.StrategyRequest;
import com.signallab.api.domain.strategy.service.StrategyService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RankingService {

    private static final List<String> PERIODS = List.of("1M", "3M", "6M", "1Y", "ALL");
    private final SignalProperties properties;
    private final StrategyService strategyService;
    private final ObjectMapper objectMapper;

    public RankingService(SignalProperties properties, StrategyService strategyService, ObjectMapper objectMapper) {
        this.properties = properties;
        this.strategyService = strategyService;
        this.objectMapper = objectMapper;
    }

    public Object copyCombination(UUID userId, String combinationId) {
        CombinationDraft draft = switch (combinationId) {
            case "combo-1" -> new CombinationDraft("EMA·RSI·거래량 복사본", rules("""
                [
                  {"left":{"kind":"CLOSE"},"operator":"CROSSES_ABOVE","right":{"kind":"INDICATOR","indicatorId":"EMA","outputKey":"ema","params":{"period":20}}},
                  {"left":{"kind":"INDICATOR","indicatorId":"RSI","outputKey":"rsi","params":{"period":14}},"operator":"LTE","right":{"kind":"VALUE","value":40}},
                  {"left":{"kind":"INDICATOR","indicatorId":"VOLUME_SPIKE","outputKey":"ratio","params":{"period":20}},"operator":"GTE","right":{"kind":"VALUE","value":1.8}}
                ]
                """));
            case "combo-2" -> new CombinationDraft("MACD·ADX 복사본", rules("""
                [
                  {"left":{"kind":"INDICATOR","indicatorId":"MACD","outputKey":"macd","params":{"fastPeriod":12,"slowPeriod":26,"signalPeriod":9}},"operator":"CROSSES_ABOVE","right":{"kind":"INDICATOR","indicatorId":"MACD","outputKey":"signal","params":{"fastPeriod":12,"slowPeriod":26,"signalPeriod":9}}},
                  {"left":{"kind":"INDICATOR","indicatorId":"ADX","outputKey":"adx","params":{"period":14}},"operator":"GTE","right":{"kind":"VALUE","value":25}}
                ]
                """));
            case "combo-3" -> new CombinationDraft("볼린저·RSI 복사본", rules("""
                [
                  {"left":{"kind":"CLOSE"},"operator":"LTE","right":{"kind":"INDICATOR","indicatorId":"BOLLINGER","outputKey":"lower","params":{"period":20,"standardDeviations":2}}},
                  {"left":{"kind":"INDICATOR","indicatorId":"RSI","outputKey":"rsi","params":{"period":14}},"operator":"LTE","right":{"kind":"VALUE","value":35}}
                ]
                """));
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "조합을 찾을 수 없습니다.");
        };
        StrategyRequest request = new StrategyRequest(
            draft.name(), "uv-kospi200-202608", "AND", draft.rules(), true, false
        );
        if (properties.resolvedDataStore() == DataStoreMode.MOCK) {
            return Map.ofEntries(
                Map.entry("id", UUID.randomUUID()), Map.entry("userId", userId),
                Map.entry("strategyId", UUID.randomUUID()), Map.entry("version", 1),
                Map.entry("name", draft.name()), Map.entry("universeVersionId", "uv-kospi200-202608"),
                Map.entry("logic", "AND"), Map.entry("rules", draft.rules()),
                Map.entry("alertsEnabled", true), Map.entry("isPublic", false), Map.entry("locked", false)
            );
        }
        return strategyService.create(userId, request);
    }

    private List<JsonNode> rules(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            return java.util.stream.StreamSupport.stream(root.spliterator(), false).toList();
        } catch (Exception exception) {
            throw new IllegalStateException("내장 랭킹 전략을 읽을 수 없습니다.", exception);
        }
    }

    public Map<String, Object> rankings(String period) {
        String normalized = period == null || period.isBlank() ? "3M" : period;
        if (!PERIODS.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 랭킹 기간입니다.");
        }
        if (properties.resolvedDataStore() != DataStoreMode.MOCK) {
            return Map.of(
                "period", normalized, "combinations", List.of(), "indicatorTiers", List.of(), "users", List.of(),
                "disclosure", "공개된 DB ranking snapshot 기준입니다. snapshot이 없으면 빈 목록을 표시합니다.",
                "indicatorDisclosure", "과거 데이터상 견고성 등급이며 미래 수익 예측이 아닙니다."
            );
        }
        return Map.of(
            "period", normalized,
            "combinations", List.of(
                new Combination(1, "combo-1", "EMA·RSI·거래량", 14.8, 61.2, -7.9, 83, 57, 0.82, "10.1~19.3%", "KOSPI 200", "signal-return-v2", "2026-08-14T09:00:00.000Z"),
                new Combination(2, "combo-2", "MACD·ADX", 12.1, 58.6, -9.4, 71, 49, 0.77, "7.2~16.8%", "KOSPI·KOSDAQ 통합", "signal-return-v2", "2026-08-14T09:00:00.000Z"),
                new Combination(3, "combo-3", "볼린저·RSI", 9.7, 56.1, -11.2, 42, 31, 0.70, "3.9~15.0%", "KOSDAQ 150", "signal-return-v2", "2026-08-14T09:00:00.000Z")
            ),
            "indicatorTiers", List.of(
                Map.of("indicatorId", "EMA", "name", "지수이동평균", "tier", "S", "score", 88, "removalImpact", 0.47, "stability", 0.86, "deduplicatedFrequency", 0.79),
                Map.of("indicatorId", "RSI", "name", "상대강도지수", "tier", "A", "score", 81, "removalImpact", 0.42, "stability", 0.78, "deduplicatedFrequency", 0.76)
            ),
            "users", List.of(),
            "disclosure", "과거 데이터 기반 순위이며 미래 수익을 보장하지 않습니다.",
            "indicatorDisclosure", "과거 데이터상 견고성 등급이며 미래 수익 예측이 아닙니다."
        );
    }

    public record Combination(int rank, String id, String name, double netExcessReturnPct, double hitRatePct,
                              double mddPct, int signalCount, int instrumentCount, double stability,
                              String confidenceInterval, String universe, String methodVersion, String updatedAt) {}
    private record CombinationDraft(String name, List<JsonNode> rules) {}
}
