import type { AppAlert, BuySignal, CombinationRank, Indicator, Position, Strategy, Universe, UserRank } from '@/domain/types';

import { appBrand } from '@signal/config';

export const APP_NAME = appBrand.name;

export const universes: Universe[] = [
  { id: 'demoTop50', name: '데모 TOP 50', count: 50, version: '10 demo + 40 합성', description: '키움 demo 10종목과 재현 가능한 합성 40종목' },
  { id: 'kospi200', name: 'KOSPI 200', count: 200, version: '2026.08', description: '대형주 중심 대표 지수 구성 종목' },
  { id: 'kosdaq150', name: 'KOSDAQ 150', count: 150, version: '2026.08', description: '코스닥 대표 성장주 구성 종목' },
  { id: 'kospi', name: 'KOSPI 전체', count: 834, version: 'v12', description: '보통주 기준 유가증권시장 전체' },
  { id: 'kosdaq', name: 'KOSDAQ 전체', count: 1712, version: 'v12', description: '보통주 기준 코스닥시장 전체' },
  { id: 'all', name: 'KOSPI · KOSDAQ', count: 2546, version: 'v12', description: '두 시장 보통주 통합 범위' },
  { id: 'custom', name: '내 종목 목록', count: 4, version: '내 목록 v3', description: '관심 종목에서 직접 고른 범위' },
];

export const indicators: Indicator[] = [
  { id: 'sma', name: '단순 이동평균', short: '일정 기간 종가 평균으로 추세를 봅니다.', defaultRule: '20일선이 60일선을 상향 돌파', minimumCandles: 60, formula: '최근 N개 종가의 산술평균', caution: '횡보장에서는 거짓 신호가 잦습니다. 단독 사용을 피하세요.', tier: 'A' },
  { id: 'ema', name: '지수 이동평균', short: '최근 가격에 더 큰 비중을 둔 추세선입니다.', defaultRule: '12일선이 26일선을 상향 돌파', minimumCandles: 26, formula: '가격 × 가중치 + 전일 EMA × (1-가중치)', caution: '빠른 반응만큼 잡음에도 민감합니다.', tier: 'B' },
  { id: 'rsi', name: 'RSI', short: '상승과 하락 힘의 균형을 0~100으로 봅니다.', defaultRule: 'RSI(14)가 30을 상향 돌파', minimumCandles: 15, formula: '100 - 100 / (1 + 평균 상승폭 / 평균 하락폭)', caution: '과매도는 곧 반등을 뜻하지 않습니다.', tier: 'S' },
  { id: 'macd', name: 'MACD', short: '두 지수이동평균 차이로 추세 변화를 봅니다.', defaultRule: 'MACD가 시그널선을 상향 돌파', minimumCandles: 35, formula: 'EMA(12) - EMA(26), Signal EMA(9)', caution: '느린 지표라 급한 반전은 늦게 잡을 수 있습니다.', tier: 'A' },
  { id: 'bollinger', name: '볼린저 밴드', short: '평균과 변동성 범위로 가격 위치를 봅니다.', defaultRule: '종가가 하단 밴드를 상향 돌파', minimumCandles: 20, formula: 'SMA(20) ± 표준편차 × 2', caution: '밴드 접촉 자체는 반전 신호가 아닙니다.', tier: 'A' },
  { id: 'volume', name: '거래량 급증', short: '평균보다 커진 거래량으로 관심 증가를 봅니다.', defaultRule: '거래량이 20일 평균의 2배 초과', minimumCandles: 20, formula: '당일 거래량 / N일 평균 거래량', caution: '뉴스성 일회 거래는 지속되지 않을 수 있습니다.', tier: 'B' },
  { id: 'stochastic', name: '스토캐스틱', short: '최근 고저 범위 속 현재 종가 위치를 봅니다.', defaultRule: '%K가 %D를 20 아래서 상향 돌파', minimumCandles: 17, formula: '(종가-최저가)/(최고가-최저가) × 100', caution: '강한 추세에서는 극단 구간이 오래 유지됩니다.', tier: 'B' },
  { id: 'atr', name: 'ATR', short: '갭을 포함한 가격 변동 폭을 봅니다.', defaultRule: 'ATR(14)이 20일 평균을 상향 돌파', minimumCandles: 34, formula: 'True Range의 N일 평균', caution: '방향이 아닌 변동성만 나타냅니다.', tier: 'C' },
  { id: 'adx', name: 'ADX', short: '상승·하락 방향과 무관한 추세 강도입니다.', defaultRule: 'ADX(14)가 25를 상향 돌파', minimumCandles: 28, formula: '방향성 지수 차이를 평활화', caution: '방향 판단에는 +DI/-DI 같은 보조값이 필요합니다.', tier: 'A' },
  { id: 'obv', name: 'OBV', short: '등락에 따라 거래량을 누적해 수급 흐름을 봅니다.', defaultRule: 'OBV가 20일 최고치를 돌파', minimumCandles: 21, formula: '상승일 거래량 더하기, 하락일 빼기', caution: '대량 거래 한 번이 오래 영향을 줄 수 있습니다.', tier: '데이터 부족' },
];

