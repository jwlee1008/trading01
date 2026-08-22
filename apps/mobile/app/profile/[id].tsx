import React from 'react';
import { router, useLocalSearchParams } from 'expo-router';
import { useQuery } from '@tanstack/react-query';
import { EmptyState, ErrorState, LoadingState, Metric, Screen, Surface, spacing } from '@signal/ui';
import { TitleBlock } from '@/components/common';
import { loadRemoteRankings } from '@/services/connected-api';
import { formatRate } from '@/utils/format';
import { StyleSheet, View } from 'react-native';

export default function ProfileScreen() {
  const { id, period: rawPeriod } = useLocalSearchParams<{ id: string; period?: string }>();
  const period = rawPeriod === '6M' || rawPeriod === '1Y' ? rawPeriod : '3M';
  const key = period.toLowerCase() as '3m' | '6m' | '1y';
  const result = useQuery({ queryKey: ['rankings', period], queryFn: () => loadRemoteRankings(period) });
  if (result.isPending) return <Screen><LoadingState label="공개 프로필 조회 중" /></Screen>;
  if (result.isError) return <Screen><ErrorState onRetry={() => void result.refetch()} /></Screen>;
  const user = result.data.users.find((entry) => entry.id === id);
  if (!user) return <Screen><EmptyState title="공개 프로필이 없습니다" body="공개 동의가 철회됐거나 기간 조건을 충족하지 않았습니다." action="랭킹으로" onAction={() => router.replace('/(tabs)/rankings')} /></Screen>;
  return <Screen><TitleBlock eyebrow={`${period} · ${user.rank}위`} title={user.nickname} body={`${user.days}일 운용 · ${user.strategyName}`} /><Surface><View style={styles.metrics}><Metric label="기간 수익률" value={formatRate(user.returnRate[key])} tone={user.returnRate[key] >= 0 ? 'positive' : 'negative'} /><Metric label="MDD" value={`${user.mdd}%`} tone="negative" /><Metric label="완료 거래" value={`${user.trades}회`} /></View></Surface></Screen>;
}

const styles = StyleSheet.create({ metrics: { flexDirection: 'row', gap: spacing.md, flexWrap: 'wrap' } });
