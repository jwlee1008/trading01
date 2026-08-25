import { z } from "zod";

export const portfolioKindSchema = z.enum([
  "MANUAL_LIVE",
]);

export const rankingPeriodSchema = z.enum(["3M", "6M", "1Y", "ALL"]);

export const indicatorIdSchema = z.enum([
  "SMA",
  "EMA",
  "RSI",
  "MACD",
  "BOLLINGER",
  "VOLUME_SPIKE",
  "STOCHASTIC",
  "ATR",
  "ADX",
  "OBV",
]);

export const strategyOperatorSchema = z.enum([
  "GT",
  "GTE",
  "LT",
  "LTE",
  "EQ",
  "CROSSES_ABOVE",
  "CROSSES_BELOW",
]);

const indicatorParamsSchema = z.record(z.string(), z.number().finite()).default({});

export const closeOperandSchema = z.object({
  kind: z.literal("CLOSE"),
}).strict();

export const indicatorOperandSchema = z.object({
  kind: z.literal("INDICATOR"),
  indicatorId: indicatorIdSchema,
  outputKey: z.string().trim().min(1).max(40).optional(),
  params: indicatorParamsSchema,
}).strict();

export const strategyOperandSchema = z.discriminatedUnion("kind", [
  closeOperandSchema,
  indicatorOperandSchema,
]);

export const valueReferenceSchema = z.object({
  kind: z.literal("VALUE"),
  value: z.number().finite(),
}).strict();

export const strategyReferenceSchema = z.discriminatedUnion("kind", [
  valueReferenceSchema,
  closeOperandSchema,
  indicatorOperandSchema,
]);

export const canonicalStrategyRuleSchema = z.object({
  left: strategyOperandSchema,
  operator: strategyOperatorSchema,
  right: strategyReferenceSchema,
}).strict();

const legacyStrategyRuleSchema = z.object({
  indicatorId: indicatorIdSchema,
  outputKey: z.string().trim().min(1).max(40).optional(),
  operator: strategyOperatorSchema,
  value: z.number().finite(),
  params: indicatorParamsSchema,
}).strict();

export const strategyRuleSchema = z.union([
  canonicalStrategyRuleSchema,
  legacyStrategyRuleSchema.transform((rule) => ({
    left: {
      kind: "INDICATOR" as const,
      indicatorId: rule.indicatorId,
      ...(rule.outputKey === undefined ? {} : { outputKey: rule.outputKey }),
      params: rule.params,
    },
    operator: rule.operator,
    right: { kind: "VALUE" as const, value: rule.value },
  })),
]);

export const strategyEvaluationSchema = z.object({
  versionId: z.string().trim().min(1).max(100),
  logic: z.enum(["AND", "OR"]),
  rules: z.array(strategyRuleSchema).min(1).max(5),
}).strict();

export const createStrategySchema = z.object({
  name: z.string().trim().min(1).max(40),
  universeVersionId: z.string().min(1),
  logic: z.enum(["AND", "OR"]),
  rules: z.array(strategyRuleSchema).min(1).max(5),
  alertsEnabled: z.boolean().default(true),
  isPublic: z.boolean().default(false),
});

export const sellRuleSchema = z.object({
  stopLossPct: z.number().gt(0).lte(100).optional(),
  takeProfitPct: z.number().gt(0).optional(),
  trailingStopPct: z.number().gt(0).lte(100).optional(),
  maxHoldingSessions: z.number().int().positive().optional(),
  technicalLogic: z.enum(["ANY", "ALL"]).default("ANY"),
  technicalRules: z.array(strategyRuleSchema).max(3).default([]),
  manualOnly: z.boolean().default(false),
}).superRefine((rule, context) => {
  const automatic = rule.stopLossPct !== undefined
    || rule.takeProfitPct !== undefined
    || rule.trailingStopPct !== undefined
    || rule.maxHoldingSessions !== undefined
    || rule.technicalRules.length > 0;
  if (rule.manualOnly === automatic) {
    context.addIssue({
      code: "custom",
      message: "수동 관리 또는 자동 규칙 중 하나를 선택하세요.",
    });
  }
});

export const manualExecutionSchema = z.object({
  symbol: z.string().regex(/^\d{6}$/),
  side: z.enum(["BUY", "SELL"]),
  positionId: z.string().min(1).nullable().default(null),
  price: z.string()
    .regex(/^(?:0|[1-9]\d{0,11})(?:\.\d{1,4})?$/)
    .refine((value) => Number(value) > 0, "가격은 0보다 커야 합니다."),
  quantity: z.number().int().positive().max(1_000_000_000),
  executedAt: z.string().datetime(),
  signalId: z.string().nullable().default(null),
  memo: z.string().max(200).default(""),
  idempotencyKey: z.string().min(8).max(100),
}).superRefine((input, context) => {
  if (input.side === "SELL" && input.positionId === null) {
    context.addIssue({ code: "custom", path: ["positionId"], message: "매도 포지션 ID가 필요합니다." });
  }
});

export const profileVisibilitySchema = z.object({
  isPublic: z.boolean(),
  nickname: z.string().trim().min(2).max(20),
  discloseOpenPositions: z.boolean().default(false),
});

const hhmmSchema = z.string().regex(/^([01]\d|2[0-3]):[0-5]\d$/, "HH:mm 형식이 필요합니다.");

export const alertSettingsSchema = z.object({
  enabled: z.boolean(),
  quietHoursEnabled: z.boolean(),
  quietStart: hhmmSchema,
  quietEnd: hhmmSchema,
  showPriceOnLockScreen: z.boolean(),
}).strict();

export const signalAdviceSchema = z.object({
  signalId: z.string().min(1),
  summary: z.string().min(1),
  evidence: z.array(z.string().min(1)).max(5),
  risks: z.array(z.string().min(1)).max(5),
  questionsToConsider: z.array(z.string().min(1)).max(5),
  disclaimer: z.string().min(1),
  source: z.enum(["GEMINI", "LOCAL"]),
  model: z.string().min(1),
  basedOn: z.string().datetime({ offset: true }),
  generatedAt: z.string().datetime({ offset: true }),
}).strict();

export type PortfolioKind = z.infer<typeof portfolioKindSchema>;
export type RankingPeriod = z.infer<typeof rankingPeriodSchema>;
export type IndicatorId = z.infer<typeof indicatorIdSchema>;
export type StrategyOperator = z.infer<typeof strategyOperatorSchema>;
export type StrategyOperand = z.infer<typeof strategyOperandSchema>;
export type StrategyReference = z.infer<typeof strategyReferenceSchema>;
export type StrategyRuleRequest = z.input<typeof strategyRuleSchema>;
export type StrategyRuleInput = z.output<typeof strategyRuleSchema>;
export type StrategyEvaluationInput = z.output<typeof strategyEvaluationSchema>;
export type CreateStrategyInput = z.infer<typeof createStrategySchema>;
export type SellRuleInput = z.infer<typeof sellRuleSchema>;
export type ManualExecutionInput = z.infer<typeof manualExecutionSchema>;
export type ProfileVisibilityInput = z.infer<typeof profileVisibilitySchema>;
export type AlertSettingsInput = z.infer<typeof alertSettingsSchema>;
export type SignalAdvice = z.infer<typeof signalAdviceSchema>;
