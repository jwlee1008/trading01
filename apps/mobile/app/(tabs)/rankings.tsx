import React, { useState } from 'react';
import { router } from 'expo-router';
import { Pressable, StyleSheet, View } from 'react-native';
import { useQuery } from '@tanstack/react-query';
import { AppText, Banner, Chip, EmptyState, ErrorState, LoadingState, Metric, Screen, Surface, spacing, useSignalTheme } from '@signal/ui';
import { Segmented } from '@/components/common';
import { connectedApiEnabled, loadRemoteRankings } from '@/services/connected-api';
import { useUniverses } from '@/hooks/useUniverses';
import { formatRate } from '@/utils/format';

type RankingType = 'combination' | 'indicator' | 'user';
type Period = '3M' | '6M' | '1Y';

export default function RankingsScreen() {
  const { colors } = useSignalTheme();
  const [type, setType] = useState<RankingType>('combination');
  const [period, setPeriod] = useState<Period>('3M');
  const universes = useUniverses().data ?? [];
  const result = useQuery({ queryKey: ['rankings', period], queryFn: () => loadRemoteRankings(period), enabled: connectedApiEnabled, retry: 2 });
  const periodKey = period.toLowerCase() as '3m' | '6m' | '1y';

  if (!connectedApiEnabled) return <Screen><EmptyState title="API 연결이 필요합니다" body="공개 랭킹을 조회할 API URL을 설정하세요." /></Screen>;
  if (result.isPending) return <Screen><LoadingState label="랭킹 snapshot 조회 중" /></Screen>;
  if (result.isError) return <Screen><ErrorState onRetry={() => void result.refetch()} /></Screen>;
  const data = result.data;

  return (
    <Screen>
      <Segmented options={[{ value: 'combination', label: '조합' }, { value: 'indicator', label: '지표 티어' }, { value: 'user', label: '사용자' }]} value={type} onChange={setType} />
      <Segmented options={[{ value: '3M', label: '3개월' }, { value: '6M', label: '6개월' }, { value: '1Y', label: '1년' }]} value={period} onChange={setPeriod} />
      <AppText variant="caption" tone="muted">기간 시작 {data.periodStart || '미확정'} · 기준 {data.asOf ? data.asOf.slice(0, 10) : 'snapshot 없음'} · 최소 {data.minimumTrades}회 체결</AppText>

      {type === 'combination' ? <>
        <Banner tone="accent" title="기간별 검증 결과" body={data.disclosure || '검증이 완료된 조합 snapshot만 표시합니다.'} />
        {data.combinations.length === 0 ? <EmptyState title="집계된 조합 랭킹이 없습니다" body="해당 기간의 검증 snapshot이 생성되면 표시됩니다." /> : data.combinations.map((item) => (
          <Pressable key={item.id} accessibilityRole="button" onPress={() => router.push({ pathname: '/ranking/[id]', params: { id: item.id, period } })}>
            <Surface style={{ gap: spacing.md }}>
              <View style={styles.row}><View style={[styles.rank, { backgroundColor: colors.accentSoft }]}><AppText variant="subtitle" tone="accent">{item.rank}</AppText></View><View style={{ flex: 1 }}><AppText variant="bodyStrong">{item.name}</AppText><AppText variant="caption" tone="muted">{universes.find((u) => u.id === item.universeId)?.name ?? item.universeId} · 신호 {item.signalCount}건</AppText></View><AppText variant="subtitle" tone={item.excessReturn[periodKey] >= 0 ? 'positive' : 'negative'}>{formatRate(item.excessReturn[periodKey])}</AppText></View>
              <View style={styles.metrics}><Metric label="적중률" value={`${item.hitRate}%`} /><Metric label="MDD" value={`${item.mdd}%`} tone="negative" /><Metric label="안정성" value={`${item.stability}/100`} /></View>
            </Surface>
          </Pressable>
        ))}
      </> : null}

      {type === 'indicator' ? <>
        <Banner title="미래 수익 예측이 아닙니다" body={data.indicatorDisclosure || '실제 검증 결과가 있는 지표만 표시합니다.'} />
        {data.indicatorTiers.length === 0 ? <EmptyState title="집계된 지표 티어가 없습니다" body="해당 기간의 지표 검증이 완료되면 표시됩니다." /> : data.indicatorTiers.map((item) => <Surface key={item.indicatorId} style={styles.row}><Chip label={item.tier || '미집계'} /><View style={{ flex: 1 }}><AppText variant="bodyStrong">{item.name}</AppText><AppText variant="caption" tone="muted">점수 {item.score}</AppText></View></Surface>)}
      </> : null}

      {type === 'user' ? <>
        <Banner tone="accent" title="공개 사용자 순위" body="공개 동의된 공식 페이퍼 트랙의 기간별 수익률로 정렬합니다." />
        {data.users.length === 0 ? <EmptyState title="공개 사용자 랭킹이 없습니다" body="해당 기간을 충족한 공개 트랙이 생기면 표시됩니다." /> : data.users.map((user) => <Pressable key={user.id} onPress={() => router.push({ pathname: '/profile/[id]', params: { id: user.id, period } })}><Surface style={styles.row}><AppText variant="subtitle">{user.rank}</AppText><View style={{ flex: 1 }}><AppText variant="bodyStrong">{user.nickname}</AppText><AppText variant="caption" tone="muted">{user.days}일 운용 · {user.trades}회</AppText></View><AppText variant="subtitle" tone={user.returnRate[periodKey] >= 0 ? 'positive' : 'negative'}>{formatRate(user.returnRate[periodKey])}</AppText></Surface></Pressable>)}
      </> : null}
    </Screen>
  );
}

const styles = StyleSheet.create({ row: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm }, rank: { width: 36, height: 36, borderRadius: 12, alignItems: 'center', justifyContent: 'center' }, metrics: { flexDirection: 'row', gap: spacing.md } });
