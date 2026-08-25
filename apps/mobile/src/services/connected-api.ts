import type { RemoteSnapshot } from '@/domain/remote';
import type { AppAlert, IndicatorId, PortfolioKind, Position, PublicStrategySummary, SellRule, SignalAdvice, Strategy, UniverseId, UserRank } from '@/domain/types';
import { getApiAccessToken, refreshApiAccessToken, supabaseConfigured } from '@/services/supabase';

type JsonRecord = Record<string, unknown>;

function record(value: unknown): JsonRecord | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? value as JsonRecord : null;
}

function array(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function text(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : fallback;
}

function number(value: unknown, fallback = 0): number {
  const parsed = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

const publicApiUrl = process.env.EXPO_PUBLIC_API_URL;
const configuredUrl = typeof publicApiUrl === 'string' && publicApiUrl.trim()
  ? publicApiUrl.trim().replace(/\/$/, '')
  : undefined;

export const connectedApiEnabled = configuredUrl !== undefined;

export interface RemoteRequestOptions {
  fetcher?: typeof fetch;
  baseUrl?: string;
  accessToken?: string | null;
  timeoutMs?: number;
  auth?: 'required' | 'none';
}

export interface RemoteStrategyInput {
  name: string;
  universeId: UniverseId;
  indicatorIds: IndicatorId[];
  conditionMode: 'ALL' | 'ANY';
  public: boolean;
}

export interface RemoteUniverseVersion {
  id: string;
  kind: string;
  name: string;
  memberCount: number;
  effectiveFrom: string;
}

export interface RemoteInstrument {
  symbol: string;
  name: string;
  market: string;
  tradeSuspended: boolean;
}

export interface RemoteProviderHealth {
  provider: string;
  state: 'CONNECTED' | 'DEGRADED' | 'DISCONNECTED';
  lastCandleAt: string | null;
  delayed: boolean;
  lastSession: string | null;
  expectedSession: string | null;
  nextEvaluationAt: string | null;
  activeInstrumentCount: number;
  coveredInstrumentCount: number;
}

export interface RemoteRankings {
  period: '3M' | '6M' | '1Y';
  asOf: string | null;
  periodStart: string;
  minimumTrades: number;
  users: UserRank[];
  disclosure: string;
}

export interface RemoteWorkerRequest {
  requestId: string;
  status: string;
  alreadyQueued: boolean;
}

async function request(path: string, init?: RequestInit, options: RemoteRequestOptions = {}): Promise<unknown> {
  const baseUrl = options.baseUrl ?? configuredUrl;
  if (!baseUrl) throw new Error('API URL이 설정되지 않았습니다.');
  const fetcher = options.fetcher ?? fetch;
  const authMode = options.auth ?? 'required';
  const explicitAccessToken = Object.prototype.hasOwnProperty.call(options, 'accessToken');
  let accessToken = authMode === 'none' ? null : explicitAccessToken
    ? options.accessToken ?? null
    : await getApiAccessToken();
  if (authMode === 'required' && !accessToken) throw new Error('로그인이 필요합니다.');
  const headers = new Headers(init?.headers);
  headers.set('content-type', 'application/json');
  if (accessToken) headers.set('authorization', `Bearer ${accessToken}`);
  else headers.delete('authorization');
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), options.timeoutMs ?? 10_000);
  try {
    const send = () => fetcher(`${baseUrl.replace(/\/$/, '')}${path}`, {
      ...init,
      signal: controller.signal,
      headers,
    });
    let response = await send();
    if (response.status === 401 && authMode === 'required' && supabaseConfigured && !explicitAccessToken) {
      accessToken = await refreshApiAccessToken();
      if (accessToken) headers.set('authorization', `Bearer ${accessToken}`);
      response = await send();
    }
    const body: unknown = await response.json();
    if (!response.ok) {
      const message = text(record(body)?.['message'], 'API 요청에 실패했습니다.');
      throw new Error(message);
    }
    const envelope = record(body);
    if (!envelope || !('data' in envelope)) throw new Error('API 응답 형식이 올바르지 않습니다.');
    return envelope['data'];
  } catch (caught) {
    if (caught instanceof Error && (caught.name === 'AbortError' || caught.message.toLowerCase().includes('aborted'))) {
      throw new Error('AI 응답 시간이 길어 요청이 종료되었습니다. 잠시 후 다시 시도해 주세요.', { cause: caught });
    }
    throw caught;
  } finally {
    clearTimeout(timeout);
  }
}

