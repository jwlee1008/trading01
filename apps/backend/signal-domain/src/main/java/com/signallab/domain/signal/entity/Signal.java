package com.signallab.domain.signal.entity;

import com.signallab.domain.signal.entity.SignalType;
import com.signallab.domain.marketdata.entity.Timeframe;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.Map;

public record Signal(
    UUID id,
    UUID userId,
    UUID strategyVersionId,
    UUID instrumentId,
    Timeframe timeframe,
    OffsetDateTime candleCloseAt,
    SignalType signalType,
    BigDecimal signalStrength,
    BigDecimal priorLiquidityScore,
    Map<String, Object> evidence,
    String datasetVersion,
    String engineVersion,
    boolean dataIsStale,
    OffsetDateTime createdAt
) {}
