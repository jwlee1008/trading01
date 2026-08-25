# 시그널랩 MVP

한국 주식 일봉 조건 신호 앱 MVP다. 모바일은 Expo, 백엔드는 Spring Boot API와 Spring Worker, 데이터베이스는 PostgreSQL/Supabase를 사용한다. 투자 추천 앱이 아니며 실제 주문을 전송하지 않는다.

## 구조

```text
apps/mobile                 Expo Router 모바일 앱
apps/backend/signal-api     Spring Boot REST /v1 API
apps/backend/signal-worker  시세 수집, 신호, outbox 배치
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

## 로컬 설치와 첫 실행

1. Docker Desktop을 설치하고 실행한다.
2. 저장소를 clone한 뒤 프로젝트 루트로 이동한다.
3. 의존성을 설치하고 `.env`를 만든다.

macOS/Linux:

```bash
pnpm install
cp .env.example .env
```

Windows PowerShell:

```powershell
pnpm install
Copy-Item .env.example .env
```

4. 로컬 Supabase를 실행하고 접속 정보를 확인한다.

```bash
pnpm supabase:start
pnpm supabase:status
```

5. 출력된 값을 `.env`에 입력한다.

```dotenv
EXPO_PUBLIC_SUPABASE_URL=<API_URL>
EXPO_PUBLIC_SUPABASE_PUBLISHABLE_KEY=<PUBLISHABLE_KEY>
SUPABASE_URL=<API_URL>
SUPABASE_ANON_KEY=<ANON_KEY>
SUPABASE_SERVICE_ROLE_KEY=<SERVICE_ROLE_KEY>
DATABASE_URL=jdbc:postgresql://127.0.0.1:54322/postgres
DATABASE_USERNAME=postgres
DATABASE_PASSWORD=postgres
```

`SUPABASE_SERVICE_ROLE_KEY`는 서버 전용이다. 절대로 `EXPO_PUBLIC_` 환경 변수에 넣거나 앱 코드에서 사용하지 않는다.

6. migration과 환경 설정을 확인한 뒤 앱, API, Worker를 함께 실행한다.

```bash
pnpm exec supabase migration up --local
pnpm env:check
pnpm dev
```

웹 앱은 기본적으로 `http://localhost:8081`, API는 `http://localhost:3000/v1`에서 실행된다. `GET /v1/health`는 인증 없이 확인할 수 있다. 실행 터미널에서 `Ctrl+C`를 누르면 앱, API, Worker가 함께 종료된다.

Android Emulator는 `.env`의 `EXPO_PUBLIC_API_URL`을 `http://10.0.2.2:3000`으로 변경한다. 실제 휴대폰에서는 `localhost` 대신 개발 PC의 LAN IP를 사용하고, 휴대폰과 개발 PC를 같은 네트워크에 연결한다.

## 두 번째 실행부터

Docker Desktop을 먼저 실행한 다음 프로젝트 루트에서 아래 명령만 실행하면 된다.

```bash
pnpm supabase:start
pnpm dev
```

전체 로컬 서비스를 종료하려면 `pnpm dev` 터미널에서 `Ctrl+C`를 누른 뒤 Supabase를 종료한다.

```bash
pnpm supabase:stop
```

## 앱에서 데이터 최신화와 테스트 신호 확인

- 시세 최신화: 로그인 후 `데이터 공급 상태` 화면에서 `데이터 최신화`를 누른다. 요청은 Worker 대기열에서 처리되므로 완료까지 시간이 걸릴 수 있다. 실제 키움 시세를 받으려면 `.env`에 키움 자격증명과 검증된 시장 캘린더 설정이 필요하다.
- 신호 기능 테스트: 로컬 개발 환경에서만 `.env`의 `SIGNAL_TEST_FIXTURE_ENABLED=true`로 변경한다. 앱을 다시 실행한 뒤 `데이터 공급 상태 > 개발 테스트 > 테스트 매수 신호 만들기`를 누르면 `TST001` 종목의 SMA 상향 돌파 신호가 실제 Worker 경로로 생성되어 홈에 표시된다.
- 테스트 종목과 신호는 공식 랭킹, 실제 데이터 커버리지, 자동 시세 수집 및 공식 랭킹 매매에서 제외된다. 운영 환경에서는 `SIGNAL_TEST_FIXTURE_ENABLED=false`를 유지한다.

API는 PostgreSQL과 Supabase JWT 인증만 사용한다. `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`와 Supabase 키를 설정하며, service role 키는 모바일 환경 변수에 넣지 않는다.

Worker 운영 상태, 개별 작업 실행·재시도, 실제 데이터 커버리지와 공식 랭킹 집계 절차는 [P1 운영 가이드](docs/P1_OPERATIONS.md)를 따른다.

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
pnpm supabase:stop
```

초기화가 필요한 경우에만 `pnpm supabase:reset`을 사용한다. 이 명령은 로컬 DB 데이터를 모두 삭제한다.

## 현재 외부 검증 제한

- 키움 실계정 credential과 공식 KRX calendar를 넣은 운영 시세 검증이 필요하다.
- 원격 Expo Push와 실제 기기 알림 수신 검증이 필요하다.
- hosted Supabase, 다중 process 경쟁과 장애 복구 검증이 필요하다.
- 실제 증권사 주문과 결제 기능은 범위에 없다.

출시 전 확인은 [법무 체크리스트](docs/LEGAL_CHECKLIST.md), 공급자 선택 근거는 [브로커 선택](docs/BROKER_SELECTION.md)을 참고한다.
