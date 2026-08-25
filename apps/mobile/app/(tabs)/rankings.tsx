import React, { useState } from 'react';
import { router } from 'expo-router';
import { Pressable, StyleSheet, View } from 'react-native';
import { useQuery } from '@tanstack/react-query';
import { AppText, Banner, EmptyState, ErrorState, LoadingState, Screen, Surface, spacing } from '@signal/ui';
import { Segmented } from '@/components/common';
import { connectedApiEnabled, loadRemoteRankings } from '@/services/connected-api';
import { formatRate } from '@/utils/format';

type Period = '3M' | '6M' | '1Y';

export default function RankingsScreen() {
  const [period, setPeriod] = useState<Period>('3M');
  const result = useQuery({ queryKey: ['rankings', period], queryFn: () => loadRemoteRankings(period), enabled: connectedApiEnabled, retry: 2 });
  const periodKey = period.toLowerCase() as '3m' | '6m' | '1y';

  if (!connectedApiEnabled) return <Screen><EmptyState title="API 연결이 필요합니다" body="공개 랭킹을 조회할 API URL을 설정하세요." /></Screen>;
  if (result.isPending) return <Screen><LoadingState label="랭킹 snapshot 조회 중" /></Screen>;
  if (result.isError) return <Screen><ErrorState onRetry={() => void result.refetch()} /></Screen>;
  const data = result.data;

  return (
    <Screen>
      <Segmented options={[{ value: '3M', label: '3개월' }, { value: '6M', label: '6개월' }, { value: '1Y', label: '1년' }]} value={period} onChange={setPeriod} />
      <AppText variant="caption" tone="muted">기간 시작 {data.periodStart || '미확정'} · 기준 {data.asOf ? data.asOf.slice(0, 10) : '매매 기록 없음'} · 최소 {data.minimumTrades}회 입력</AppText>
      <Banner tone="accent" title="사용자 작성 실제 매매 순위" body={data.disclosure} />
      {data.users.length === 0 ? <EmptyState title="공개 사용자 랭킹이 없습니다" body="공개 사용자가 실제 매매 기록을 한 번 입력하면 표시됩니다." /> : data.users.map((user) => <Pressable key={user.id} onPress={() => router.push({ pathname: '/profile/[id]', params: { id: user.id, period } })}><Surface style={styles.row}><AppText variant="subtitle">{user.rank}</AppText><View style={{ flex: 1 }}><AppText variant="bodyStrong">{user.nickname}</AppText><AppText variant="caption" tone="muted">{user.days}일 기록 · 입력 {user.trades}회</AppText></View><AppText variant="subtitle" tone={user.returnRate[periodKey] >= 0 ? 'positive' : 'negative'}>{formatRate(user.returnRate[periodKey])}</AppText></Surface></Pressable>)}
    </Screen>
  );
}

const styles = StyleSheet.create({ row: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm } });
