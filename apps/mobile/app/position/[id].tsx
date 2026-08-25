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
  const realizedProfit = position.realizedProfit ?? replay.realizedProfit;
  const kindName = '실제 매매';
  const active = position.status !== 'CLOSED' && position.status !== 'ARCHIVED';
  const routeParams = { positionId: position.id, symbol: position.symbol, name: position.instrumentName, price: String(position.currentPrice) };
  return (
    <Screen>
      <TitleBlock eyebrow={`${kindName} · ${position.status}`} title={position.instrumentName} body={`${position.symbol} · 잔여 ${position.quantity}주`} />
      <Surface style={{ gap: spacing.md }}>
        <View style={styles.metrics}><Metric label="평가 손익" value={position.marketPriceAvailable === false ? '계산 대기' : formatWon(unrealizedProfit(position))} tone={rate >= 0 ? 'positive' : 'negative'} /><Metric label="수익률" value={position.marketPriceAvailable === false ? '—' : `${rate >= 0 ? '▲' : '▼'} ${formatRate(rate)}`} tone={rate >= 0 ? 'positive' : 'negative'} /></View>
        <Divider />
        <View style={styles.metrics}><Metric label="평균 단가" value={formatPrice(position.averagePrice)} /><Metric label="현재가" value={formatPrice(position.currentPrice)} /><Metric label="최고 종가" value={formatPrice(position.highestClose)} /></View>
      </Surface>
      {active && position.marketPriceAvailable === false ? <Banner tone="warning" title="현재 시세가 없습니다" body="이 종목의 완성 일봉이 수집되면 평가손익과 수익률을 계산합니다. 매수·매도 체결 및 실현손익 기록은 그대로 유지됩니다." /> : null}

      {position.id === 'pos-051910' && active ? <Banner tone="warning" title="매도 조건 상태 알림 1건" body="기술 조건과 가격 보호 규칙 상태를 확인하세요. 자동 주문이 아닙니다." action="상세" onAction={() => router.push({ pathname: '/sell-signal/[id]', params: { id: position.id } })} /> : null}

      <SectionTitle title="매도 규칙" {...(active ? { action: '수정', onAction: () => router.push({ pathname: '/sell-rule/[positionId]', params: { positionId: position.id } }) } : {})} />
      {position.sellRule ? (
        <Surface style={{ gap: spacing.sm }}>
          <View style={styles.row}><AppText variant="bodyStrong">규칙 v{position.sellRule.version}</AppText><Chip label={position.sellRule.manualOnly ? '수동 관리만' : '감시 중'} selected={!position.sellRule.manualOnly} /></View>
          {position.sellRule.manualOnly ? <Banner title="자동 매도 신호 없음" body="수량 변화는 실제 체결을 직접 등록한 뒤에만 반영됩니다." /> : <View style={styles.wrap}>{position.sellRule.stopLossPercent ? <Chip label={`손절 ${position.sellRule.stopLossPercent}%`} /> : null}{position.sellRule.takeProfitPercent ? <Chip label={`목표 +${position.sellRule.takeProfitPercent}%`} /> : null}{position.sellRule.trailingStopPercent ? <Chip label={`추적 ${position.sellRule.trailingStopPercent}%`} /> : null}{position.sellRule.maxHoldingDays ? <Chip label={`최대 ${position.sellRule.maxHoldingDays}일`} /> : null}{position.sellRule.technicalIds.map((item) => <Chip key={item} label={item.toUpperCase()} />)}</View>}
          <AppText variant="caption" tone="muted">보호 규칙은 OR · 기술 그룹 안에서 {position.sellRule.technicalMode}</AppText>
        </Surface>
      ) : <EmptyState title="매도 규칙이 없어요" body="수치를 몰래 켜지 않습니다. 수동 관리 또는 자동 감시 규칙을 직접 확인하세요." action="규칙 만들기" onAction={() => router.push({ pathname: '/sell-rule/[positionId]', params: { positionId: position.id } })} />}

      {active ? (
        <View style={styles.actions}>
          <Button label="추가 매수" kind="secondary" onPress={() => router.push({ pathname: '/trade', params: { ...routeParams, side: 'BUY' } })} />
          <Button label="부분 · 전량 매도" onPress={() => router.push({ pathname: '/trade', params: { ...routeParams, side: 'SELL' } })} />
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
      <Surface style={{ gap: spacing.sm }}><View style={styles.row}><AppText tone="muted">실현 손익</AppText><AppText variant="bodyStrong" tone={realizedProfit >= 0 ? 'positive' : 'negative'}>{formatWon(realizedProfit)}</AppText></View><AppText variant="caption" tone="muted">이동가중평균법 v1 · 서버 체결 원장 집계값</AppText></Surface>
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
