# 실제 시세 공급자 연결 가이드

상태: 키움 OpenAPI REST 1차 adapter 연결. 실제 자격증명 미입력.

## 1. 경계

도메인·지표·신호 코드는 증권사 SDK, endpoint, 응답 field를 import하지 않는다. 실제 구현은 `MarketDataProvider` adapter 한 곳에 둔다.

MVP 코드의 현재 interface:

```ts
interface MarketDataProvider {
  readonly calendarVersion: string;
  readonly calendar: TradingCalendar;
  searchInstruments(query: string): Promise<Instrument[]>;
  getUniverseMembership(universeVersionId: string): Promise<Instrument[]>;
  getMarketSessions(from: string, to: string): Promise<string[]>;
  getHistoricalCandles(symbol: string, from: string, to: string): Promise<Candle[]>;
  getCurrentPrice(symbol: string): Promise<number | null>;
  getConnectionStatus(): Promise<ProviderStatus>;
  recoverMissing(symbol: string, sessionDates: string[]): Promise<Candle[]>;
}
```

실시간 stream, cursor 종목 목록, point-in-time 구성 이력 page는 실제 adapter 선정 뒤 contract를 버전 확장한다. 현재 Worker는 이 contract 밖 SDK를 import하지 않는다.

현재 provider 오류는 `RATE_LIMIT`, `TOKEN_EXPIRED`, `DISCONNECT`로 정규화한다. 운영 전 `RATE_LIMITED`, `AUTH_EXPIRED`, `TRANSIENT`, `INVALID_REQUEST`, `NOT_ENTITLED`, `DATA_GAP`, `PROVIDER_DOWN` contract로 확장한다. 원문 body와 secret은 log에 남기지 않는다.

## 2. 연결 전 승인

- `docs/BROKER_SELECTION.md` 실사 완료
- 상용 backend 호출, 저장, 가공, 파생값, 사용자 표시, 공개 성과의 권리 서면 확인
- 개발·운영 키 분리
- secret manager, rotation, 비상 폐기 담당 지정
- endpoint별 rate limit과 WebSocket 동시 구독 한도 기록
- 자동 주문 endpoint는 scope에서 제거

## 3. 환경값

```text
MARKET_DATA_PROVIDER=kiwoom
MARKET_CALENDAR_VERSION=krx-provisional-2023-2026-v1
MARKET_CALENDAR_HOLIDAYS=YYYY-MM-DD,...
MARKET_CALENDAR_EXTRA_SESSIONS=YYYY-MM-DD,...
MARKET_CALENDAR_FROM=YYYY-MM-DD
MARKET_CALENDAR_THROUGH=YYYY-MM-DD
KIWOOM_MODE=demo
KIWOOM_API_BASE_URL=
KIWOOM_APP_KEY=
KIWOOM_APP_SECRET=
KIWOOM_APP_KEY_DEMO=
KIWOOM_APP_SECRET_DEMO=
KIWOOM_UNIVERSE_SYMBOLS=005930:삼성전자:KOSPI,...
```

값은 서버 secret store에 넣는다. 모바일 bundle, Expo public env, crash report, analytics에 넣지 않는다. 저장소에는 `.env.example`만 둔다. `.env.example`의 KRX 휴장일은 2023-08-18~2026-08-18 시제품용 provisional 값이다. production 전 KRX 공지 기준으로 version을 새로 만든다.

## 4. 정규화 규칙

- 공급자 종목 ID를 내부 `instrument_id`와 매핑
- symbol 문자열만 primary key로 쓰지 않음
- provider timestamp와 수신 timestamp 모두 저장
- KST 거래 세션을 versioned `market_sessions`에 매핑
- 가격은 decimal 문자열로 parse
- volume은 0과 missing을 구분
- raw 공식 OHLC와 adjusted 분석 가격을 별도 열로 보존
- provider sequence/revision로 중복·역순·정정 처리
- final 확정 전 candle은 신호 평가 금지

## 5. 키움 REST 호출 범위

현재 구현:

- OAuth token: `POST /oauth2/token`
- 국내주식 일봉: `POST /api/dostk/chart`, header `api-id: ka10081`
- 요청 body: `stk_cd`, `base_dt`, `upd_stkpc_tp=1`
- 응답 list: `stk_dt_pole_chart_qry`
- mapping: `dt`, `open_pric`, `high_pric`, `low_pric`, `cur_prc`, `trde_qty`
- 연속조회: 응답 header `cont-yn=Y`, `next-key`가 있으면 다음 page 요청
- 국내주식 종목 master import: 기본 `api-id=ka10099`, `path=/api/dostk/stkinfo`, `KIWOOM_MASTER_MARKETS=0:KOSPI,10:KOSDAQ`
- NXT/SOR suffix symbol(`_NX`, `_AL`) 제외
- PostgreSQL 저장:
  - `instruments`
  - `universe_versions`
  - `universe_memberships`
  - `market_sessions`
  - `candles`