function indicatorId(value: unknown): IndicatorId | null {
  const mapped: Record<string, IndicatorId> = {
    SMA: 'sma', EMA: 'ema', RSI: 'rsi', MACD: 'macd', BOLLINGER: 'bollinger',
    VOLUME_SPIKE: 'volume', STOCHASTIC: 'stochastic', ATR: 'atr', ADX: 'adx', OBV: 'obv',
  };
  return mapped[text(value)] ?? null;
}

function universeId(value: unknown): UniverseId {
  const key = text(value).toLowerCase();
  const normalized = key.replace(/[^a-z0-9]/g, '');
  if (normalized.includes('demotop30')) return 'demoTop30';
  if (normalized.includes('kospitop10')) return 'kospiTop10';
  if (normalized.includes('kospi200')) return 'kospi200';
  if (normalized.includes('kosdaq150')) return 'kosdaq150';
  if (normalized.includes('kosdaq')) return 'kosdaq';
  if (normalized.includes('kospi')) return 'kospi';
  return 'all';
}

function portfolioKind(value: unknown): PortfolioKind | null {
  return value === 'MANUAL_LIVE' ? value : null;
}

function ruleIndicatorIds(value: unknown): IndicatorId[] {
  const rule = record(value);
  if (!rule) return [];
  const ids = [indicatorId(rule['indicatorId'])];
  for (const key of ['left', 'right']) {
    const operand = record(rule[key]);
    if (operand?.['kind'] === 'INDICATOR') ids.push(indicatorId(operand['indicatorId']));
  }
  return ids.filter((id): id is IndicatorId => id !== null);
}

function strategyFrom(value: unknown): Strategy | null {
  const item = record(value);
  if (!item) return null;
  const id = text(item['id']);
  if (!id) return null;
  const ids = array(item['rules']).flatMap(ruleIndicatorIds);
  const remoteStrategyId = text(item['strategyId']);
  return {
    id, name: text(item['name'], '이름 없는 전략'), version: number(item['version'], 1),
    ...(remoteStrategyId ? { remoteStrategyId } : {}),
    universeId: universeId(item['universeKind'] ?? item['universeVersionId']), indicatorIds: [...new Set(ids)],
    conditionMode: item['logic'] === 'OR' ? 'ANY' : 'ALL', alertEnabled: item['alertsEnabled'] !== false,
    cooldownHours: 24, public: item['isPublic'] === true, locked: item['locked'] === true,
    createdAt: text(item['createdAt'], new Date(0).toISOString()),
  };
}

