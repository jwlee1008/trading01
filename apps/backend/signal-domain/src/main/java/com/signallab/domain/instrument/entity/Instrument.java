package com.signallab.domain.instrument.entity;

import com.signallab.domain.instrument.entity.InstrumentKind;
import com.signallab.domain.instrument.entity.MarketCode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.Map;

public record Instrument(
    UUID id,
    String symbol,
    String nameKo,
    MarketCode market,
    InstrumentKind kind,
    String isin,
    LocalDate listedOn,
    LocalDate delistedOn,
    boolean isManaged,
    boolean isTradeSuspended,
    Map<String, Object> providerRefs,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {}
