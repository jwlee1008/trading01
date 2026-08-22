import React, { useState } from 'react';
import { router, useLocalSearchParams } from 'expo-router';
import { Alert, StyleSheet, View } from 'react-native';
import { AppText, Banner, Button, Chip, Divider, EmptyState, ListRow, Metric, Screen, SectionTitle, Surface, spacing } from '@signal/ui';
import { TitleBlock } from '@/components/common';
import { combinations, indicators, universes } from '@/data/mock';
import { copyRemoteCombination } from '@/services/connected-api';
import { useRemoteApiReady } from '@/hooks/useRemoteApiReady';
import { useAppStore } from '@/store/useAppStore';
import { formatRate } from '@/utils/format';

export default function RankingDetailScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const item = combinations.find((entry) => entry.id === id);
  const cloneStrategy = useAppStore((state) => state.cloneStrategy);
  const upsertRemoteStrategy = useAppStore((state) => state.upsertRemoteStrategy);
  const remoteApiReady = useRemoteApiReady();
  const [copying, setCopying] = useState(false);
  if (!item) return <Screen><EmptyState title="랭킹 조합을 찾지 못했어요" body="새 snapshot에서 제외됐을 수 있어요." action="랭킹" onAction={() => router.replace('/(tabs)/rankings')} /></Screen>;
  const clone = async () => {
    setCopying(true);
    try {
      let strategyId: string;
      if (remoteApiReady) {
        const strategy = await copyRemoteCombination(item.id);
        upsertRemoteStrategy(strategy);
        strategyId = strategy.id;
      } else {
        strategyId = cloneStrategy(item.name, item.universeId, item.indicatorIds);
      }
      Alert.alert('내 전략으로 복사했어요', '범위·지표·값을 자유롭게 바꿀 수 있습니다. 복사본은 공식 랭킹 트랙과 연결되지 않습니다.');
      router.replace({ pathname: '/strategy/[id]', params: { id: strategyId } });
    } catch (caught) {
      Alert.alert('전략 복사 실패', caught instanceof Error ? caught.message : '잠시 뒤 다시 시도하세요.');
    } finally {
      setCopying(false);
    }
  };
  return (
    <Screen>
      <TitleBlock eyebrow={`${item.rank}위 · 신호 후 성과`} title={item.name} body={`${universes.find((entry) => entry.id === item.universeId)?.name} · 비용·벤치마크 차감`} />
      <Surface style={{ gap: spacing.md }}><View style={styles.metrics}><Metric label="3개월 초과" value={formatRate(item.excessReturn['3m'])} tone="positive" /><Metric label="6개월 초과" value={formatRate(item.excessReturn['6m'])} tone="positive" /><Metric label="1년 초과" value={formatRate(item.excessReturn['1y'])} tone="positive" /></View><Divider /><View style={styles.metrics}><Metric label="적중률" value={`${item.hitRate}%`} /><Metric label="MDD" value={`${item.mdd}%`} tone="negative" /><Metric label="안정성" value={`${item.stability}/100`} /></View></Surface>
      <Banner title={item.signalCount < 30 ? '데이터 부족' : '신뢰 구간'} body={`${item.confidence} · 완료된 신호만 포함`} />
      <SectionTitle title="조건 구성" />
      <Surface style={{ paddingVertical: 0 }}>{item.indicatorIds.map((indicatorId, index) => { const indicator = indicators.find((entry) => entry.id === indicatorId); return indicator ? <React.Fragment key={indicator.id}><ListRow title={indicator.name} subtitle={indicator.defaultRule} value={indicator.tier} onPress={() => router.push({ pathname: '/indicator/[id]', params: { id: indicator.id } })} />{index < item.indicatorIds.length - 1 ? <Divider /> : null}</React.Fragment> : null; })}</Surface>
      <SectionTitle title="검증 정보" />
      <Surface style={{ paddingVertical: 0 }}><ListRow title="신호·종목 수" subtitle={`${item.signalCount}개 신호 · ${item.instrumentCount}개 종목`} /><Divider /><ListRow title="체결 가정" subtitle="t일 종가 신호 · t+1 공식 시가 · 비용 차감" /><Divider /><ListRow title="평가 구간" subtitle="5 · 20 · 60 거래일, 중복 신호 제외" /><Divider /><ListRow title="검증" subtitle="훈련/검증/홀드아웃 · 롤링 워크포워드" /></Surface>
      <View style={styles.wrap}><Chip label="dataset krx-mock-v4" /><Chip label="indicator v2" /><Chip label="engine v1.4" /><Chip label="cost krx-v3" /></View>
      <Button label={copying ? '복사 중…' : '내 전략으로 복사'} onPress={() => { void clone(); }} disabled={copying} />
      <AppText variant="caption" tone="muted" style={{ textAlign: 'center' }}>백테스트와 페이퍼 성과는 미래 성과를 보장하지 않습니다.</AppText>
    </Screen>
  );
}

const styles = StyleSheet.create({ metrics: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.md }, wrap: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs } });
