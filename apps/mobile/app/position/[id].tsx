import React from 'react';
import { router, useLocalSearchParams } from 'expo-router';
import { StyleSheet, View } from 'react-native';
import { AppText, Banner, Button, Chip, Divider, EmptyState, ListRow, Metric, Screen, SectionTitle, Surface, spacing } from '@signal/ui';
import { TitleBlock } from '@/components/common';
import { profitRate, replayExecutions, unrealizedProfit } from '@/domain/portfolio';
import { useAppStore } from '@/store/useAppStore';
import { formatDateTime, formatPrice, formatRate, formatWon } from '@/utils/format';

export default function PositionDetailScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const position = useAppStore((state) => state.positions.find((item) => item.id === id));
  if (!position) return <Screen><EmptyState title="포지션을 찾지 못했어요" body="보유 목록에서 다시 선택하세요." action="보유 목록" onAction={() => router.replace('/holdings')} /></Screen>;
  const rate = profitRate(position);
  const replay = replayExecutions(position.executions);
  const kindName = position.kind === 'MANUAL_LIVE' ? '실제 수동' : position.kind === 'SANDBOX_PAPER' ? '연습 페이퍼' : '공식 랭킹';
  const active = position.status !== 'CLOSED' && position.status !== 'ARCHIVED';
  const routeParams = { positionId: position.id, symbol: position.symbol, name: position.instrumentName, price: String(position.currentPrice) };
  return (
    <Screen>
      <TitleBlock eyebrow={`${kindName} · ${position.status}`} title={position.instrumentName} body={`${position.symbol} · 잔여 ${position.quantity}주`} />
      {position.kind === 'RANKED_PAPER' ? <Banner title="공식 랭킹 포지션" body="추가매수·부분매도·사용자 취소가 금지됩니다. 매도 신호 뒤 전량 자동 가상 주문합니다." /> : null}
      <Surface style={{ gap: spacing.md }}>
        <View style={styles.metrics}><Metric label="평가 손익" value={formatWon(unrealizedProfit(position))} tone={rate >= 0 ? 'positive' : 'negative'} /><Metric label="수익률" value={`${rate >= 0 ? '▲' : '▼'} ${formatRate(rate)}`} tone={rate >= 0 ? 'positive' : 'negative'} /></View>
        <Divider />
        <View style={styles.metrics}><Metric label="평균 단가" value={formatPrice(position.averagePrice)} /><Metric label="현재가" value={formatPrice(position.currentPrice)} /><Metric label="최고 종가" value={formatPrice(position.highestClose)} /></View>
      </Surface>

      {position.id === 'pos-051910' && active ? <Banner tone="warning" title="매도 조건 상태 알림 1건" body="기술 조건과 가격 보호 규칙 상태를 확인하세요. 자동 주문이 아닙니다." action="상세" onAction={() => router.push({ pathname: '/sell-signal/[id]', params: { id: position.id } })} /> : null}

      <SectionTitle title="매도 규칙" {...(active && position.kind !== 'RANKED_PAPER' ? { action: '수정', onAction: () => router.push({ pathname: '/sell-rule/[positionId]', params: { positionId: position.id } }) } : {})} />
      {position.sellRule ? (
        <Surface style={{ gap: spacing.sm }}>
          <View style={styles.row}><AppText variant="bodyStrong">규칙 v{position.sellRule.version}</AppText><Chip label={position.sellRule.manualOnly ? '수동 관리만' : '감시 중'} selected={!position.sellRule.manualOnly} /></View>
          {position.sellRule.manualOnly ? <Banner title="자동 매도 신호 없음" body="수량 변화는 실제 등록 또는 페이퍼 SELL 체결 뒤에만 반영됩니다." /> : <View style={styles.wrap}>{position.sellRule.stopLossPercent ? <Chip label={`손절 ${position.sellRule.stopLossPercent}%`} /> : null}{position.sellRule.takeProfitPercent ? <Chip label={`목표 +${position.sellRule.takeProfitPercent}%`} /> : null}{position.sellRule.trailingStopPercent ? <Chip label={`추적 ${position.sellRule.trailingStopPercent}%`} /> : null}{position.sellRule.maxHoldingDays ? <Chip label={`최대 ${position.sellRule.maxHoldingDays}일`} /> : null}{position.sellRule.technicalIds.map((item) => <Chip key={item} label={item.toUpperCase()} />)}</View>}
          <AppText variant="caption" tone="muted">보호 규칙은 OR · 기술 그룹 안에서 {position.sellRule.technicalMode}</AppText>
        </Surface>
      ) : <EmptyState title="매도 규칙이 없어요" body="수치를 몰래 켜지 않습니다. 수동 관리 또는 자동 감시 규칙을 직접 확인하세요." action="규칙 만들기" onAction={() => router.push({ pathname: '/sell-rule/[positionId]', params: { positionId: position.id } })} />}

      {active && position.kind !== 'RANKED_PAPER' ? (
        <View style={styles.actions}>
          <Button label="추가 매수" kind="secondary" onPress={() => router.push({ pathname: '/trade', params: { ...routeParams, mode: position.kind === 'MANUAL_LIVE' ? 'manual' : 'paper', side: 'BUY' } })} />
          <Button label="부분 · 전량 매도" onPress={() => router.push({ pathname: '/trade', params: { ...routeParams, mode: position.kind === 'MANUAL_LIVE' ? 'manual' : 'paper', side: 'SELL' } })} />
        </View>
      ) : null}

      <SectionTitle title="체결 원장" />
      <Surface style={{ paddingVertical: 0 }}>
        {position.executions.map((execution, index) => (
          <React.Fragment key={execution.id}>
            <ListRow title={`${execution.side} ${execution.quantity}주 · ${formatPrice(execution.price)}`} subtitle={`${formatDateTime(execution.executedAt)} · 비용 ${formatWon(-(execution.fee + execution.tax))} · ${execution.memo}`} value={execution.side === 'BUY' ? '+' : '-'} />
            {index < position.executions.length - 1 ? <Divider /> : null}
          </React.Fragment>
        ))}
      </Surface>
      <Surface style={{ gap: spacing.sm }}><View style={styles.row}><AppText tone="muted">실현 손익</AppText><AppText variant="bodyStrong" tone={replay.realizedProfit >= 0 ? 'positive' : 'negative'}>{formatWon(replay.realizedProfit)}</AppText></View><AppText variant="caption" tone="muted">이동가중평균법 v1 · 집계값은 append-only 체결 원장에서 재계산</AppText></Surface>
      {!active ? <Banner tone="positive" title="전량매도 · 감시 종료" body="활성 매도 신호와 미발송 알림을 취소했습니다. 재매수 시 새 포지션이 생깁니다." /> : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', gap: spacing.sm },
  metrics: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.md },
  wrap: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs },
  actions: { gap: spacing.sm },
});
