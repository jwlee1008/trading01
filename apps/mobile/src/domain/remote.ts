import type { AppAlert, BuySignal, PaperOrder, Position, Strategy } from '@/domain/types';

export interface RemoteSnapshot {
  strategies: Strategy[];
  strategyHistory: Strategy[];
  signals: BuySignal[];
  positions: Position[];
  orders: PaperOrder[];
  sandboxCash: number;
  alerts?: AppAlert[];
  notificationsEnabled?: boolean;
  quietHoursEnabled?: boolean;
}
