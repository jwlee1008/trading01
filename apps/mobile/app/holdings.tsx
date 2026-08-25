import React, { useMemo } from 'react';
import { router, useLocalSearchParams } from 'expo-router';
import { Button, EmptyState, Screen, SectionTitle } from '@signal/ui';
import { PositionCard, TitleBlock } from '@/components/common';
import type { PortfolioKind } from '@/domain/types';
import { useAppStore } from '@/store/useAppStore';

export default function HoldingsScreen() {
  const params = useLocalSearchParams<{ kind?: PortfolioKind }>();
  const positions = useAppStore((state) => state.positions);
  const shown = useMemo(() => positions.filter((item) => item.status !== 'CLOSED' && (!params.kind || item.kind === params.kind)), [params.kind, positions]);
  const title = params.kind === 'MANUAL_LIVE' ? '실제 매매 기록' : '모든 포지션';
  return (
    <Screen>
      <TitleBlock title={title} body="앱 밖에서 체결한 실제 매매를 직접 기록합니다." />
      {params.kind === 'MANUAL_LIVE' ? <Button label="신호 없이 실제 보유 등록" onPress={() => router.push('/watchlist')} /> : null}
      <SectionTitle title={`열린 포지션 ${shown.length}개`} />
      {shown.length === 0 ? <EmptyState title="열린 포지션이 없어요" body="실제 매수 체결 내역을 직접 등록하세요." action="새 신호 보기" onAction={() => router.navigate('/(tabs)')} /> : shown.map((position) => <PositionCard key={position.id} position={position} onPress={() => router.push({ pathname: '/position/[id]', params: { id: position.id } })} />)}
      <Button label="종료 기록" kind="secondary" onPress={() => router.push('/history')} />
    </Screen>
  );
}
