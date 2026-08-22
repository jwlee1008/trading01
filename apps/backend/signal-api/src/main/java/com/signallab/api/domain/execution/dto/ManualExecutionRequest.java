package com.signallab.api.domain.execution.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.OffsetDateTime;

public record ManualExecutionRequest(
    @NotBlank @Pattern(regexp = "^\\d{6}$") String symbol,
    @NotBlank @Pattern(regexp = "^(BUY|SELL)$") String side,
    String positionId,
    @NotBlank @Pattern(regexp = "^(?:0|[1-9]\\d{0,11})(?:\\.\\d{1,4})?$") String price,
    @NotNull @Min(1) @Max(1_000_000_000) Long quantity,
    @NotNull OffsetDateTime executedAt,
    String signalId,
    String memo,
    @NotBlank @Pattern(regexp = "^.{8,100}$") String idempotencyKey
) {}
