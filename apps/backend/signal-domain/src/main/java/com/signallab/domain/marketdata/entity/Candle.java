package com.signallab.domain.marketdata.entity;

import com.signallab.domain.marketdata.entity.Timeframe;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record Candle(
    UUID id,
    UUID instrumentId,
    Timeframe timeframe,
    LocalDate sessionDate,
    OffsetDateTime openAt,
    OffsetDateTime closeAt,
    BigDecimal open,
    BigDecimal high,
    BigDecimal low,
    BigDecimal close,
    BigDecimal adjustedClose,
    BigDecimal volume,
    boolean isFinal,
    boolean isStale,
    String provider,
    String datasetVersion,
    OffsetDateTime receivedAt,
    OffsetDateTime createdAt
) {}
