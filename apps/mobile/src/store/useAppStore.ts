import AsyncStorage from '@react-native-async-storage/async-storage';
import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import { defaultAlerts, defaultPositions, defaultSignals, defaultStrategies } from '@/data/mock';
import { addExecution } from '@/domain/portfolio';
import type { RemoteSnapshot } from '@/domain/remote';
import type { AppAlert, BuySignal, IndicatorId, PaperOrder, Position, SellRule, Strategy, UniverseId } from '@/domain/types';

type ConnectionMode = 'online' | 'offline' | 'delayed' | 'error';

interface StrategyInput {
  name: string;
  universeId: UniverseId;
  indicatorIds: IndicatorId[];
  conditionMode: 'ALL' | 'ANY';
  public: boolean;
}

interface HoldingInput {
  symbol: string;
  instrumentName: string;
  quantity: number;
  price: number;
  boughtAt: string;
  memo: string;
  signalId: string | null;
}

interface OrderInput {
  side: 'BUY' | 'SELL';
  symbol: string;
  instrumentName: string;
  quantity: number;
  estimatedPrice: number;
  signalId: string | null;
  positionId: string | null;
}

interface AppState {
  hydrated: boolean;
  hasSeenOnboarding: boolean;
  trialMode: boolean;
  nickname: string;
  profilePublic: boolean;
  delayedPositionPublic: boolean;
  themeMode: 'system' | 'light' | 'dark';
  selectedUniverseId: UniverseId;
  customSymbols: string[];
  strategies: Strategy[];
  strategyHistory: Strategy[];
  signals: BuySignal[];
  positions: Position[];
  orders: PaperOrder[];
  sandboxCash: number;
  alerts: AppAlert[];
  connectionMode: ConnectionMode;
  notificationsEnabled: boolean;
  quietHoursEnabled: boolean;
  setHydrated: (hydrated: boolean) => void;
  completeOnboarding: (trialMode: boolean) => void;
  setUniverse: (id: UniverseId) => void;
  setThemeMode: (mode: 'system' | 'light' | 'dark') => void;
  setConnectionMode: (mode: ConnectionMode) => void;
  applyRemoteSnapshot: (snapshot: RemoteSnapshot) => void;
  upsertRemoteStrategy: (strategy: Strategy) => void;
  addStrategy: (input: StrategyInput) => string;
  reviseStrategy: (id: string, input: StrategyInput) => void;
  cloneStrategy: (name: string, universeId: UniverseId, indicatorIds: IndicatorId[]) => string;
  toggleStrategyPublic: (id: string) => void;
  markSignalRead: (id: string) => void;
  markAlertRead: (id: string) => void;
  markAllAlertsRead: () => void;
  registerManualHolding: (input: HoldingInput) => string;
  placePaperOrder: (input: OrderInput) => string;
  cancelOrder: (id: string) => void;
  fillOrder: (id: string) => void;
  addManualBuy: (positionId: string, quantity: number, price: number) => void;
  sellManual: (positionId: string, quantity: number, price: number) => void;
  saveSellRule: (positionId: string, rule: SellRule) => void;
  setProfilePublic: (value: boolean) => void;
  setDelayedPositionPublic: (value: boolean) => void;
  setNickname: (value: string) => void;
  setNotificationsEnabled: (value: boolean) => void;
  setQuietHoursEnabled: (value: boolean) => void;
  toggleWatchlist: (symbol: string) => void;
  resetDemo: () => void;
}

