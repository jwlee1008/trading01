import React from 'react';
import { router } from 'expo-router';
import { Pressable, StyleSheet, View } from 'react-native';
import { AppText, Banner, Button, Chip, Divider, EmptyState, ErrorState, ListRow, LoadingState, Metric, Screen, SectionTitle, Surface, spacing, useSignalTheme } from '@signal/ui';
import { PositionCard, PriceChange, TitleBlock } from '@/components/common';
import { useUniverses } from '@/hooks/useUniverses';
import { useProviderHealth } from '@/hooks/useProviderHealth';
import { useAppStore } from '@/store/useAppStore';
import { formatDateTime } from '@/utils/format';

export default function HomeScreen() {
  const { colors } = useSignalTheme();
  const selectedUniverseId = useAppStore((state) => state.selectedUniverseId);
  const signals = useAppStore((state) => state.signals);
  const strategies = useAppStore((state) => state.strategies);
  const positions = useAppStore((state) => state.positions);
  const orders = useAppStore((state) => state.orders);
  const connectionMode = useAppStore((state) => state.connectionMode);
  const status = useProviderHealth();
  const universes = useUniverses().data ?? [];
  const universe = universes.find((item) => item.id === selectedUniverseId);
  const openPositions = positions.filter((item) => item.status !== 'CLOSED' && item.status !== 'ARCHIVED');
  const pendingOrders = orders.filter((item) => item.status === 'PENDING');
  const strategyUniverse = new Map(strategies.map((item) => [item.id, item.universeId]));
  const visibleSignals = signals.filter((item) => item.symbol.startsWith('TST') || strategyUniverse.get(item.strategyId) === selectedUniverseId);
  const visibleStrategies = strategies.filter((item) => item.name.startsWith('[테스트]') || item.universeId === selectedUniverseId);
  const latestCandle = visibleSignals[0]?.candleClose;

  if (status.isPending) return <Screen><LoadingState label="실제 데이터 상태 확인 중" /></Screen>;

  return (
    <Screen>
      <TitleBlock eyebrow={latestCandle ? `${formatDateTime(latestCandle)} 완성 일봉` : '최신 완성 일봉'} title={`전략 ${visibleStrategies.length}개 · 새 신호 ${visibleSignals.length}개`} body="저장한 전략과 현재 선택한 종목 범위의 신호를 함께 표시합니다. 조건 충족은 매수 추천이 아닙니다." />
      {connectionMode === 'offline' ? <Banner tone="negative" title="오프라인 상태" body="저장된 화면만 볼 수 있어요. 주문 전송은 막혔습니다." action="상태 확인" onAction={() => router.push('/provider-status')} /> : null}
      {status.data?.delayed ? <Banner title="데이터 지연" body={`마지막 일봉 ${status.data.lastCandleAt ?? '확인 불가'}`} action="자세히" onAction={() => router.push('/provider-status')} /> : null}
      {status.isError && connectionMode !== 'offline' ? <ErrorState onRetry={() => void status.refetch()} /> : null}

      <Surface style={styles.overview}>
        <View style={styles.row}>
          <View style={{ gap: 4 }}><AppText variant="caption" tone="muted">내 기본 종목 범위</AppText><AppText variant="subtitle">{universe?.name ?? '선택 안 됨'}</AppText></View>
          <Button label="바꾸기" kind="ghost" compact onPress={() => router.push({ pathname: '/universe', params: { origin: 'home' } })} />
        </View>
        <Divider />
        <View style={styles.metrics}>
          <Metric label="열린 포지션" value={`${openPositions.length}개`} />
          <Metric label="주문 대기" value={`${pendingOrders.length}건`} tone={pendingOrders.length ? 'warning' : 'default'} />
          <Metric label="안 읽은 신호" value={`${visibleSignals.filter((item) => !item.read).length}개`} tone="accent" />
        </View>
      </Surface>

      <SectionTitle title="내 전략" action={visibleStrategies.length ? '전체 보기' : '만들기'} onAction={() => router.navigate('/(tabs)/create')} />
      {visibleStrategies.length === 0 ? <EmptyState title="이 종목 범위에 저장된 전략이 없습니다" body="전략을 만들면 여기에서 저장 상태와 새 신호 발생 여부를 바로 확인할 수 있습니다." action="전략 만들기" onAction={() => router.navigate('/(tabs)/create')} /> : visibleStrategies.slice(0, 3).map((strategy) => {
        const signalCount = visibleSignals.filter((signal) => signal.strategyId === strategy.id).length;
        return (
          <Pressable key={strategy.id} accessibilityRole="button" onPress={() => router.push({ pathname: '/strategy/[id]', params: { id: strategy.id } })}>
            <Surface style={styles.row}>
              <View style={{ flex: 1, gap: 4 }}><AppText variant="bodyStrong">{strategy.name}</AppText><AppText variant="caption" tone="muted">v{strategy.version} · {strategy.indicatorIds.length}개 조건 · 새 신호 {signalCount}건</AppText></View>
              <Chip label={strategy.alertEnabled ? '평가 대상' : '알림 꺼짐'} selected={strategy.alertEnabled} />
            </Surface>
          </Pressable>
        );
      })}

      <SectionTitle title="새 신호" action="알림 전체" onAction={() => router.navigate('/(tabs)/alerts')} />
      {visibleSignals.length === 0 ? <EmptyState title={visibleStrategies.length ? '저장된 전략에 새 신호가 없습니다' : '아직 신호가 없습니다'} body="실제 완성 일봉에서 조건이 false → true로 바뀐 경우에만 신호가 생성됩니다." action="전략 보기" onAction={() => router.navigate('/(tabs)/create')} /> : visibleSignals.map((signal) => (
        <Pressable key={signal.id} accessibilityRole="button" accessibilityLabel={`${signal.instrumentName} 신호 상세`} onPress={() => router.push({ pathname: '/signal/[id]', params: { id: signal.id } })}>
          <Surface style={{ gap: spacing.sm }}>
            <View style={styles.row}>
              <View style={{ gap: 4 }}><View style={{ flexDirection: 'row', gap: spacing.xs, alignItems: 'center' }}><AppText variant="bodyStrong">{signal.instrumentName}</AppText>{!signal.read ? <View style={[styles.unread, { backgroundColor: colors.accent }]} /> : null}</View><AppText variant="caption" tone="muted">{signal.symbol} · {formatDateTime(signal.createdAt)}</AppText></View>
              <PriceChange price={signal.closePrice} change={signal.changeRate} />
            </View>
            <View style={styles.wrap}>{signal.reasons.slice(0, 2).map((reason) => <Chip key={reason} label={reason} selected />)}</View>
            {signal.delayed ? <Banner title="이 신호에 데이터 지연 표시가 있어요" /> : null}
          </Surface>
        </Pressable>
      ))}

      <SectionTitle title="보유 포지션" action="전체" onAction={() => router.push('/holdings')} />
      {openPositions.slice(0, 2).map((position) => <PositionCard key={position.id} position={position} onPress={() => router.push({ pathname: '/position/[id]', params: { id: position.id } })} />)}

      <SectionTitle title="빠른 메뉴" />
      <Surface style={{ paddingVertical: 0 }}>
        <ListRow title="신호 없이 실제 보유 등록" subtitle="앱 밖에서 산 종목도 직접 기록" onPress={() => router.push({ pathname: '/trade', params: { mode: 'manual', side: 'BUY' } })} />
        <Divider />
        <ListRow title="종목 검색 · 관심 종목" subtitle="내 종목 목록 범위에 사용" onPress={() => router.push('/watchlist')} />
        <Divider />
        <ListRow title="데이터 공급 상태" subtitle={status.data ? `마지막 일봉 ${status.data.lastCandleAt ?? '없음'}` : '연결 확인 필요'} onPress={() => router.push('/provider-status')} />
      </Surface>
    </Screen>
  );
}

const styles = StyleSheet.create({
  overview: { gap: spacing.md },
  row: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.sm },
  metrics: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.md },
  wrap: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs },
  unread: { width: 7, height: 7, borderRadius: 4 },
});
