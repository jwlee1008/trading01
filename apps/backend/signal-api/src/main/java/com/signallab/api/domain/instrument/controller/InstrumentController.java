package com.signallab.api.domain.instrument.controller;

import com.signallab.api.global.web.ApiEnvelope;
import com.signallab.api.global.web.CurrentUser;
import com.signallab.api.global.health.service.DatabaseHealthService;
import com.signallab.domain.instrument.entity.Instrument;
import com.signallab.domain.instrument.repository.InstrumentRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v1")
public class InstrumentController {

    private final InstrumentRepository instrumentRepository;
    private final DatabaseHealthService databaseHealthService;
    private final JdbcTemplate jdbcTemplate;

    public InstrumentController(InstrumentRepository instrumentRepository, DatabaseHealthService databaseHealthService, JdbcTemplate jdbcTemplate) {
        this.instrumentRepository = instrumentRepository;
        this.databaseHealthService = databaseHealthService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/catalog")
    public Map<String, Object> getCatalog() {
        return ApiEnvelope.ok(instrumentRepository.findAll(), databaseHealthService.isPostgres());
    }

    @GetMapping("/universe-versions")
    public Map<String, Object> getUniverseVersions() {
        List<UniverseVersionItem> versions = jdbcTemplate.query("""
            SELECT DISTINCT ON (ud.kind)
                   uv.id::text AS id, ud.kind::text AS kind, ud.name_ko,
                   uv.source_revision, uv.effective_from, count(um.id) AS member_count
            FROM universe_versions uv
            JOIN universe_definitions ud ON ud.id = uv.universe_definition_id
            LEFT JOIN universe_memberships um ON um.universe_version_id = uv.id
            WHERE uv.finalized_at IS NOT NULL AND ud.user_id IS NULL
            GROUP BY uv.id, ud.kind, ud.name_ko, uv.source_revision, uv.effective_from, uv.created_at
            ORDER BY ud.kind, uv.effective_from DESC, uv.created_at DESC
            """, (rs, rowNum) -> new UniverseVersionItem(
                rs.getString("id"), rs.getString("kind"), rs.getString("name_ko"),
                rs.getString("source_revision"), rs.getObject("effective_from").toString(), rs.getInt("member_count")
            ));
        return ApiEnvelope.ok(versions, databaseHealthService.isPostgres());
    }

    @GetMapping("/watchlist")
    public Map<String, Object> getWatchlist(@CurrentUser String userId) {
        return ApiEnvelope.ok(
            instrumentRepository.findByWatchlist(parseUserId(userId)),
            databaseHealthService.isPostgres()
        );
    }

    @PostMapping("/watchlist/{symbol}")
    public Map<String, Object> addWatchlist(
        @CurrentUser String userId,
        @PathVariable String symbol
    ) {
        Instrument instrument = requireInstrument(symbol);
        UUID authenticatedUserId = parseUserId(userId);
        instrumentRepository.addToWatchlist(authenticatedUserId, instrument.id());
        return ApiEnvelope.ok(
            instrumentRepository.findByWatchlist(authenticatedUserId),
            databaseHealthService.isPostgres()
        );
    }

    @DeleteMapping("/watchlist/{symbol}")
    public Map<String, Object> removeWatchlist(
        @CurrentUser String userId,
        @PathVariable String symbol
    ) {
        UUID authenticatedUserId = parseUserId(userId);
        instrumentRepository.findBySymbol(symbol).ifPresent(
            instrument -> instrumentRepository.removeFromWatchlist(authenticatedUserId, instrument.id())
        );
        return ApiEnvelope.ok(
            instrumentRepository.findByWatchlist(authenticatedUserId),
            databaseHealthService.isPostgres()
        );
    }

    private Instrument requireInstrument(String symbol) {
        if (!symbol.matches("\\d{6}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "유효한 종목 코드를 입력하세요.");
        }
        return instrumentRepository.findBySymbol(symbol)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "유효한 종목 코드를 입력하세요."));
    }

    private UUID parseUserId(String userId) {
        try {
            return UUID.fromString(userId);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("인증된 사용자 식별자가 UUID 형식이 아닙니다.", exception);
        }
    }

    private record UniverseVersionItem(
        String id, String kind, String name, String sourceRevision, String effectiveFrom, int memberCount
    ) {}
}
