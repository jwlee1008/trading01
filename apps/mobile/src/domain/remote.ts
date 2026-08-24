import type { AppAlert, BuySignal, PaperOrder, PortfolioKind, Position, Strategy, UniverseId } from '@/domain/types';

export interface RankingTrack {
  id: string;
  strategyVersionId: string;
  portfolioId: string;
  strategyName: string;
  initialCapital: number;
  returnRate: number;
  maxDrawdown: number;
  tradeCount: number;
  isPublic: boolean;
  startedAt: string;
}

export interface RemoteSnapshot {
  strategies: Strategy[];
  strategyHistory: Strategy[];
  signals: BuySignal[];
  positions: Position[];
  orders: PaperOrder[];
  portfolioIds: Partial<Record<PortfolioKind, string>>;
  sandboxCash: number;
  nickname?: string;
  profilePublic?: boolean;
  delayedPositionPublic?: boolean;
  selectedUniverseId?: UniverseId;
  alerts?: AppAlert[];
  notificationsEnabled?: boolean;
  quietHoursEnabled?: boolean;
  rankingTrack: RankingTrack | null;
}
