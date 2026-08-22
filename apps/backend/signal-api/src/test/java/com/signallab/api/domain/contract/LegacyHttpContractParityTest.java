package com.signallab.api.domain.contract;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@SpringBootTest
@TestPropertySource(properties = {"signal.auth-mode=mock", "signal.data-store=mock"})
class LegacyHttpContractParityTest {
    private static final Set<String> LEGACY_CONTRACT = Set.of(
        "GET /v1/health", "GET /v1/provider/status", "GET /v1/catalog",
        "GET /v1/watchlist", "POST /v1/watchlist/{symbol}", "DELETE /v1/watchlist/{symbol}",
        "GET /v1/strategies", "POST /v1/strategies", "POST /v1/strategies/{strategyId}/versions",
        "GET /v1/signals", "GET /v1/signals/{signalId}", "PATCH /v1/signals/{signalId}/acknowledge",
        "GET /v1/portfolios", "POST /v1/portfolios/{portfolioId}/executions",
        "GET /v1/paper-orders", "POST /v1/paper-orders", "POST /v1/paper-orders/{orderId}/cancel",
        "GET /v1/positions", "POST /v1/positions/{positionId}/sell-rules",
        "GET /v1/rankings", "POST /v1/rankings/combinations/{combinationId}/copy",
        "GET /v1/profiles/{userId}/public", "PUT /v1/me/visibility", "GET /v1/me/entitlements",
        "GET /v1/alerts", "GET /v1/alert-settings", "PUT /v1/alert-settings",
        "POST /v1/profiles/{userId}/reports", "DELETE /v1/me",
        "POST /v1/internal/worker/cycle", "GET /v1/internal/worker/state"
    );

    @Autowired
    private RequestMappingHandlerMapping mappings;

    @Test
    void springExposesEveryLegacyNestHttpRoute() {
        Set<String> actual = new HashSet<>();
        mappings.getHandlerMethods().forEach((info, handler) -> {
            if (!handler.getBeanType().getPackageName().startsWith("com.signallab")) return;
            for (String path : info.getPatternValues()) {
                info.getMethodsCondition().getMethods().forEach(method -> actual.add(method.name() + " " + path));
            }
        });
        Set<String> missing = new HashSet<>(LEGACY_CONTRACT);
        missing.removeAll(actual);
        assertTrue(missing.isEmpty(), () -> "Spring API is missing legacy routes: " + missing + "; actual=" + actual);
    }
}
