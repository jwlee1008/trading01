# P1 운영 가이드

## 최초 실행

```bash
pnpm install
cp .env.example .env
pnpm env:check
pnpm supabase:start
pnpm exec supabase migration up --local
pnpm dev
```

Windows PowerShell에서는 `Copy-Item .env.example .env`를 사용한다. Docker Desktop이 먼저 실행되어 있어야 한다. `pnpm env:check`는 비밀값을 출력하지 않고 URL, JDBC 계정, Supabase 및 시세 공급자 설정을 검증한다.

## 실제 데이터 수집

`WORKER_MARKET_DATA_AUTO_ENABLED=true`이면 Worker가 마지막 완료 장 기준으로 누락 종목을 `WORKER_MARKET_DATA_AUTO_BATCH_SIZE`개씩 순환 수집한다. 기존 종목은 최근 14일, 신규 종목은 3년을 요청한다. 한 종목 실패가 신호·랭킹·체결·알림 작업을 막지 않는다.

운영 시작 전에는 다음 상태를 확인한다.

```bash
pnpm worker:admin state
pnpm worker:admin run market-data
```

`marketDataQuality.missingLatestCoverage`가 0이 되기 전에는 공급자 상태가 지연으로 표시될 수 있다. 실패 작업은 상태 응답의 run ID로 재시도한다.

```bash
pnpm worker:admin retry <run-id>
```

관리 API는 `WORKER_SERVICE_TOKEN`을 요구하며 앱 사용자에게 노출하지 않는다.

## 작업 분리와 순서

각 작업은 별도 실행 기록과 idempotency key를 가진다.

1. `market-data`: 종목·거래일·완성 일봉 수집
2. `signal`: 확정 전략의 false→true 매수 조건 평가
4. `sell-signal`: 손절·익절·추적·기간·기술 청산 상태 평가
5. `notification`: outbox 알림 전송

테스트 기간의 사용자 랭킹은 공개 프로필에 기간 내 `MANUAL_LIVE` 체결 입력이 1건이라도 있으면 노출되며, SELL 기록 전 수익률은 0%다.

## 출시 전 점검

```bash
pnpm check
cd apps/backend && bash ./gradlew test
cd ../.. && pnpm check:web
pnpm worker:admin state
```

공급자 상태의 `expectedSession`, 최신 일봉 날짜, 누락 커버리지를 확인한다. 로컬 DB를 지우는 `pnpm supabase:reset`은 데이터 재생성이 필요한 경우에만 실행한다.
