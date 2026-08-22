import React, { useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AppText, Banner, Button, Chip, Divider, ErrorState, Field, LoadingState, Metric, Screen, SectionTitle, Surface, spacing } from '@signal/ui';
import { loadRemoteDemoTop50, updateRemoteDemoTop50, type DemoScenario, type DemoTop50Instrument, type DemoTop50Settings, type IndicatorTestPattern } from '@/services/connected-api';

const scenarios: { id: DemoScenario; label: string }[] = [
  { id: 'UPTREND', label: '상승' }, { id: 'DOWNTREND', label: '하락' }, { id: 'SIDEWAYS', label: '횡보' },
  { id: 'VOLATILE', label: '급등락' }, { id: 'REVERSAL', label: '반전' },
];
const testPatterns: { id: IndicatorTestPattern; label: string }[] = [
  { id: 'NONE', label: '사용 안 함' }, { id: 'RSI_ONLY', label: 'RSI만' }, { id: 'EMA_ONLY', label: 'EMA만' },
  { id: 'BOLLINGER_ONLY', label: '볼린저만' }, { id: 'RSI_EMA', label: 'RSI + EMA' },
  { id: 'RSI_BOLLINGER', label: 'RSI + 볼린저' }, { id: 'EMA_BOLLINGER', label: 'EMA + 볼린저' },
  { id: 'RSI_EMA_BOLLINGER', label: '3개 모두' },
];
type Filter = 'ALL' | 'PROVIDER' | 'SYNTHETIC';
type Draft = Record<keyof DemoTop50Settings, string>;

function draftOf(item: DemoTop50Instrument): Draft {
  return { name: item.name, scenario: item.scenario || 'SIDEWAYS', basePrice: String(item.basePrice), trendPerDay: String(item.trendPerDay), volatilityPct: String(item.volatilityPct), baseVolume: String(item.baseVolume), testPattern: item.testPattern || 'NONE' };
}

function won(value: number) { return `${Math.round(value).toLocaleString('ko-KR')}원`; }
function count(value: number) { return Math.round(value).toLocaleString('ko-KR'); }

