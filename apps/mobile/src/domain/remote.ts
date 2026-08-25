import type { AppAlert, BuySignal, PortfolioKind, Position, Strategy, UniverseId } from '@/domain/types';

export interface RemoteSnapshot {
  strategies: Strategy[];
  strategyHistory: Strategy[];
  signals: BuySignal[];
  positions: Position[];
  portfolioIds: Partial<Record<PortfolioKind, string>>;
  nickname?: string;
  profilePublic?: boolean;
  selectedUniverseId?: UniverseId;
  alerts?: AppAlert[];
  notificationsEnabled?: boolean;
  quietHoursEnabled?: boolean;
}
