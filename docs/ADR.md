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

결과: 수정은 새 버전이다. 공식 랭킹 트랙은 시작 시 모든 ID를 잠근다. 현재 지수 구성을 과거에 소급하지 않는다.

## ADR-005. 세 원장 분리

결정: `MANUAL_LIVE`, `SANDBOX_PAPER`, `RANKED_PAPER` portfolio를 분리한다. execution은 portfolio kind를 중복 저장하고 DB trigger가 일치를 검증한다.

이유: 수동 과거 입력과 자유 주문이 공식 랭킹을 오염시키면 안 된다.

결과: paper execution은 paper order가 필수다. manual execution은 paper order를 금지한다. 공식 랭킹 query는 `RANKED_PAPER`만 읽는다.

## ADR-006. Append-only 원장

결정: execution, cash ledger, order event, ranking event, audit log의 UPDATE/DELETE를 DB trigger로 막는다. 정정은 reversal/correction 행이다.

이유: 체결 수정과 성과 기록 세탁을 막고 replay를 가능하게 한다.

결과: position 집계값과 NAV snapshot은 캐시다. ledger replay 검사 job을 둔다.

## ADR-007. Decimal

결정: 가격·수량·현금·수익은 PostgreSQL `numeric`과 코드 Decimal을 쓴다. JSON API는 decimal을 문자열로 보낸다.

이유: JavaScript 부동소수 오차를 원장에 넣지 않는다.

## ADR-008. 신호와 transactional outbox

결정: signal 및 `push_outbox` insert를 한 transaction으로 처리한다. 유일 dedupe key를 DB가 강제한다.

발송 직전 worker가 포지션·신호 상태를 다시 읽는다. CLOSED, 해결, 취소 상태면 outbox를 취소한다.

## ADR-009. 공식 페이퍼 체결

결정: 신호 확정 다음 거래 가능 세션의 비수정 공식 시가를 쓴다. fill/cost 모델을 버전 고정한다. BUY 실패는 만료, SELL 실패는 `EXIT_PENDING` 재시도다.

결과: 실제 주문 API와 같은 adapter를 쓰지 않는다. ranked 주문은 사용자 취소·가격 수정을 허용하지 않는다.

## ADR-010. 랭킹 분리

결정: 조합 랭킹과 사용자 랭킹을 별도 snapshot kind로 저장한다. 완전 매도 규칙 없는 조합은 `신호 후 성과`로 표시한다.

사용자 순위는 범위 보정 없는 비용 후 누적수익률만 쓴다. MDD·운용 기간·닉네임은 동률 처리다.

## ADR-011. RLS와 공개 DTO

결정: 사용자 base table은 owner RLS만 허용한다. 공개 조회는 선택 열과 공개 동의 조건만 가진 `public_*` view로 제한한다.

결과: 이메일, 토큰, 실제 보유, 실제 투자금은 공개 경로에 없다. 공개 철회는 view 결과에서 즉시 빠진다. 감사 원장은 보존한다.

차단 목록도 공개 view 조건에 포함한다. 계정 hard delete는 service-role 전용 함수만 허용한다. 법정 보존 대상 분리·만료 뒤 호출한다.

## ADR-012. 공개 미청산 포지션 지연

결정: 미청산 공식 페이퍼 execution은 직전 완료 거래 세션 마감 이전 데이터만 공개한다. 종료 포지션은 완료 기록으로 공개한다.

결과: 휴일은 단순 24시간이 아니라 `market_sessions`로 계산한다.

## ADR-013. 무료 entitlement shell

결정: plan, feature, plan entitlement, user override, flag, usage counter를 둔다. `effective_user_entitlements` view를 중앙 service가 읽는다.

결과: MVP free plan은 모든 기능을 허용한다. 결제 SDK와 `isPremium` 분기는 없다.

## ADR-014. 보안 경계

결정: anon key만 앱에 허용한다. service role, broker secret, Expo token은 서버 비밀 저장소에 둔다. 원장·랭킹 mutation은 서버만 수행한다.

결과: RLS는 마지막 방어선이다. API도 user/portfolio ownership, schema, rate limit, idempotency를 검사한다.
