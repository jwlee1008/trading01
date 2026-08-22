package com.signallab.worker.domain.marketdata.service;

import com.signallab.domain.demo.IndicatorTestPattern;
import com.signallab.worker.global.config.WorkerProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DemoTop50DataService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final JdbcTemplate jdbc;

    public DemoTop50DataService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public MarketDataImportService.Report prepare(WorkerProperties properties) {
        if (!properties.isDemoTop50Enabled()) throw new IllegalStateException("DEMO_TOP50_ENABLED=true is required");
        if (properties.getDemoTop50RealCount() != 10 || properties.getDemoTop50SyntheticCount() != 40)
            throw new IllegalArgumentException("Demo TOP50 requires exactly 10 provider and 40 synthetic instruments");
        LocalDate through = optionalDate(properties.getMarketDataThrough(), LocalDate.now(SEOUL));
        LocalDate from = optionalDate(properties.getMarketDataFrom(), through.minusYears(1));
        if (from.isAfter(through)) throw new IllegalArgumentException("BACKFILL_FROM must not be after BACKFILL_THROUGH");

        List<UUID> providerIds = latestUniverseMembers("KOSPI_TOP_10");
        if (providerIds.size() != 10) throw new IllegalStateException("Finalize KOSPI_TOP_10 before preparing DEMO_TOP_50");
        List<UUID> syntheticIds = new ArrayList<>();
        for (int index = 1; index <= 40; index++) syntheticIds.add(upsertSyntheticInstrument(index));

        UUID definitionId = ensureDefinition();
        String revision = "demo-top50:" + properties.getDemoTop50Seed() + ":" + through;
        UUID versionId = ensureVersion(definitionId, revision, through);
        jdbc.update("DELETE FROM universe_memberships WHERE universe_version_id=?", versionId);
        int position = 0;
        for (UUID id : providerIds) addMembership(versionId, id, through, "provider-demo", ++position);
        for (UUID id : syntheticIds) addMembership(versionId, id, through, "synthetic", ++position);

        int candles = 0;
        for (int index = 0; index < syntheticIds.size(); index++) {
            SyntheticSettings settings = settings(syntheticIds.get(index), index);
            for (LocalDate date = from; !date.isAfter(through); date = date.plusDays(1)) {
                if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
                upsertSyntheticCandle(syntheticIds.get(index), index, date, from, properties.getDemoTop50Seed(), settings);
                candles++;
            }
            applyTestPattern(syntheticIds.get(index), settings.testPattern());
        }
        jdbc.update("UPDATE universe_versions SET finalized_at=now() WHERE id=?", versionId);
        return new MarketDataImportService.Report("prepare-demo-top50", 50, candles, 0, 0);
    }

    private List<UUID> latestUniverseMembers(String kind) {
        return jdbc.query("""
            SELECT um.instrument_id FROM universe_memberships um
            WHERE um.universe_version_id=(SELECT uv.id FROM universe_versions uv
              JOIN universe_definitions ud ON ud.id=uv.universe_definition_id
              WHERE ud.kind=?::universe_kind AND uv.finalized_at IS NOT NULL
              ORDER BY uv.effective_from DESC,uv.version DESC LIMIT 1)
            ORDER BY um.id
            """, (rs, row) -> rs.getObject(1, UUID.class), kind);
    }

    private UUID upsertSyntheticInstrument(int index) {
        String symbol = "DEMO" + String.format("%02d", index);
        return jdbc.queryForObject("""
            INSERT INTO instruments(symbol,name_ko,market,kind,is_managed,is_trade_suspended,provider_refs)
            VALUES (?,?,'KOSPI','COMMON',false,false,jsonb_build_object('demo',true,'synthetic',true,'scenario',?))
            ON CONFLICT(symbol) DO UPDATE SET
              name_ko=CASE WHEN coalesce((instruments.provider_refs->>'uiConfigured')::boolean,false) THEN instruments.name_ko ELSE excluded.name_ko END,
              provider_refs=excluded.provider_refs || instruments.provider_refs,updated_at=now()
            RETURNING id
            """, UUID.class, symbol, "합성 시나리오 " + String.format("%02d", index), scenario(index));
    }

    private UUID ensureDefinition() {
        List<UUID> existing = jdbc.query("SELECT id FROM universe_definitions WHERE kind='DEMO_TOP_50'::universe_kind AND user_id IS NULL",
            (rs, row) -> rs.getObject(1, UUID.class));
        if (!existing.isEmpty()) return existing.getFirst();
        return jdbc.queryForObject("""
            INSERT INTO universe_definitions(kind,name_ko,description)
            VALUES ('DEMO_TOP_50','데모 TOP 50','키움 demo 10종목과 결정론적 합성 40종목') RETURNING id
            """, UUID.class);
    }

    private UUID ensureVersion(UUID definitionId, String revision, LocalDate effectiveFrom) {
        List<UUID> existing = jdbc.query("SELECT id FROM universe_versions WHERE universe_definition_id=? AND source_revision=?",
            (rs, row) -> rs.getObject(1, UUID.class), definitionId, revision);
        if (!existing.isEmpty()) return existing.getFirst();
        Integer version = jdbc.queryForObject("SELECT coalesce(max(version),0)+1 FROM universe_versions WHERE universe_definition_id=?", Integer.class, definitionId);
        return jdbc.queryForObject("""
            INSERT INTO universe_versions(universe_definition_id,version,effective_from,inclusion_policy,source,source_revision)
            VALUES (?,?,?,jsonb_build_object('demo',true,'providerCount',10,'syntheticCount',40),'demo-mixed',?) RETURNING id
            """, UUID.class, definitionId, version, effectiveFrom, revision);
    }

    private void addMembership(UUID versionId, UUID instrumentId, LocalDate effectiveFrom, String source, int rank) {
        jdbc.update("""
            INSERT INTO universe_memberships(universe_version_id,instrument_id,effective_from)
            VALUES (?,?,?) ON CONFLICT(universe_version_id,instrument_id,effective_from) DO NOTHING
            """, versionId, instrumentId, effectiveFrom);
    }

    private void upsertSyntheticCandle(UUID instrumentId, int index, LocalDate date, LocalDate from, long seed, SyntheticSettings settings) {
        long day = date.toEpochDay() - from.toEpochDay();
        double base = settings.basePrice();
        double trend = settings.trendPerDay() * day;
        if (settings.scenario().equals("REVERSAL") && day > 130) trend = -settings.trendPerDay() * (day - 260);
        double cycle = Math.sin((day + index * 7 + seed % 97) / (6d + index % 9)) * base * settings.volatilityPct();
        double shock = settings.scenario().equals("VOLATILE") && day % 47 == 0 ? base * Math.max(0.06, settings.volatilityPct() * 3) : 0d;
        double close = Math.max(1_000d, base + trend + cycle + shock);
        double open = close * (1d + Math.sin(day * 0.73 + index) * Math.min(0.02, settings.volatilityPct() / 3));
        double high = Math.max(open, close) * (1.01 + (index % 3) * 0.003);
        double low = Math.min(open, close) * (0.99 - (index % 2) * 0.003);
        long volume = settings.baseVolume() + Math.abs((day * 79_919L + seed + index * 104_729L) % Math.max(1, settings.baseVolume() * 3));
        jdbc.update("""
            INSERT INTO candles(instrument_id,timeframe,session_date,open_at,close_at,open,high,low,close,adjusted_close,
              volume,is_final,is_stale,provider,provider_revision,dataset_version,received_at)
            VALUES (?,'D1',?,?,?,?,?,?,?,?,?,true,false,'synthetic-demo',?,'demo-top50-v1',now())
            ON CONFLICT(instrument_id,timeframe,close_at) DO UPDATE SET open=excluded.open,high=excluded.high,low=excluded.low,
              close=excluded.close,adjusted_close=excluded.adjusted_close,volume=excluded.volume,is_final=true,is_stale=false,
              provider=excluded.provider,provider_revision=excluded.provider_revision,dataset_version=excluded.dataset_version
            """, instrumentId, date, timestamp(date, LocalTime.of(9,0)), timestamp(date, LocalTime.of(15,30)),
            decimal(open), decimal(high), decimal(low), decimal(close), decimal(close), volume, "seed:" + seed + ":" + settings.scenario());
    }

    private SyntheticSettings settings(UUID instrumentId, int index) {
        return jdbc.queryForObject("""
            SELECT coalesce(provider_refs->>'scenario',?),
              coalesce((provider_refs->>'basePrice')::double precision,?),
              coalesce((provider_refs->>'trendPerDay')::double precision,?),
              coalesce((provider_refs->>'volatilityPct')::double precision,?),
              coalesce((provider_refs->>'baseVolume')::bigint,?),coalesce(provider_refs->>'testPattern','NONE')
            FROM instruments WHERE id=?
            """, (rs, row) -> new SyntheticSettings(rs.getString(1),rs.getDouble(2),rs.getDouble(3),rs.getDouble(4),rs.getLong(5),rs.getString(6)),
            scenario(index + 1), 20_000d + index * 3_000d, defaultTrend(index), 0.025 + (index % 4) * 0.008, 100_000L + index * 15_000L, instrumentId);
    }

    private void applyTestPattern(UUID instrumentId, String pattern) {
        if (IndicatorTestPattern.NONE.equalsIgnoreCase(pattern)) return;
        List<CandleClose> candles = jdbc.query("SELECT id,close FROM candles WHERE instrument_id=? AND timeframe='D1' ORDER BY session_date",
            (rs,row) -> new CandleClose(rs.getObject(1,UUID.class),rs.getDouble(2)),instrumentId);
        IndicatorTestPattern.Result result = IndicatorTestPattern.apply(candles.stream().map(CandleClose::close).toList(),pattern);
        for(int i=0;i<candles.size();i++) {
            double close=result.closes().get(i);
            jdbc.update("UPDATE candles SET open=?,high=?,low=?,close=?,adjusted_close=?,provider_revision=?,dataset_version='demo-top50-pattern-v1' WHERE id=?",
                decimal(close*.997),decimal(close*1.012),decimal(close*.985),decimal(close),decimal(close),"indicator-test:"+pattern,candles.get(i).id());
        }
    }

    private double defaultTrend(int index) {
        return switch (index % 5) { case 0 -> 35d; case 1 -> -18d; case 2 -> 0d; case 3 -> 10d; default -> 4d; };
    }

    private String scenario(int index) {
        return switch ((index - 1) % 5) { case 0 -> "UPTREND"; case 1 -> "DOWNTREND"; case 2 -> "SIDEWAYS"; case 3 -> "VOLATILE"; default -> "REVERSAL"; };
    }
    private BigDecimal decimal(double value) { return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP); }
    private LocalDate optionalDate(String value, LocalDate fallback) { return value == null || value.isBlank() ? fallback : LocalDate.parse(value); }
    private Timestamp timestamp(LocalDate date, LocalTime time) { return Timestamp.from(ZonedDateTime.of(date,time,SEOUL).toInstant()); }
    private record CandleClose(UUID id,double close) {}
    private record SyntheticSettings(String scenario, double basePrice, double trendPerDay, double volatilityPct, long baseVolume, String testPattern) {}
}
