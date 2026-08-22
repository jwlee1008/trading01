package com.signallab.worker.domain.marketdata.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Kiwoom REST adapter. It contains no database concerns and is testable through Transport. */
public final class KiwoomMarketDataProvider {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final Config config;
    private final ObjectMapper mapper;
    private final Transport transport;
    private final Clock clock;
    private Token token;
    private long lastRequestStartedNanos;

    public KiwoomMarketDataProvider(Config config, ObjectMapper mapper, Transport transport, Clock clock) {
        this.config = Objects.requireNonNull(config);
        this.mapper = Objects.requireNonNull(mapper);
        this.transport = Objects.requireNonNull(transport);
        this.clock = Objects.requireNonNull(clock);
        config.validate();
    }

    public String calendarVersion() { return config.calendarVersion(); }
    public TradingCalendar calendar() { return config.calendar(); }

    public List<Candle> historicalCandles(String symbol, LocalDate from, LocalDate through) {
        requireSymbol(symbol);
        List<JsonNode> rows = new ArrayList<>();
        for (JsonNode page : requestPages("ka10081", "/api/dostk/chart", Map.of(
            "stk_cd", symbol, "base_dt", through.format(DateTimeFormatter.BASIC_ISO_DATE), "upd_stkpc_tp", "1"
        ))) rows.addAll(firstArray(page, "stk_dt_pole_chart_qry", "stk_dt_pole_chart", "output", "data"));
        Instant receivedAt = clock.instant();
        LocalDate latestCompleted = latestCompletedSession();
        return rows.stream().map(row -> new Candle(
                symbol, date(field(row, "dt", "date")), number(row, "open_pric", "open"),
                number(row, "high_pric", "high"), number(row, "low_pric", "low"),
                number(row, "cur_prc", "close_pric", "close"), number(row, "trde_qty", "volume"),
                !date(field(row, "dt", "date")).isAfter(latestCompleted), receivedAt
            ))
            .filter(candle -> !candle.sessionDate().isBefore(from) && !candle.sessionDate().isAfter(through))
            .sorted(Comparator.comparing(Candle::sessionDate)).toList();
    }

    public List<ListedInstrument> listedInstruments() {
        Map<String, ListedInstrument> result = new LinkedHashMap<>();
        for (MarketSpec spec : config.markets()) {
            for (JsonNode page : requestPages(config.masterApiId(), config.masterPath(), Map.of("mrkt_tp", spec.code()))) {
                for (JsonNode row : firstArray(page, "list", "stk_info", "stk_info_list", "output", "data")) {
                    String rawSymbol = optionalField(row, "stk_cd", "code", "symbol", "isu_cd", "shrn_iscd");
                    String symbol = normalizeSymbol(rawSymbol);
                    String name = optionalField(row, "stk_nm", "name", "kor_name", "hts_kor_isnm");
                    if (symbol == null || name == null) continue;
                    result.put(symbol, new ListedInstrument(symbol, name, spec.market(), classify(name, row),
                        optionalField(row, "isin", "isin_cd", "std_cd"),
                        yes(row, "mang_stk_yn", "managed_yn", "admst_yn"),
                        yes(row, "trde_stop_yn", "trade_stop_yn", "suspension_yn")));
                }
            }
        }
        return result.values().stream().sorted(Comparator.comparing(ListedInstrument::symbol)).toList();
    }

    public Double currentPrice(String symbol) {
        requireSymbol(symbol);
        JsonNode body = requestPage("ka10001", "/api/dostk/stkinfo", Map.of("stk_cd", symbol), null, null).body();
        String raw = optionalField(body, "cur_prc", "cur_prc_n", "price");
        return raw == null ? null : positiveNumber(raw, "cur_prc");
    }

    public MarketCapitalization marketCapitalization(String symbol) {
        requireSymbol(symbol);
        JsonNode body = requestPage("ka10001", "/api/dostk/stkinfo", Map.of("stk_cd", symbol), null, null).body();
        String rawMarketCap = optionalField(body, "mac", "market_cap", "marketCap");
        if (rawMarketCap == null) throw new IllegalArgumentException("Missing Kiwoom field: mac");
        try {
            BigDecimal value = new BigDecimal(rawMarketCap.replace(",", "").replace("+", "").trim()).abs();
            if (value.signum() <= 0) throw new IllegalArgumentException("Kiwoom market cap must be positive");
            return new MarketCapitalization(symbol, value, clock.instant());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid Kiwoom number: mac", error);
        }
    }

    private List<JsonNode> requestPages(String apiId, String path, Map<String, String> payload) {
        List<JsonNode> pages = new ArrayList<>();
        String continuation = null;
        String nextKey = null;
        for (int page = 0; page < config.maxPages(); page++) {
            Page result = requestPage(apiId, path, payload, continuation, nextKey);
            pages.add(result.body());
            if (!"Y".equals(result.continuation()) || result.nextKey() == null || result.nextKey().isBlank()) return pages;
            continuation = result.continuation();
            nextKey = result.nextKey();
        }
        throw new ProviderException(Code.RATE_LIMIT, "Kiwoom pagination exceeded maxPages=" + config.maxPages(), true);
    }

