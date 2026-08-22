package com.signallab.api.domain.instrument.repository;

import com.signallab.domain.instrument.entity.Instrument;
import com.signallab.domain.instrument.entity.InstrumentKind;
import com.signallab.domain.instrument.entity.MarketCode;
import com.signallab.domain.instrument.repository.InstrumentRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcInstrumentRepository implements InstrumentRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcInstrumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<Instrument> rowMapper = (rs, rowNum) -> new Instrument(
            UUID.fromString(rs.getString("id")),
            rs.getString("symbol"),
            rs.getString("name_ko"),
            MarketCode.valueOf(rs.getString("market")),
            InstrumentKind.valueOf(rs.getString("kind")),
            rs.getString("isin"),
            rs.getDate("listed_on") != null ? rs.getDate("listed_on").toLocalDate() : null,
            rs.getDate("delisted_on") != null ? rs.getDate("delisted_on").toLocalDate() : null,
            rs.getBoolean("is_managed"),
            rs.getBoolean("is_trade_suspended"),
            null, // providerRefs (Map) mapping would require JSON library, keeping null for now
            rs.getTimestamp("created_at").toInstant().atOffset(java.time.ZoneOffset.UTC),
            rs.getTimestamp("updated_at").toInstant().atOffset(java.time.ZoneOffset.UTC)
    );

    @Override
    public Optional<Instrument> findById(UUID id) {
        String sql = "SELECT * FROM instruments WHERE id = ?";
        return jdbcTemplate.query(sql, rowMapper, id).stream().findFirst();
    }

    @Override
    public Optional<Instrument> findBySymbol(String symbol) {
        String sql = "SELECT * FROM instruments WHERE symbol = ?";
        return jdbcTemplate.query(sql, rowMapper, symbol).stream().findFirst();
    }

    @Override
    public List<Instrument> findAll() {
        String sql = "SELECT * FROM instruments";
        return jdbcTemplate.query(sql, rowMapper);
    }

    @Override
    public List<Instrument> findByWatchlist(UUID userId) {
        String sql = """
            SELECT i.* FROM instruments i
            JOIN watchlist_items w ON i.id = w.instrument_id
            WHERE w.user_id = ?
            """;
        return jdbcTemplate.query(sql, rowMapper, userId);
    }

    @Override
    public void addToWatchlist(UUID userId, UUID instrumentId) {
        String sql = """
            INSERT INTO watchlist_items (user_id, instrument_id)
            VALUES (?, ?)
            ON CONFLICT (user_id, instrument_id) DO NOTHING
            """;
        jdbcTemplate.update(sql, userId, instrumentId);
    }

    @Override
    public void removeFromWatchlist(UUID userId, UUID instrumentId) {
        String sql = """
            DELETE FROM watchlist_items
            WHERE user_id = ? AND instrument_id = ?
            """;
        jdbcTemplate.update(sql, userId, instrumentId);
    }
}
