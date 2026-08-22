package com.signallab.worker.domain.marketdata.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.signallab.worker.global.config.WorkerProperties;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class KiwoomProviderFactory {
    private final ObjectMapper objectMapper;
    public KiwoomProviderFactory(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    public KiwoomMarketDataProvider create(WorkerProperties properties) {
        boolean demo = "demo".equals(properties.getKiwoomMode());
        if (!demo && !"real".equals(properties.getKiwoomMode())) throw new IllegalArgumentException("KIWOOM_MODE must be real or demo");
        String baseUrl = properties.getKiwoomBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = demo ? "https://mockapi.kiwoom.com" : "https://api.kiwoom.com";
        String appKey = demo && !properties.getKiwoomDemoAppKey().isBlank() ? properties.getKiwoomDemoAppKey() : properties.getKiwoomAppKey();
        String appSecret = demo && !properties.getKiwoomDemoAppSecret().isBlank() ? properties.getKiwoomDemoAppSecret() : properties.getKiwoomAppSecret();
        int requestDelayMs = demo ? Math.max(1_100, properties.getBackfillRequestDelayMs())
            : Math.max(250, properties.getBackfillRequestDelayMs());
        TradingCalendar calendar = createCalendar(properties);
        return new KiwoomMarketDataProvider(new KiwoomMarketDataProvider.Config(
            baseUrl, appKey, appSecret,
            properties.getKiwoomMaxPages(), properties.getMarketCalendarVersion(), calendar,
            "ka10099", "/api/dostk/stkinfo", List.of(
                new KiwoomMarketDataProvider.MarketSpec("0", KiwoomMarketDataProvider.Market.KOSPI),
                new KiwoomMarketDataProvider.MarketSpec("10", KiwoomMarketDataProvider.Market.KOSDAQ)
            ), requestDelayMs, properties.getBackfillMaxRetries()), objectMapper,
            new KiwoomMarketDataProvider.HttpTransport(), Clock.systemUTC());
    }

    public TradingCalendar createCalendar(WorkerProperties properties) {
        String version = properties.getMarketCalendarVersion();
        if (version == null || version.isBlank() || version.toLowerCase().contains("mock"))
            throw new IllegalArgumentException("MARKET_CALENDAR_VERSION must be verified and non-mock");
        return new TradingCalendar(dates(properties.getMarketCalendarHolidays()), dates(properties.getMarketCalendarExtraSessions()));
    }

    private Set<LocalDate> dates(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();
        return Arrays.stream(raw.split(",")).map(String::trim).filter(value -> !value.isEmpty())
            .map(LocalDate::parse).collect(Collectors.toUnmodifiableSet());
    }
}
