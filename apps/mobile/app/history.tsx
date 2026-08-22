import React, { useMemo } from 'react';
import { router } from 'expo-router';
import { AppText, Chip, EmptyState, Screen, Surface, spacing } from '@signal/ui';
import { useAppStore } from '@/store/useAppStore';
import { replayExecutions } from '@/domain/portfolio';
import { formatWon } from '@/utils/format';
import { View } from 'react-native';

export default function HistoryScreen() {
  const positions = useAppStore((state) => state.positions);
  const closed = useMemo(() => positions.filter((item) => item.status === 'CLOSED'), [positions]);
  return (
    <Screen>
      <AppText variant="title">종료 포지션</AppText>
      <AppText tone="muted">전량매도 시점에 활성 감시와 미발송 알림을 멈춥니다. 재매수는 새 포지션입니다.</AppText>
      {closed.length === 0 ? <EmptyState symbol="□" title="종료 기록이 없어요" body="전량매도한 포지션이 이곳에 남습니다." action="열린 포지션" onAction={() => router.push('/holdings')} /> : closed.map((position) => {
        const replay = replayExecutions(position.executions);
        return <Surface key={position.id} style={{ gap: spacing.sm }}><View style={{ flexDirection: 'row', justifyContent: 'space-between' }}><AppText variant="bodyStrong">{position.instrumentName}</AppText><Chip label="CLOSED" /></View><AppText tone={replay.realizedProfit >= 0 ? 'positive' : 'negative'} variant="subtitle">실현 {formatWon(replay.realizedProfit)}</AppText><AppText variant="caption" tone="muted">체결 {position.executions.length}건 · 잔여 0주 · 감시 종료</AppText></Surface>;
      })}
    </Screen>
  );
}
