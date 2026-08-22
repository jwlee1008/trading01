package com.signallab.worker.domain.marketdata.service;

import com.signallab.worker.global.config.WorkerProperties;
import java.sql.Timestamp;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MarketDataImportService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final JdbcTemplate jdbc;
    private final KiwoomProviderFactory providerFactory;
    private final DemoTop50DataService demoTop50DataService;

    public MarketDataImportService(JdbcTemplate jdbc, KiwoomProviderFactory providerFactory, DemoTop50DataService demoTop50DataService) {
        this.jdbc = jdbc; this.providerFactory = providerFactory; this.demoTop50DataService = demoTop50DataService;
    }

    public Report run(WorkerProperties properties) {
        return switch (properties.getMarketDataAction()) {
            case "none", "" -> new Report("none", 0, 0, 0, 0);
            case "import-calendar" -> importCalendar(properties);
            case "import-instruments" -> importInstruments(properties);
            case "refresh-kospi-top10" -> refreshKospiTop10(properties);
            case "backfill-candles" -> backfillCandles(properties);
            case "backfill-kospi-top10" -> backfillCandles(properties);
            case "prepare-demo-top50" -> demoTop50DataService.prepare(properties);
            case "prepare" -> {
                Report instruments = importInstruments(properties);
                Report candles = backfillCandles(properties);
                yield new Report("prepare", instruments.instruments(), candles.candles(), candles.gaps(), candles.invalid());
            }
            default -> throw new IllegalArgumentException("Unsupported MARKET_DATA_ACTION=" + properties.getMarketDataAction());
        };
    }

    /** Idempotent catch-up used by the long-running Worker. Provider calls occur only when the latest session is missing. */
    public synchronized Report automaticTop10Refresh(WorkerProperties properties) {
        if (!properties.isDemoTop50Enabled()) throw new IllegalStateException("DEMO_TOP50_ENABLED=true is required for automatic TOP 10 refresh");
        KiwoomMarketDataProvider provider = providerFactory.create(properties);
        LocalDate through = provider.latestCompletedSession();
        if (automaticDataReady(through)) return new Report("automatic-top10-up-to-date", 10, 0, 0, 0);

        Integer eligible = jdbc.queryForObject("""
            SELECT count(*) FROM instruments WHERE market='KOSPI' AND kind='COMMON' AND delisted_on IS NULL
              AND is_managed=false AND is_trade_suspended=false
            """, Integer.class);
        if (eligible == null || eligible < 10) importInstruments(properties);

        String previousFrom = properties.getMarketDataFrom();
        String previousThrough = properties.getMarketDataThrough();
        String previousAsOf = properties.getTop100AsOf();
        int previousMaxInstruments = properties.getBackfillMaxInstruments();
        try {
            Integer kiwoomCandles = jdbc.queryForObject("SELECT count(*) FROM candles WHERE provider='kiwoom'", Integer.class);
            Integer syntheticCandles = jdbc.queryForObject("SELECT count(*) FROM candles WHERE provider='synthetic-demo'", Integer.class);
            boolean initialHistory = kiwoomCandles == null || kiwoomCandles < 2_000 || syntheticCandles == null || syntheticCandles < 3_200;
            properties.setTop100AsOf(through.toString());
            properties.setMarketDataThrough(through.toString());
            properties.setMarketDataFrom((initialHistory ? through.minusYears(1) : through.minusDays(14)).toString());
            properties.setBackfillMaxInstruments(10);
            Report universe = refreshKospiTop10(properties);
            Report candles = backfillCandlesForTop10(properties);
            Report demo = demoTop50DataService.prepare(properties);
            return new Report("automatic-top10-refreshed", universe.instruments(), candles.candles() + demo.candles(),
                candles.gaps(), universe.invalid() + candles.invalid() + demo.invalid());
        } finally {
            properties.setMarketDataFrom(previousFrom);
            properties.setMarketDataThrough(previousThrough);
            properties.setTop100AsOf(previousAsOf);
            properties.setBackfillMaxInstruments(previousMaxInstruments);
        }
    }

    private Report backfillCandlesForTop10(WorkerProperties properties) {
        String previousAction = properties.getMarketDataAction();
        try { properties.setMarketDataAction("backfill-kospi-top10"); return backfillCandles(properties); }
        finally { properties.setMarketDataAction(previousAction); }
    }

    private boolean automaticDataReady(LocalDate through) {
        Integer count = jdbc.queryForObject("""
            SELECT count(*) FROM universe_memberships um JOIN instruments i ON i.id=um.instrument_id
            WHERE um.universe_version_id=(SELECT uv.id FROM universe_versions uv JOIN universe_definitions ud
              ON ud.id=uv.universe_definition_id WHERE ud.kind='KOSPI_TOP_10'::universe_kind
              AND uv.finalized_at IS NOT NULL AND uv.effective_from=? ORDER BY uv.version DESC LIMIT 1)
              AND EXISTS (SELECT 1 FROM candles c WHERE c.instrument_id=i.id AND c.timeframe='D1' AND c.session_date=? AND c.is_final)
            """, Integer.class, through, through);
        Integer demo = jdbc.queryForObject("""
            SELECT count(*) FROM universe_memberships um WHERE um.universe_version_id=(SELECT uv.id FROM universe_versions uv
              JOIN universe_definitions ud ON ud.id=uv.universe_definition_id WHERE ud.kind='DEMO_TOP_50'::universe_kind
              AND uv.finalized_at IS NOT NULL AND uv.effective_from=? ORDER BY uv.version DESC LIMIT 1)
            """, Integer.class, through);
        return count != null && count == 10 && demo != null && demo == 50;
    }

    private Report importCalendar(WorkerProperties properties) {
        TradingCalendar calendar = providerFactory.createCalendar(properties);
        LocalDate from = requiredDate(properties.getMarketDataFrom(), "MARKET_CALENDAR_FROM/BACKFILL_FROM");
        LocalDate through = requiredDate(properties.getMarketDataThrough(), "MARKET_CALENDAR_THROUGH/BACKFILL_THROUGH");
        if (from.isAfter(through)) throw new IllegalArgumentException("calendar from must not be after through");
        int sessions = 0;
        for (LocalDate cursor = from; !cursor.isAfter(through); cursor = cursor.plusDays(1)) {
            if (!calendar.isSession(cursor)) continue;
            sessions++;
            if (properties.isBackfillDryRun()) continue;
            for (String market : List.of("KOSPI", "KOSDAQ")) jdbc.update("""
                INSERT INTO market_sessions (calendar_version,market,session_date,is_trading_day,open_at,close_at,order_cutoff_at,note)
                VALUES (?,?::market_code,?,true,?,?,?,'calendar-import')
                ON CONFLICT(calendar_version,market,session_date) DO UPDATE SET is_trading_day=true,open_at=excluded.open_at,
                  close_at=excluded.close_at,order_cutoff_at=excluded.order_cutoff_at,note=excluded.note
                """, properties.getMarketCalendarVersion(), market, cursor, timestamp(cursor, LocalTime.of(9,0)),
                timestamp(cursor, LocalTime.of(15,30)), timestamp(cursor, LocalTime.of(9,0)));
        }
        return new Report("import-calendar", 0, sessions * 2, 0, 0);
    }

    private Report importInstruments(WorkerProperties properties) {
        KiwoomMarketDataProvider provider = providerFactory.create(properties);
        List<KiwoomMarketDataProvider.ListedInstrument> rows = provider.listedInstruments();
        if (properties.isBackfillDryRun()) return new Report("import-instruments", rows.size(), 0, 0, 0);
        for (var row : rows) jdbc.update("""
            INSERT INTO instruments (symbol, name_ko, market, kind, isin, is_managed, is_trade_suspended, provider_refs)
            VALUES (?, ?, ?::market_code, ?::instrument_kind, ?, ?, ?, jsonb_build_object('kiwoom', jsonb_build_object('symbol', ?)))
            ON CONFLICT (symbol) DO UPDATE SET name_ko=excluded.name_ko, market=excluded.market, kind=excluded.kind,
              isin=excluded.isin, is_managed=excluded.is_managed, is_trade_suspended=excluded.is_trade_suspended,
              provider_refs=excluded.provider_refs, updated_at=now()
            """, row.symbol(), row.name(), row.market().name(), row.kind().name(), row.isin(), row.managed(),
            row.tradeSuspended(), row.symbol());
        refreshUniverses(rows, properties, provider.calendarVersion());
        return new Report("import-instruments", rows.size(), 0, 0, 0);
    }

    private Report refreshKospiTop10(WorkerProperties properties) {
        KiwoomMarketDataProvider provider = providerFactory.create(properties);
        LocalDate asOf = properties.getTop100AsOf() == null || properties.getTop100AsOf().isBlank()
            ? provider.latestCompletedSession()
            : requiredDate(properties.getTop100AsOf(), "TOP100_AS_OF");
        List<Top100Candidate> ranked = jdbc.query("""
            SELECT i.id,i.symbol,i.name_ko,m.market_cap
            FROM market_cap_snapshots m JOIN instruments i ON i.id=m.instrument_id
            WHERE m.session_date=? AND m.rank<=10 ORDER BY m.rank
            """, (rs, index) -> new Top100Candidate(UUID.fromString(rs.getString("id")),
                rs.getString("symbol"), rs.getString("name_ko"), rs.getBigDecimal("market_cap")), asOf);
        int invalid = 0;
        if (ranked.size() != 10) {
            List<Top100Candidate> candidates = jdbc.query("""
                SELECT id, symbol, name_ko FROM instruments
                WHERE market='KOSPI' AND kind='COMMON' AND delisted_on IS NULL
                  AND is_managed=false AND is_trade_suspended=false
                ORDER BY symbol
                """, (rs, index) -> new Top100Candidate(
                    UUID.fromString(rs.getString("id")), rs.getString("symbol"), rs.getString("name_ko"), null));
            if (candidates.size() < 10) throw new IllegalStateException("At least 10 eligible KOSPI instruments are required");
            List<Top100Candidate> fetched = new ArrayList<>();
            for (Top100Candidate candidate : candidates) {
                try {
                    KiwoomMarketDataProvider.MarketCapitalization result = retry(
                        properties.getBackfillMaxRetries(), properties.getBackfillRequestDelayMs(),
                        () -> provider.marketCapitalization(candidate.symbol()));
                    fetched.add(new Top100Candidate(candidate.instrumentId(), candidate.symbol(), candidate.name(), result.value()));
                } catch (RuntimeException error) {
                    invalid++;
                }
            }
            ranked = fetched.stream()
                .sorted(Comparator.comparing(Top100Candidate::marketCap).reversed()
                    .thenComparing(Top100Candidate::symbol))
                .limit(10)
                .toList();
        }
        if (ranked.size() != 10)
            throw new IllegalStateException("Unable to determine 10 KOSPI instruments; valid=" + ranked.size() + ", invalid=" + invalid);
        if (properties.isBackfillDryRun()) return new Report("refresh-kospi-top10", 10, 0, 0, invalid);

        String revision = "kiwoom-ka10001-market-cap:" + asOf;
        UUID definitionId = ensureUniverseDefinition("KOSPI_TOP_10");
        UniverseVersion version = ensureUniverseVersion(definitionId, revision, asOf);
        if (version.finalized()) return new Report("refresh-kospi-top10", 10, 0, 0, invalid);

        jdbc.update("DELETE FROM market_cap_snapshots WHERE session_date=?", asOf);
        int rank = 1;
        for (Top100Candidate candidate : ranked) {
            jdbc.update("""
                INSERT INTO market_cap_snapshots
                  (session_date,instrument_id,market_cap,rank,source,source_revision)
                VALUES (?,?,?,?,'kiwoom-ka10001',?)
                """, asOf, candidate.instrumentId(), candidate.marketCap(), rank, revision);
            jdbc.update("""
                INSERT INTO universe_memberships (universe_version_id,instrument_id,effective_from)
                VALUES (?,?,?) ON CONFLICT (universe_version_id,instrument_id,effective_from) DO NOTHING
                """, version.id(), candidate.instrumentId(), asOf);
            rank++;
        }
        Integer memberCount = jdbc.queryForObject(
            "SELECT count(*) FROM universe_memberships WHERE universe_version_id=?", Integer.class, version.id());
        if (memberCount == null || memberCount != 10)
            throw new IllegalStateException("KOSPI Top 10 universe must contain exactly 10 instruments");
        jdbc.update("UPDATE universe_versions SET finalized_at=now() WHERE id=? AND finalized_at IS NULL", version.id());
        return new Report("refresh-kospi-top10", 10, 0, 0, invalid);
    }

    private void refreshUniverses(List<KiwoomMarketDataProvider.ListedInstrument> rows, WorkerProperties properties,
                                  String calendarVersion) {
        LocalDate effectiveFrom = requiredDate(properties.getUniverseEffectiveFrom(), "MARKET_DATA_UNIVERSE_EFFECTIVE_FROM");
        String baseRevision = properties.getUniverseSourceRevision();
        if (baseRevision == null || baseRevision.isBlank())
            baseRevision = "kiwoom-master:" + effectiveFrom + ":" + calendarVersion;
        for (String kind : List.of("KOSPI_ALL", "KOSDAQ_ALL", "KR_ALL")) {
            UUID definitionId = ensureUniverseDefinition(kind);
            String revision = baseRevision + ":" + kind;
            UniverseVersion version = ensureUniverseVersion(definitionId, revision, effectiveFrom);
            if (version.finalized()) continue;
            for (var row : rows) {
                boolean member = !row.tradeSuspended() && ("KR_ALL".equals(kind) || row.market().name().equals(kind.replace("_ALL", "")));
                if (!member) continue;
                UUID instrumentId = jdbc.query("SELECT id FROM instruments WHERE symbol=?",
                    rs -> rs.next() ? UUID.fromString(rs.getString(1)) : null, row.symbol());
                if (instrumentId != null) jdbc.update("""
                    INSERT INTO universe_memberships (universe_version_id, instrument_id, effective_from)
                    VALUES (?, ?, ?) ON CONFLICT (universe_version_id, instrument_id, effective_from) DO NOTHING
                    """, version.id(), instrumentId, effectiveFrom);
            }
            jdbc.update("UPDATE universe_versions SET finalized_at=now() WHERE id=? AND finalized_at IS NULL", version.id());
        }
    }

    private UUID ensureUniverseDefinition(String kind) {
        UUID existing = jdbc.query("SELECT id FROM universe_definitions WHERE kind=?::universe_kind AND user_id IS NULL",
            rs -> rs.next() ? UUID.fromString(rs.getString(1)) : null, kind);
        if (existing != null) return existing;
        String name = switch (kind) {
            case "KOSPI_ALL" -> "코스피 전체";
            case "KOSDAQ_ALL" -> "코스닥 전체";
            case "KOSPI_TOP_100" -> "코스피 시가총액 상위 100";
            case "KOSPI_TOP_10" -> "코스피 시가총액 상위 10";
            default -> "한국 전체";
        };
        return jdbc.queryForObject("""
            INSERT INTO universe_definitions (kind, name_ko, description)
            VALUES (?::universe_kind, ?, '키움 시장 데이터 기반') RETURNING id
            """, UUID.class, kind, name);
    }

    private UniverseVersion ensureUniverseVersion(UUID definitionId, String revision, LocalDate effectiveFrom) {
        UniverseVersion existing = jdbc.query("""
            SELECT id, finalized_at IS NOT NULL AS finalized FROM universe_versions
            WHERE universe_definition_id=? AND source_revision=? LIMIT 1
            """, rs -> rs.next() ? new UniverseVersion(UUID.fromString(rs.getString("id")), rs.getBoolean("finalized")) : null,
            definitionId, revision);
        if (existing != null) return existing;
        Integer versionNumber = jdbc.queryForObject(
            "SELECT coalesce(max(version),0)+1 FROM universe_versions WHERE universe_definition_id=?",
            Integer.class, definitionId);
        try {
            String source = revision.startsWith("kiwoom-ka10001-market-cap:") ? "kiwoom-ka10001" : "kiwoom-master";
            return jdbc.queryForObject("""
                INSERT INTO universe_versions (universe_definition_id, version, effective_from, inclusion_policy, source, source_revision)
                VALUES (?, ?, ?, jsonb_build_object('provider','kiwoom','sourceRevision',?), ?, ?)
                RETURNING id, false AS finalized
                """, (rs, index) -> new UniverseVersion(UUID.fromString(rs.getString("id")), false),
                definitionId, versionNumber == null ? 1 : versionNumber, effectiveFrom, revision, source, revision);
        } catch (org.springframework.dao.DataIntegrityViolationException error) {
            throw new IllegalStateException("Universe version collision for revision=" + revision, error);
        }
    }

    private Report backfillCandles(WorkerProperties properties) {
        KiwoomMarketDataProvider provider = providerFactory.create(properties);
        boolean top10Action = "backfill-kospi-top10".equals(properties.getMarketDataAction());
        LocalDate through = properties.getMarketDataThrough() == null || properties.getMarketDataThrough().isBlank()
            ? provider.calendar().latestSessionOnOrBefore(LocalDate.now(SEOUL))
            : requiredDate(properties.getMarketDataThrough(), "BACKFILL_THROUGH");
        LocalDate from = properties.getMarketDataFrom() == null || properties.getMarketDataFrom().isBlank()
            ? (top10Action ? through.minusYears(1) : requiredDate(properties.getMarketDataFrom(), "BACKFILL_FROM"))
            : requiredDate(properties.getMarketDataFrom(), "BACKFILL_FROM");
        if (from.isAfter(through)) throw new IllegalArgumentException("BACKFILL_FROM must not be after BACKFILL_THROUGH");
        Set<String> selected = symbols(properties.getMarketDataSymbols());
        String universeKind = top10Action ? "KOSPI_TOP_10" : properties.getBackfillUniverseKind();
        List<InstrumentRow> instruments;
        if (universeKind != null && !universeKind.isBlank()) {
            instruments = jdbc.query("""
                SELECT i.id,i.symbol
                FROM universe_memberships um
                JOIN instruments i ON i.id=um.instrument_id
                WHERE um.universe_version_id=(
                  SELECT uv.id FROM universe_versions uv
                  JOIN universe_definitions ud ON ud.id=uv.universe_definition_id
                  WHERE ud.kind=?::universe_kind AND ud.user_id IS NULL AND uv.finalized_at IS NOT NULL
                  ORDER BY uv.effective_from DESC,uv.version DESC LIMIT 1
                )
                ORDER BY i.symbol
                """, (rs, index) -> new InstrumentRow(UUID.fromString(rs.getString("id")), rs.getString("symbol")),
                universeKind);
            if (instruments.isEmpty()) throw new IllegalStateException("No finalized universe found for " + universeKind);
        } else {
            instruments = jdbc.query("""
                SELECT id, symbol FROM instruments
                WHERE delisted_on IS NULL AND is_trade_suspended=false
                ORDER BY symbol
                """, (rs, index) -> new InstrumentRow(UUID.fromString(rs.getString("id")), rs.getString("symbol")));
        }
        instruments = instruments.stream().filter(row -> selected.isEmpty() || selected.contains(row.symbol())).toList();
        if (!selected.isEmpty() && instruments.size() != selected.size())
            throw new IllegalArgumentException("BACKFILL_SYMBOLS contains unknown or suspended instruments");
        if (properties.getBackfillMaxInstruments() > 0 && instruments.size() > properties.getBackfillMaxInstruments())
            instruments = instruments.subList(0, properties.getBackfillMaxInstruments());

        validateBackfillControls(properties);
        upsertMarketSessions(provider, from, through, properties.isBackfillDryRun());

        int inserted = 0, gaps = 0, invalid = 0;
        MarketCandleNormalizer normalizer = new MarketCandleNormalizer();
        for (InstrumentRow instrument : instruments) {
            for (DateChunk chunk : dateRangeChunks(from, through, properties.getBackfillChunkDays())) {
                List<KiwoomMarketDataProvider.Candle> raw;
                try {
                    raw = retry(properties.getBackfillMaxRetries(), properties.getBackfillRequestDelayMs(),
                        () -> provider.historicalCandles(instrument.symbol(), chunk.from(), chunk.through()));
                } catch (RuntimeException error) { invalid++; continue; }
                List<MarketCandleNormalizer.Candle> normalizedInput = raw.stream().map(candle ->
                    new MarketCandleNormalizer.Candle(candle.sessionDate(), candle.open(), candle.high(), candle.low(),
                        candle.close(), Math.round(candle.volume()))).toList();
                MarketCandleNormalizer.Result result;
                try { result = normalizer.normalize(normalizedInput, provider.calendar(), chunk.from(), chunk.through()); }
                catch (IllegalArgumentException error) { invalid++; continue; }
                gaps += result.missingSessions().size();
                if (!result.duplicateSessions().isEmpty() || result.outOfOrder()) { invalid++; continue; }
                for (KiwoomMarketDataProvider.Candle candle : raw) {
                    if (!candle.completed()) continue;
                    if (properties.isBackfillDryRun()) { inserted++; continue; }
                jdbc.update("""
                    INSERT INTO candles (instrument_id, timeframe, session_date, open_at, close_at, open, high, low, close,
                      adjusted_close, volume, is_final, is_stale, provider, provider_revision, dataset_version, received_at)
                    VALUES (?, 'D1', ?, ?, ?, ?, ?, ?, ?, ?, ?, true, false, 'kiwoom', ?, ?, ?)
                    ON CONFLICT (instrument_id, timeframe, close_at) DO UPDATE SET session_date=excluded.session_date,
                      open_at=excluded.open_at, open=excluded.open, high=excluded.high, low=excluded.low, close=excluded.close,
                      adjusted_close=excluded.adjusted_close, volume=excluded.volume, is_final=excluded.is_final,
                      is_stale=excluded.is_stale, provider=excluded.provider, provider_revision=excluded.provider_revision,
                      dataset_version=excluded.dataset_version, received_at=excluded.received_at
                    """, instrument.id(), candle.sessionDate(), timestamp(candle.sessionDate(), LocalTime.of(9, 0)),
                    timestamp(candle.sessionDate(), LocalTime.of(15, 30)), candle.open(), candle.high(), candle.low(),
                    candle.close(), candle.close(), Math.round(candle.volume()), "kiwoom:" + provider.calendarVersion(),
                    properties.getMarketDataDatasetVersion(), Timestamp.from(candle.receivedAt()));
                inserted++;
                }
                sleep(properties.getBackfillRequestDelayMs());
            }
        }
        return new Report(top10Action ? "backfill-kospi-top10" : "backfill-candles",
            instruments.size(), inserted, gaps, invalid);
    }

    private void upsertMarketSessions(KiwoomMarketDataProvider provider, LocalDate from, LocalDate through, boolean dryRun) {
        if (dryRun) return;
        LocalDate cursor = from;
        while (!cursor.isAfter(through)) {
            if (provider.calendar().isSession(cursor)) for (String market : List.of("KOSPI", "KOSDAQ")) jdbc.update("""
                INSERT INTO market_sessions (calendar_version, market, session_date, is_trading_day, open_at, close_at, order_cutoff_at, note)
                VALUES (?, ?::market_code, ?, true, ?, ?, ?, 'kiwoom-backfill')
                ON CONFLICT (calendar_version, market, session_date) DO UPDATE SET is_trading_day=true,
                  open_at=excluded.open_at, close_at=excluded.close_at, order_cutoff_at=excluded.order_cutoff_at, note=excluded.note
                """, provider.calendarVersion(), market, cursor, timestamp(cursor, LocalTime.of(9, 0)),
                timestamp(cursor, LocalTime.of(15, 30)), timestamp(cursor, LocalTime.of(9, 0)));
            cursor = cursor.plusDays(1);
        }
    }

    static List<DateChunk> dateRangeChunks(LocalDate from, LocalDate through, int chunkDays) {
        if (from.isAfter(through)) throw new IllegalArgumentException("from must not be after through");
        if (chunkDays <= 0) throw new IllegalArgumentException("chunkDays must be positive");
        List<DateChunk> chunks = new ArrayList<>();
        LocalDate cursor = from;
        while (!cursor.isAfter(through)) {
            LocalDate end = cursor.plusDays(chunkDays - 1L);
            if (end.isAfter(through)) end = through;
            chunks.add(new DateChunk(cursor, end));
            cursor = end.plusDays(1);
        }
        return List.copyOf(chunks);
    }

    static <T> T retry(int maxRetries, int delayMs, Supplier<T> task) {
        int attempt = 0;
        while (true) {
            try { return task.get(); }
            catch (KiwoomMarketDataProvider.ProviderException error) {
                if (!error.retryable() || attempt >= maxRetries) throw error;
                attempt++;
                sleep((long) delayMs * attempt);
            }
        }
    }

    private static void validateBackfillControls(WorkerProperties properties) {
        if (properties.getBackfillChunkDays() < 7 || properties.getBackfillChunkDays() > 370)
            throw new IllegalArgumentException("BACKFILL_CHUNK_DAYS must be within 7..370");
        if (properties.getBackfillMaxRetries() < 0 || properties.getBackfillMaxRetries() > 10)
            throw new IllegalArgumentException("BACKFILL_MAX_RETRIES must be within 0..10");
        if (properties.getBackfillRequestDelayMs() < 0 || properties.getBackfillRequestDelayMs() > 60_000)
            throw new IllegalArgumentException("BACKFILL_REQUEST_DELAY_MS must be within 0..60000");
    }

    private static void sleep(long millis) {
        if (millis <= 0) return;
        try { Thread.sleep(millis); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException("Backfill interrupted", error); }
    }

    private static int sourceVersionNumber(String revision) {
        String digits = revision.replaceAll("\\D", "");
        if (digits.length() > 6) digits = digits.substring(0, 6);
        try { int value = Integer.parseInt(digits); return value > 0 ? value : 1; }
        catch (NumberFormatException ignored) { return 1; }
    }

    private static Timestamp timestamp(LocalDate date, LocalTime time) {
        return Timestamp.from(ZonedDateTime.of(date, time, SEOUL).toInstant());
    }
    private static LocalDate requiredDate(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return LocalDate.parse(value);
    }
    private static Set<String> symbols(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();
        Set<String> result = Arrays.stream(raw.split(",")).map(String::trim).filter(value -> !value.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
        if (result.stream().anyMatch(value -> !value.matches("\\d{6}"))) throw new IllegalArgumentException("BACKFILL_SYMBOLS must contain 6-digit symbols");
        return result;
    }
    public record Report(String action, int instruments, int candles, int gaps, int invalid) {}
    record DateChunk(LocalDate from, LocalDate through) {}
    private record InstrumentRow(UUID id, String symbol) {}
    private record Top100Candidate(UUID instrumentId, String symbol, String name, BigDecimal marketCap) {}
    private record UniverseVersion(UUID id, boolean finalized) {}
}
