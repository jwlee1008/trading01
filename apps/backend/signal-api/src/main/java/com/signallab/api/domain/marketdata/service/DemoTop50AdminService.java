package com.signallab.api.domain.marketdata.service;

import com.signallab.domain.demo.IndicatorTestPattern;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DemoTop50AdminService {
    private static final long SEED = 20260822L;
    private static final Set<String> SCENARIOS = Set.of("UPTREND", "DOWNTREND", "SIDEWAYS", "VOLATILE", "REVERSAL");
    private final JdbcTemplate jdbc;

    public DemoTop50AdminService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<InstrumentView> list() {
        return jdbc.query("""
            WITH latest_version AS (
              SELECT uv.id FROM universe_versions uv
              JOIN universe_definitions ud ON ud.id=uv.universe_definition_id
              WHERE ud.kind='DEMO_TOP_50'::universe_kind AND uv.finalized_at IS NOT NULL
              ORDER BY uv.effective_from DESC,uv.version DESC LIMIT 1
            ), stats AS (
              SELECT instrument_id,count(*) candle_count,min(session_date) first_date,max(session_date) last_date
              FROM candles WHERE timeframe='D1' AND is_final=true GROUP BY instrument_id
            )
            SELECT i.symbol,i.name_ko,(i.provider_refs->>'synthetic')::boolean AS synthetic,
              i.provider_refs->>'scenario',i.provider_refs->>'basePrice',i.provider_refs->>'trendPerDay',
              i.provider_refs->>'volatilityPct',i.provider_refs->>'baseVolume',coalesce(i.provider_refs->>'testPattern','NONE'),
              coalesce(s.candle_count,0),s.first_date,s.last_date,
              c.open,c.high,c.low,c.close,c.volume,c.provider
            FROM universe_memberships um JOIN latest_version lv ON lv.id=um.universe_version_id
            JOIN instruments i ON i.id=um.instrument_id LEFT JOIN stats s ON s.instrument_id=i.id
            LEFT JOIN LATERAL (
              SELECT open,high,low,close,volume,provider FROM candles
              WHERE instrument_id=i.id AND timeframe='D1' AND is_final=true ORDER BY session_date DESC LIMIT 1
            ) c ON true
            ORDER BY CASE WHEN coalesce((i.provider_refs->>'synthetic')::boolean,false) THEN 1 ELSE 0 END,i.symbol
            """, (rs, row) -> {
                String symbol = rs.getString(1);
                boolean synthetic = rs.getBoolean(3);
                Defaults defaults = defaults(symbol);
                return new InstrumentView(symbol, rs.getString(2), synthetic ? "SYNTHETIC" : "PROVIDER", synthetic,
                    value(rs.getString(4), defaults.scenario()), decimal(rs.getString(5), defaults.basePrice()),
                    decimal(rs.getString(6), defaults.trendPerDay()), decimal(rs.getString(7), defaults.volatilityPct()),
                    longValue(rs.getString(8), defaults.baseVolume()), rs.getString(9), rs.getLong(10), localDate(rs.getDate(11)), localDate(rs.getDate(12)),
                    rs.getBigDecimal(13), rs.getBigDecimal(14), rs.getBigDecimal(15), rs.getBigDecimal(16), rs.getBigDecimal(17), rs.getString(18));
            });
    }

    @Transactional
    public InstrumentView update(String rawSymbol, UpdateRequest request) {
        String symbol = rawSymbol.toUpperCase(Locale.ROOT);
        if (!symbol.matches("DEMO(0[1-9]|[1-3][0-9]|40)"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "합성 종목 DEMO01~DEMO40만 수정할 수 있습니다.");
        validate(request);
        UUID id = jdbc.query("SELECT id FROM instruments WHERE symbol=? AND coalesce((provider_refs->>'synthetic')::boolean,false)=true",
            (rs, row) -> rs.getObject(1, UUID.class), symbol).stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "데모 종목을 찾을 수 없습니다."));
        String scenario = request.scenario().toUpperCase(Locale.ROOT);
        jdbc.update("""
            UPDATE instruments SET name_ko=?,provider_refs=provider_refs || jsonb_build_object(
              'demo',true,'synthetic',true,'uiConfigured',true,'scenario',?,'basePrice',?,'trendPerDay',?,'volatilityPct',?,'baseVolume',?,'testPattern',?),updated_at=now()
            WHERE id=?
            """, request.name().trim(), scenario, request.basePrice(), request.trendPerDay(), request.volatilityPct(), request.baseVolume(),
            request.testPattern().toUpperCase(Locale.ROOT), id);

        List<LocalDate> dates = jdbc.query("SELECT session_date FROM candles WHERE instrument_id=? AND timeframe='D1' ORDER BY session_date",
            (rs, row) -> rs.getDate(1).toLocalDate(), id);
        if (dates.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "먼저 pnpm worker:prepare:demo-top50을 실행해 일봉을 준비하세요.");
        int index = Integer.parseInt(symbol.substring(4)) - 1;
        LocalDate from = dates.getFirst();
        for (LocalDate date : dates) regenerate(id, index, date, from, request);
        applyTestPattern(id, request.testPattern());
        return list().stream().filter(item -> item.symbol().equals(symbol)).findFirst().orElseThrow();
    }

    private void applyTestPattern(UUID id, String pattern) {
        if (IndicatorTestPattern.NONE.equalsIgnoreCase(pattern)) return;
        List<CandleClose> candles = jdbc.query("SELECT id,close FROM candles WHERE instrument_id=? AND timeframe='D1' ORDER BY session_date",
            (rs,row) -> new CandleClose(rs.getObject(1,UUID.class),rs.getDouble(2)),id);
        IndicatorTestPattern.Result result;
        try { result = IndicatorTestPattern.apply(candles.stream().map(CandleClose::close).toList(), pattern); }
        catch (IllegalArgumentException | IllegalStateException error) { throw new ResponseStatusException(HttpStatus.CONFLICT,error.getMessage()); }
        for(int i=0;i<candles.size();i++) {
            double close=result.closes().get(i), open=close*.997, high=close*1.012, low=close*.985;
            jdbc.update("UPDATE candles SET open=?,high=?,low=?,close=?,adjusted_close=?,provider_revision=? WHERE id=?",
                price(open),price(high),price(low),price(close),price(close),"indicator-test:"+pattern,candles.get(i).id());
        }
    }

    private void regenerate(UUID id, int index, LocalDate date, LocalDate from, UpdateRequest request) {
        long day = date.toEpochDay() - from.toEpochDay();
        double base = request.basePrice().doubleValue();
        double trend = request.trendPerDay().doubleValue() * day;
        double volatility = request.volatilityPct().doubleValue();
        double cycle = Math.sin((day + index * 7 + SEED % 97) / (6d + index % 9)) * base * volatility;
        double shock = request.scenario().equalsIgnoreCase("VOLATILE") && day % 47 == 0 ? base * Math.max(0.06, volatility * 3) : 0d;
        if (request.scenario().equalsIgnoreCase("REVERSAL") && day > 130) trend = -request.trendPerDay().doubleValue() * (day - 260);
        double close = Math.max(1_000d, base + trend + cycle + shock);
        double open = close * (1d + Math.sin(day * 0.73 + index) * Math.min(0.02, volatility / 3));
        double high = Math.max(open, close) * (1.01 + (index % 3) * 0.003);
        double low = Math.min(open, close) * (0.99 - (index % 2) * 0.003);
        long volume = request.baseVolume() + Math.abs((day * 79_919L + SEED + index * 104_729L) % Math.max(1, request.baseVolume() * 3));
        jdbc.update("""
            UPDATE candles SET open=?,high=?,low=?,close=?,adjusted_close=?,volume=?,provider='synthetic-demo',
              provider_revision=?,dataset_version='demo-top50-ui-v1',received_at=now()
            WHERE instrument_id=? AND timeframe='D1' AND session_date=?
            """, price(open), price(high), price(low), price(close), price(close), volume, "ui:" + request.scenario(), id, date);
    }

    private void validate(UpdateRequest r) {
        if (r == null || r.name() == null || r.name().trim().isEmpty() || r.name().trim().length() > 40)
            bad("종목명은 1~40자로 입력하세요.");
        if (r.scenario() == null || !SCENARIOS.contains(r.scenario().toUpperCase(Locale.ROOT))) bad("지원하지 않는 시나리오입니다.");
        if (r.testPattern() == null || !IndicatorTestPattern.supported().contains(r.testPattern().toUpperCase(Locale.ROOT))) bad("지원하지 않는 지표 테스트 패턴입니다.");
        range(r.basePrice(), new BigDecimal("1000"), new BigDecimal("10000000"), "기준가");
        range(r.trendPerDay(), new BigDecimal("-100000"), new BigDecimal("100000"), "일별 추세");
        range(r.volatilityPct(), BigDecimal.ZERO, new BigDecimal("0.5"), "변동성");
        if (r.baseVolume() < 1 || r.baseVolume() > 1_000_000_000L) bad("기준 거래량 범위를 확인하세요.");
    }
    private void range(BigDecimal value, BigDecimal min, BigDecimal max, String label) { if (value == null || value.compareTo(min)<0 || value.compareTo(max)>0) bad(label + " 범위를 확인하세요."); }
    private void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private BigDecimal price(double value) { return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP); }
    private LocalDate localDate(Date value) { return value == null ? null : value.toLocalDate(); }
    private String value(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private BigDecimal decimal(String value, BigDecimal fallback) { return value == null ? fallback : new BigDecimal(value); }
    private long longValue(String value, long fallback) { return value == null ? fallback : Long.parseLong(value); }
    private Defaults defaults(String symbol) {
        if (!symbol.startsWith("DEMO")) return new Defaults("", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0);
        int index = Integer.parseInt(symbol.substring(4)) - 1;
        String scenario = List.of("UPTREND","DOWNTREND","SIDEWAYS","VOLATILE","REVERSAL").get(index % 5);
        double trend = switch (index % 5) { case 0 -> 35; case 1 -> -18; case 2 -> 0; case 3 -> 10; default -> 4; };
        return new Defaults(scenario, BigDecimal.valueOf(20_000L + index * 3_000L), BigDecimal.valueOf(trend), BigDecimal.valueOf(0.025 + (index % 4) * 0.008), 100_000L + index * 15_000L);
    }

    private record Defaults(String scenario, BigDecimal basePrice, BigDecimal trendPerDay, BigDecimal volatilityPct, long baseVolume) {}
    private record CandleClose(UUID id,double close) {}
    public record UpdateRequest(String name, String scenario, BigDecimal basePrice, BigDecimal trendPerDay, BigDecimal volatilityPct, long baseVolume, String testPattern) {}
    public record InstrumentView(String symbol, String name, String source, boolean editable, String scenario, BigDecimal basePrice,
        BigDecimal trendPerDay, BigDecimal volatilityPct, long baseVolume, String testPattern, long candleCount, LocalDate firstDate, LocalDate lastDate,
        BigDecimal latestOpen, BigDecimal latestHigh, BigDecimal latestLow, BigDecimal latestClose, BigDecimal latestVolume, String provider) {}
}