export function mapRemoteSnapshot(input: { strategies: unknown; signals: unknown; portfolios: unknown; alerts?: unknown; alertSettings?: unknown; profileSettings?: unknown }): RemoteSnapshot {
  const allStrategies = array(input.strategies).map(strategyFrom).filter((item): item is Strategy => item !== null);
  const strategyGroups = new Map<string, Strategy[]>();
  for (const strategy of allStrategies) {
    const key = strategy.remoteStrategyId ?? strategy.id;
    strategyGroups.set(key, [...(strategyGroups.get(key) ?? []), strategy]);
  }
  const strategies: Strategy[] = [];
  const strategyHistory: Strategy[] = [];
  for (const versions of strategyGroups.values()) {
    const sorted = [...versions].sort((a, b) => b.version - a.version || b.createdAt.localeCompare(a.createdAt));
    if (sorted[0]) strategies.push(sorted[0]);
    strategyHistory.push(...sorted.slice(1));
  }
  strategies.sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  strategyHistory.sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  const rawSignals = array(input.signals);
  const signals = rawSignals.flatMap((value) => {
    const item = record(value);
    if (!item || item['type'] !== 'BUY_CONDITION') return [];
    const reasons = array(item['reasons']).map(record).filter((reason): reason is JsonRecord => reason !== null);
    return [{
      id: text(item['id']), symbol: text(item['symbol']), instrumentName: text(item['name']),
      strategyId: text(item['strategyVersionId']), closePrice: number(item['closePrice']), changeRate: 0,
      candleClose: text(item['candleClose']), createdAt: text(item['candleClose']),
      reasons: reasons.map((reason) => `${text(reason['label'])} ${text(reason['value'])}`.trim()),
      values: reasons.map((reason) => ({ label: text(reason['label']), value: text(reason['value']) })),
      delayed: item['stale'] === true, read: item['status'] === 'ACKNOWLEDGED',
    }];
  });
  const sellSignalAlerts = rawSignals.flatMap((value): AppAlert[] => {
    const item = record(value);
    if (!item || item['type'] !== 'SELL_CONDITION' || typeof item['positionId'] !== 'string') return [];
    const reasons = array(item['reasons']).map(record).filter((reason): reason is JsonRecord => reason !== null);
    return [{
      id: text(item['id']), kind: 'SELL_SIGNAL', title: `${text(item['name'], text(item['symbol']))} 매도 조건 상태`,
      body: reasons.map((reason) => `${text(reason['label'])} ${text(reason['value'])}`.trim()).join(' · '),
      createdAt: text(item['candleClose']), read: item['status'] === 'ACKNOWLEDGED', signalId: null,
      positionId: text(item['positionId']), delayed: item['stale'] === true,
    }];
  });
  const positions: Position[] = [];
  const portfolioIds: Partial<Record<PortfolioKind, string>> = {};
  for (const value of array(input.portfolios)) {
    const portfolio = record(value);
    if (!portfolio) continue;
    const kind = portfolioKind(portfolio['kind']);
    const portfolioId = text(portfolio['id']);
    if (!kind) continue;
    portfolioIds[kind] = portfolioId;
    for (const rawPosition of array(portfolio['positions'])) {
      const item = record(rawPosition);
      if (!item) continue;
      const symbol = text(item['symbol']);
      const status = text(item['status']);
      const executions = array(item['executions']).flatMap((rawExecution): Position['executions'] => {
        const execution = record(rawExecution);
        if (!execution || (execution['side'] !== 'BUY' && execution['side'] !== 'SELL')) return [];
        return [{
          id: text(execution['id']), side: execution['side'], price: number(execution['price']),
          quantity: number(execution['quantity']), fee: number(execution['fee']), tax: number(execution['tax']),
          executedAt: text(execution['executedAt']), memo: text(execution['memo']),
        }];
      });
      positions.push({
        id: text(item['id']), kind, symbol, instrumentName: text(item['name'], symbol),
        quantity: number(item['quantity']), averagePrice: number(item['averagePrice']), currentPrice: number(item['currentPrice']),
        marketPriceAvailable: item['marketPriceAvailable'] === true,
        status: ['OPEN', 'EXIT_PENDING', 'PARTIALLY_CLOSED', 'CLOSED', 'ARCHIVED'].includes(status) ? status as Position['status'] : 'OPEN',
        firstBoughtAt: text(item['openedAt']).slice(0, 10), highestClose: number(item['highestClose']),
        realizedProfit: number(item['realizedPnl']),
        signalId: typeof item['linkedSignalId'] === 'string' ? item['linkedSignalId'] : null,
        strategyVersion: null, executions, sellRule: null,
      });
    }
  }
  const alertSettings = record(input.alertSettings);
  const profileSettings = record(input.profileSettings);
  const remoteAlerts = input.alerts === undefined ? undefined : array(input.alerts).flatMap((value): AppAlert[] => {
    const item = record(value);
    if (!item) return [];
    const signalId = typeof item['signalId'] === 'string' ? item['signalId'] : null;
    const linkedSignal = signalId ? signals.find((signal) => signal.id === signalId) : undefined;
    return [{
      id: text(item['id']), kind: signalId ? 'BUY_SIGNAL' : 'SYSTEM', title: text(item['title']), body: text(item['body']),
      createdAt: text(item['createdAt']), read: item['read'] === true, signalId, positionId: null, delayed: linkedSignal?.delayed ?? false,
    }];
  });
  const alerts = remoteAlerts === undefined && sellSignalAlerts.length === 0
    ? undefined
    : [...(remoteAlerts ?? []), ...sellSignalAlerts];
  return {
    strategies, strategyHistory, signals, positions, portfolioIds,
    ...(alerts ? { alerts } : {}),
    ...(alertSettings ? {
      notificationsEnabled: alertSettings['enabled'] === true,
      quietHoursEnabled: alertSettings['quietHoursEnabled'] === true,
    } : {}),
    ...(profileSettings ? {
      nickname: text(profileSettings['nickname']),
      profilePublic: profileSettings['isPublic'] === true,
      ...(typeof profileSettings['selectedUniverseKind'] === 'string'
        ? { selectedUniverseId: universeId(profileSettings['selectedUniverseKind']) }
        : {}),
    } : {}),
  };
}

