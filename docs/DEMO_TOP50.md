# 데모 TOP 50

`DEMO_TOP_50`은 기능 검증 전용 종목군이다. 실제 KOSPI TOP 50을 의미하지 않는다.

- 키움 demo TOP10 구성 종목 10개
- seed 기반 합성 종목 40개
- 합성 시나리오: 상승, 하락, 횡보, 변동성, 반전
- 동일 `DEMO_TOP50_SEED`와 날짜 범위에서는 같은 OHLCV를 생성한다.
- 신호 근거와 AI 입력에는 `키움 demo 일봉` 또는 `합성 demo 일봉` 출처가 포함된다.

준비 명령:

```powershell
pnpm supabase:start
pnpm exec supabase migration up --local
pnpm worker:refresh:kospi-top10
pnpm worker:backfill:kospi-top10
pnpm worker:prepare:demo-top50
```

마지막 명령은 재실행해도 같은 종목·캔들을 upsert한다.
