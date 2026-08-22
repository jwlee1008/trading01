export type UniverseId = 'kospi200' | 'kosdaq150' | 'kospiTop10' | 'kospi' | 'kosdaq' | 'all' | 'custom';
export type IndicatorId = 'sma' | 'ema' | 'rsi' | 'macd' | 'bollinger' | 'volume' | 'stochastic' | 'atr' | 'adx' | 'obv';
export type PortfolioKind = 'MANUAL_LIVE' | 'SANDBOX_PAPER' | 'RANKED_PAPER';
export type PositionStatus = 'OPEN' | 'EXIT_PENDING' | 'PARTIALLY_CLOSED' | 'CLOSED' | 'ARCHIVED';
export type OrderStatus = 'PENDING' | 'FILLED' | 'CANCELLED' | 'REJECTED' | 'EXPIRED';

export interface Universe {
  id: UniverseId;
  name: string;
  count: number;
  version: string;
  description: string;
}

export interface Indicator {
  id: IndicatorId;
  name: string;
  short: string;
  defaultRule: string;
  minimumCandles: number;
  formula: string;
  caution: string;
  tier: 'S' | 'A' | 'B' | 'C' | '데이터 부족';
}

export interface Strategy {
  id: string;
  remoteStrategyId?: string;
  name: string;
  version: number;
  universeId: UniverseId;
  indicatorIds: IndicatorId[];
  conditionMode: 'ALL' | 'ANY';
  alertEnabled: boolean;
  cooldownHours: number;
  public: boolean;
  locked: boolean;
  createdAt: string;
}

export interface BuySignal {
  id: string;
  symbol: string;
  instrumentName: string;
  strategyId: string;
  closePrice: number;
  changeRate: number;
  candleClose: string;
  createdAt: string;
  reasons: string[];
  values: { label: string; value: string }[];
  delayed: boolean;
  read: boolean;
}

export interface SignalAdvice {
  signalId: string;
  summary: string;
  evidence: string[];
  risks: string[];
  questionsToConsider: string[];
  disclaimer: string;
  source: 'GEMINI' | 'LOCAL';
  model: string;
  basedOn: string;
  generatedAt: string;
}

export interface Execution {
  id: string;
  side: 'BUY' | 'SELL';
  price: number;
  quantity: number;
  fee: number;
  tax: number;
  executedAt: string;
  memo: string;
}

export interface SellRule {
  version: number;
  manualOnly: boolean;
  stopLossPercent: number | null;
  takeProfitPercent: number | null;
  trailingStopPercent: number | null;
  maxHoldingDays: number | null;
  technicalIds: IndicatorId[];
  technicalMode: 'ANY' | 'ALL';
}

export interface Position {
  id: string;
  kind: PortfolioKind;
  symbol: string;
  instrumentName: string;
  quantity: number;
  averagePrice: number;
  currentPrice: number;
  status: PositionStatus;
  firstBoughtAt: string;
  highestClose: number;
  signalId: string | null;
  strategyVersion: number | null;
  executions: Execution[];
  sellRule: SellRule | null;
}

export interface PaperOrder {
  id: string;
  kind: 'SANDBOX_PAPER' | 'RANKED_PAPER';
  side: 'BUY' | 'SELL';
  symbol: string;
  instrumentName: string;
  quantity: number;
  estimatedPrice: number;
  status: OrderStatus;
  createdAt: string;
  scheduledSession: string;
  reservedAmount: number;
  signalId: string | null;
  positionId: string | null;
  rejectReason: string | null;
}

export interface AppAlert {
  id: string;
  kind: 'BUY_SIGNAL' | 'SELL_SIGNAL' | 'SYSTEM';
  title: string;
  body: string;
  createdAt: string;
  read: boolean;
  signalId: string | null;
  positionId: string | null;
  delayed: boolean;
}

export interface CombinationRank {
  id: string;
  rank: number;
  name: string;
  indicatorIds: IndicatorId[];
  universeId: UniverseId;
  excessReturn: Record<'3m' | '6m' | '1y', number>;
  hitRate: number;
  mdd: number;
  signalCount: number;
  instrumentCount: number;
  stability: number;
  confidence: string;
}

export interface UserRank {
  id: string;
  rank: number;
  nickname: string;
  returnRate: Record<'3m' | '6m' | '1y' | 'all', number>;
  universeId: UniverseId;
  mdd: number;
  trades: number;
  days: number;
  strategyName: string;
  public: boolean;
}
