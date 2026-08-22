package com.signallab.api.domain.strategy.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record StrategyRequest(
    @NotBlank @Size(max = 40) String name,
    @NotBlank String universeVersionId,
    @NotBlank String logic,
    @NotEmpty @Size(max = 5) List<JsonNode> rules,
    Boolean alertsEnabled,
    Boolean isPublic
) {}
