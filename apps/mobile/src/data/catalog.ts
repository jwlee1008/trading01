import { appBrand } from '@signal/config';
import type { Indicator, UniverseId } from '@/domain/types';

export const APP_NAME = appBrand.name;

export const indicators: Indicator[] = [
  { id: 'sma', name: '단순 이동평균', short: '일정 기간 종가 평균으로 추세를 봅니다.', defaultRule: '종가가 SMA(20)를 상향 돌파', minimumCandles: 20, formula: '최근 N개 종가의 산술평균', caution: '횡보장에서는 거짓 신호가 잦습니다. 단독 사용을 피하세요.', tier: '데이터 부족' },
  { id: 'ema', name: '지수 이동평균', short: '최근 가격에 더 큰 비중을 둔 추세선입니다.', defaultRule: '종가가 EMA(20)를 상향 돌파', minimumCandles: 20, formula: '가격 × 가중치 + 전일 EMA × (1-가중치)', caution: '빠른 반응만큼 잡음에도 민감합니다.', tier: '데이터 부족' },
  { id: 'rsi', name: 'RSI', short: '상승과 하락 힘의 균형을 0~100으로 봅니다.', defaultRule: 'RSI(14)가 30을 상향 돌파', minimumCandles: 15, formula: '100 - 100 / (1 + 평균 상승폭 / 평균 하락폭)', caution: '과매도는 곧 반등을 뜻하지 않습니다.', tier: '데이터 부족' },
  { id: 'macd', name: 'MACD', short: '두 지수이동평균 차이로 추세 변화를 봅니다.', defaultRule: 'MACD가 시그널선을 상향 돌파', minimumCandles: 35, formula: 'EMA(12) - EMA(26), Signal EMA(9)', caution: '느린 지표라 급한 반전은 늦게 잡을 수 있습니다.', tier: '데이터 부족' },
  { id: 'bollinger', name: '볼린저 밴드', short: '평균과 변동성 범위로 가격 위치를 봅니다.', defaultRule: '종가가 하단 밴드를 상향 돌파', minimumCandles: 20, formula: 'SMA(20) ± 표준편차 × 2', caution: '밴드 접촉 자체는 반전 신호가 아닙니다.', tier: '데이터 부족' },
  { id: 'volume', name: '거래량 급증', short: '평균보다 커진 거래량으로 관심 증가를 봅니다.', defaultRule: '거래량이 20일 평균의 2배 초과', minimumCandles: 20, formula: '당일 거래량 / N일 평균 거래량', caution: '뉴스성 일회 거래는 지속되지 않을 수 있습니다.', tier: '데이터 부족' },
  { id: 'stochastic', name: '스토캐스틱', short: '최근 고저 범위 속 현재 종가 위치를 봅니다.', defaultRule: '%K가 %D를 상향 돌파', minimumCandles: 17, formula: '(종가-최저가)/(최고가-최저가) × 100', caution: '강한 추세에서는 극단 구간이 오래 유지됩니다.', tier: '데이터 부족' },
  { id: 'atr', name: 'ATR', short: '갭을 포함한 가격 변동 폭을 봅니다.', defaultRule: 'ATR(14)이 ATR(20)을 상향 돌파', minimumCandles: 21, formula: 'True Range의 N일 평균', caution: '방향이 아닌 변동성만 나타냅니다.', tier: '데이터 부족' },
  { id: 'adx', name: 'ADX', short: '상승·하락 방향과 무관한 추세 강도입니다.', defaultRule: 'ADX(14)가 25를 상향 돌파', minimumCandles: 28, formula: '방향성 지수 차이를 평활화', caution: '방향 판단에는 +DI/-DI 같은 보조값이 필요합니다.', tier: '데이터 부족' },
  { id: 'obv', name: 'OBV', short: '등락에 따라 거래량을 누적해 수급 흐름을 봅니다.', defaultRule: 'OBV가 0선을 상향 돌파', minimumCandles: 2, formula: '상승일 거래량 더하기, 하락일 빼기', caution: '대량 거래 한 번이 오래 영향을 줄 수 있습니다.', tier: '데이터 부족' },
];

export const universeIdFromKind = (kind: string): UniverseId => {
  const normalized = kind.toUpperCase();
  if (normalized === 'KOSPI_200') return 'kospi200';
  if (normalized === 'KOSDAQ_150') return 'kosdaq150';
  if (normalized === 'KOSPI_TOP_10') return 'kospiTop10';
  if (normalized === 'KOSPI_ALL') return 'kospi';
  if (normalized === 'KOSDAQ_ALL') return 'kosdaq';
  if (normalized === 'CUSTOM') return 'custom';
  return 'all';
};

export const universeName = (id: UniverseId): string => ({
  kospi200: 'KOSPI 200', kosdaq150: 'KOSDAQ 150', kospiTop10: 'KOSPI 시가총액 TOP 10', kospi: 'KOSPI', kosdaq: 'KOSDAQ',
  all: 'KOSPI · KOSDAQ', custom: '내 종목 목록',
})[id];
