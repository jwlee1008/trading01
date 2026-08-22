package com.signallab.domain.strategy.repository;

import com.signallab.domain.strategy.entity.Strategy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StrategyRepository {
    Optional<Strategy> findById(UUID id);
    List<Strategy> findByUserId(UUID userId);
    void save(Strategy strategy);
}
