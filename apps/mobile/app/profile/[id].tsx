import React, { useState } from 'react';
import { router, useLocalSearchParams } from 'expo-router';
import { useQuery } from '@tanstack/react-query';
import { AppText, Banner, Button, Chip, EmptyState, ErrorState, LoadingState, Metric, Screen, SectionTitle, Surface, spacing } from '@signal/ui';
import { TitleBlock } from '@/components/common';
import { copyRemotePublicStrategy, loadRemoteRankings } from '@/services/connected-api';
import { useRemoteApiReady } from '@/hooks/useRemoteApiReady';
import { useAppStore } from '@/store/useAppStore';
import { formatRate } from '@/utils/format';
import { Alert, StyleSheet, View } from 'react-native';

export default function ProfileScreen() {
  const remoteApiReady = useRemoteApiReady();
  const upsertRemoteStrategy = useAppStore((state) => state.upsertRemoteStrategy);
  const [copyingId, setCopyingId] = useState<string | null>(null);
  const { id, period: rawPeriod } = useLocalSearchParams<{ id: string; period?: string }>();
  const period = rawPeriod === '6M' || rawPeriod === '1Y' ? rawPeriod : '3M';
  const key = period.toLowerCase() as '3m' | '6m' | '1y';
  const result = useQuery({ queryKey: ['rankings', period], queryFn: () => loadRemoteRankings(period) });
  if (result.isPending) return <Screen><LoadingState label="공개 프로필 조회 중" /></Screen>;
  if (result.isError) return <Screen><ErrorState onRetry={() => void result.refetch()} /></Screen>;
  const user = result.data.users.find((entry) => entry.id === id);
  if (!user) return <Screen><EmptyState title="공개 프로필이 없습니다" body="공개 동의가 철회됐거나 기간 조건을 충족하지 않았습니다." action="랭킹으로" onAction={() => router.replace('/(tabs)/rankings')} /></Screen>;
  const copyStrategy = async (strategyId: string) => {
    if (!remoteApiReady) return Alert.alert('로그인이 필요합니다', '전략 복사는 로그인 후 사용할 수 있습니다.');
    setCopyingId(strategyId);
    try {
      const strategy = await copyRemotePublicStrategy(user.id, strategyId);
      upsertRemoteStrategy(strategy);
      Alert.alert('전략을 복사했습니다', '내 전략에 비공개 복사본을 만들었습니다.');
      router.push({ pathname: '/strategy/[id]', params: { id: strategy.id } });
    } catch (caught) {
      Alert.alert('전략 복사 실패', caught instanceof Error ? caught.message : '잠시 뒤 다시 시도하세요.');
    } finally {
      setCopyingId(null);
    }
  };
  return (
    <Screen>
      <TitleBlock eyebrow={`${period} · ${user.rank}위`} title={user.nickname} body={`${user.days}일 기록 · 사용자 작성 실제 매매`} />
      <Surface><View style={styles.metrics}><Metric label="기간 실현수익률" value={formatRate(user.returnRate[key])} tone={user.returnRate[key] >= 0 ? 'positive' : 'negative'} /><Metric label="매매 입력" value={`${user.trades}회`} /></View></Surface>
      <Banner title="사용자 작성 기록" body="매도 기록이 없으면 수익률은 0%입니다. 증권사 인증 내역이 아니므로 참고용으로만 확인하세요." />
      <SectionTitle title={`공개 전략 ${user.strategies.length}개`} />
      <Banner tone="accent" title="이 사용자가 공개한 전략입니다" body="랭킹 수익률에 실제로 사용된 전략과 다를 수 있습니다." />
      {user.strategies.length === 0 ? <EmptyState title="공개한 전략이 없습니다" body="이 사용자가 전략을 공개하면 여기에 표시됩니다." /> : user.strategies.map((strategy) => (
        <Surface key={strategy.id} style={{ gap: spacing.sm }}>
          <View style={styles.strategyHeader}><View style={{ flex: 1 }}><AppText variant="bodyStrong">{strategy.name}</AppText><AppText variant="caption" tone="muted">v{strategy.version} · {strategy.conditionMode === 'ALL' ? '모든 조건 충족' : '하나 이상 충족'}</AppText></View><Chip label={strategy.universeId} /></View>
          <View style={styles.chips}>{strategy.indicatorIds.map((indicator) => <Chip key={indicator} label={indicator.toUpperCase()} />)}</View>
          <Button label={copyingId === strategy.id ? '복사 중…' : '이 전략 복사하기'} kind="secondary" compact disabled={copyingId !== null || !remoteApiReady} onPress={() => { void copyStrategy(strategy.id); }} />
        </Surface>
      ))}
    </Screen>
  );
}

const styles = StyleSheet.create({
  metrics: { flexDirection: 'row', gap: spacing.md, flexWrap: 'wrap' },
  strategyHeader: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs },
});
