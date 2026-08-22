package com.signallab.api.domain.order.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record PaperOrderRequest(
    @NotBlank String portfolioId,
    @NotBlank @Pattern(regexp = "^\\d{6}$") String symbol,
    @NotBlank @Pattern(regexp = "^(BUY|SELL)$") String side,
    String positionId,
    @NotNull @Min(1) @Max(1_000_000_000) Long quantity,
    String signalId,
    @NotBlank @Pattern(regexp = "^.{8,100}$") String idempotencyKey
) {}
