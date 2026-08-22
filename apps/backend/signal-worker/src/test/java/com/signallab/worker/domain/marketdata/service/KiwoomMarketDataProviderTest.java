package com.signallab.worker.domain.marketdata.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KiwoomMarketDataProviderTest {

    @Test
    void tokenAndContinuationHeadersMapDailyCandles() {
        FakeTransport transport = new FakeTransport(List.of(
            response("{\"return_code\":0,\"token\":\"token-1\",\"expires_dt\":\"20260818150000\"}"),
            new KiwoomMarketDataProvider.Response(200, Map.of("cont-yn", "Y", "next-key", "page-2"),
                "{\"return_code\":0,\"stk_dt_pole_chart_qry\":[{\"dt\":\"20260814\",\"open_pric\":\"+79000\",\"high_pric\":\"81,000\",\"low_pric\":\"78,500\",\"cur_prc\":\"80,000\",\"trde_qty\":\"1,234,567\"}]}"),
            response("{\"return_code\":0,\"stk_dt_pole_chart_qry\":[{\"dt\":\"20260813\",\"open_pric\":\"78000\",\"high_pric\":\"79500\",\"low_pric\":\"77000\",\"cur_prc\":\"79000\",\"trde_qty\":\"900000\"}]}")
        ));
        KiwoomMarketDataProvider provider = provider(transport);

        List<KiwoomMarketDataProvider.Candle> candles = provider.historicalCandles(
            "005930", LocalDate.parse("2026-08-13"), LocalDate.parse("2026-08-14")
        );

        assertEquals(List.of(LocalDate.parse("2026-08-13"), LocalDate.parse("2026-08-14")),
            candles.stream().map(KiwoomMarketDataProvider.Candle::sessionDate).toList());
        assertEquals(80_000, candles.get(1).close());
        assertTrue(candles.get(1).completed());
        assertEquals("Bearer token-1", transport.calls.get(1).headers().get("authorization"));
        assertEquals("page-2", transport.calls.get(2).headers().get("next-key"));
    }

    @Test
    void listedInstrumentImportFiltersInvalidAndNxtCodes() {
        FakeTransport transport = new FakeTransport(List.of(
            response("{\"return_code\":0,\"token\":\"token-1\",\"expires_dt\":\"20260818150000\"}"),
            response("{\"return_code\":0,\"list\":[{\"stk_cd\":\"005930\",\"stk_nm\":\"삼성전자\",\"isin\":\"KR7005930003\"},{\"stk_cd\":\"000000_NX\",\"stk_nm\":\"NXT제외\"},{\"stk_cd\":\"ABC\",\"stk_nm\":\"오류\"}]}"),
            response("{\"return_code\":0,\"list\":[{\"stk_cd\":\"247540\",\"stk_nm\":\"에코프로비엠\"}]}" )
        ));

        List<KiwoomMarketDataProvider.ListedInstrument> result = provider(transport).listedInstruments();

        assertEquals(List.of("005930", "247540"), result.stream().map(KiwoomMarketDataProvider.ListedInstrument::symbol).toList());
        assertEquals(KiwoomMarketDataProvider.Market.KOSDAQ, result.get(1).market());
    }

    @Test
    void stockInfoMapsMarketCapitalization() {
        FakeTransport transport = new FakeTransport(List.of(
            response("{\"return_code\":0,\"token\":\"token-1\",\"expires_dt\":\"20260818150000\"}"),
            response("{\"return_code\":0,\"stk_cd\":\"005930\",\"mac\":\"+3,456,789\"}")
        ));

        KiwoomMarketDataProvider.MarketCapitalization result =
            provider(transport).marketCapitalization("005930");

        assertEquals("005930", result.symbol());
        assertEquals(new BigDecimal("3456789"), result.value());
    }

    @Test
    void mockCalendarAndPageOverflowFailClosed() {
        TradingCalendar calendar = new TradingCalendar(Set.of(), Set.of());
        assertThrows(IllegalArgumentException.class, () -> new KiwoomMarketDataProvider(
            new KiwoomMarketDataProvider.Config("https://mockapi.kiwoom.com", "key", "secret", 1,
                "krx-mock-v1", calendar, "ka10099", "/api/dostk/stkinfo", List.of(), 0, 3),
            new ObjectMapper(), new FakeTransport(List.of()), Clock.systemUTC()
        ));
    }

    private KiwoomMarketDataProvider provider(FakeTransport transport) {
        TradingCalendar calendar = new TradingCalendar(Set.of(), Set.of());
        return new KiwoomMarketDataProvider(
            new KiwoomMarketDataProvider.Config("https://mockapi.kiwoom.com", "key", "secret", 30,
                "krx-licensed-test-v1", calendar, "ka10099", "/api/dostk/stkinfo",
                List.of(new KiwoomMarketDataProvider.MarketSpec("0", KiwoomMarketDataProvider.Market.KOSPI),
                    new KiwoomMarketDataProvider.MarketSpec("10", KiwoomMarketDataProvider.Market.KOSDAQ)), 0, 3),
            new ObjectMapper(), transport,
            Clock.fixed(Instant.parse("2026-08-17T07:00:00Z"), ZoneOffset.UTC)
        );
    }

    private static KiwoomMarketDataProvider.Response response(String body) {
        return new KiwoomMarketDataProvider.Response(200, Map.of(), body);
    }

    private static final class FakeTransport implements KiwoomMarketDataProvider.Transport {
        private final ArrayDeque<KiwoomMarketDataProvider.Response> responses;
        private final List<Call> calls = new ArrayList<>();
        private FakeTransport(List<KiwoomMarketDataProvider.Response> responses) { this.responses = new ArrayDeque<>(responses); }
        @Override public KiwoomMarketDataProvider.Response post(String url, Map<String, String> headers, String body) {
            calls.add(new Call(url, Map.copyOf(headers), body));
            return responses.removeFirst();
        }
    }
    private record Call(String url, Map<String, String> headers, String body) {}
}
