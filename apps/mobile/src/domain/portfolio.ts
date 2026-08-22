import type { Execution, Position, PositionStatus } from './types';

export interface PositionSnapshot {
  quantity: number;
  averagePrice: number;
  realizedProfit: number;
  status: PositionStatus;
}

export function replayExecutions(executions: Execution[]): PositionSnapshot {
  let quantity = 0;
  let cost = 0;
  let realizedProfit = 0;
  let hasSell = false;

  for (const execution of executions) {
    if (!Number.isInteger(execution.quantity) || execution.quantity <= 0 || execution.price <= 0) {
      throw new Error('체결 수량과 단가는 0보다 커야 합니다.');
    }
    if (execution.side === 'BUY') {
      quantity += execution.quantity;
      cost += execution.price * execution.quantity + execution.fee + execution.tax;
      continue;
    }
    if (execution.quantity > quantity) throw new Error('보유 수량을 초과해 매도할 수 없습니다.');
    hasSell = true;
    const averagePrice = quantity === 0 ? 0 : cost / quantity;
    realizedProfit += (execution.price - averagePrice) * execution.quantity - execution.fee - execution.tax;
    cost -= averagePrice * execution.quantity;
    quantity -= execution.quantity;
  }

  const averagePrice = quantity === 0 ? 0 : Math.round(cost / quantity);
  const status: PositionStatus = quantity === 0 ? 'CLOSED' : hasSell ? 'PARTIALLY_CLOSED' : 'OPEN';
  return { quantity, averagePrice, realizedProfit: Math.round(realizedProfit), status };
}

export function addExecution(position: Position, execution: Execution): Position {
  const executions = [...position.executions, execution];
  const snapshot = replayExecutions(executions);
  return { ...position, executions, quantity: snapshot.quantity, averagePrice: snapshot.averagePrice, status: snapshot.status };
}

export function profitRate(position: Position): number {
  if (position.averagePrice === 0) return 0;
  return ((position.currentPrice - position.averagePrice) / position.averagePrice) * 100;
}

export function unrealizedProfit(position: Position): number {
  return Math.round((position.currentPrice - position.averagePrice) * position.quantity);
}
