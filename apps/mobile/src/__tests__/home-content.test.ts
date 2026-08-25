import { selectHomeContent } from '@/domain/home';
import type { BuySignal, Strategy } from '@/domain/types';

const strategy = (id: string, universeId: Strategy['universeId']): Strategy => ({
  id, remoteStrategyId: `parent-${id}`, name: id, version: 1, universeId,
  indicatorIds: ['rsi'], conditionMode: 'ALL', alertEnabled: true,
  cooldownHours: 24, public: false, locked: false, createdAt: '2026-08-25T00:00:00Z',
});

const signal = (id: string, strategyId: string, symbol: string): BuySignal => ({
  id, strategyId, symbol, instrumentName: symbol, closePrice: 100, changeRate: 0,
  candleClose: '2026-08-25T06:30:00Z', createdAt: '2026-08-25T06:30:00Z',
  reasons: [], values: [], delayed: false, read: false,
});

describe('home universe filtering', () => {
  it('shows only strategies and signals bound to the selected universe', () => {
    const strategies = [strategy('kospi-v1', 'kospi'), strategy('demo-v1', 'demoTop30')];
    const signals = [signal('real', 'kospi-v1', '005930'), signal('fixture', 'demo-v1', 'TST001')];

    expect(selectHomeContent('kospi', strategies, signals)).toEqual({
      strategies: [strategies[0]], signals: [signals[0]],
    });
    expect(selectHomeContent('demoTop30', strategies, signals)).toEqual({
      strategies: [strategies[1]], signals: [signals[1]],
    });
  });

  it('does not leak test symbols into another universe', () => {
    const strategies = [strategy('demo-v1', 'demoTop30')];
    const signals = [signal('fixture', 'demo-v1', 'TST001')];
    expect(selectHomeContent('kospi', strategies, signals)).toEqual({ strategies: [], signals: [] });
  });
});
