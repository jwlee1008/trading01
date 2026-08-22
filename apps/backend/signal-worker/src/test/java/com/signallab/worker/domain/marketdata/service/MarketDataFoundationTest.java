package com.signallab.worker.domain.marketdata.service;

import com.signallab.worker.global.config.WorkerProperties;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;

class MarketDataFoundationTest {

    private final TradingCalendar calendar = new TradingCalendar(
        Set.of(LocalDate.parse("2026-08-17")), Set.of()
    );

    @Test
    void calendarSkipsWeekendAndConfiguredHoliday() {
        assertEquals(LocalDate.parse("2026-08-18"), calendar.nextSessionAfter(LocalDate.parse("2026-08-14")));
        assertEquals(LocalDate.parse("2026-08-14"), calendar.latestSessionOnOrBefore(LocalDate.parse("2026-08-17")));
    }

    @Test
    void normalizerReportsOrderDuplicatesAndMissingSessions() {
        MarketCandleNormalizer normalizer = new MarketCandleNormalizer();
        MarketCandleNormalizer.Candle day14 = candle("2026-08-14", 100);
        MarketCandleNormalizer.Candle day18 = candle("2026-08-18", 110);
        MarketCandleNormalizer.Result result = normalizer.normalize(
            List.of(day18, day14, day14), calendar, LocalDate.parse("2026-08-19")
        );
        assertTrue(result.outOfOrder());
        assertEquals(List.of(LocalDate.parse("2026-08-14")), result.duplicateSessions());
        assertEquals(List.of(LocalDate.parse("2026-08-19")), result.missingSessions());
        assertFalse(result.safeToEvaluate());
    }

    @Test
    void malformedOhlcFailsClosed() {
        MarketCandleNormalizer normalizer = new MarketCandleNormalizer();
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize(
            List.of(new MarketCandleNormalizer.Candle(LocalDate.parse("2026-08-18"), 100, 90, 80, 95, 1)),
            calendar, LocalDate.parse("2026-08-18")
        ));
    }

    @Test
    void explicitRangeDetectsLeadingMissingSession() {
        MarketCandleNormalizer.Result result = new MarketCandleNormalizer().normalize(
            List.of(candle("2026-08-19", 100)), calendar,
            LocalDate.parse("2026-08-18"), LocalDate.parse("2026-08-19")
        );
        assertEquals(List.of(LocalDate.parse("2026-08-18")), result.missingSessions());
    }

    @Test
    void backfillRangesAreBoundedAndRetryHonorsRetryableFlag() {
        assertEquals(List.of(
            new MarketDataImportService.DateChunk(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-04")),
            new MarketDataImportService.DateChunk(LocalDate.parse("2026-01-05"), LocalDate.parse("2026-01-08")),
            new MarketDataImportService.DateChunk(LocalDate.parse("2026-01-09"), LocalDate.parse("2026-01-10"))
        ), MarketDataImportService.dateRangeChunks(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-10"), 4));

        AtomicInteger attempts = new AtomicInteger();
        String result = MarketDataImportService.retry(2, 0, () -> {
            if (attempts.incrementAndGet() < 3) throw new KiwoomMarketDataProvider.ProviderException(
                KiwoomMarketDataProvider.Code.RATE_LIMIT, "retry", true);
            return "ok";
        });
        assertEquals("ok", result);
        assertEquals(3, attempts.get());

        assertThrows(KiwoomMarketDataProvider.ProviderException.class, () ->
            MarketDataImportService.retry(3, 0, () -> { throw new KiwoomMarketDataProvider.ProviderException(
                KiwoomMarketDataProvider.Code.TOKEN_EXPIRED, "stop", false); })
        );
    }

    @Test
    void calendarDryRunNeedsNoKiwoomCredentialsOrDatabase() {
        WorkerProperties properties = new WorkerProperties();
        properties.setMarketDataAction("import-calendar");
        properties.setMarketCalendarVersion("krx-verified-test-v1");
        properties.setMarketCalendarHolidays("2026-08-17");
        properties.setMarketDataFrom("2026-08-14");
        properties.setMarketDataThrough("2026-08-18");
        properties.setBackfillDryRun(true);
        MarketDataImportService service = new MarketDataImportService(
            org.mockito.Mockito.mock(JdbcTemplate.class), new KiwoomProviderFactory(new ObjectMapper())
        );
        MarketDataImportService.Report report = service.run(properties);
        assertEquals("import-calendar", report.action());
        assertEquals(4, report.candles());
    }

    private MarketCandleNormalizer.Candle candle(String date, double close) {
        return new MarketCandleNormalizer.Candle(LocalDate.parse(date), close, close, close, close, 1000);
    }
}
