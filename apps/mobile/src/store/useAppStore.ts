import AsyncStorage from '@react-native-async-storage/async-storage';
import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import type { RemoteSnapshot } from '@/domain/remote';
import type { AppAlert, BuySignal, PortfolioKind, Position, SellRule, Strategy, UniverseId } from '@/domain/types';

type ConnectionMode = 'online' | 'offline' | 'delayed' | 'error';

interface AppState {
  hydrated: boolean;
  hasSeenOnboarding: boolean;
  nickname: string;
  profilePublic: boolean;
  themeMode: 'system' | 'light' | 'dark';
  selectedUniverseId: UniverseId;
  strategies: Strategy[];
  strategyHistory: Strategy[];
  signals: BuySignal[];
  positions: Position[];
  portfolioIds: Partial<Record<PortfolioKind, string>>;
  alerts: AppAlert[];
  connectionMode: ConnectionMode;
  notificationsEnabled: boolean;
  quietHoursEnabled: boolean;
  setHydrated: (hydrated: boolean) => void;
  completeOnboarding: () => void;
  setUniverse: (id: UniverseId) => void;
  setThemeMode: (mode: 'system' | 'light' | 'dark') => void;
  setConnectionMode: (mode: ConnectionMode) => void;
  applyRemoteSnapshot: (snapshot: RemoteSnapshot) => void;
  upsertRemoteStrategy: (strategy: Strategy) => void;
  removeRemoteStrategy: (remoteStrategyId: string) => void;
  markSignalRead: (id: string) => void;
  markAlertRead: (id: string) => void;
  markAllAlertsRead: () => void;
  saveSellRule: (positionId: string, rule: SellRule) => void;
  setProfilePublic: (value: boolean) => void;
  setNickname: (value: string) => void;
  setNotificationsEnabled: (value: boolean) => void;
  setQuietHoursEnabled: (value: boolean) => void;
  clearSessionData: () => void;
}

const remoteInitial = {
  strategies: [] as Strategy[], strategyHistory: [] as Strategy[], signals: [] as BuySignal[],
  positions: [] as Position[], portfolioIds: {} as Partial<Record<PortfolioKind, string>>, alerts: [] as AppAlert[],
};

export const useAppStore = create<AppState>()(
  persist(
    (set) => ({
      hydrated: false,
      hasSeenOnboarding: false,
      nickname: '',
      profilePublic: false,
      themeMode: 'system',
      selectedUniverseId: 'all',
      ...remoteInitial,
      connectionMode: 'online',
      notificationsEnabled: false,
      quietHoursEnabled: false,
      setHydrated: (hydrated) => set({ hydrated }),
      completeOnboarding: () => set({ hasSeenOnboarding: true }),
      setUniverse: (selectedUniverseId) => set({ selectedUniverseId }),
      setThemeMode: (themeMode) => set({ themeMode }),
      setConnectionMode: (connectionMode) => set({ connectionMode }),
      applyRemoteSnapshot: (snapshot) => set({
        strategies: snapshot.strategies,
        strategyHistory: snapshot.strategyHistory,
        signals: snapshot.signals,
        positions: snapshot.positions,
        portfolioIds: snapshot.portfolioIds,
        alerts: snapshot.alerts ?? [],
        ...(snapshot.nickname === undefined ? {} : { nickname: snapshot.nickname }),
        ...(snapshot.profilePublic === undefined ? {} : { profilePublic: snapshot.profilePublic }),
        ...(snapshot.selectedUniverseId === undefined ? {} : { selectedUniverseId: snapshot.selectedUniverseId }),
        ...(snapshot.notificationsEnabled === undefined ? {} : { notificationsEnabled: snapshot.notificationsEnabled }),
        ...(snapshot.quietHoursEnabled === undefined ? {} : { quietHoursEnabled: snapshot.quietHoursEnabled }),
      }),
      upsertRemoteStrategy: (strategy) => set((state) => {
        const key = strategy.remoteStrategyId ?? strategy.id;
        const current = state.strategies.find((item) => (item.remoteStrategyId ?? item.id) === key);
        const previousVersionIds = new Set([...state.strategies, ...state.strategyHistory]
          .filter((item) => (item.remoteStrategyId ?? item.id) === key)
          .map((item) => item.id));
        return {
          strategies: [strategy, ...state.strategies.filter((item) => (item.remoteStrategyId ?? item.id) !== key)],
          strategyHistory: [...(current && current.id !== strategy.id ? [current] : []), ...state.strategyHistory],
          signals: state.signals.filter((item) => !previousVersionIds.has(item.strategyId)),
        };
      }),
      removeRemoteStrategy: (remoteStrategyId) => set((state) => {
        const versionIds = new Set([...state.strategies, ...state.strategyHistory]
          .filter((item) => (item.remoteStrategyId ?? item.id) === remoteStrategyId)
          .map((item) => item.id));
        return {
          strategies: state.strategies.filter((item) => (item.remoteStrategyId ?? item.id) !== remoteStrategyId),
          strategyHistory: state.strategyHistory.filter((item) => (item.remoteStrategyId ?? item.id) !== remoteStrategyId),
          signals: state.signals.filter((item) => !versionIds.has(item.strategyId)),
        };
      }),
      markSignalRead: (id) => set((state) => ({ signals: state.signals.map((item) => item.id === id ? { ...item, read: true } : item) })),
      markAlertRead: (id) => set((state) => ({ alerts: state.alerts.map((item) => item.id === id ? { ...item, read: true } : item) })),
      markAllAlertsRead: () => set((state) => ({ alerts: state.alerts.map((item) => ({ ...item, read: true })) })),
      saveSellRule: (positionId, sellRule) => set((state) => ({ positions: state.positions.map((item) => item.id === positionId ? { ...item, sellRule } : item) })),
      setProfilePublic: (profilePublic) => set({ profilePublic }),
      setNickname: (nickname) => set({ nickname }),
      setNotificationsEnabled: (notificationsEnabled) => set({ notificationsEnabled }),
      setQuietHoursEnabled: (quietHoursEnabled) => set({ quietHoursEnabled }),
      clearSessionData: () => set({ ...remoteInitial, nickname: '', profilePublic: false }),
    }),
    {
      name: 'signal-lab-settings-v4',
      storage: createJSONStorage(() => AsyncStorage),
      partialize: (state) => ({
        hasSeenOnboarding: state.hasSeenOnboarding, themeMode: state.themeMode,
        selectedUniverseId: state.selectedUniverseId, notificationsEnabled: state.notificationsEnabled,
        quietHoursEnabled: state.quietHoursEnabled,
      }),
      onRehydrateStorage: () => (state) => state?.setHydrated(true),
    },
  ),
);