export const defaultStrategies: Strategy[] = [
  { id: 'strategy-core', name: '추세 · 모멘텀 확인', version: 2, universeId: 'kospi200', indicatorIds: ['sma', 'rsi', 'volume'], conditionMode: 'ALL', alertEnabled: true, cooldownHours: 24, public: false, locked: false, createdAt: '2026-07-03T09:00:00+09:00' },
  { id: 'strategy-ranked', name: '공식 랭킹 트랙', version: 1, universeId: 'all', indicatorIds: ['macd', 'adx'], conditionMode: 'ALL', alertEnabled: true, cooldownHours: 24, public: true, locked: true, createdAt: '2026-05-02T09:00:00+09:00' },
];

export const defaultSignals: BuySignal[] = [
  { id: 'sig-005930', symbol: '005930', instrumentName: '삼성전자', strategyId: 'strategy-core', closePrice: 78200, changeRate: 2.09, candleClose: '2026-08-14', createdAt: '2026-08-14T18:06:00+09:00', reasons: ['20일선이 60일선을 상향 돌파', 'RSI(14)가 30을 상향 돌파', '거래량이 20일 평균의 2.1배'], values: [{ label: 'RSI(14)', value: '34.8' }, { label: '거래량 배수', value: '2.1×' }, { label: '20일선', value: '76,420원' }], delayed: false, read: false },
  { id: 'sig-035420', symbol: '035420', instrumentName: 'NAVER', strategyId: 'strategy-core', closePrice: 228500, changeRate: -0.43, candleClose: '2026-08-14', createdAt: '2026-08-14T18:10:00+09:00', reasons: ['RSI(14)가 30을 상향 돌파', '종가가 20일선을 회복'], values: [{ label: 'RSI(14)', value: '32.1' }, { label: '20일선', value: '227,100원' }], delayed: true, read: false },
  { id: 'sig-000660', symbol: '000660', instrumentName: 'SK하이닉스', strategyId: 'strategy-ranked', closePrice: 194200, changeRate: 3.3, candleClose: '2026-08-13', createdAt: '2026-08-13T18:04:00+09:00', reasons: ['MACD가 시그널선을 상향 돌파', 'ADX(14)가 25를 상향 돌파'], values: [{ label: 'MACD', value: '+1,440' }, { label: 'ADX(14)', value: '27.4' }], delayed: false, read: true },
];

export const defaultPositions: Position[] = [
  {
    id: 'pos-068270', kind: 'MANUAL_LIVE', symbol: '068270', instrumentName: '셀트리온', quantity: 12, averagePrice: 181500, currentPrice: 187800, status: 'OPEN', firstBoughtAt: '2026-08-05', highestClose: 190200, signalId: null, strategyVersion: null,
    executions: [{ id: 'exe-1', side: 'BUY', price: 181500, quantity: 12, fee: 0, tax: 0, executedAt: '2026-08-05T10:20:00+09:00', memo: '앱 밖 실제 매수 등록' }],
    sellRule: { version: 1, manualOnly: false, stopLossPercent: 7, takeProfitPercent: 14, trailingStopPercent: null, maxHoldingDays: 40, technicalIds: [], technicalMode: 'ANY' },
  },
  {
    id: 'pos-051910', kind: 'SANDBOX_PAPER', symbol: '051910', instrumentName: 'LG화학', quantity: 5, averagePrice: 318000, currentPrice: 302500, status: 'PARTIALLY_CLOSED', firstBoughtAt: '2026-07-21', highestClose: 329000, signalId: 'sig-old', strategyVersion: 1,
    executions: [{ id: 'exe-2', side: 'BUY', price: 318000, quantity: 8, fee: 508, tax: 0, executedAt: '2026-07-21T09:00:00+09:00', memo: 'D+1 공식 시가 체결' }, { id: 'exe-3', side: 'SELL', price: 310000, quantity: 3, fee: 148, tax: 1860, executedAt: '2026-08-08T09:00:00+09:00', memo: '부분매도' }],
    sellRule: { version: 2, manualOnly: false, stopLossPercent: 8, takeProfitPercent: 15, trailingStopPercent: 10, maxHoldingDays: 60, technicalIds: ['rsi'], technicalMode: 'ANY' },
  },
];

