import React, { useMemo } from 'react';
import { router, useLocalSearchParams } from 'expo-router';
import { Alert, StyleSheet, View } from 'react-native';
import { AppText, Banner, Button, Chip, EmptyState, Screen, SectionTitle, Surface, spacing } from '@signal/ui';
import { PositionCard, TitleBlock } from '@/components/common';
import type { PortfolioKind } from '@/domain/types';
import { cancelRemotePaperOrder } from '@/services/connected-api';
import { useRemoteApiReady } from '@/hooks/useRemoteApiReady';
import { useAppStore } from '@/store/useAppStore';
import { formatDateTime, formatPrice } from '@/utils/format';

export default function HoldingsScreen() {
  const remoteApiReady = useRemoteApiReady();
  const params = useLocalSearchParams<{ kind?: PortfolioKind }>();
  const positions = useAppStore((state) => state.positions);
  const orders = useAppStore((state) => state.orders);
  const sandboxCash = useAppStore((state) => state.sandboxCash);
  const fillOrder = useAppStore((state) => state.fillOrder);
  const cancelOrder = useAppStore((state) => state.cancelOrder);
  const shown = useMemo(() => positions.filter((item) => item.status !== 'CLOSED' && (!params.kind || item.kind === params.kind)), [params.kind, positions]);
  const pending = orders.filter((item) => item.status === 'PENDING' && (!params.kind || item.kind === params.kind));
  const reservedCash = pending.filter((item) => item.side === 'BUY').reduce((sum, item) => sum + item.reservedAmount, 0);
  const title = params.kind === 'MANUAL_LIVE' ? '실제 수동 보유' : params.kind === 'SANDBOX_PAPER' ? '연습 페이퍼' : '모든 포지션';
  return (
    <Screen>
      <TitleBlock title={title} body="실제 수동 · 연습 · 공식 랭킹 원장은 섞이지 않습니다." />
      {params.kind === 'SANDBOX_PAPER' ? <Banner tone="accent" title={`가용 현금 ${formatPrice(sandboxCash - reservedCash)}`} body={`현금 ${formatPrice(sandboxCash)} · BUY 예약 ${formatPrice(reservedCash)}`} /> : null}
      {params.kind === 'MANUAL_LIVE' ? <Button label="신호 없이 실제 보유 등록" onPress={() => router.push({ pathname: '/trade', params: { mode: 'manual', side: 'BUY' } })} /> : null}
      {pending.length ? <SectionTitle title={`다음 시가 대기 ${pending.length}건`} /> : null}
      {pending.map((order) => (
        <Surface key={order.id} style={{ gap: spacing.sm }}>
          <View style={styles.row}><View><AppText variant="bodyStrong">{order.instrumentName} {order.side}</AppText><AppText variant="caption" tone="muted">{order.quantity}주 · 추정 {formatPrice(order.estimatedPrice)}</AppText></View><Chip label="PENDING" tone="warning" /></View>
          <Banner title={`${order.scheduledSession} 공식 시가`} body={remoteApiReady ? 'Worker가 다음 거래 세션을 처리합니다. 화면은 API 상태를 자동 갱신합니다.' : '아래 QA 버튼은 Worker의 다음 거래 세션 실행을 로컬에서 재현합니다. 입력한 추정 단가는 체결가로 쓰지 않습니다.'} />
          <View style={styles.actions}>
            {remoteApiReady ? null : <Button label="Mock D+1 체결" compact onPress={() => fillOrder(order.id)} />}
            <Button label="주문 취소" kind="ghost" compact onPress={() => {
              if (!remoteApiReady) return cancelOrder(order.id);
              void cancelRemotePaperOrder(order.id)
                .then(() => cancelOrder(order.id))
                .catch((reason: unknown) => Alert.alert('취소 실패', reason instanceof Error ? reason.message : '다시 시도하세요.'));
            }} />
          </View>
          <AppText variant="caption" tone="muted">접수 {formatDateTime(order.createdAt)} · 불리한 slippage/호가 반올림/비용 적용</AppText>
        </Surface>
      ))}
      <SectionTitle title={`열린 포지션 ${shown.length}개`} />
      {shown.length === 0 ? <EmptyState title="열린 포지션이 없어요" body="신호 상세에서 실제 보유를 등록하거나 연습 주문을 접수하세요." action="새 신호 보기" onAction={() => router.push('/(tabs)')} /> : shown.map((position) => <PositionCard key={position.id} position={position} onPress={() => router.push({ pathname: '/position/[id]', params: { id: position.id } })} />)}
      <Button label="종료 기록" kind="secondary" onPress={() => router.push('/history')} />
    </Screen>
  );
}

const styles = StyleSheet.create({ row: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.sm }, actions: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs } });
