begin;

insert into public.plans (code, name_ko, is_active, is_paid)
values ('free', 'MVP 무료', true, false)
on conflict (code) do update
set name_ko = excluded.name_ko, is_active = true, is_paid = false;

insert into public.features (code, name_ko, description_ko)
values
  ('universe.manage', '종목 범위', '기본·사용자 지정 종목 범위 사용'),
  ('strategy.manage', '전략', '최대 5개 지표 전략 생성·버전 관리'),
  ('signal.buy', '매수 조건 신호', '완성 일봉 매수 조건 신호'),
  ('position.manual', '실제 보유', '수동 실제 보유와 체결 기록'),
  ('paper.sandbox', '연습 페이퍼', '사용자 선택 페이퍼 주문'),
  ('paper.ranked', '공식 랭킹 페이퍼', '잠긴 전략 자동 가상 체결'),
  ('signal.sell', '매도 조건 신호', '열린 포지션 매도 규칙 감시'),
  ('ranking.user', '사용자 랭킹', '공식 페이퍼 통합 순위'),
  ('profile.public', '공개 프로필', '명시 동의 기반 공개 전략·성과'),
  ('notification.push', '알림', '신호 알림과 설정')
on conflict (code) do update
set name_ko = excluded.name_ko, description_ko = excluded.description_ko;

insert into public.plan_entitlements (plan_id, feature_id, enabled, configuration)
select p.id, f.id, true, jsonb_build_object('mvp', true, 'price', 0)
from public.plans p
cross join public.features f
where p.code = 'free'
on conflict (plan_id, feature_id) do update
set enabled = true, limit_value = null,
    configuration = jsonb_build_object('mvp', true, 'price', 0);

insert into public.feature_flags (code, enabled, configuration)
values
  ('market_data.broker', false, '{"release_blocker":"provider contract and credentials"}'::jsonb),
  ('push.remote', false, '{"development_provider":"console"}'::jsonb)
on conflict (code) do update
set enabled = excluded.enabled, configuration = excluded.configuration, updated_at = now();

insert into public.universe_definitions (kind, name_ko, description)
select seed.kind, seed.name_ko, seed.description
from (values
  ('KOSPI_200'::public.universe_kind, 'KOSPI 200', '효력일별 KOSPI 200 구성'),
  ('KOSDAQ_150'::public.universe_kind, 'KOSDAQ 150', '효력일별 KOSDAQ 150 구성'),
  ('KOSPI_ALL'::public.universe_kind, 'KOSPI 전체', '버전 정책에 맞는 KOSPI 종목'),
  ('KOSDAQ_ALL'::public.universe_kind, 'KOSDAQ 전체', '버전 정책에 맞는 KOSDAQ 종목'),
  ('KR_ALL'::public.universe_kind, 'KOSPI·KOSDAQ 통합', '두 시장 통합 범위')
) as seed(kind, name_ko, description)
where not exists (
  select 1 from public.universe_definitions d where d.kind = seed.kind and d.user_id is null
);

insert into public.indicator_definitions
  (code, version, name_ko, short_description_ko, formula_ko, default_params,
   minimum_bars, signal_definition, common_misconception_ko, weakness_ko)
