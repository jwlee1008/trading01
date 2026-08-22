# 시그널랩 MVP

한국 주식 일봉 조건 신호 앱 MVP다. 모바일은 Expo, 백엔드는 Spring Boot API와 Spring Worker, 데이터베이스는 PostgreSQL/Supabase를 사용한다. 투자 추천 앱이 아니며 실제 주문을 전송하지 않는다.

## 구조

```text
apps/mobile                 Expo Router 모바일 앱
apps/backend/signal-api     Spring Boot REST /v1 API
apps/backend/signal-worker  시세 수집, 신호, outbox, 페이퍼 체결, 랭킹 배치
apps/backend/signal-domain  API와 Worker 공용 Java 도메인
packages/api-client         모바일용 API 타입과 fetch client
packages/config             브랜드, feature flag, entitlement
packages/ui                 공용 React Native UI
supabase                    PostgreSQL migration, RLS, view, seed
docs                        제품·아키텍처·공급자 문서
```

기존 NestJS API, BullMQ Worker와 그 전용 TypeScript 패키지는 제거되었다. 백엔드는 `apps/backend` 아래의 Spring Boot 멀티 모듈만 사용한다.

## 요구 환경

- Node.js 22+, pnpm 11+
- JDK 21
- Supabase local 사용 시 Docker Desktop
- iOS Simulator, Android Emulator 또는 Expo Go

저장소에 포함된 실행 스크립트는 `apps/backend/.tooling/jdk21`의 JDK를 우선 사용한다.

## 설치와 실행

```powershell
pnpm install
Copy-Item .env.example .env
pnpm dev:api
pnpm dev:worker
pnpm dev:mobile
```

API 기본 주소는 `http://localhost:3000/v1`이며 `GET /v1/health`는 공개다. API와 Worker를 함께 실행하려면 `pnpm dev`를 사용한다. Android Emulator는 `EXPO_PUBLIC_API_URL=http://10.0.2.2:3000`, 실기기는 개발 PC의 LAN 주소를 사용한다.

API는 PostgreSQL과 Supabase JWT 인증만 사용한다. `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`와 Supabase 키를 설정하며, service role 키는 모바일 환경 변수에 넣지 않는다.

Worker 한 번 실행과 시장 데이터 준비:

```powershell
pnpm spring-worker:once
pnpm worker:import:calendar
pnpm worker:import:instruments
pnpm worker:backfill:candles
pnpm worker:prepare:market-data
```

키움 연동은 `MARKET_DATA_PROVIDER=kiwoom`, 검증된 `MARKET_CALENDAR_VERSION`, 키움 자격증명과 `DATABASE_URL`이 필요하다. `BACKFILL_DRY_RUN=true`는 DB에 쓰지 않는 연결 점검용이다. 자세한 절차는 [시장 데이터 공급자 연동](docs/MARKET_DATA_PROVIDER_INTEGRATION.md)을 참고한다.

## 검증

```powershell
pnpm check:spring-api
pnpm check:spring-worker
pnpm worker:benchmark
pnpm lint
pnpm typecheck
pnpm test:run
pnpm build
```

Spring Worker는 신호/outbox, 매도 규칙, 공식 랭킹 BUY 배분, D+1 paper fill, 시세 수집과 백테스트 엔진을 포함한다. 실제 PostgreSQL 통합 검증은 실행 가능한 DB와 `DATABASE_URL`이 있어야 한다.

## Supabase local

```powershell
pnpm supabase:start
pnpm supabase:status
```

초기화가 필요한 경우에만 `pnpm supabase:reset`을 사용한다. 이 명령은 로컬 DB 데이터를 모두 삭제한다.

## 현재 외부 검증 제한

- 키움 실계정 credential과 공식 KRX calendar를 넣은 운영 시세 검증이 필요하다.
- 원격 Expo Push와 실제 기기 알림 수신 검증이 필요하다.
- hosted Supabase, 다중 process 경쟁과 장애 복구 검증이 필요하다.
- 실제 증권사 주문과 결제 기능은 범위에 없다.

출시 전 확인은 [법무 체크리스트](docs/LEGAL_CHECKLIST.md), 공급자 선택 근거는 [브로커 선택](docs/BROKER_SELECTION.md)을 참고한다.