export async function loadRemoteSnapshot(fetcher: typeof fetch = fetch): Promise<RemoteSnapshot> {
  const options = { fetcher };
  const [strategies, signals, portfolios, alerts, alertSettings, profileSettings] = await Promise.all([
    request('/v1/strategies', undefined, options), request('/v1/signals', undefined, options),
    request('/v1/portfolios', undefined, options),
    request('/v1/alerts', undefined, options),
    request('/v1/alert-settings', undefined, options),
    request('/v1/me/settings', undefined, options),
  ]);
  return mapRemoteSnapshot({ strategies, signals, portfolios, alerts, alertSettings, profileSettings });
}

export function remoteIdempotencyKey(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

const remoteUniverseKinds: Record<UniverseId, string> = {
  kospi200: 'KOSPI_200', kosdaq150: 'KOSDAQ_150', kospiTop10: 'KOSPI_TOP_10', demoTop30: 'DEMO_TOP_30', kospi: 'KOSPI_ALL',
  kosdaq: 'KOSDAQ_ALL', all: 'KR_ALL', custom: 'CUSTOM',
};

export interface TestTop30Fixture {
  symbol: string;
  name: string;
  indicatorIds: string[];
}

export interface TestTop30Status {
  ready: boolean;
  top10: Array<{ symbol: string; name: string }>;
  fixtures: TestTop30Fixture[];
  universe: { id?: string; version?: number; memberCount?: number };
}

function mapTestTop30(value: unknown): TestTop30Status {
  const item = record(value) ?? {};
  const top10 = array(item['top10']).map((value) => record(value) ?? {});
  const fixtures = array(item['fixtures']).map((value) => record(value) ?? {});
  const universe = record(item['universe']) ?? {};
  return {
    ready: Boolean(item['ready']),
    top10: top10.map((row) => ({ symbol: text(row['symbol']), name: text(row['name']) })),
    fixtures: fixtures.map((row) => ({
      symbol: text(row['symbol']), name: text(row['name']), indicatorIds: array(row['indicatorIds']).map((value) => text(value)).filter(Boolean),
    })),
    universe: {
      ...(text(universe['id']) ? { id: text(universe['id']) } : {}),
      ...(number(universe['version']) ? { version: number(universe['version']) } : {}),
      ...(number(universe['memberCount']) ? { memberCount: number(universe['memberCount']) } : {}),
    },
  };
}

export async function loadTestTop30(options?: RemoteRequestOptions): Promise<TestTop30Status> {
  return mapTestTop30(await request('/v1/me/test-top30', undefined, options));
}

export async function configureTestTop30(
  entries: Array<{ name: string; indicatorIds: IndicatorId[] }>, options?: RemoteRequestOptions,
): Promise<TestTop30Status> {
  if (entries.length !== 20) throw new Error('합성 테스트 종목은 정확히 20개여야 합니다.');
  const fixtures = entries.map((entry, index) => ({
    slot: index + 1, name: entry.name, indicatorIds: entry.indicatorIds.map((id) => remoteIndicatorIds[id]),
  }));
  return mapTestTop30(await request('/v1/me/test-top30', { method: 'PUT', body: JSON.stringify({ fixtures }) }, options));
}

const remoteIndicatorIds: Record<IndicatorId, string> = {
  sma: 'SMA', ema: 'EMA', rsi: 'RSI', macd: 'MACD', bollinger: 'BOLLINGER',
  volume: 'VOLUME_SPIKE', stochastic: 'STOCHASTIC', atr: 'ATR', adx: 'ADX', obv: 'OBV',
};

const remoteRuleLabels: Record<IndicatorId, string> = {
  sma: '종가가 SMA(20)를 상향 돌파',
  ema: '종가가 EMA(20)를 상향 돌파',
  rsi: 'RSI(14)가 30을 상향 돌파',
  macd: 'MACD가 시그널선을 상향 돌파',
  bollinger: '종가가 볼린저 하단 밴드를 상향 돌파',
  volume: '거래량이 20일 평균의 2배 초과',
  stochastic: '%K가 %D를 상향 돌파',
  atr: 'ATR(14)가 ATR(20)을 상향 돌파',
  adx: 'ADX(14)가 25를 상향 돌파',
  obv: 'OBV가 0선을 상향 돌파 · rolling-high operand 미지원',
};

export function remoteStrategyRuleLabel(id: IndicatorId): string {
  return remoteRuleLabels[id];
}

function remoteIndicatorRule(id: IndicatorId): JsonRecord {
  const indicator = (indicatorId: IndicatorId, outputKey: string, params: JsonRecord = {}) => ({
    kind: 'INDICATOR', indicatorId: remoteIndicatorIds[indicatorId], outputKey, params,
  });
  const value = (amount: number) => ({ kind: 'VALUE', value: amount });
  switch (id) {
    case 'sma': return { left: { kind: 'CLOSE' }, operator: 'CROSSES_ABOVE', right: indicator('sma', 'sma', { period: 20 }) };
    case 'ema': return { left: { kind: 'CLOSE' }, operator: 'CROSSES_ABOVE', right: indicator('ema', 'ema', { period: 20 }) };
    case 'rsi': return { left: indicator('rsi', 'rsi', { period: 14 }), operator: 'CROSSES_ABOVE', right: value(30) };
    case 'macd': return {
      left: indicator('macd', 'macd', { fastPeriod: 12, slowPeriod: 26, signalPeriod: 9 }), operator: 'CROSSES_ABOVE',
      right: indicator('macd', 'signal', { fastPeriod: 12, slowPeriod: 26, signalPeriod: 9 }),
    };
    case 'bollinger': return {
      left: { kind: 'CLOSE' }, operator: 'CROSSES_ABOVE',
      right: indicator('bollinger', 'lower', { period: 20, standardDeviations: 2 }),
    };
    case 'volume': return { left: indicator('volume', 'ratio', { period: 20, threshold: 2 }), operator: 'GT', right: value(2) };
    case 'stochastic': return {
      left: indicator('stochastic', 'k', { kPeriod: 14, smoothKPeriod: 3, dPeriod: 3 }), operator: 'CROSSES_ABOVE',
      right: indicator('stochastic', 'd', { kPeriod: 14, smoothKPeriod: 3, dPeriod: 3 }),
    };
    case 'atr': return {
      left: indicator('atr', 'atr', { period: 14 }), operator: 'CROSSES_ABOVE', right: indicator('atr', 'atr', { period: 20 }),
    };
    case 'adx': return { left: indicator('adx', 'adx', { period: 14 }), operator: 'CROSSES_ABOVE', right: value(25) };
    case 'obv': return { left: indicator('obv', 'obv'), operator: 'CROSSES_ABOVE', right: value(0) };
  }
}

export function buildRemoteStrategyInput(input: RemoteStrategyInput, universeVersionId = remoteUniverseKinds[input.universeId]): JsonRecord {
  if (!input.name.trim()) throw new Error('전략 이름을 입력하세요.');
  if (input.indicatorIds.length < 1 || input.indicatorIds.length > 5) throw new Error('지표는 1~5개만 선택할 수 있습니다.');
  return {
    name: input.name.trim(), universeVersionId,
    logic: input.conditionMode === 'ANY' ? 'OR' : 'AND',
    rules: input.indicatorIds.map(remoteIndicatorRule), alertsEnabled: true, isPublic: input.public,
  };
}

async function resolveRemoteUniverseVersionId(input: RemoteStrategyInput, options?: RemoteRequestOptions): Promise<string> {
  const data = await loadRemoteUniverseVersions(options);
  const expectedKind = remoteUniverseKinds[input.universeId];
  const id = data.find((item) => item.kind === expectedKind)?.id ?? '';
  if (!id) {
    const label = input.universeId === 'custom' ? '내 종목 목록' : expectedKind;
    throw new Error(`${label} 종목군 데이터가 아직 준비되지 않았습니다. 다른 종목 범위를 선택하거나 Worker 데이터 수집을 실행하세요.`);
  }
  return id;
}

export async function loadRemoteUniverseVersions(options?: RemoteRequestOptions): Promise<RemoteUniverseVersion[]> {
  return array(await request('/v1/universe-versions', undefined, { ...options, auth: 'none' })).map(record).flatMap((item) => {
    const id = text(item?.['id']);
    const kind = text(item?.['kind']);
    if (!id || !kind) return [];
    return [{ id, kind, name: text(item?.['name']), memberCount: number(item?.['memberCount']), effectiveFrom: text(item?.['effectiveFrom']) }];
  });
}

export function remoteUniverseKind(id: UniverseId): string {
  return remoteUniverseKinds[id];
}

export async function saveRemoteUniversePreference(id: UniverseId, options?: RemoteRequestOptions): Promise<void> {
  const versions = await loadRemoteUniverseVersions(options);
  const versionId = versions.find((item) => item.kind === remoteUniverseKinds[id])?.id;
  if (!versionId) throw new Error('선택한 종목군의 확정 버전을 찾을 수 없습니다.');
  await request('/v1/me/universe', { method: 'PUT', body: JSON.stringify({ universeVersionId: versionId }) }, options);
}

function instrumentFrom(value: unknown): RemoteInstrument | null {
  const item = record(value);
  const symbol = text(item?.['symbol']);
  if (!item || !symbol) return null;
  return { symbol, name: text(item['nameKo'], symbol), market: text(item['market']), tradeSuspended: item['tradeSuspended'] === true };
}

export async function loadRemoteCatalog(options?: RemoteRequestOptions): Promise<RemoteInstrument[]> {
  return array(await request('/v1/catalog', undefined, { ...options, auth: 'none' })).map(instrumentFrom).filter((item): item is RemoteInstrument => item !== null);
}

export async function loadRemoteWatchlist(options?: RemoteRequestOptions): Promise<RemoteInstrument[]> {
  return array(await request('/v1/watchlist', undefined, options)).map(instrumentFrom).filter((item): item is RemoteInstrument => item !== null);
}

export async function setRemoteWatchlist(symbol: string, enabled: boolean, options?: RemoteRequestOptions): Promise<RemoteInstrument[]> {
  const result = await request(`/v1/watchlist/${encodeURIComponent(symbol)}`, { method: enabled ? 'POST' : 'DELETE' }, options);
  return array(result).map(instrumentFrom).filter((item): item is RemoteInstrument => item !== null);
}

export async function loadRemoteProviderHealth(options?: RemoteRequestOptions): Promise<RemoteProviderHealth> {
  const item = record(await request('/v1/provider/status', undefined, { ...options, auth: 'none' }));
  if (!item) throw new Error('데이터 공급자 상태 응답이 올바르지 않습니다.');
  const state = text(item['state']);
  if (!['CONNECTED', 'DEGRADED', 'DISCONNECTED'].includes(state)) throw new Error('데이터 공급자 상태가 올바르지 않습니다.');
  return {
    provider: text(item['provider']), state: state as RemoteProviderHealth['state'],
    lastCandleAt: typeof item['lastCandleAt'] === 'string' ? item['lastCandleAt'] : null,
    delayed: item['delayed'] === true,
    lastSession: typeof item['lastSession'] === 'string' ? item['lastSession'] : null,
    expectedSession: typeof item['expectedSession'] === 'string' ? item['expectedSession'] : null,
    nextEvaluationAt: typeof item['nextEvaluationAt'] === 'string' ? item['nextEvaluationAt'] : null,
    activeInstrumentCount: number(item['activeInstrumentCount']),
    coveredInstrumentCount: number(item['coveredInstrumentCount']),
  };
}

function workerRequestFrom(value: unknown): RemoteWorkerRequest {
  const item = record(value);
  if (!item || !text(item['requestId'])) throw new Error('데이터 갱신 요청 응답이 올바르지 않습니다.');
  return { requestId: text(item['requestId']), status: text(item['status']), alreadyQueued: item['alreadyQueued'] === true };
}

export async function requestRemoteMarketDataRefresh(options?: RemoteRequestOptions): Promise<RemoteWorkerRequest> {
  return workerRequestFrom(await request('/v1/me/market-data/refresh', { method: 'POST' }, options));
}

export async function createRemoteTestSignal(options?: RemoteRequestOptions): Promise<{ symbol: string; message: string; request: RemoteWorkerRequest }> {
  const item = record(await request('/v1/me/test-fixtures/buy-signal', { method: 'POST' }, options));
  if (!item) throw new Error('테스트 신호 응답이 올바르지 않습니다.');
  return { symbol: text(item['symbol']), message: text(item['message']), request: workerRequestFrom(item['workerRequest']) };
}

export async function loadRemoteRankings(period: '3M' | '6M' | '1Y', options?: RemoteRequestOptions): Promise<RemoteRankings> {
  const item = record(await request(`/v1/rankings?period=${period}`, undefined, { ...options, auth: 'none' }));
  if (!item) throw new Error('랭킹 응답이 올바르지 않습니다.');
  return {
    period,
    asOf: typeof item['asOf'] === 'string' ? item['asOf'] : null,
    periodStart: text(item['periodStart']), minimumTrades: number(item['minimumTrades']),
    users: array(item['users']).flatMap((value): UserRank[] => {
      const user = record(value);
      if (!user || !text(user['id'])) return [];
      const rawReturns = record(user['returnRate']);
      const strategies = array(user['strategies']).flatMap((raw): PublicStrategySummary[] => {
        const strategy = record(raw);
        if (!strategy || !text(strategy['id'])) return [];
        return [{
          id: text(strategy['id']), strategyId: text(strategy['strategyId']), name: text(strategy['name']),
          version: number(strategy['version'], 1), universeId: universeId(strategy['universeKind']),
          indicatorIds: array(strategy['indicatorIds']).map(indicatorId).filter((id): id is IndicatorId => id !== null),
          conditionMode: strategy['conditionMode'] === 'ANY' ? 'ANY' : 'ALL',
        }];
      });
      return [{
        id: text(user['id']), rank: number(user['rank']), nickname: text(user['nickname']),
        returnRate: {
          '3m': number(rawReturns?.['3m']), '6m': number(rawReturns?.['6m']),
          '1y': number(rawReturns?.['1y']), all: number(rawReturns?.['all']),
        },
        universeId: universeId(user['universeId']), mdd: number(user['mdd']), trades: number(user['trades']),
        days: number(user['days']), strategyName: text(user['strategyName']), public: user['public'] === true,
        strategies,
      }];
    }),
    disclosure: text(item['disclosure']),
  };
}

function requiredStrategy(value: unknown): Strategy {
  const strategy = strategyFrom(value);
  if (!strategy) throw new Error('전략 API 응답이 올바르지 않습니다.');
  return strategy;
}

export async function createRemoteStrategy(input: RemoteStrategyInput, options?: RemoteRequestOptions): Promise<Strategy> {
  const universeVersionId = await resolveRemoteUniverseVersionId(input, options);
  const data = await request('/v1/strategies', { method: 'POST', body: JSON.stringify(buildRemoteStrategyInput(input, universeVersionId)) }, options);
  return requiredStrategy(data);
}

export async function reviseRemoteStrategy(strategyId: string, input: RemoteStrategyInput, options?: RemoteRequestOptions): Promise<Strategy> {
  const universeVersionId = await resolveRemoteUniverseVersionId(input, options);
  const data = await request(`/v1/strategies/${encodeURIComponent(strategyId)}/versions`, { method: 'POST', body: JSON.stringify(buildRemoteStrategyInput(input, universeVersionId)) }, options);
  return requiredStrategy(data);
}

export async function acknowledgeRemoteSignal(signalId: string, options?: RemoteRequestOptions): Promise<void> {
  await request(`/v1/signals/${encodeURIComponent(signalId)}/acknowledge`, { method: 'PATCH' }, options);
}

function stringArray(value: unknown): string[] {
  return array(value).filter((item): item is string => typeof item === 'string' && item.trim().length > 0);
}

function adviceFrom(value: unknown): SignalAdvice {
  const item = record(value);
  if (!item || !text(item['signalId']) || !text(item['summary'])) throw new Error('AI 설명 응답이 올바르지 않습니다.');
  return {
    signalId: text(item['signalId']), summary: text(item['summary']), evidence: stringArray(item['evidence']),
    risks: stringArray(item['risks']), questionsToConsider: stringArray(item['questionsToConsider']),
    disclaimer: text(item['disclaimer']), source: item['source'] === 'GEMINI' ? 'GEMINI' : 'LOCAL',
    model: text(item['model']), basedOn: text(item['basedOn']), generatedAt: text(item['generatedAt']),
  };
}

export async function requestRemoteSignalAdvice(signalId: string, options?: RemoteRequestOptions): Promise<SignalAdvice> {
  const data = await request(`/v1/signals/${encodeURIComponent(signalId)}/advice`, { method: 'POST' }, { ...options, timeoutMs: options?.timeoutMs ?? 35_000 });
  return adviceFrom(data);
}

export async function updateRemoteProfileVisibility(input: {
  isPublic: boolean; nickname: string; discloseOpenPositions: boolean;
}, options?: RemoteRequestOptions): Promise<void> {
  await request('/v1/me/visibility', { method: 'PUT', body: JSON.stringify(input) }, options);
}

export async function updateRemoteAlertSettings(input: {
  enabled: boolean; quietHoursEnabled: boolean; quietStart: string; quietEnd: string; showPriceOnLockScreen: boolean;
}, options?: RemoteRequestOptions): Promise<void> {
  await request('/v1/alert-settings', { method: 'PUT', body: JSON.stringify(input) }, options);
}

export function buildRemoteSellRuleInput(rule: SellRule): JsonRecord {
  return {
    ...(rule.manualOnly ? {} : {
      ...(rule.stopLossPercent === null ? {} : { stopLossPct: rule.stopLossPercent }),
      ...(rule.takeProfitPercent === null ? {} : { takeProfitPct: rule.takeProfitPercent }),
      ...(rule.trailingStopPercent === null ? {} : { trailingStopPct: rule.trailingStopPercent }),
      ...(rule.maxHoldingDays === null ? {} : { maxHoldingSessions: rule.maxHoldingDays }),
    }),
    technicalLogic: rule.technicalMode,
    technicalRules: rule.manualOnly ? [] : rule.technicalIds.map(remoteIndicatorRule),
    manualOnly: rule.manualOnly,
  };
}

export async function saveRemoteSellRule(positionId: string, rule: SellRule, options?: RemoteRequestOptions): Promise<void> {
  await request(`/v1/positions/${encodeURIComponent(positionId)}/sell-rules`, {
    method: 'POST', body: JSON.stringify(buildRemoteSellRuleInput(rule)),
  }, options);
}

export async function deleteRemoteAccount(options?: RemoteRequestOptions): Promise<void> {
  await request('/v1/me', { method: 'DELETE' }, options);
}

export async function deleteRemoteStrategy(strategyId: string, options?: RemoteRequestOptions): Promise<void> {
  await request(`/v1/strategies/${encodeURIComponent(strategyId)}`, { method: 'DELETE' }, options);
}

export async function submitRemoteManualExecution(input: {
  portfolioId: string; positionId: string | null; symbol: string; side: 'BUY' | 'SELL'; price: number; quantity: number;
  executedAt: string; signalId: string | null; memo: string; idempotencyKey: string;
}): Promise<void> {
  const { portfolioId, ...body } = input;
  await request(`/v1/portfolios/${encodeURIComponent(portfolioId)}/executions`, { method: 'POST', body: JSON.stringify({ ...body, price: String(input.price) }) });
}

export async function copyRemotePublicStrategy(publicProfileId: string, strategyVersionId: string, options?: RemoteRequestOptions): Promise<Strategy> {
  const data = await request(`/v1/profiles/${encodeURIComponent(publicProfileId)}/strategies/${encodeURIComponent(strategyVersionId)}/copy`, { method: 'POST' }, options);
  return requiredStrategy(data);
}
