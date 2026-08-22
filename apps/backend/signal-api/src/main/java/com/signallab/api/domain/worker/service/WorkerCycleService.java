package com.signallab.api.domain.worker.service;

import com.signallab.api.global.config.DataStoreMode;
import com.signallab.api.global.config.SignalProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Spring replacement for the local Mock worker hand-off endpoint.
 *
 * Production cycles write directly to PostgreSQL from the Spring worker.  Until
 * that runner is migrated, this service keeps the development hand-off fully in
 * the Spring API process and deliberately refuses the endpoint in postgres mode.
 */
@Service
public class WorkerCycleService {

    private final SignalProperties properties;
    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, StoredSignal> signalsByKey = new ConcurrentHashMap<>();
    private final Map<String, StoredOutbox> outboxByKey = new ConcurrentHashMap<>();

    public WorkerCycleService(SignalProperties properties) {
        this.properties = properties;
    }

    public Map<String, Object> runCycle(Map<String, Object> raw) {
        if (properties.resolvedDataStore() != DataStoreMode.MOCK) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                "PostgreSQL worker cycle은 Spring worker 전환 뒤 직접 실행됩니다.");
        }
        String cycleId = requiredString(raw, "cycleId", 200);
        String userId = requiredString(raw, "userId", 100);
        String expectedUserId = System.getenv().getOrDefault("WORKER_MOCK_USER_ID", "demo-user");
        if (!expectedUserId.equals(userId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "로컬 Mock 워커 사용자 ID가 올바르지 않습니다.");
        }

        List<Map<String, Object>> quotes = objectList(raw.get("quotes"), "quotes");
        Map<String, Map<String, Object>> quoteBySymbol = new LinkedHashMap<>();
        for (Map<String, Object> quote : quotes) {
            String symbol = requiredSymbol(quote, "symbol");
            if (quoteBySymbol.putIfAbsent(symbol, quote) != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "한 주기에는 종목별 시세가 하나만 허용됩니다.");
            }
        }

        int inserted = 0;
        int deduplicated = 0;
        List<Map<String, Object>> signals = objectList(raw.get("signals"), "signals");
        for (Map<String, Object> candidate : signals) {
            String key = requiredString(candidate, "key", 300);
            String strategyVersionId = requiredString(candidate, "strategyVersionId", 100);
            String symbol = requiredSymbol(candidate, "symbol");
            String candleClose = requiredDate(candidate, "candleClose");
            Object evidence = candidate.get("evidence");
            if (!(evidence instanceof Map<?, ?>)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "evidence 입력이 올바르지 않습니다.");
            }
            String fingerprint = candidate.toString();
            StoredSignal existing = signalsByKey.get(key);
            if (existing != null) {
                if (!existing.fingerprint().equals(fingerprint)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "워커 신호 키가 다른 본문에 이미 사용되었습니다: " + key);
                }
                deduplicated++;
                continue;
            }
            Map<String, Object> quote = quoteBySymbol.get(symbol);
            String closePrice = quote != null && candleClose.equals(quote.get("sessionDate")) && quote.get("close") != null
                ? String.valueOf(quote.get("close")) : null;
            String id = "worker-signal-" + sequence.incrementAndGet();
            StoredSignal created = new StoredSignal(id, key, fingerprint, userId, strategyVersionId, symbol, candleClose, closePrice, Instant.now().toString());
            signalsByKey.put(key, created);
            outboxByKey.put(key, new StoredOutbox("worker-outbox-" + sequence.incrementAndGet(), key, id, "PENDING", Instant.now().toString()));
            inserted++;
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("cycleId", cycleId);
        report.put("signalsInserted", inserted);
        report.put("signalsDeduplicated", deduplicated);
        report.put("outboxCreated", inserted);
        report.put("ordersFilled", 0);
        report.put("ordersRejected", 0);
        report.put("rankedOrdersCreated", 0);
        report.put("rankedOrdersExpired", 0);
        report.put("rankedSellRetries", 0);
        report.put("positionsOpened", 0);
        report.put("positionsClosed", 0);
        report.put("sellSignalsCreated", 0);
        report.put("rankedSellSignalsCreated", 0);
        report.put("executions", 0);
        return report;
    }

    public Map<String, Object> state() {
        return Map.of(
            "provider", "mock",
            "database", "memory",
            "signals", new ArrayList<>(signalsByKey.values()),
            "outbox", new ArrayList<>(outboxByKey.values()),
            "paperOrders", List.of(),
            "portfolios", List.of(),
            "positions", List.of(),
            "executions", List.of()
        );
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> objectList(Object value, String field) {
        if (!(value instanceof List<?> values)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " 입력이 올바르지 않습니다.");
        }
        if (values.size() > 10_000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " 항목이 너무 많습니다.");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof Map<?, ?> map)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " 입력이 올바르지 않습니다.");
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, entry) -> normalized.put(String.valueOf(key), entry));
            result.add(normalized);
        }
        return result;
    }

    private String requiredString(Map<String, Object> value, String field, int maximumLength) {
        Object raw = value.get(field);
        if (!(raw instanceof String text) || text.isBlank() || text.length() > maximumLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " 입력이 올바르지 않습니다.");
        }
        return text;
    }

    private String requiredSymbol(Map<String, Object> value, String field) {
        String symbol = requiredString(value, field, 6);
        if (!symbol.matches("\\d{6}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "종목 코드가 올바르지 않습니다.");
        return symbol;
    }

    private String requiredDate(Map<String, Object> value, String field) {
        String date = requiredString(value, field, 10);
        if (!date.matches("\\d{4}-\\d{2}-\\d{2}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " 입력이 올바르지 않습니다.");
        return date;
    }

    private record StoredSignal(String id, String key, String fingerprint, String userId, String strategyVersionId,
                                String symbol, String candleClose, String closePrice, String createdAt) {}
    private record StoredOutbox(String id, String sourceKey, String signalId, String state, String createdAt) {}
}
