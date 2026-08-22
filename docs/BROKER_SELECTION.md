# 국내주식 시세 공급자 선택표

상태: 키움 OpenAPI REST 1차 선정  
확인일: 2026-08-17

이 문서는 기술 후보 조사표다. API 무료 여부와 시세 데이터 이용권은 같은 뜻이 아니다. 운영 전 공급자·거래소와 서면 계약 범위를 확인한다.

## 1. 결정

키움 OpenAPI REST로 실전 개발을 시작한다.

선정 근거:

- 공식 REST API 포털이 있고 국내주식 차트, 시세, 주문, 계좌, 조건검색 범위가 공개되어 있다.
- 공식 GitHub 저장소가 OAuth, REST, WebSocket 런타임과 API 스펙, 예제를 제공한다.
- 운영/모의투자 base URL이 분리되어 있다.
- IP 허용 정책이 있어 서버 backend 운영 모델과 맞출 수 있다.

미래에셋은 이번 범위에서 제외한다. 공개 개인용 REST API로 바로 붙일 근거가 약하고, AnyLink는 신규 신청 중단 이력이 있으며, 로보링크/FIX/DMA는 별도 계좌·영업점·자격 심사 영역이다.

## 2. 후보 1차 표

`공식 확인 필요` 값은 공개 페이지에서 상용 앱 조건을 확정하지 못한 항목이다.

| 항목 | 키움 OpenAPI REST | 한국투자증권 Open API | LS증권 Open API | 금융위원회 주식시세정보 OpenAPI |
|---|---|---|---|---|
| 국내주식 실시간 시세 비용 | 공식 계약 확인 필요 | 공식 계약 확인 필요 | 공식 계약 확인 필요 | 실시간 용도 부적합. 안내상 영업일 D+1 제공 |
| 과거 데이터 비용·범위 | 차트 API 존재. 보존·대량 범위 확인 필요 | 일/주/월/년 API 존재. 보존·대량 범위 확인 필요 | 주식시세 API 존재. 범위 확인 필요 | 일 단위 자료. 기간·정정 정책 확인 필요 |
| 계좌 보유 조건 | OpenAPI 사용 신청, App Key/Secret 필요 | 앱키 발급·서비스 이용 조건 확인 필요 | API 신청·계좌 조건 확인 필요 | 계좌 불필요, 활용 신청 필요 |
| REST·WebSocket | 공식 문서상 둘 다 제공 | 공식 문서상 둘 다 제공 | 공식 portal에서 API guide·테스트베드 제공. 상세 제한 확인 필요 | REST. 실시간 stream 아님 |
| rate/구독 제한 | endpoint·환경별 공식 확인 필요 | endpoint·환경별 공식 확인 필요 | TR·실시간별 공식 확인 필요 | 개발 트래픽 안내 확인. 운영 증액 절차 확인 |
| 개인·상용 이용 | 상용 재서비스 서면 확인 필요 | 상용 재서비스 서면 확인 필요 | 법인·상용 조건 서면 확인 필요 | 이용허락 표시와 별도 이용조건 확인 |
| 저장·가공·재배포 | 증권사 API 약관과 원천 시세 권리 별도 확인 | 증권사 API 약관과 원천 시세 권리 별도 확인 | 증권사 API 약관과 원천 시세 권리 별도 확인 | 공공데이터 이용조건·원천기관 권리 확인 |
| 인증·토큰 수명 | appkey/appsecret, OAuth token. 수명은 최신 guide 확인 | appkey/appsecret, REST access token, WS approval key. 수명은 최신 guide 확인 | OAuth/API key 세부는 최신 guide 확인 | service key. 만료·재발급 정책 확인 |
| Sandbox | 모의 REST endpoint 제공 | 공식 문서상 모의 REST·WebSocket endpoint | 공식 portal에 테스트베드 표시 | 개발계정 제공. 체결 sandbox 개념 없음 |
| 장애·공식 지원 | 공지·공식 GitHub·개발자 포털 확인 | 공지·개발자센터 확인 | 공지·공식 문의 채널 확인 | 공공데이터 오류신고·문의 |
| MVP 적합성 | 1차 선택. 일봉 adapter부터 구현 | fallback 후보 | fallback 후보 | EOD 보조·대조 후보 |

공식 출처:

- [KIS Developers API 서비스](https://apiportal.koreainvestment.com/apiservice-summary)
- [LS증권 OPEN API](https://openapi.ls-sec.co.kr/)
- [금융위원회 주식시세정보](https://www.data.go.kr/data/15094808/openapi.do)
- [KRX Data Marketplace OPEN API 이용방법](https://openapi.krx.co.kr/contents/OPP/INFO/OPPINFO003.jsp)
- [키움 REST API](https://openapi.kiwoom.com/)
- [키움 공식 REST API GitHub](https://github.com/Kiwoom-Securities/Kiwoom-REST-API)

## 3. 필수 실사 질문

### 비용

- API 이용료, 계좌·거래 조건, KRX 시세 이용료를 각각 분리 견적했는가
- 개발, QA, 운영, DR 환경별 비용이 있는가
- 과거 backfill, 실시간 동시 종목, 사용자 수에 따라 비용이 바뀌는가

### 권리

- 원시 tick·candle 저장 기간은 얼마인가
- OHLCV 정규화, 수정주가, 지표값, 신호, 집계 랭킹을 저장·노출할 수 있는가
- 앱 사용자에게 현재가·지연가·차트·완료 체결을 재전송할 수 있는가
- 공개 프로필의 종목·체결가·성과가 재배포로 분류되는가
- 서비스 종료 뒤 데이터 삭제 의무가 있는가

### 기능

- 종목 master와 상장폐지·시장 이전·관리종목·거래정지 상태 제공 여부
- 지수 구성 이력과 효력일 제공 여부
- adjusted와 unadjusted 가격, corporate action 제공 여부
- official open, 0 volume, 상·하한가 잠김 판정 가능 여부
- 정정·취소·지연 패킷과 sequence 제공 여부
- REST backfill과 WebSocket gap recovery 방식

### 운영

- 토큰 수명, 동시 연결, 초당·분당·일 한도
- 429, auth 만료, 점검, 장애 상태 코드
- Sandbox 데이터 현실성
- SLA, 장애 공지, 긴급 지원, 변경 사전 고지
- IP allowlist, 법인 계정, 키 rotation, DR 정책

## 4. 통과 기준

필수:

- 일봉·공식 시가·거래 가능성 판정에 필요한 데이터
- point-in-time 종목과 corporate action 이력 또는 계약 가능한 별도 source
- 앱 서비스에 필요한 저장·가공·표시 권리의 서면 확인
- gap 복구, 정정 식별, rate limit 문서
- 개발·운영 자격증명 분리와 키 rotation

탈락:

- 개인용 API를 상용 backend에서 공유 사용 금지
- 파생 지표·신호 저장도 금지
- 사용자 화면 재표시 권리 없음
- 공식 시가와 거래정지 여부를 재현할 수 없음
- 장애·정정·rate limit 정책 확인 불가

## 5. 결정 절차

1. NDA·약관·시세 계약 원문 수령
2. 법무가 저장·가공·재배포 행렬 서명
3. 20영업일 shadow ingest
4. 누락률, 지연 p95/p99, 정정률, backfill 시간, 비용 측정
5. Mock golden fixture와 provider 결과 대조
6. primary·fallback·EOD 대조 source 결정
7. 운영 승인 뒤 `MARKET_DATA_PROVIDER=kiwoom` 사용