export const defaultAlerts: AppAlert[] = [
  { id: 'alert-1', kind: 'BUY_SIGNAL', title: '삼성전자 조건 충족', body: '3개 지표가 8월 14일 완성 일봉에서 충족됐어요.', createdAt: '2026-08-14T18:06:00+09:00', read: false, signalId: 'sig-005930', positionId: null, delayed: false },
  { id: 'alert-2', kind: 'BUY_SIGNAL', title: 'NAVER 조건 충족 · 데이터 지연', body: '일부 데이터 확인이 늦어졌어요. 상세 근거를 확인하세요.', createdAt: '2026-08-14T18:10:00+09:00', read: false, signalId: 'sig-035420', positionId: null, delayed: true },
  { id: 'alert-3', kind: 'SELL_SIGNAL', title: 'LG화학 매도 조건 상태', body: '손절 기준에 가까워졌어요. 자동 주문이 아닙니다.', createdAt: '2026-08-13T18:10:00+09:00', read: true, signalId: null, positionId: 'pos-051910', delayed: false },
];

export const combinations: CombinationRank[] = [
  { id: 'combo-1', rank: 1, name: 'RSI 반전 + 거래량', indicatorIds: ['rsi', 'volume'], universeId: 'kospi200', excessReturn: { '3m': 8.4, '6m': 13.7, '1y': 21.2 }, hitRate: 63.1, mdd: -8.2, signalCount: 94, instrumentCount: 61, stability: 88, confidence: '95% CI +5.1~+11.4%' },
  { id: 'combo-2', rank: 2, name: 'MACD + ADX 추세', indicatorIds: ['macd', 'adx'], universeId: 'all', excessReturn: { '3m': 7.1, '6m': 11.6, '1y': 18.9 }, hitRate: 59.4, mdd: -9.8, signalCount: 162, instrumentCount: 118, stability: 82, confidence: '95% CI +3.8~+10.2%' },
  { id: 'combo-3', rank: 3, name: '밴드 회귀 + OBV', indicatorIds: ['bollinger', 'obv'], universeId: 'kosdaq150', excessReturn: { '3m': 4.2, '6m': 8.5, '1y': 12.4 }, hitRate: 55.2, mdd: -12.1, signalCount: 24, instrumentCount: 19, stability: 61, confidence: '데이터 부족 · 최소 30건 필요' },
];

export const userRanks: UserRank[] = [
  { id: 'user-1', rank: 1, nickname: '차분한거북이', returnRate: { '3m': 18.4, '6m': 27.1, '1y': 39.2, all: 44.8 }, universeId: 'all', mdd: -7.2, trades: 38, days: 412, strategyName: '추세가속 v4', public: true },
  { id: 'user-2', rank: 2, nickname: '긴호흡', returnRate: { '3m': 16.9, '6m': 22.4, '1y': 31.5, all: 36.7 }, universeId: 'kospi200', mdd: -6.4, trades: 29, days: 365, strategyName: '대형주 RSI v2', public: true },
  { id: 'me', rank: 17, nickname: '신호연습생', returnRate: { '3m': 6.3, '6m': 9.8, '1y': 0, all: 9.8 }, universeId: 'all', mdd: -5.6, trades: 14, days: 188, strategyName: '공식 랭킹 트랙', public: false },
  { id: 'user-4', rank: 18, nickname: '분산투자', returnRate: { '3m': 5.8, '6m': 9.1, '1y': 12.2, all: 15.4 }, universeId: 'kosdaq150', mdd: -8.3, trades: 31, days: 504, strategyName: '변동성 돌파 v3', public: true },
];

export const watchlist = [
  { symbol: '005930', name: '삼성전자', price: 78200, change: 2.09 },
  { symbol: '000660', name: 'SK하이닉스', price: 194200, change: 3.3 },
  { symbol: '035420', name: 'NAVER', price: 228500, change: -0.43 },
  { symbol: '068270', name: '셀트리온', price: 187800, change: 1.18 },
];

export async function mockDelay<T>(value: T, ms = 380): Promise<T> {
  await new Promise<void>((resolve) => setTimeout(() => resolve(), ms));
  return value;
}
