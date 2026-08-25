import type { BuySignal, Strategy, UniverseId } from '@/domain/types';

export function selectHomeContent(selectedUniverseId: UniverseId, strategies: Strategy[], signals: BuySignal[]) {
  const visibleStrategies = strategies.filter((strategy) => strategy.universeId === selectedUniverseId);
  const visibleVersionIds = new Set(visibleStrategies.map((strategy) => strategy.id));
  return {
    strategies: visibleStrategies,
    signals: signals.filter((signal) => visibleVersionIds.has(signal.strategyId)),
  };
}
