package com.signallab.api.domain.signal.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record SignalResponse(
    UUID id,
    UUID userId,
    UUID strategyVersionId,
    String symbol,
    String name,
    String type,
    OffsetDateTime candleClose,
    String closePrice,
    String status,
    List<Reason> reasons,
    boolean stale,
    boolean userActionRequired
) {
    public record Reason(String label, String value) {}
}
