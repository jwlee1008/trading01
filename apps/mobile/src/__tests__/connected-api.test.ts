import { createStrategySchema, sellRuleSchema } from '../../../../packages/api-client/src/schemas';
import {
  buildRemoteSellRuleInput,
  buildRemoteStrategyInput,
  createRemoteStrategy,
  mapRemoteSnapshot,
  requestRemoteSignalAdvice,
  updateRemoteAlertSettings,
} from '@/services/connected-api';

describe('connected API snapshot', () => {
  it('maps API strategy, signal, portfolio, position, and order data', () => {
    const snapshot = mapRemoteSnapshot({
      strategies: [{
        id: 'sv-1', name: '교차 전략', version: 2, universeVersionId: 'kospi200-v1',
        logic: 'AND', alertsEnabled: true, isPublic: false, locked: true,
        strategyId: 'strategy-1', createdAt: '2026-08-15T00:00:00.000Z',
        rules: [{ left: { kind: 'CLOSE' }, operator: 'CROSSES_ABOVE', right: { kind: 'INDICATOR', indicatorId: 'SMA' } }],
      }],
      signals: [{
        id: 'sig-1', type: 'BUY_CONDITION', symbol: '005930', name: '삼성전자',
        strategyVersionId: 'sv-1', closePrice: '71000', candleClose: '2026-08-14T06:30:00.000Z',
        status: 'ACTIVE', stale: false, reasons: [{ label: '종가', value: '71,000' }],
      }],
      portfolios: [{
        id: 'pf-sandbox', kind: 'SANDBOX_PAPER', cash: '9000000', positions: [{
          id: 'pos-1', symbol: '005930', name: '삼성전자', quantity: '10', averagePrice: '70000',
          currentPrice: '71000', highestClose: '72000', status: 'OPEN',
          openedAt: '2026-08-14T00:00:00.000Z', linkedSignalId: 'sig-1',
        }],
      }],
      orders: [{
        id: 'ord-1', portfolioId: 'pf-sandbox', side: 'SELL', symbol: '005930', quantity: 5,
        estimatedPrice: 71000, reservedCash: 0, status: 'PENDING',
        submittedAt: '2026-08-15T00:00:00.000Z', scheduledSession: '2026-08-18',
        signalId: 'sig-1', positionId: 'pos-1',
      }],
    });

    expect(snapshot.strategies[0]).toMatchObject({ id: 'sv-1', remoteStrategyId: 'strategy-1', universeId: 'kospi200', indicatorIds: ['sma'] });
    expect(snapshot.strategyHistory).toEqual([]);
    expect(snapshot.signals[0]).toMatchObject({ id: 'sig-1', symbol: '005930', closePrice: 71000 });
    expect(snapshot.positions[0]).toMatchObject({ id: 'pos-1', kind: 'SANDBOX_PAPER', quantity: 10 });
    expect(snapshot.orders[0]).toMatchObject({ id: 'ord-1', side: 'SELL', positionId: 'pos-1' });
    expect(snapshot.portfolioIds).toEqual({ SANDBOX_PAPER: 'pf-sandbox' });
    expect(snapshot.sandboxCash).toBe(9000000);
  });

  it('drops unknown rows and defaults optional values safely', () => {
    const snapshot = mapRemoteSnapshot({
      strategies: [null],
      signals: [{ id: 'sell', type: 'SELL_CONDITION' }],
      portfolios: [{ id: 'bad', kind: 'UNKNOWN', positions: [] }],
      orders: [{ id: 'bad', portfolioId: 'bad', status: 'PENDING' }],
    });

    expect(snapshot).toEqual({ strategies: [], strategyHistory: [], signals: [], positions: [], orders: [], portfolioIds: {}, sandboxCash: 0, rankingTrack: null });
  });

  it('keeps latest remote strategy and moves prior versions to history', () => {
    const snapshot = mapRemoteSnapshot({
      strategies: [
        { id: 'sv-1', strategyId: 's-1', version: 1, name: 'v1', universeVersionId: 'uv-all-202608', rules: [{ indicatorId: 'RSI' }] },
        { id: 'sv-2', strategyId: 's-1', version: 2, name: 'v2', universeVersionId: 'uv-all-202608', rules: [{ indicatorId: 'MACD' }] },
      ],
      signals: [], portfolios: [], orders: [],
    });

    expect(snapshot.strategies.map((item) => item.id)).toEqual(['sv-2']);
    expect(snapshot.strategyHistory.map((item) => item.id)).toEqual(['sv-1']);
  });

  it('keeps KOSPI all and KOSPI top 10 as distinct universe identities', () => {
    const snapshot = mapRemoteSnapshot({
      strategies: [
        { id: 'sv-all', strategyId: 's-all', universeVersionId: 'uuid-all', universeKind: 'KOSPI_ALL', rules: [] },
        { id: 'sv-top10', strategyId: 's-top10', universeVersionId: 'uuid-top10', universeKind: 'KOSPI_TOP_10', rules: [] },
      ],
      signals: [], portfolios: [], orders: [],
    });

    expect(snapshot.strategies.map((item) => item.universeId)).toEqual(['kospi', 'kospiTop10']);
  });

  it('maps sell-condition signal to position-bound alert', () => {
    const snapshot = mapRemoteSnapshot({
      strategies: [], portfolios: [], orders: [],
      signals: [{
        id: 'sell-1', type: 'SELL_CONDITION', positionId: 'pos-1', symbol: '005930', name: '삼성전자',
        candleClose: '2026-08-15T06:30:00.000Z', status: 'ACTIVE', stale: true,
        reasons: [{ label: '손절', value: '-8%' }],
      }],
    });

    expect(snapshot.signals).toEqual([]);
    expect(snapshot.alerts).toEqual([expect.objectContaining({
      id: 'sell-1', kind: 'SELL_SIGNAL', positionId: 'pos-1', signalId: null, delayed: true,
    })]);
  });

  it('builds canonical source-pair rules and exact sell-rule fields', () => {
    const payload = buildRemoteStrategyInput({
      name: ' 교차 ', universeId: 'kospi200', indicatorIds: ['sma', 'macd', 'atr'], conditionMode: 'ALL', public: false,
    });
    const rules = payload['rules'] as Array<Record<string, unknown>>;
    expect(payload).toMatchObject({ name: '교차', universeVersionId: 'KOSPI_200', logic: 'AND' });
    expect(rules[0]).toMatchObject({ left: { kind: 'CLOSE' }, operator: 'CROSSES_ABOVE', right: { indicatorId: 'SMA', outputKey: 'sma' } });
    expect(rules[1]).toMatchObject({ left: { indicatorId: 'MACD', outputKey: 'macd' }, right: { indicatorId: 'MACD', outputKey: 'signal' } });
    expect(rules[2]).toMatchObject({ left: { indicatorId: 'ATR', params: { period: 14 } }, right: { indicatorId: 'ATR', params: { period: 20 } } });
    expect(createStrategySchema.safeParse(payload).success).toBe(true);
    const sellRule = buildRemoteSellRuleInput({
      version: 2, manualOnly: false, stopLossPercent: 8, takeProfitPercent: null,
      trailingStopPercent: 10, maxHoldingDays: 60, technicalIds: ['rsi'], technicalMode: 'ANY',
    });
    expect(sellRule).toMatchObject({ stopLossPct: 8, trailingStopPct: 10, maxHoldingSessions: 60, technicalLogic: 'ANY', manualOnly: false });
    expect(sellRuleSchema.safeParse(sellRule).success).toBe(true);
  });

  it('sends one create mutation and maps returned server identity', async () => {
    const fetcher = jest.fn((url: string | URL | Request) => {
      const requestUrl = typeof url === 'string' ? url : url instanceof URL ? url.href : url.url;
      return Promise.resolve({
        ok: true,
        json: () => Promise.resolve(requestUrl.endsWith('/v1/universe-versions') ? { data: [{ id: 'real-universe-uuid', kind: 'KOSPI_200' }] } : { data: {
          id: 'sv-new', strategyId: 's-new', name: '원격 전략', version: 1, universeVersionId: 'uv-kospi200-202608',
          logic: 'AND', rules: [{ left: { kind: 'INDICATOR', indicatorId: 'RSI' }, right: { kind: 'VALUE', value: 30 } }],
          alertsEnabled: true, isPublic: false, locked: false, createdAt: '2026-08-15T00:00:00.000Z',
        } }),
      } as Response);
    }) as unknown as jest.MockedFunction<typeof fetch>;

    const strategy = await createRemoteStrategy({
      name: '원격 전략', universeId: 'kospi200', indicatorIds: ['rsi'], conditionMode: 'ALL', public: false,
    }, { fetcher, baseUrl: 'https://api.test/', accessToken: 'test-access-token' });

    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(fetcher.mock.calls[0]?.[0]).toBe('https://api.test/v1/universe-versions');
    expect(fetcher.mock.calls[1]?.[0]).toBe('https://api.test/v1/strategies');
    expect(fetcher.mock.calls[1]?.[1]).toMatchObject({ method: 'POST' });
    expect(new Headers(fetcher.mock.calls[0]?.[1]?.headers).get('authorization')).toBeNull();
    expect(new Headers(fetcher.mock.calls[1]?.[1]?.headers).get('authorization')).toBe('Bearer test-access-token');
    const body = fetcher.mock.calls[1]?.[1]?.body;
    if (typeof body !== 'string') throw new Error('JSON body expected');
    expect(JSON.parse(body)).toMatchObject({ universeVersionId: 'real-universe-uuid' });
    expect(strategy).toMatchObject({ id: 'sv-new', remoteStrategyId: 's-new', indicatorIds: ['rsi'] });
  });

  it('sends complete alert settings contract', async () => {
    const fetcher = jest.fn(() => Promise.resolve({ ok: true, json: () => Promise.resolve({ data: {} }) } as Response)) as unknown as jest.MockedFunction<typeof fetch>;
    await updateRemoteAlertSettings({
      enabled: true, quietHoursEnabled: true, quietStart: '22:00', quietEnd: '07:00', showPriceOnLockScreen: false,
    }, { fetcher, baseUrl: 'https://api.test', accessToken: 'test-access-token' });
    const init = fetcher.mock.calls[0]?.[1];
    expect(fetcher.mock.calls[0]?.[0]).toBe('https://api.test/v1/alert-settings');
    if (typeof init?.body !== 'string') throw new Error('JSON body expected');
    expect(JSON.parse(init.body)).toEqual({ enabled: true, quietHoursEnabled: true, quietStart: '22:00', quietEnd: '07:00', showPriceOnLockScreen: false });
  });

  it('uses the current session token for API authorization', async () => {
    const fetcher = jest.fn(() => Promise.resolve({ ok: true, json: () => Promise.resolve({ data: {} }) } as Response)) as unknown as jest.MockedFunction<typeof fetch>;
    await updateRemoteAlertSettings({
      enabled: true, quietHoursEnabled: false, quietStart: '22:00', quietEnd: '07:00', showPriceOnLockScreen: false,
    }, { fetcher, baseUrl: 'https://api.test', accessToken: 'supabase-access-token' });

    const headers = new Headers(fetcher.mock.calls[0]?.[1]?.headers);
    expect(headers.get('authorization')).toBe('Bearer supabase-access-token');
  });

  it('requests and validates remote signal advice', async () => {
    const fetcher = jest.fn(() => Promise.resolve({ ok: true, json: () => Promise.resolve({ data: {
      signalId: 'sig-1', summary: '조건 충족 설명', evidence: ['RSI'], risks: ['변동성'],
      questionsToConsider: ['손실 범위?'], disclaimer: '교육 목적', source: 'GEMINI', model: 'test-model',
      basedOn: '2026-08-21T06:30:00.000Z', generatedAt: '2026-08-21T06:31:00.000Z',
    } }) } as Response)) as unknown as jest.MockedFunction<typeof fetch>;
    const advice = await requestRemoteSignalAdvice('sig-1', { fetcher, baseUrl: 'https://api.test', accessToken: 'test-access-token' });
    expect(fetcher.mock.calls[0]?.[0]).toBe('https://api.test/v1/signals/sig-1/advice');
    expect(fetcher.mock.calls[0]?.[1]).toMatchObject({ method: 'POST' });
    expect(advice).toMatchObject({ signalId: 'sig-1', source: 'GEMINI', model: 'test-model' });
  });
});
