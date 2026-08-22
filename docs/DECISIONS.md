# 결정·가정 기록

기준일: 2026-08-15

| ID | 결정 | 이유·영향 |
|---|---|---|
| D-001 | 가칭 `시그널랩` | 중앙 설정값. 상표·스토어 심사 전 교체 가능 |
| D-002 | 시장 시각 `Asia/Seoul`, 저장 시각 UTC `timestamptz` | 휴장·DST 오해 방지. 한국은 현재 DST 없음 |
| D-003 | 통화 KRW, 금액 `numeric(24,6)`, 비율 `numeric(18,10)` | 원장 계산 오차 방지 |
| D-004 | 일봉 완료는 versioned market session close 뒤 provider final 상태 | wall-clock만으로 완료 추정 금지 |
| D-005 | provider 기본값 `kiwoom` | 키움 OpenAPI REST를 1차 선택. 자격증명·공식 calendar 없으면 fail closed |
| D-006 | 개발 seed `20260815` | golden/E2E 재현 |
| D-007 | 개인 전략 5개, 자동 탐색 4개 | 5개 자동 탐색은 benchmark flag 뒤 활성 |
| D-008 | 최소 유효 신호 30 | 미달은 데이터 부족 |
| D-009 | 공식 초기 자본 10,000,000원 | 운영 설정과 track에 버전 고정 |
| D-010 | 공식 종목당 목표·최대 비중 10%, 동시 10종목 | 추가매수 금지. 주문 전 NAV 기준 |
| D-011 | 공식 트랙 재시작 cooldown 30일 | versioned 운영 flag로 관리 |
| D-012 | ranked BUY 미체결은 한 세션 뒤 만료 | 결과 선택 편향 차단 |
| D-013 | ranked SELL 미체결은 EXIT_PENDING 재시도 | 손실 숨김 방지 |
| D-014 | 수동 과거 등록 시 최신 완료 봉만 rule 초기화 | 과거 알림 소급 생성 금지 |
| D-015 | 공개 기본값 false | profile, strategy, track 각각 명시 동의 |
| D-016 | 열린 ranked execution 공개 cutoff는 직전 완료 거래 세션 | 단순 24시간 대신 거래일 지연 |
| D-017 | base user table는 owner RLS, 교차 사용자 공개는 safe view | 민감 열 누출 축소 |
| D-018 | 원장 mutation은 service role API/worker만 | 모바일 직접 insert 금지. RLS는 owner select만 |
| D-019 | 체결·현금·order event·ranking event·audit는 append-only | 정정은 새 reversal/correction event |
| D-020 | position·NAV는 cache | ledger replay로 언제든 재생성 |
| D-021 | 앱 JSON decimal은 문자열 | JS float 변환 방지 |
| D-022 | full feature free plan | 결제·페이월 없음. 중앙 entitlement만 준비 |
| D-023 | push 기본 `console` | Expo 자격증명 전 원격 성공 주장 금지 |
| D-024 | 실제 provider는 키움 OpenAPI REST로 시작 | API 사용권과 시세 저장·재배포 계약은 운영 전 분리 검토 |
| D-025 | 공개 nickname은 대소문자 무시 unique | 사칭·혼동 축소. 금칙어·신고 정책은 운영 필요 |
| D-026 | 계정 삭제는 즉시 공개 철회 후 비동기 법정 보존·삭제 처리 | 감사·분쟁 보존과 삭제권 충돌은 법무 확정 필요 |

## 미확정 출시 차단 항목

- 키움/KRX 데이터 저장·가공·재배포 범위
- 금융투자업·투자자문업·유사투자자문업 해당 여부에 대한 한국 변호사 의견
- 공개 전략·성과, 랭킹, 알림 문구 법무 검수
- 개인정보 보유 기간, 국외 이전, 수탁자, 삭제 예외
- 앱 이름 상표와 스토어 금융 앱 정책
- Expo push 운영 자격증명과 잠금 화면 정보 정책
