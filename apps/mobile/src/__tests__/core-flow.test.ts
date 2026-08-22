import { useAppStore } from '@/store/useAppStore';

describe('local MVP core flow', () => {
  beforeEach(() => {
    useAppStore.getState().resetDemo();
  });

  it('creates max-five-indicator strategy and blocks sixth indicator', () => {
    const id = useAppStore.getState().addStrategy({ name: '5개 전략', universeId: 'kospi200', indicatorIds: ['sma', 'ema', 'rsi', 'macd', 'bollinger'], conditionMode: 'ALL', public: false });
    expect(useAppStore.getState().strategies.find((item) => item.id === id)?.indicatorIds).toHaveLength(5);
    expect(() => useAppStore.getState().addStrategy({ name: '6개 전략', universeId: 'all', indicatorIds: ['sma', 'ema', 'rsi', 'macd', 'bollinger', 'volume'], conditionMode: 'ANY', public: false })).toThrow('1~5개');
  });

  it('preserves previous strategy version when revising', () => {
    const current = useAppStore.getState().strategies.find((item) => !item.locked);
    expect(current).toBeDefined();
    useAppStore.getState().reviseStrategy(current!.id, { name: '개정 전략', universeId: 'kosdaq150', indicatorIds: ['rsi', 'macd'], conditionMode: 'ANY', public: false });
    expect(useAppStore.getState().strategies.find((item) => item.id === current!.id)?.version).toBe(current!.version + 1);
    expect(useAppStore.getState().strategyHistory).toContainEqual(current);
  });

  it('does not create position from buy signal alone, then fills explicit paper order', () => {
    const before = useAppStore.getState().positions.length;
    const signal = useAppStore.getState().signals[0];
    expect(signal).toBeDefined();
    expect(useAppStore.getState().positions).toHaveLength(before);

    const orderId = useAppStore.getState().placePaperOrder({ side: 'BUY', symbol: signal!.symbol, instrumentName: signal!.instrumentName, quantity: 3, estimatedPrice: signal!.closePrice, signalId: signal!.id, positionId: null });
    expect(useAppStore.getState().positions).toHaveLength(before);
    expect(useAppStore.getState().orders.find((item) => item.id === orderId)?.status).toBe('PENDING');

    useAppStore.getState().fillOrder(orderId);
    expect(useAppStore.getState().orders.find((item) => item.id === orderId)?.status).toBe('FILLED');
    expect(useAppStore.getState().positions).toHaveLength(before + 1);
    expect(useAppStore.getState().positions.find((item) => item.signalId === signal!.id)?.status).toBe('OPEN');
  });

  it('registers signal-free live holding and stops watch after full manual sell', () => {
    const positionId = useAppStore.getState().registerManualHolding({ symbol: '005380', instrumentName: '현대차', quantity: 4, price: 244_000, boughtAt: '2026-08-14', memo: '신호 없이 등록', signalId: null });
    const open = useAppStore.getState().positions.find((item) => item.id === positionId);
    expect(open?.signalId).toBeNull();
    expect(open?.status).toBe('OPEN');

    useAppStore.getState().sellManual(positionId, 2, 250_000);
    expect(useAppStore.getState().positions.find((item) => item.id === positionId)?.status).toBe('PARTIALLY_CLOSED');
    expect(useAppStore.getState().positions.find((item) => item.id === positionId)?.quantity).toBe(2);

    useAppStore.getState().sellManual(positionId, 2, 251_000);
    expect(useAppStore.getState().positions.find((item) => item.id === positionId)?.status).toBe('CLOSED');
  });

  it('rejects paper sell above available quantity', () => {
    const position = useAppStore.getState().positions.find((item) => item.kind === 'SANDBOX_PAPER');
    expect(position).toBeDefined();
    expect(() => useAppStore.getState().placePaperOrder({ side: 'SELL', symbol: position!.symbol, instrumentName: position!.instrumentName, quantity: position!.quantity + 1, estimatedPrice: position!.currentPrice, signalId: null, positionId: position!.id })).toThrow('보유 수량');
  });

  it('rejects unlinked and over-reserved paper sells', () => {
    const position = useAppStore.getState().positions.find((item) => item.kind === 'SANDBOX_PAPER');
    expect(position).toBeDefined();
    expect(() => useAppStore.getState().placePaperOrder({ side: 'SELL', symbol: position!.symbol, instrumentName: position!.instrumentName, quantity: 1, estimatedPrice: position!.currentPrice, signalId: null, positionId: null })).toThrow('포지션');
    useAppStore.getState().placePaperOrder({ side: 'SELL', symbol: position!.symbol, instrumentName: position!.instrumentName, quantity: position!.quantity, estimatedPrice: position!.currentPrice, signalId: null, positionId: position!.id });
    expect(() => useAppStore.getState().placePaperOrder({ side: 'SELL', symbol: position!.symbol, instrumentName: position!.instrumentName, quantity: 1, estimatedPrice: position!.currentPrice, signalId: null, positionId: position!.id })).toThrow('예약 수량');
  });

  it('reserves pending paper buys against sandbox cash', () => {
    const cash = useAppStore.getState().sandboxCash;
    expect(() => useAppStore.getState().placePaperOrder({ side: 'BUY', symbol: '005930', instrumentName: '삼성전자', quantity: Math.ceil(cash / 79_000), estimatedPrice: 79_000, signalId: null, positionId: null })).toThrow('현금');
  });

  it('keeps local sell-rule detail while server snapshot owns ledger fields', () => {
    const local = useAppStore.getState().positions.find((item) => item.sellRule !== null);
    expect(local).toBeDefined();
    useAppStore.getState().applyRemoteSnapshot({
      strategies: [], strategyHistory: [], signals: [], orders: [], sandboxCash: 100,
      positions: [{
        ...local!, quantity: 99, currentPrice: 123_456, status: 'EXIT_PENDING', executions: [], sellRule: null, strategyVersion: null,
      }],
    });
    const merged = useAppStore.getState().positions[0];
    expect(merged).toMatchObject({ quantity: 99, currentPrice: 123_456, status: 'EXIT_PENDING' });
    expect(merged?.sellRule).toEqual(local?.sellRule);
    expect(merged?.executions).toEqual(local?.executions);
  });
});