values
  ('SMA', 1, '단순이동평균', '최근 종가의 같은 비중 평균', '최근 N개 완성 종가 합계 / N',
   '{"period":20}', 20, '{"operators":["GT","LT","CROSSES_ABOVE","CROSSES_BELOW"]}',
   '가격을 미리 예측하지 않는다.', '횡보 구간에서 잦은 거짓 교차가 생긴다.'),
  ('EMA', 1, '지수이동평균', '최근 값에 더 큰 비중을 둔 평균', 'EMA_t = alpha*close_t + (1-alpha)*EMA_(t-1)',
   '{"period":20}', 20, '{"operators":["GT","LT","CROSSES_ABOVE","CROSSES_BELOW"]}',
   'SMA보다 빠르지만 선행 지표는 아니다.', '짧은 기간은 잡음에 민감하다.'),
  ('RSI', 1, '상대강도지수', '상승·하락 폭 비율의 모멘텀', '100 - 100/(1 + 평균상승/평균하락)',
   '{"period":14}', 15, '{"operators":["GT","LT","CROSSES_ABOVE","CROSSES_BELOW"]}',
   '과매수는 즉시 하락 뜻이 아니다.', '강한 추세에서 극단값이 오래 간다.'),
  ('MACD', 1, 'MACD', '두 EMA 차이와 signal line', 'EMA(fast)-EMA(slow), signal=EMA(MACD)',
   '{"fastPeriod":12,"slowPeriod":26,"signalPeriod":9}', 34, '{"operators":["GT","LT","CROSSES_ABOVE","CROSSES_BELOW"]}',
   '교차 자체가 수익을 보장하지 않는다.', '후행성과 횡보 whipsaw가 있다.'),
  ('BOLLINGER', 1, '볼린저 밴드', '이동평균 주변 표준편차 밴드', 'middle=SMA(N), upper/lower=middle±K*표준편차',
   '{"period":20,"standardDeviations":2}', 20, '{"operators":["GT","LT","CROSSES_ABOVE","CROSSES_BELOW"]}',
   '밴드 접촉만으로 반전을 뜻하지 않는다.', '분포·변동성 체제가 바뀌면 해석이 약해진다.'),
  ('VOLUME_SPIKE', 1, '거래량 급증', '최근 평균 대비 현재 거래량 배수', '현재 거래량 / 직전 N개 거래량 평균',
   '{"period":20,"threshold":2}', 21, '{"operators":["GT","GTE"]}',
   '거래량 증가만으로 방향을 알 수 없다.', '이벤트·상장 초기 값에 왜곡될 수 있다.'),
  ('STOCHASTIC', 1, '스토캐스틱', '최근 고저 범위 안 종가 위치', '%K=100*(close-low_N)/(high_N-low_N), %D=SMA(%K)',
   '{"kPeriod":14,"smoothKPeriod":3,"dPeriod":3}', 18, '{"operators":["GT","LT","CROSSES_ABOVE","CROSSES_BELOW"]}',
   '낮은 값이 곧 저평가 뜻은 아니다.', '강한 추세에서 극단값이 지속된다.'),
  ('ATR', 1, 'ATR', '갭을 포함한 평균 진폭', 'TR=max(H-L,abs(H-prevC),abs(L-prevC)); ATR=RMA(TR,N)',
   '{"period":14}', 14, '{"operators":["GT","LT"]}',
   '방향 지표가 아니다.', '가격 수준이 다른 종목 간 원값 비교가 어렵다.'),
  ('ADX', 1, 'ADX', '방향과 무관한 추세 강도', '평활 +DM/-DM과 TR로 DX를 구한 뒤 평균',
   '{"period":14}', 28, '{"operators":["GT","LT","CROSSES_ABOVE","CROSSES_BELOW"]}',
   '높은 ADX가 상승을 뜻하지 않는다.', '후행하며 짧은 데이터에 불안정하다.'),
  ('OBV', 1, 'OBV', '종가 방향으로 거래량을 누적', '상승일 +volume, 하락일 -volume, 동일일 유지',
   '{}', 1, '{"operators":["GT","LT","CROSSES_ABOVE","CROSSES_BELOW"]}',
   '절대값 자체보다 변화와 divergence를 본다.', '비정상 거래량 한 번이 누적값을 오래 왜곡한다.')
on conflict (code, version) do update
set name_ko = excluded.name_ko,
    short_description_ko = excluded.short_description_ko,
    formula_ko = excluded.formula_ko,
    default_params = excluded.default_params,
    minimum_bars = excluded.minimum_bars,
    signal_definition = excluded.signal_definition,
    common_misconception_ko = excluded.common_misconception_ko,
    weakness_ko = excluded.weakness_ko,
    is_active = true;

-- Mock-only models. Do not claim current broker fees or Korean tax rates.
insert into public.paper_fill_model_versions
  (code, version, slippage_buy_bps, slippage_sell_bps, spread_bps,
   tick_rule_version, configuration, effective_from)
values
  ('mock_official_open', 1, 5, 5, 2, 'mock-krx-tick-v1',
   '{"mock":true,"fill":"all_or_none","price":"unadjusted_official_open","release_blocker":"replace after provider contract"}',
   '2026-01-01T00:00:00Z')
on conflict (code, version) do nothing;

insert into public.cost_model_versions
  (code, version, buy_fee_rate, sell_fee_rate, sell_tax_rate, configuration, effective_from)
values
  ('mock_cost', 1, 0.00015, 0.00015, 0.0018,
   '{"mock":true,"not_current_legal_or_broker_rate":true,"release_blocker":"verify broker fee and statutory tax"}',
   '2026-01-01T00:00:00Z')
on conflict (code, version) do nothing;

commit;
