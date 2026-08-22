package com.signallab.api.domain.advice.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record AdviceResponse(
    UUID signalId,
    String summary,
    List<String> evidence,
    List<String> risks,
    List<String> questionsToConsider,
    String disclaimer,
    String source,
    String model,
    OffsetDateTime basedOn,
    OffsetDateTime generatedAt
) {}
