package com.signallab.domain.instrument.repository;

import com.signallab.domain.instrument.entity.Instrument;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InstrumentRepository {
    Optional<Instrument> findById(UUID id);
    Optional<Instrument> findBySymbol(String symbol);
    List<Instrument> findAll();
    List<Instrument> findByWatchlist(UUID userId);
    void addToWatchlist(UUID userId, UUID instrumentId);
    void removeFromWatchlist(UUID userId, UUID instrumentId);
}