- Backfill CLI:
  - `pnpm worker:import:calendar`
  - `pnpm worker:import:instruments`
  - `pnpm worker:backfill:candles`
  - `pnpm worker:prepare:market-data`

다음 구현:

- 키 수령 뒤 실제 `ka10099` row field smoke와 필요시 env override
- 국내주식 종목정보 조회 `ka10001` field 확정
- 실시간 WebSocket gap recovery
- 계좌/주문 endpoint는 읽기·모의 검증 전까지 앱 runtime에서 비활성

## 6. 인증

1. process 시작 시 secret store에서 key 조회
2. access token 발급과 만료 시각 저장
3. 만료 전 단일 refresh lock 사용
4. 401/인증 만료 한 번만 refresh 후 재시도
5. 반복 실패 시 circuit open, provider status `DEGRADED`
6. token·app secret은 구조화 log mask

여러 worker가 같은 token을 갱신하면 Redis lock과 fencing token을 쓴다. 계정·주문 권한이 필요 없는 market-data 전용 app key를 우선한다.

## 7. REST backfill

1. 요청 범위를 거래 세션 chunk로 분할
2. rate budget 예약
3. 오래된 chunk부터 요청
4. raw 응답 checksum과 ingest run ID 기록
5. `(instrument, timeframe, close_at)`로 idempotent upsert
6. revision 증가 시 영향 구간 지표·신호를 재계산하되 이미 발송된 신호를 몰래 삭제하지 않음
7. gap scan 재실행

현재 CLI 기본값:

```text
BACKFILL_YEARS=3
MARKET_CALENDAR_FROM=2023-08-18
MARKET_CALENDAR_THROUGH=2026-08-18
BACKFILL_CHUNK_DAYS=180
BACKFILL_REQUEST_DELAY_MS=250
BACKFILL_MAX_RETRIES=3
BACKFILL_MARKETS=KOSPI,KOSDAQ
```

`BACKFILL_SYMBOLS=005930,000660`와 `BACKFILL_MAX_INSTRUMENTS=20`으로 작은 smoke를 먼저 실행한다. 전체 실행 전 `BACKFILL_DRY_RUN=true`로 API 응답 mapping만 확인한다.

과거 데이터 수정은 audit event로 남긴다. ranked 결과 변경 정책은 별도 산식 버전과 재계산 공지를 요구한다.

## 8. WebSocket

- 구독 shard는 provider 한도 아래 고정
- heartbeat와 마지막 sequence 추적
- disconnect 시 jitter exponential backoff
- 재접속 전 REST로 gap 복구
- duplicate와 out-of-order event를 fixture로 검증
- 429는 `Retry-After` 또는 공식 backoff 준수
- stream 값만으로 일봉 final을 확정하지 않음. 공식 EOD 확인 사용

## 9. 거래 가능성·페이퍼 체결

fill worker 입력은 다음을 모두 가져야 한다.

- 해당 세션 공식 비수정 시가
- 거래정지 여부
- 시가 volume 또는 거래 성립 여부
- 상·하한가 잠김 판정 자료
- 데이터 final·revision 상태
- tick size 규칙 버전

하나라도 불확실하면 체결하지 않는다. 분석용 adjusted close를 체결가로 쓰지 않는다.

## 10. 검증 fixture

- 정상 20거래일
- split·병합·배당·권리락
- 0 volume, 시가 없음, 거래정지
- 중복, 역순, late correction
- 장중 disconnect와 gap
- 429와 `Retry-After`
- token 만료와 동시 refresh
- 종목 코드 변경·상장폐지·시장 이전
- 휴장·임시 휴장

shadow 기간 지표:

- candle 누락률
- 공식 EOD 대조 불일치율
- 지연 p50/p95/p99
- correction 빈도
- reconnect·gap recovery 시간
- instrument master diff
- 일·월 예상 비용

## 11. 전환·rollback

1. mock과 broker를 병렬 ingest하되 broker 신호 발송 off
2. 20영업일 shadow 결과 승인
3. 내부 사용자만 broker 신호 flag on
4. 전 사용자 단계 확대
5. 오류 예산 초과 시 신호 생성 중단, stale 배지 표시, mock으로 운영 신호 대체 금지
6. 원인 해결 뒤 gap backfill과 결정적 replay

실제 시세를 mock으로 조용히 대체하면 잘못된 신호가 생긴다. 장애 시 fail closed가 기본이다.