export default function DemoTop50Screen() {
  const queryClient = useQueryClient();
  const query = useQuery({ queryKey: ['demo-top50'], queryFn: () => loadRemoteDemoTop50() });
  const [filter, setFilter] = useState<Filter>('ALL');
  const [editing, setEditing] = useState<string | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [saved, setSaved] = useState<string | null>(null);
  const mutation = useMutation({
    mutationFn: ({ symbol, input }: { symbol: string; input: DemoTop50Settings }) => updateRemoteDemoTop50(symbol, input),
    onSuccess: (item) => {
      queryClient.setQueryData<DemoTop50Instrument[]>(['demo-top50'], (old) => old?.map((current) => current.symbol === item.symbol ? item : current));
      setEditing(null); setDraft(null); setSaved(`${item.name} 데이터가 다시 생성되었습니다.`);
    },
  });
  const data = query.data ?? [];
  const visible = data.filter((item) => filter === 'ALL' || item.source === filter);
  const providers = data.filter((item) => item.source === 'PROVIDER').length;
  const synthetic = data.filter((item) => item.source === 'SYNTHETIC').length;

  const save = (symbol: string) => {
    if (!draft) return;
    const input: DemoTop50Settings = {
      name: draft.name.trim(), scenario: draft.scenario as DemoScenario, basePrice: Number(draft.basePrice),
      trendPerDay: Number(draft.trendPerDay), volatilityPct: Number(draft.volatilityPct), baseVolume: Number(draft.baseVolume),
      testPattern: draft.testPattern as IndicatorTestPattern,
    };
    if (!input.name || Object.values(input).some((value) => typeof value === 'number' && !Number.isFinite(value))) return;
    setSaved(null); mutation.mutate({ symbol, input });
  };

  return (
    <Screen>
      <View style={{ gap: 4 }}><AppText variant="title">데모 TOP 50</AppText><AppText tone="muted">공급사 데이터 10종목은 확인하고, 합성 데이터 40종목은 직접 바꿀 수 있습니다.</AppText></View>
      <Surface style={styles.metrics}><Metric label="전체" value={`${data.length}종목`} /><Metric label="공급사 데이터" value={`${providers}종목`} /><Metric label="설정 가능" value={`${synthetic}종목`} tone="accent" /></Surface>
      <Banner tone="accent" title="저장하면 해당 종목의 일봉 전체를 즉시 다시 계산합니다" body="저장 후 Worker를 한 번 실행하면 바뀐 데이터로 전략 조건을 다시 평가할 수 있습니다." />
      {saved ? <Banner tone="positive" title="저장 완료" body={saved} /> : null}
      {mutation.error ? <Banner tone="negative" title="저장 실패" body={mutation.error instanceof Error ? mutation.error.message : '입력값을 확인해 주세요.'} /> : null}
      <View style={styles.wrap}>
        <Chip label={`전체 ${data.length}`} selected={filter === 'ALL'} onPress={() => setFilter('ALL')} />
        <Chip label={`실데이터 ${providers}`} selected={filter === 'PROVIDER'} onPress={() => setFilter('PROVIDER')} />
        <Chip label={`합성 ${synthetic}`} selected={filter === 'SYNTHETIC'} onPress={() => setFilter('SYNTHETIC')} />
      </View>
      {query.isPending ? <LoadingState label="TOP 50 데이터 불러오는 중" /> : null}
      {query.isError ? <ErrorState onRetry={() => void query.refetch()} /> : null}
      {!query.isPending && !query.isError ? <SectionTitle title={`종목 ${visible.length}개`} /> : null}
      {visible.map((item) => {
        const open = editing === item.symbol;
        return <Surface key={item.symbol} style={{ gap: spacing.sm }}>
          <View style={styles.header}>
            <View style={{ flex: 1, gap: 3 }}><AppText variant="bodyStrong">{item.name}</AppText><AppText variant="caption" tone="muted">{item.symbol} · {item.candleCount}일 · {item.firstDate || '-'} ~ {item.lastDate || '-'}</AppText></View>
            <Chip label={item.editable ? '합성 · 설정 가능' : '공급사 · 읽기 전용'} selected={item.editable} />
          </View>
          <Divider />
          {item.candleCount > 0 ? <>
            <View style={styles.metrics}><Metric label="최근 종가" value={won(item.latestClose)} tone="accent" /><Metric label="시가" value={won(item.latestOpen)} /><Metric label="거래량" value={count(item.latestVolume)} /></View>
            <AppText variant="caption" tone="muted">고가 {won(item.latestHigh)} · 저가 {won(item.latestLow)} · 공급자 {item.provider || '-'}</AppText>
          </> : <Banner title="일봉 데이터가 없습니다" body="데모 데이터 준비 명령을 먼저 실행하세요." />}
          {item.editable && !open ? <Button label="이 종목 설정" kind="secondary" compact onPress={() => { setEditing(item.symbol); setDraft(draftOf(item)); setSaved(null); }} /> : null}
          {item.editable && open && draft ? <View style={{ gap: spacing.sm }}>
            <Divider />
            <Field label="표시 종목명" value={draft.name} onChangeText={(name) => setDraft({ ...draft, name })} />
            <AppText variant="caption" tone="muted">움직임 시나리오</AppText>
            <View style={styles.wrap}>{scenarios.map((scenario) => <Chip key={scenario.id} label={scenario.label} selected={draft.scenario === scenario.id} onPress={() => setDraft({ ...draft, scenario: scenario.id })} />)}</View>
            <AppText variant="caption" tone="muted">지표 테스트 패턴 · 마지막 날 false → true 전환을 생성합니다</AppText>
            <View style={styles.wrap}>{testPatterns.map((pattern) => <Chip key={pattern.id} label={pattern.label} selected={draft.testPattern === pattern.id} onPress={() => setDraft({ ...draft, testPattern: pattern.id })} />)}</View>
            {draft.testPattern !== 'NONE' ? <Banner tone="accent" title="테스트 패턴 우선 적용" body="일반 시나리오로 전체 일봉을 만든 뒤 마지막 80개 일봉을 조정하고, 선택하지 않은 지표가 함께 충족되지 않는지도 검증합니다." /> : null}
            <View style={styles.twoColumns}>
              <Field label="기준가(원)" keyboardType="numeric" value={draft.basePrice} onChangeText={(basePrice) => setDraft({ ...draft, basePrice })} style={styles.field} />
              <Field label="일별 추세(원)" keyboardType="numbers-and-punctuation" value={draft.trendPerDay} onChangeText={(trendPerDay) => setDraft({ ...draft, trendPerDay })} style={styles.field} />
            </View>
            <View style={styles.twoColumns}>
              <Field label="변동성(0.03 = 3%)" keyboardType="decimal-pad" value={draft.volatilityPct} onChangeText={(volatilityPct) => setDraft({ ...draft, volatilityPct })} style={styles.field} />
              <Field label="기준 거래량" keyboardType="numeric" value={draft.baseVolume} onChangeText={(baseVolume) => setDraft({ ...draft, baseVolume })} style={styles.field} />
            </View>
            <View style={styles.wrap}><Button label="저장하고 일봉 재생성" compact busy={mutation.isPending} onPress={() => save(item.symbol)} /><Button label="취소" kind="ghost" compact disabled={mutation.isPending} onPress={() => { setEditing(null); setDraft(null); }} /></View>
          </View> : null}
        </Surface>;
      })}
    </Screen>
  );
}

const styles = StyleSheet.create({
  header: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  metrics: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.md },
  wrap: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs, alignItems: 'center' },
  twoColumns: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
  field: { minWidth: 180 },
});
