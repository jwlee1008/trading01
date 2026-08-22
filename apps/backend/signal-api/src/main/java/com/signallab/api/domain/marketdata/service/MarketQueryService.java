package com.signallab.api.domain.marketdata.service;

import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MarketQueryService {
    private final JdbcTemplate jdbc;

    public MarketQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Top10Response kospiTop10(LocalDate requestedAsOf) {
        LocalDate asOf = requestedAsOf;
        if (asOf == null) {
            asOf = jdbc.query("SELECT max(session_date) FROM market_cap_snapshots",
                rs -> rs.next() ? rs.getObject(1, LocalDate.class) : null);
        }
        if (asOf == null) return new Top10Response(null, List.of());
        List<Top10Item> items = jdbc.query("""
            SELECT m.rank,i.symbol,i.name_ko,m.market_cap
            FROM market_cap_snapshots m
            JOIN instruments i ON i.id=m.instrument_id
            WHERE m.session_date=?
            ORDER BY m.rank
            """, (rs, index) -> new Top10Item(rs.getInt("rank"), rs.getString("symbol"),
                rs.getString("name_ko"), rs.getBigDecimal("market_cap").toPlainString()), asOf);
        return new Top10Response(asOf, items);
    }

    public DailyPriceResponse dailyPrices(String symbol, LocalDate from, LocalDate to) {
        String name = jdbc.query("SELECT name_ko FROM instruments WHERE symbol=?",
            rs -> rs.next() ? rs.getString(1) : null, symbol);
        if (name == null) return null;
        List<DailyPrice> prices = jdbc.query("""
            SELECT session_date,open,close
            FROM candles c JOIN instruments i ON i.id=c.instrument_id
            WHERE i.symbol=? AND c.timeframe='D1' AND c.is_final AND NOT c.is_stale
              AND c.session_date BETWEEN ? AND ?
            ORDER BY c.session_date
            """, (rs, index) -> new DailyPrice(rs.getObject("session_date", LocalDate.class),
                rs.getBigDecimal("open").toPlainString(), rs.getBigDecimal("close").toPlainString()),
            symbol, from, to);
        return new DailyPriceResponse(symbol, name, from, to, prices);
    }

    public record Top10Response(LocalDate asOf, List<Top10Item> items) {}
    public record Top10Item(int rank, String symbol, String name, String marketCap) {}
    public record DailyPriceResponse(String symbol, String name, LocalDate from, LocalDate to,
                                     List<DailyPrice> prices) {}
    public record DailyPrice(LocalDate date, String open, String close) {}
}