    private Page requestPage(String apiId, String path, Map<String, String> payload, String continuation, String nextKey) {
        int attempt = 0;
        while (true) {
            paceRequest();
            try {
                return requestPageOnce(apiId, path, payload, continuation, nextKey);
            } catch (ProviderException error) {
                if (error.code() != Code.RATE_LIMIT || attempt >= config.maxRetries()) throw error;
                attempt++;
                sleep((long) config.requestDelayMs() * attempt);
            }
        }
    }

    private Page requestPageOnce(String apiId, String path, Map<String, String> payload, String continuation, String nextKey) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json;charset=UTF-8");
        headers.put("authorization", "Bearer " + ensureToken());
        headers.put("api-id", apiId);
        if (continuation != null) headers.put("cont-yn", continuation);
        if (nextKey != null) headers.put("next-key", nextKey);
        Response response = transport.post(config.baseUrl() + path, headers, json(payload));
        JsonNode body = parse(response.body());
        if (response.status() < 200 || response.status() >= 300) {
            Code code = response.status() == 429 ? Code.RATE_LIMIT : Code.DISCONNECT;
            String returnCode = safeDiagnostic(body.path("return_code").asText("unknown"));
            String returnMessage = safeDiagnostic(body.path("return_msg").asText("no message"));
            throw new ProviderException(code,
                "Kiwoom request failed: httpStatus=" + response.status()
                    + ", returnCode=" + returnCode + ", returnMsg=" + returnMessage,
                response.status() == 429 || response.status() >= 500);
        }
        String returnCode = body.path("return_code").asText("0");
        if (!"0".equals(returnCode)) {
            String message = body.path("return_msg").asText("Kiwoom API error");
            throw new ProviderException(message.contains("토큰") ? Code.TOKEN_EXPIRED : Code.DISCONNECT, message, false);
        }
        return new Page(body, response.header("cont-yn"), response.header("next-key"));
    }

    private synchronized void paceRequest() {
        if (config.requestDelayMs() <= 0) return;
        long minimumNanos = config.requestDelayMs() * 1_000_000L;
        long elapsedNanos = System.nanoTime() - lastRequestStartedNanos;
        if (lastRequestStartedNanos != 0 && elapsedNanos < minimumNanos)
            sleep((minimumNanos - elapsedNanos + 999_999L) / 1_000_000L);
        lastRequestStartedNanos = System.nanoTime();
    }

    private String ensureToken() {
        Instant now = clock.instant();
        if (token != null && token.expiresAt().minusSeconds(60).isAfter(now)) return token.value();
        Response response = transport.post(config.baseUrl() + "/oauth2/token",
            Map.of("Content-Type", "application/json;charset=UTF-8"),
            json(Map.of("grant_type", "client_credentials", "appkey", config.appKey(), "secretkey", config.appSecret())));
        JsonNode body = parse(response.body());
        if (response.status() < 200 || response.status() >= 300 || !"0".equals(body.path("return_code").asText())
            || body.path("token").asText().isBlank()) {
            throw new ProviderException(Code.TOKEN_EXPIRED, "Kiwoom token issue failed", true);
        }
        Instant expiresAt = parseExpiry(body.path("expires_dt").asText(""), now.plusSeconds(20 * 60 * 60));
        token = new Token(body.path("token").asText(), expiresAt);
        return token.value();
    }

    public LocalDate latestCompletedSession() {
        OffsetDateTime now = clock.instant().atZone(SEOUL).toOffsetDateTime();
        LocalDate candidate = now.toLocalTime().isBefore(java.time.LocalTime.of(15, 30))
            ? now.toLocalDate().minusDays(1) : now.toLocalDate();
        return config.calendar().latestSessionOnOrBefore(candidate);
    }

    private JsonNode parse(String value) {
        try { return mapper.readTree(value); }
        catch (Exception error) { throw new ProviderException(Code.DISCONNECT, "Kiwoom returned invalid JSON", true); }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("Kiwoom request serialization failed", error); }
    }

    private static String safeDiagnostic(String value) {
        String singleLine = value == null ? "" : value.replaceAll("[\\r\\n\\t]", " ").trim();
        return singleLine.length() <= 300 ? singleLine : singleLine.substring(0, 300);
    }

    private static void sleep(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ProviderException(Code.DISCONNECT, "Kiwoom request interrupted", true);
        }
    }

    private static List<JsonNode> firstArray(JsonNode root, String... keys) {
        for (String key : keys) if (root.path(key).isArray()) {
            List<JsonNode> rows = new ArrayList<>(); root.path(key).forEach(rows::add); return rows;
        }
        return List.of();
    }

    private static String field(JsonNode row, String... keys) {
        String value = optionalField(row, keys);
        if (value == null) throw new IllegalArgumentException("Missing Kiwoom field: " + keys[0]);
        return value;
    }

    private static String optionalField(JsonNode row, String... keys) {
        for (String key : keys) if (row.hasNonNull(key) && !row.path(key).asText().trim().isEmpty()) return row.path(key).asText().trim();
        return null;
    }

    private static double number(JsonNode row, String... keys) { return positiveNumber(field(row, keys), keys[0]); }
    private static double positiveNumber(String raw, String field) {
        try { return Math.abs(Double.parseDouble(raw.replace(",", "").trim())); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("Invalid Kiwoom number: " + field, error); }
    }
    private static LocalDate date(String raw) {
        String compact = raw.replaceAll("\\D", "");
        if (compact.length() < 8) throw new IllegalArgumentException("Invalid Kiwoom date: " + raw);
        return LocalDate.parse(compact.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE);
    }
    private static String normalizeSymbol(String raw) {
        if (raw == null || raw.contains("_")) return null;
        String digits = raw.replaceAll("\\D", "");
        return digits.matches("\\d{6}") ? digits : null;
    }
    private static void requireSymbol(String symbol) {
        if (symbol == null || !symbol.matches("\\d{6}")) throw new IllegalArgumentException("Invalid KRX symbol");
    }
    private static boolean yes(JsonNode row, String... keys) {
        String value = optionalField(row, keys); if (value == null) return false;
        return List.of("Y", "1", "TRUE", "예").contains(value.toUpperCase());
    }
    private static Kind classify(String name, JsonNode row) {
        String type = (optionalField(row, "stk_tp", "stock_type", "sec_tp", "type") + " " + name).toUpperCase();
        if (type.contains("ETF")) return Kind.ETF; if (type.contains("ETN")) return Kind.ETN;
        if (type.contains("SPAC") || type.contains("스팩")) return Kind.SPAC;
        if (type.contains("우선") || name.endsWith("우")) return Kind.PREFERRED;
        return Kind.COMMON;
    }
    private static Instant parseExpiry(String raw, Instant fallback) {
        try {
            String digits = raw.replaceAll("\\D", ""); if (digits.length() < 14) return fallback;
            return java.time.LocalDateTime.parse(digits.substring(0, 14), DateTimeFormatter.ofPattern("yyyyMMddHHmmss")).atZone(SEOUL).toInstant();
        } catch (Exception ignored) { return fallback; }
    }

    public record Config(String baseUrl, String appKey, String appSecret, int maxPages, String calendarVersion,
                         TradingCalendar calendar, String masterApiId, String masterPath, List<MarketSpec> markets,
                         int requestDelayMs, int maxRetries) {
        public void validate() {
            if (baseUrl == null || baseUrl.isBlank() || appKey == null || appKey.isBlank() || appSecret == null || appSecret.isBlank())
                throw new IllegalArgumentException("Kiwoom credentials and baseUrl are required");
            if (maxPages < 1 || maxPages > 500) throw new IllegalArgumentException("maxPages must be within 1..500");
            if (requestDelayMs < 0 || requestDelayMs > 60_000)
                throw new IllegalArgumentException("requestDelayMs must be within 0..60000");
            if (maxRetries < 0 || maxRetries > 10)
                throw new IllegalArgumentException("maxRetries must be within 0..10");
            if (calendarVersion == null || calendarVersion.isBlank() || calendarVersion.toLowerCase().contains("mock"))
                throw new IllegalArgumentException("Kiwoom provider requires a verified non-mock calendar");
            Objects.requireNonNull(calendar); Objects.requireNonNull(markets);
        }
    }
    public record MarketSpec(String code, Market market) {}
    public record Candle(String symbol, LocalDate sessionDate, double open, double high, double low, double close,
                         double volume, boolean completed, Instant receivedAt) {}
    public record ListedInstrument(String symbol, String name, Market market, Kind kind, String isin,
                                   boolean managed, boolean tradeSuspended) {}
    public record MarketCapitalization(String symbol, BigDecimal value, Instant receivedAt) {}
    public enum Market { KOSPI, KOSDAQ }
    public enum Kind { COMMON, PREFERRED, ETF, ETN, SPAC, OTHER }
    public enum Code { DISCONNECT, RATE_LIMIT, TOKEN_EXPIRED }
    public static final class ProviderException extends RuntimeException {
        private final Code code; private final boolean retryable;
        public ProviderException(Code code, String message, boolean retryable) { super(message); this.code = code; this.retryable = retryable; }
        public Code code() { return code; } public boolean retryable() { return retryable; }
    }
    public interface Transport { Response post(String url, Map<String, String> headers, String body); }
    public record Response(int status, Map<String, String> headers, String body) {
        public String header(String name) { return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue).findFirst().orElse(null); }
    }
    public static final class HttpTransport implements Transport {
        private final HttpClient client = HttpClient.newHttpClient();
        @Override public Response post(String url, Map<String, String> headers, String body) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString(body));
                headers.forEach(builder::header);
                HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                Map<String, String> resultHeaders = new LinkedHashMap<>(); response.headers().map().forEach((k, v) -> resultHeaders.put(k, v.getFirst()));
                return new Response(response.statusCode(), resultHeaders, response.body());
            } catch (Exception error) { Thread.currentThread().interrupt(); throw new ProviderException(Code.DISCONNECT, "Kiwoom connection failed", true); }
        }
    }
    private record Token(String value, Instant expiresAt) {}
    private record Page(JsonNode body, String continuation, String nextKey) {}
}
