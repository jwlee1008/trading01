import React, { useState } from 'react';
import { router, useLocalSearchParams } from 'expo-router';
import { Alert, StyleSheet, View } from 'react-native';
import { useQuery } from '@tanstack/react-query';
import { AppText, Banner, Button, EmptyState, ErrorState, LoadingState, Metric, Screen, Surface, spacing } from '@signal/ui';
import { TitleBlock } from '@/components/common';
import { copyRemoteCombination, loadRemoteRankings } from '@/services/connected-api';
import { useAppStore } from '@/store/useAppStore';
import { formatRate } from '@/utils/format';

export default function RankingDetailScreen() {
  const { id, period: rawPeriod } = useLocalSearchParams<{ id: string; period?: string }>();
  const period = rawPeriod === '6M' || rawPeriod === '1Y' ? rawPeriod : '3M';
  const periodKey = period.toLowerCase() as '3m' | '6m' | '1y';
  const result = useQuery({ queryKey: ['rankings', period], queryFn: () => loadRemoteRankings(period) });
  const upsert = useAppStore((state) => state.upsertRemoteStrategy);
  const [copying, setCopying] = useState(false);
  if (result.isPending) return <Screen><LoadingState label="랭킹 상세 조회 중" /></Screen>;
  if (result.isError) return <Screen><ErrorState onRetry={() => void result.refetch()} /></Screen>;
  const item = result.data.combinations.find((entry) => entry.id === id);
  if (!item) return <Screen><EmptyState title="랭킹 snapshot을 찾지 못했습니다" body="기간이 변경되었거나 공개가 종료된 결과입니다." action="랭킹으로" onAction={() => router.replace('/(tabs)/rankings')} /></Screen>;
  const copy = async () => {
    setCopying(true);
    try {
      const strategy = await copyRemoteCombination(item.id);
      upsert(strategy);
      Alert.alert('전략을 복사했습니다');
      router.push({ pathname: '/strategy/[id]', params: { id: strategy.id, saved: '1' } });
    } catch (caught) { Alert.alert('복사 실패', caught instanceof Error ? caught.message : '잠시 후 다시 시도하세요.'); }
    finally { setCopying(false); }
  };
  return <Screen>
    <TitleBlock eyebrow={`${period} · ${item.rank}위`} title={item.name} body="저장된 검증 snapshot의 결과입니다." />
    <Surface style={{ gap: spacing.md }}><View style={styles.metrics}><Metric label="초과수익" value={formatRate(item.excessReturn[periodKey])} tone={item.excessReturn[periodKey] >= 0 ? 'positive' : 'negative'} /><Metric label="적중률" value={`${item.hitRate}%`} /><Metric label="MDD" value={`${item.mdd}%`} tone="negative" /></View><AppText tone="muted">신호 {item.signalCount}건 · 종목 {item.instrumentCount}개 · 안정성 {item.stability}/100</AppText></Surface>
    <Banner title="과거 검증 결과" body={result.data.disclosure || '미래 수익을 보장하지 않습니다.'} />
    <Button label={copying ? '복사 중…' : '내 전략으로 복사'} busy={copying} onPress={() => void copy()} />
  </Screen>;
}

const styles = StyleSheet.create({ metrics: { flexDirection: 'row', gap: spacing.md, flexWrap: 'wrap' } });
