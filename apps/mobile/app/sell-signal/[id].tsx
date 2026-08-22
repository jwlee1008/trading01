import React from 'react';
import { router, useLocalSearchParams } from 'expo-router';
import { View } from 'react-native';
import { AppText, Banner, Button, Chip, Divider, EmptyState, ListRow, Metric, Screen, SectionTitle, Surface, spacing } from '@signal/ui';
import { TitleBlock } from '@/components/common';
import { useAppStore } from '@/store/useAppStore';
import { profitRate } from '@/domain/portfolio';
import { formatPrice, formatRate } from '@/utils/format';

export default function SellSignalDetailScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const position = useAppStore((state) => state.positions.find((item) => item.id === id));
  if (!position) return <Screen><EmptyState title="매도 신호를 찾지 못했어요" body="포지션이 닫혀 취소됐을 수 있어요." /></Screen>;
  const rate = profitRate(position);
  const params = { positionId: position.id, symbol: position.symbol, name: position.instrumentName, price: String(position.currentPrice), side: 'SELL', mode: position.kind === 'MANUAL_LIVE' ? 'manual' : 'paper' };
  return (
    <Screen>
      <TitleBlock eyebrow="매도 조건 상태 · ACTIVE" title={position.instrumentName} body="8월 14일 완성 일봉 · 같은 봉의 충족 근거를 합친 알림" />
      <Banner title="자동 매도 주문이 아닙니다" body="실제 수동·연습 모드는 등록 또는 페이퍼 SELL 체결 뒤에만 수량이 바뀝니다." />
      <Surface style={{ gap: spacing.md }}><View style={{ flexDirection: 'row', gap: spacing.md }}><Metric label="기준 종가" value={formatPrice(position.currentPrice)} /><Metric label="평균 매수가" value={formatPrice(position.averagePrice)} /></View><View style={{ flexDirection: 'row', gap: spacing.md }}><Metric label="비용 전 수익률" value={formatRate(rate)} tone={rate >= 0 ? 'positive' : 'negative'} /><Metric label="잔여 수량" value={`${position.quantity}주`} /></View></Surface>
      <SectionTitle title="충족 근거" />
      <Surface style={{ paddingVertical: 0 }}><ListRow title="RSI(14) 약세 전환" subtitle="기술 조건 false → true" value="충족" /><Divider /><ListRow title="추적 손절 접근" subtitle={`최고 ${formatPrice(position.highestClose)} 대비 하락`} value="관찰" /></Surface>
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs }}><Chip label="데이터 정상" selected /><Chip label="규칙 v2" /><Chip label="신호 중복 방지" /></View>
      <AppText variant="caption" tone="muted">조건이 계속 true면 새 push를 보내지 않습니다. false로 풀린 뒤 다시 true가 되면 새 신호가 생깁니다.</AppText>
      {position.kind !== 'RANKED_PAPER' ? <Button label="부분 · 전량 매도 처리" onPress={() => router.push({ pathname: '/trade', params })} /> : <Banner tone="accent" title="다음 시가 전량 자동 가상 주문" body="미체결 시 EXIT_PENDING으로 두고 첫 체결 가능 시가까지 재시도합니다." />}
    </Screen>
  );
}
