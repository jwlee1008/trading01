import { describe, expect, it } from "vitest";
import {
  alertSettingsSchema,
  createStrategySchema,
  manualExecutionSchema,
  rankingPeriodSchema,
  sellRuleSchema,
  strategyEvaluationSchema,
  strategyRuleSchema,
} from "../src/index.js";

describe("shared input schemas", () => {
  it("rejects a sixth personal indicator", () => {
    const rules = Array.from({ length: 6 }, () => ({
      indicatorId: "RSI",
      operator: "LT",
      value: 30,
      params: {},
    }));
    expect(createStrategySchema.safeParse({
      name: "과다 전략",
      universeVersionId: "uv-kospi200-2026",
      logic: "AND",
      rules,
    }).success).toBe(false);
  });

  it("requires explicit manual-only or one automatic exit", () => {
    expect(sellRuleSchema.safeParse({ technicalRules: [] }).success).toBe(false);
    expect(sellRuleSchema.safeParse({ manualOnly: true, technicalRules: [] }).success).toBe(true);
    expect(sellRuleSchema.safeParse({ stopLossPct: 7, technicalRules: [] }).success).toBe(true);
  });

  it("accepts only complete strict alert settings", () => {
    const valid = {
      enabled: true,
      quietHoursEnabled: true,
      quietStart: "22:00",
      quietEnd: "07:00",
      showPriceOnLockScreen: false,
    };
    expect(alertSettingsSchema.safeParse(valid).success).toBe(true);
    expect(alertSettingsSchema.safeParse({ ...valid, quietStart: "25:00" }).success).toBe(false);
    expect(alertSettingsSchema.safeParse({ ...valid, extra: true }).success).toBe(false);
    expect(alertSettingsSchema.safeParse({ enabled: true }).success).toBe(false);
  });

  it("accepts only supported ranking periods", () => {
    for (const period of ["3M", "6M", "1Y", "ALL"]) expect(rankingPeriodSchema.safeParse(period).success).toBe(true);
    expect(rankingPeriodSchema.safeParse("YTD").success).toBe(false);
  });

  it("rejects zero, overflow, and unsafe manual prices", () => {
    const input = {
      symbol: "005930",
      side: "BUY",
      positionId: null,
      price: "79200",
      quantity: 1,
      executedAt: "2026-08-15T00:00:00.000Z",
      signalId: null,
      memo: "",
      idempotencyKey: "manual-key-1",
    };
    expect(manualExecutionSchema.safeParse(input).success).toBe(true);
    expect(manualExecutionSchema.safeParse({ ...input, price: "0" }).success).toBe(false);
    expect(manualExecutionSchema.safeParse({ ...input, price: "0.0000" }).success).toBe(false);
    expect(manualExecutionSchema.safeParse({ ...input, price: "9".repeat(400) }).success).toBe(false);
    expect(manualExecutionSchema.safeParse({ ...input, quantity: Number.MAX_SAFE_INTEGER }).success).toBe(false);
    expect(manualExecutionSchema.safeParse({ ...input, side: "SELL", positionId: null }).success).toBe(false);
    expect(manualExecutionSchema.safeParse({ ...input, side: "SELL", positionId: "position-1" }).success).toBe(true);
  });

  it("normalizes legacy threshold rules", () => {
    const result = strategyRuleSchema.parse({
      indicatorId: "RSI",
      outputKey: "rsi",
      operator: "LTE",
      value: 30,
      params: { period: 14 },
    });
    expect(result).toEqual({
      left: { kind: "INDICATOR", indicatorId: "RSI", outputKey: "rsi", params: { period: 14 } },
      operator: "LTE",
      right: { kind: "VALUE", value: 30 },
    });
  });

  it("accepts close-to-indicator and indicator-to-indicator rules", () => {
    const rules = [
      {
        left: { kind: "CLOSE" },
        operator: "CROSSES_ABOVE",
        right: { kind: "INDICATOR", indicatorId: "SMA", outputKey: "sma", params: { period: 20 } },
      },
      {
        left: { kind: "INDICATOR", indicatorId: "MACD", outputKey: "macd", params: { fastPeriod: 12, slowPeriod: 26, signalPeriod: 9 } },
        operator: "CROSSES_ABOVE",
        right: { kind: "INDICATOR", indicatorId: "MACD", outputKey: "signal", params: { fastPeriod: 12, slowPeriod: 26, signalPeriod: 9 } },
      },
    ];
    expect(strategyEvaluationSchema.safeParse({ versionId: "sv-source-pairs", logic: "AND", rules }).success).toBe(true);
  });

  it("rejects ambiguous and loose operand objects", () => {
    expect(strategyRuleSchema.safeParse({
      left: { kind: "CLOSE", indicatorId: "SMA" },
      operator: "GT",
      right: { kind: "VALUE", value: 1 },
    }).success).toBe(false);
    expect(strategyRuleSchema.safeParse({
      left: { kind: "INDICATOR", indicatorId: "RSI", outputKey: "rsi", params: {}, extra: true },
      operator: "GT",
      right: { kind: "VALUE", value: 1 },
    }).success).toBe(false);
    expect(strategyEvaluationSchema.safeParse({
      versionId: "sv1",
      logic: "AND",
      rules: [{ left: { kind: "CLOSE" }, operator: "GT", right: { kind: "NOPE" } }],
      extra: true,
    }).success).toBe(false);
  });
});