const now = () => new Date().toISOString();
const makeId = (prefix: string) => `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
const mockFillModel = { buySlippage: 0.001, sellSlippage: 0.001, feeRate: 0.00015 } as const;
const mockOfficialOpen: Record<string, number> = {
  '005930': 79_100,
  '000660': 195_500,
  '035420': 227_000,
  '051910': 321_000,
  '068270': 188_500,
};
const mockNextSession = '2026-08-17';

function estimatedBuyReserve(price: number, quantity: number) {
  const adversePrice = Math.ceil(price * (1 + mockFillModel.buySlippage));
  const fee = Math.ceil(adversePrice * quantity * mockFillModel.feeRate);
  return adversePrice * quantity + fee;
}

const initialData = {
  hasSeenOnboarding: false,
  trialMode: true,
  nickname: '신호연습생',
  profilePublic: false,
  delayedPositionPublic: true,
  themeMode: 'system' as const,
  selectedUniverseId: 'demoTop50' as UniverseId,
  customSymbols: ['005930', '000660', '035420', '068270'],
  strategies: defaultStrategies,
  strategyHistory: [] as Strategy[],
  signals: defaultSignals,
  positions: defaultPositions,
  orders: [] as PaperOrder[],
  sandboxCash: 8_383_484,
  alerts: defaultAlerts,
  connectionMode: 'online' as ConnectionMode,
  notificationsEnabled: true,
  quietHoursEnabled: false,
};

function createExecution(side: 'BUY' | 'SELL', price: number, quantity: number, memo: string) {
  return { id: makeId('exe'), side, price, quantity, fee: Math.round(price * quantity * 0.00015), tax: side === 'SELL' ? Math.round(price * quantity * 0.0018) : 0, executedAt: now(), memo } as const;
}

export const useAppStore = create<AppState>()(
  persist(
    (set, get) => ({
      hydrated: false,
      ...initialData,
      setHydrated: (hydrated) => set({ hydrated }),
      completeOnboarding: (trialMode) => set({ hasSeenOnboarding: true, trialMode }),
      setUniverse: (selectedUniverseId) => set({ selectedUniverseId }),
      setThemeMode: (themeMode) => set({ themeMode }),
      setConnectionMode: (connectionMode) => set({ connectionMode }),
      applyRemoteSnapshot: (snapshot) => set((state) => ({
        strategies: snapshot.strategies,
        strategyHistory: snapshot.strategyHistory,
        signals: snapshot.signals.map((signal) => ({
          ...signal,
          read: state.signals.find((item) => item.id === signal.id)?.read ?? signal.read,
        })),
        positions: snapshot.positions.map((position) => {
          const local = state.positions.find((item) => item.id === position.id);
          return {
            ...position,
            executions: local?.executions ?? position.executions,
            sellRule: local?.sellRule ?? position.sellRule,
            strategyVersion: local?.strategyVersion ?? position.strategyVersion,
          };
        }),
        orders: snapshot.orders,
        sandboxCash: snapshot.sandboxCash,
        ...(snapshot.alerts === undefined ? {} : {
          alerts: snapshot.alerts.map((alert) => ({
            ...alert,
            read: state.alerts.find((item) => item.id === alert.id)?.read ?? alert.read,
          })),
        }),
        ...(snapshot.notificationsEnabled === undefined ? {} : { notificationsEnabled: snapshot.notificationsEnabled }),
        ...(snapshot.quietHoursEnabled === undefined ? {} : { quietHoursEnabled: snapshot.quietHoursEnabled }),
      })),
      upsertRemoteStrategy: (strategy) => set((state) => {
        const key = strategy.remoteStrategyId ?? strategy.id;
        const sameStrategy = (item: Strategy) => (item.remoteStrategyId ?? item.id) === key;
        const current = state.strategies.find(sameStrategy);
        return {
          strategies: [strategy, ...state.strategies.filter((item) => !sameStrategy(item))],
          strategyHistory: [
            ...(current && current.id !== strategy.id ? [current] : []),
            ...state.strategyHistory.filter((item) => item.id !== strategy.id && item.id !== current?.id),
          ],
        };
      }),
      addStrategy: (input) => {
        if (input.indicatorIds.length === 0 || input.indicatorIds.length > 5) throw new Error('지표는 1~5개만 선택할 수 있습니다.');
        const id = makeId('strategy');
        const strategy: Strategy = { ...input, id, version: 1, alertEnabled: true, cooldownHours: 24, locked: false, createdAt: now() };
        set((state) => ({ strategies: [strategy, ...state.strategies] }));
        return id;
      },
      reviseStrategy: (id, input) => {
        if (input.indicatorIds.length === 0 || input.indicatorIds.length > 5) throw new Error('지표는 1~5개만 선택할 수 있습니다.');
        set((state) => {
          const current = state.strategies.find((strategy) => strategy.id === id && !strategy.locked);
          if (!current) return state;
          return { strategyHistory: [current, ...state.strategyHistory], strategies: state.strategies.map((strategy) => strategy.id === id ? { ...strategy, ...input, version: strategy.version + 1, createdAt: now() } : strategy) };
        });
      },
      cloneStrategy: (name, universeId, indicatorIds) => get().addStrategy({ name: `${name} 복사본`, universeId, indicatorIds, conditionMode: 'ALL', public: false }),
      toggleStrategyPublic: (id) => set((state) => {
        const current = state.strategies.find((item) => item.id === id && !item.locked);
        if (!current) return state;
        return { strategyHistory: [current, ...state.strategyHistory], strategies: state.strategies.map((item) => item.id === id ? { ...item, public: !item.public, version: item.version + 1, createdAt: now() } : item) };
      }),
      markSignalRead: (id) => set((state) => ({ signals: state.signals.map((signal) => signal.id === id ? { ...signal, read: true } : signal) })),
      markAlertRead: (id) => set((state) => ({ alerts: state.alerts.map((alert) => alert.id === id ? { ...alert, read: true } : alert) })),
      markAllAlertsRead: () => set((state) => ({ alerts: state.alerts.map((alert) => ({ ...alert, read: true })) })),
      registerManualHolding: (input) => {
        if (!Number.isInteger(input.quantity) || input.quantity <= 0 || input.price <= 0) throw new Error('수량과 단가를 확인하세요.');
        if (input.signalId) {
          const signal = get().signals.find((item) => item.id === input.signalId);
          if (!signal || signal.symbol !== input.symbol) throw new Error('신호와 종목이 일치하지 않습니다.');
        }
        const existing = get().positions.find((item) => item.kind === 'MANUAL_LIVE' && item.symbol === input.symbol && item.status !== 'CLOSED');
        const execution = createExecution('BUY', input.price, input.quantity, input.memo);
        if (existing) {
          set((state) => ({ positions: state.positions.map((item) => item.id === existing.id ? addExecution(item, execution) : item) }));
          return existing.id;
        }
        const id = makeId('pos');
        const position: Position = { id, kind: 'MANUAL_LIVE', symbol: input.symbol, instrumentName: input.instrumentName, quantity: input.quantity, averagePrice: input.price, currentPrice: input.price, status: 'OPEN', firstBoughtAt: input.boughtAt, highestClose: input.price, signalId: input.signalId, strategyVersion: null, executions: [execution], sellRule: null };
        set((state) => ({ positions: [position, ...state.positions] }));
        return id;
      },
      placePaperOrder: (input) => {
        if (!Number.isInteger(input.quantity) || input.quantity <= 0) throw new Error('정수 수량만 주문할 수 있습니다.');
        if (!Number.isFinite(input.estimatedPrice) || input.estimatedPrice <= 0) throw new Error('추정 단가를 확인하세요.');
        if (input.signalId) {
          const signal = get().signals.find((item) => item.id === input.signalId);
          if (!signal || signal.symbol !== input.symbol) throw new Error('신호와 종목이 일치하지 않습니다.');
        }
        let reservedAmount = 0;
        if (input.side === 'BUY') {
          reservedAmount = estimatedBuyReserve(input.estimatedPrice, input.quantity);
          const pendingBuyReserve = get().orders
            .filter((order) => order.status === 'PENDING' && order.side === 'BUY')
            .reduce((sum, order) => sum + order.reservedAmount, 0);
          if (reservedAmount + pendingBuyReserve > get().sandboxCash) throw new Error('예약 주문을 포함한 연습 현금이 부족합니다.');
        } else {
          if (!input.positionId) throw new Error('매도 주문에는 연습 포지션이 필요합니다.');
          const position = get().positions.find((item) => item.id === input.positionId);
          if (!position || position.kind !== 'SANDBOX_PAPER' || position.symbol !== input.symbol || position.status === 'CLOSED') throw new Error('유효한 연습 포지션이 필요합니다.');
          const reservedQuantity = get().orders
            .filter((order) => order.status === 'PENDING' && order.side === 'SELL' && order.positionId === input.positionId)
            .reduce((sum, order) => sum + order.quantity, 0);
          if (input.quantity + reservedQuantity > position.quantity) throw new Error('예약 수량을 포함해 보유 수량을 초과했습니다.');
        }
        const id = makeId('order');
        const order: PaperOrder = { ...input, id, kind: 'SANDBOX_PAPER', status: 'PENDING', createdAt: now(), scheduledSession: mockNextSession, reservedAmount, rejectReason: null };
        set((state) => ({ orders: [order, ...state.orders] }));
        return id;
      },
      cancelOrder: (id) => set((state) => ({ orders: state.orders.map((order) => order.id === id && order.kind === 'SANDBOX_PAPER' && order.status === 'PENDING' ? { ...order, status: 'CANCELLED' } : order) })),
      fillOrder: (id) => {
        const order = get().orders.find((item) => item.id === id);
        if (!order || order.status !== 'PENDING') return;
        const officialOpen = mockOfficialOpen[order.symbol];
        if (!officialOpen) {
          set((state) => ({ orders: state.orders.map((item) => item.id === id ? { ...item, status: 'REJECTED', rejectReason: 'Mock 공식 시가 없음' } : item) }));
          return;
        }
        const fillPrice = Math.round(officialOpen * (order.side === 'BUY' ? 1 + mockFillModel.buySlippage : 1 - mockFillModel.sellSlippage));
        const execution = createExecution(order.side, fillPrice, order.quantity, 'Mock D+1 공식 시가 · 불리한 슬리피지 적용');
        if (order.side === 'BUY') {
          const debit = fillPrice * order.quantity + execution.fee;
          if (debit > get().sandboxCash) {
            set((state) => ({ orders: state.orders.map((item) => item.id === id ? { ...item, status: 'REJECTED', rejectReason: '체결 시점 현금 부족' } : item) }));
            return;
          }
          const existing = get().positions.find((item) => item.kind === order.kind && item.symbol === order.symbol && item.status !== 'CLOSED');
          if (existing) {
            set((state) => ({ positions: state.positions.map((item) => item.id === existing.id ? addExecution(item, execution) : item) }));
          } else {
            const position: Position = { id: makeId('pos'), kind: order.kind, symbol: order.symbol, instrumentName: order.instrumentName, quantity: order.quantity, averagePrice: fillPrice, currentPrice: order.estimatedPrice, status: 'OPEN', firstBoughtAt: now().slice(0, 10), highestClose: order.estimatedPrice, signalId: order.signalId, strategyVersion: order.signalId ? 1 : null, executions: [execution], sellRule: null };
            set((state) => ({ positions: [position, ...state.positions] }));
          }
          set((state) => ({ sandboxCash: state.sandboxCash - debit }));
        } else if (order.positionId) {
          const position = get().positions.find((item) => item.id === order.positionId && item.kind === 'SANDBOX_PAPER' && item.status !== 'CLOSED');
          if (!position || order.quantity > position.quantity) {
            set((state) => ({ orders: state.orders.map((item) => item.id === id ? { ...item, status: 'REJECTED', rejectReason: '체결 시점 가용 수량 부족' } : item) }));
            return;
          }
          set((state) => ({ positions: state.positions.map((item) => item.id === order.positionId ? addExecution(item, execution) : item) }));
          set((state) => ({ sandboxCash: state.sandboxCash + fillPrice * order.quantity - execution.fee - execution.tax }));
        }
        set((state) => ({ orders: state.orders.map((item) => item.id === id ? { ...item, status: 'FILLED' } : item) }));
      },
      addManualBuy: (positionId, quantity, price) => {
        const execution = createExecution('BUY', price, quantity, '추가 매수 등록');
        set((state) => ({ positions: state.positions.map((position) => position.id === positionId ? addExecution(position, execution) : position) }));
      },
      sellManual: (positionId, quantity, price) => {
        const execution = createExecution('SELL', price, quantity, '수동 매도 등록');
        set((state) => ({ positions: state.positions.map((position) => position.id === positionId ? addExecution(position, execution) : position) }));
      },
      saveSellRule: (positionId, sellRule) => set((state) => ({ positions: state.positions.map((position) => position.id === positionId ? { ...position, sellRule } : position) })),
      setProfilePublic: (profilePublic) => set({ profilePublic }),
      setDelayedPositionPublic: (delayedPositionPublic) => set({ delayedPositionPublic }),
      setNickname: (nickname) => set({ nickname }),
      setNotificationsEnabled: (notificationsEnabled) => set({ notificationsEnabled }),
      setQuietHoursEnabled: (quietHoursEnabled) => set({ quietHoursEnabled }),
      toggleWatchlist: (symbol) => set((state) => ({ customSymbols: state.customSymbols.includes(symbol) ? state.customSymbols.filter((item) => item !== symbol) : [...state.customSymbols, symbol] })),
      resetDemo: () => set({ ...initialData, hydrated: true }),
    }),
    {
      name: 'signal-lab-local-v3',
      storage: createJSONStorage(() => AsyncStorage),
      partialize: (state) => ({ ...state, hydrated: undefined }),
      onRehydrateStorage: () => (state) => state?.setHydrated(true),
    },
  ),
);
