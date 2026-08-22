export const appBrand = {
  name: "시그널랩",
  slug: "signal-lab",
  accent: "#335CFF",
  supportEmail: "support@example.invalid",
} as const;

export const rankingPolicy = {
  initialCashKrw: 10_000_000,
  maxPositionWeight: 0.1,
  maxOpenPositions: 10,
  restartCooldownDays: 30,
  maxAutoCombinationSize: 4,
  minimumValidSignals: 30,
} as const;

export const featureFlags = {
  automaticFiveIndicatorSearch: false,
  remotePush: false,
  liveBroker: false,
  subscriptions: false,
} as const;

export type FeatureId =
  | "strategy.create"
  | "signal.read"
  | "paper.trade"
  | "ranking.read"
  | "profile.publish";

export interface EntitlementDecision {
  allowed: boolean;
  source: "MVP_FREE" | "PLAN" | "USER_OVERRIDE";
}

export interface EntitlementService {
  can(userId: string, feature: FeatureId): Promise<EntitlementDecision>;
}

export class FreeMvpEntitlementService implements EntitlementService {
  can(userId: string, feature: FeatureId): Promise<EntitlementDecision> {
    void userId;
    void feature;
    return Promise.resolve({ allowed: true, source: "MVP_FREE" });
  }
}
