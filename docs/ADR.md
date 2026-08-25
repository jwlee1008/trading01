# Architecture Decision Record

현재 실전 개발 런타임은 Supabase Auth, PostgreSQL API repository, Worker DB 경로를 쓴다. 시세 provider는 키움 OpenAPI REST를 1차 선택했다. Mock 경로는 test/dev fixture다.

상태: Accepted for MVP  
기준일: 2026-08-17

## ADR-001. 모노레포와 책임 분리

결정: Expo React Native와 Spring Boot REST `/v1`·배치 Worker, PostgreSQL/Supabase Auth를 한 workspace에 둔다.

이유: 모바일은 설정·조회만 맡고 서버는 장시간 감시와 순서 보장을 맡아야 한다. 공용 Java domain 모듈과 동일한 지표 정의로 API/Worker 간 규칙 차이를 막는다.

결과: Worker 장애 시 앱이 신호를 대신 만들지 않는다. PostgreSQL outbox 재처리와 DB idempotency로 복구한다.

## ADR-002. 공급자 경계

결정: 도메인은 `MarketDataProvider`만 참조한다. 운영 provider는 `kiwoom`, test/dev fixture는 `mock`으로 분리한다.

필수 계약: 종목·검색, 종목군 이력, 과거 candle, 현재가/stream, 상태, gap 복구, 오류·429·토큰 만료 정규화.

결과: 공급자 SDK import는 adapter 밖에서 금지한다. 키움 자격증명과 검증된 KRX calendar가 없으면 Worker는 fail closed 한다.

## ADR-003. 일봉 확정과 단일 지표 엔진

결정: 완성 일봉만 계산한다. 실시간 신호와 백테스트는 같은 순수 함수와 지표 정의 버전을 쓴다.

이유: 미완성 봉 재도색, 구현 차이, 미래 데이터 누수를 줄인다.

## ADR-004. 버전과 point-in-time 데이터

결정: Universe, Strategy, SellRule, fill/cost 모델, 달력, 지표, 데이터셋, 엔진, 산식을 버전으로 고정한다. 확정 버전은 수정하지 않는다.

결과: 수정은 새 버전이다. 현재 지수 구성을 과거에 소급하지 않는다.

## ADR-005. 실제 매매 기록 원장

결정: 사용자가 직접 작성하는 `MANUAL_LIVE` portfolio와 append-only execution만 운용한다.

이유: 모의 체결과 자동 랭킹 트랙을 제거하고 실제 매매 기록 흐름을 단순화한다.

결과: 사용자 랭킹은 공개 프로필의 `MANUAL_LIVE` 매도 기록만 읽으며, 증권사 인증 내역이 아님을 고지한다.

## ADR-006. Append-only 원장

결정: execution, cash ledger, order event, ranking event, audit log의 UPDATE/DELETE를 DB trigger로 막는다. 정정은 reversal/correction 행이다.

이유: 체결 수정과 성과 기록 세탁을 막고 replay를 가능하게 한다.

결과: position 집계값은 캐시이며 execution ledger에서 재생할 수 있다.

## ADR-007. Decimal

결정: 가격·수량·현금·수익은 PostgreSQL `numeric`과 코드 Decimal을 쓴다. JSON API는 decimal을 문자열로 보낸다.

이유: JavaScript 부동소수 오차를 원장에 넣지 않는다.

## ADR-008. 신호와 transactional outbox

결정: signal 및 `push_outbox` insert를 한 transaction으로 처리한다. 유일 dedupe key를 DB가 강제한다.

발송 직전 worker가 포지션·신호 상태를 다시 읽는다. CLOSED, 해결, 취소 상태면 outbox를 취소한다.

## ADR-009. 모의투자 폐기

결정: 페이퍼 주문, 가상 체결 Worker, 공식 자동 랭킹 트랙을 제공하지 않는다.

결과: 앱은 실제 주문을 실행하지 않으며 사용자가 앱 밖 실제 체결을 직접 기록한다.

## ADR-010. 사용자 랭킹

결정: 사용자 순위는 기간 내 직접 작성한 매도 기록의 실현손익을 해당 수량의 매입원가로 나눈 수익률을 쓴다. 테스트 기간에는 실제 매매 입력 1회부터 노출하고 매도 전 수익률은 0%로 처리한다. 동률은 입력 횟수, 기록 기간, 닉네임 순으로 처리한다.

## ADR-011. RLS와 공개 DTO

결정: 사용자 base table은 owner RLS만 허용한다. 공개 조회는 선택 열과 공개 동의 조건만 가진 `public_*` view로 제한한다.

결과: 이메일, 토큰, 실제 보유, 실제 투자금은 공개 경로에 없다. 공개 철회는 view 결과에서 즉시 빠진다. 감사 원장은 보존한다.

차단 목록도 공개 view 조건에 포함한다. 계정 hard delete는 service-role 전용 함수만 허용한다. 법정 보존 대상 분리·만료 뒤 호출한다.

## ADR-012. 사용자 랭킹 공개 범위

결정: 사용자 랭킹에는 닉네임, 기간 수익률, 입력 횟수, 기록 일수와 사용자가 별도로 공개한 전략만 공개한다. 개별 체결, 실제 투자금, 미청산 포지션은 공개하지 않는다. 공개 전략이 랭킹 수익률에 사용됐다고 단정하지 않는다.

결과: 공개 철회 시 즉시 랭킹에서 제외한다.

## ADR-013. 무료 entitlement shell

결정: plan, feature, plan entitlement, user override, flag, usage counter를 둔다. `effective_user_entitlements` view를 중앙 service가 읽는다.

결과: MVP free plan은 모든 기능을 허용한다. 결제 SDK와 `isPremium` 분기는 없다.

## ADR-014. 보안 경계

결정: anon key만 앱에 허용한다. service role, broker secret, Expo token은 서버 비밀 저장소에 둔다. 원장·랭킹 mutation은 서버만 수행한다.

결과: RLS는 마지막 방어선이다. API도 user/portfolio ownership, schema, rate limit, idempotency를 검사한다.
