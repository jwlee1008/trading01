import React from 'react';
import { Alert, Pressable, StyleSheet, TextInput, View } from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { AppText, Banner, Button, Chip, Divider, ErrorState, LoadingState, Screen, SectionTitle, Surface, spacing } from '@signal/ui';
import { TitleBlock } from '@/components/common';
import { indicators } from '@/data/catalog';
import type { IndicatorId } from '@/domain/types';
import { configureTestTop30, loadTestTop30 } from '@/services/connected-api';

const indicatorCodeToId = (code: string): IndicatorId | null => ({
  SMA: 'sma', EMA: 'ema', RSI: 'rsi', MACD: 'macd', BOLLINGER: 'bollinger',
  VOLUME_SPIKE: 'volume', STOCHASTIC: 'stochastic', ATR: 'atr', ADX: 'adx', OBV: 'obv',
}[code] as IndicatorId | undefined) ?? null;

type FixtureDraft = { name: string; indicatorIds: IndicatorId[] };
const defaults: FixtureDraft[] = Array.from({ length: 20 }, (_, index) => ({
  name: `합성 테스트 ${index + 1}`, indicatorIds: [],
}));

export default function TestTop30Screen() {
  const queryClient = useQueryClient();
  const query = useQuery({ queryKey: ['test-top30'], queryFn: () => loadTestTop30(), retry: false });
  const [fixtures, setFixtures] = React.useState<FixtureDraft[]>(defaults);
  const [saving, setSaving] = React.useState(false);
  React.useEffect(() => {
    if (query.data?.fixtures.length === 20) setFixtures(query.data.fixtures.map((item) => ({
      name: item.name,
      indicatorIds: item.indicatorIds.map(indicatorCodeToId).filter((id): id is IndicatorId => id !== null),
    })));
  }, [query.data]);
  if (query.isPending) return <Screen><LoadingState label="테스트 30종목 설정 조회 중" /></Screen>;
  if (query.isError) return <Screen><ErrorState onRetry={() => void query.refetch()} /></Screen>;
  const save = async () => {
    setSaving(true);
    try {
      await configureTestTop30(fixtures);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['test-top30'] }),
        queryClient.invalidateQueries({ queryKey: ['universe-versions'] }),
      ]);
      Alert.alert('테스트 유니버스 준비 완료', '실제 KOSPI Top 10과 지표 시나리오 20종목을 합친 30종목 버전을 만들었습니다.');
    } catch (caught) {
      Alert.alert('설정 실패', caught instanceof Error ? caught.message : '잠시 뒤 다시 시도하세요.');
    } finally {
      setSaving(false);
    }
  };
  return (
    <Screen>
      <TitleBlock title="테스트 TOP 30 구성" body="키움 실제 KOSPI Top 10에 지표 조건을 의도적으로 만드는 합성 종목 20개를 더합니다." />
      {query.data.top10.length !== 10
        ? <Banner tone="warning" title="KOSPI Top 10 준비 필요" body="Worker가 Top 10 선정과 1년치 일봉 수집을 완료한 뒤 저장할 수 있습니다." />
        : <Banner tone="accent" title="실제 Top 10 준비됨" body={`${query.data.top10.map((item) => item.name).join(', ')}`} />}
      <SectionTitle title="합성 종목 이름과 신호 지표" />
      <Banner title="지표는 선택 사항" body="종목마다 지표를 선택하지 않거나 여러 개 선택할 수 있습니다. 선택한 지표에 맞춰 1년 분량의 합성 OHLCV를 다시 만듭니다." />
      <Surface style={{ paddingVertical: 0 }}>
        {fixtures.map((fixture, slot) => (
          <React.Fragment key={slot}>
            <View style={styles.slot}>
              <AppText variant="bodyStrong">TST{String(slot + 1).padStart(3, '0')}</AppText>
              <TextInput
                accessibilityLabel={`TST${String(slot + 1).padStart(3, '0')} 종목 이름`}
                value={fixture.name}
                maxLength={40}
                placeholder="합성 종목 이름"
                placeholderTextColor="#7f8ca3"
                style={styles.input}
                onChangeText={(name) => setFixtures((current) => current.map((item, index) => index === slot ? { ...item, name } : item))}
              />
              <View style={styles.chips}>
                {indicators.map((indicator) => (
                  <Pressable key={indicator.id} onPress={() => setFixtures((current) => current.map((item, index) => {
                    if (index !== slot) return item;
                    const selected = item.indicatorIds.includes(indicator.id);
                    return { ...item, indicatorIds: selected
                      ? item.indicatorIds.filter((id) => id !== indicator.id)
                      : [...item.indicatorIds, indicator.id] };
                  }))}>
                    <Chip label={indicator.name} selected={fixture.indicatorIds.includes(indicator.id)} />
                  </Pressable>
                ))}
              </View>
            </View>
            {slot < fixtures.length - 1 ? <Divider /> : null}
          </React.Fragment>
        ))}
      </Surface>
      <Button label={saving ? '합성 데이터 생성 중…' : '30종목 테스트 유니버스 만들기'} disabled={saving || query.data.top10.length !== 10 || fixtures.some((item) => !item.name.trim())} onPress={() => { void save(); }} />
    </Screen>
  );
}

const styles = StyleSheet.create({
  slot: { gap: spacing.sm, paddingVertical: spacing.md },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs },
  input: { borderWidth: 1, borderColor: '#334155', borderRadius: 10, color: '#f8fafc', paddingHorizontal: spacing.md, paddingVertical: spacing.sm, fontSize: 16 },
});
