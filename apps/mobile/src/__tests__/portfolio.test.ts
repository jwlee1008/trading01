import { addExecution, profitRate, replayExecutions } from '@/domain/portfolio';
import type { Execution, Position } from '@/domain/types';

const execution = (id: string, side: 'BUY' | 'SELL', price: number, quantity: number): Execution => ({
  id,
  side,
  price,
  quantity,
  fee: 0,
  tax: 0,
  executedAt: '2026-08-15T09:00:00+09:00',
  memo: 'test',
});

describe('position ledger replay', () => {
  it('opens, recalculates weighted average, and keeps remaining quantity after partial sell', () => {
    const result = replayExecutions([
      execution('1', 'BUY', 10_000, 10),
      execution('2', 'BUY', 12_000, 10),
      execution('3', 'SELL', 13_000, 5),
    ]);

    expect(result).toEqual({ quantity: 15, averagePrice: 11_000, realizedProfit: 10_000, status: 'PARTIALLY_CLOSED' });
  });

  it('closes only after full sell and rejects oversell', () => {
    expect(replayExecutions([execution('1', 'BUY', 10_000, 3), execution('2', 'SELL', 11_000, 3)]).status).toBe('CLOSED');
    expect(() => replayExecutions([execution('1', 'BUY', 10_000, 3), execution('2', 'SELL', 11_000, 4)])).toThrow('보유 수량');
  });

  it('derives profit from current price without mixing ledger kinds', () => {
    const position: Position = {
      id: 'p', kind: 'MANUAL_LIVE', symbol: '005930', instrumentName: '삼성전자', quantity: 2, averagePrice: 50_000, currentPrice: 55_000, status: 'OPEN', firstBoughtAt: '2026-08-01', highestClose: 55_000, signalId: null, strategyVersion: null, executions: [execution('1', 'BUY', 50_000, 2)], sellRule: null,
    };
    expect(profitRate(position)).toBe(10);
    expect(addExecution(position, execution('2', 'SELL', 55_000, 1)).status).toBe('PARTIALLY_CLOSED');
  });
});
