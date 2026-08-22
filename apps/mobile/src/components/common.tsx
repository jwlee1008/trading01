import React from 'react';
import { Pressable, StyleSheet, View } from 'react-native';
import { AppText, Chip, Divider, Surface, spacing, useSignalTheme } from '@signal/ui';
import type { Position } from '@/domain/types';
import { profitRate, unrealizedProfit } from '@/domain/portfolio';
import { formatPrice, formatRate, formatWon } from '@/utils/format';

export function TitleBlock({ eyebrow, title, body }: { eyebrow?: string; title: string; body?: string }) {
  return (
    <View style={{ gap: spacing.xs, marginVertical: spacing.xs }}>
      {eyebrow ? <AppText variant="caption" tone="accent" style={{ fontWeight: '800' }}>{eyebrow}</AppText> : null}
      <AppText variant="title">{title}</AppText>
      {body ? <AppText tone="muted" style={{ lineHeight: 22 }}>{body}</AppText> : null}
    </View>
  );
}

export function Segmented<T extends string>({ options, value, onChange }: { options: { value: T; label: string }[]; value: T; onChange: (value: T) => void }) {
  const { colors } = useSignalTheme();
  return (
    <View accessibilityRole="tablist" style={[styles.segmented, { backgroundColor: colors.surfaceMuted }]}>
      {options.map((option) => (
        <Pressable
          key={option.value}
          accessibilityRole="tab"
          accessibilityState={{ selected: value === option.value }}
          onPress={() => onChange(option.value)}
          style={[styles.segment, value === option.value && { backgroundColor: colors.surface }]}
        >
          <AppText variant="bodyStrong" tone={value === option.value ? 'default' : 'muted'}>{option.label}</AppText>
        </Pressable>
      ))}
    </View>
  );
}

export function PriceChange({ price, change }: { price: number; change: number }) {
  const tone = change > 0 ? 'positive' : change < 0 ? 'negative' : 'muted';
  const arrow = change > 0 ? '▲' : change < 0 ? '▼' : '―';
  return (
    <View style={{ alignItems: 'flex-end', gap: 3 }}>
      <AppText variant="bodyStrong">{formatPrice(price)}</AppText>
      <AppText tone={tone} variant="caption">{arrow} {formatRate(change)}</AppText>
    </View>
  );
}

export function PositionCard({ position, onPress }: { position: Position; onPress: () => void }) {
  const rate = profitRate(position);
  const tone = rate >= 0 ? 'positive' : 'negative';
  const kind = position.kind === 'MANUAL_LIVE' ? '실제 수동' : position.kind === 'SANDBOX_PAPER' ? '연습' : '공식 랭킹';
  return (
    <Pressable accessibilityRole="button" accessibilityLabel={`${position.instrumentName} 포지션 상세`} onPress={onPress}>
      <Surface style={{ gap: spacing.sm }}>
        <View style={styles.row}>
          <View style={{ gap: 3 }}><AppText variant="bodyStrong">{position.instrumentName}</AppText><AppText variant="caption" tone="muted">{position.symbol}</AppText></View>
          <Chip label={kind} />
        </View>
        <Divider />
        <View style={styles.row}>
          <View style={{ gap: 3 }}><AppText variant="caption" tone="muted">평가 손익</AppText><AppText variant="subtitle" tone={tone}>{formatWon(unrealizedProfit(position))}</AppText></View>
          <View style={{ alignItems: 'flex-end', gap: 3 }}><AppText variant="caption" tone="muted">수익률</AppText><AppText variant="subtitle" tone={tone}>{rate >= 0 ? '▲' : '▼'} {formatRate(rate)}</AppText></View>
        </View>
        <AppText variant="caption" tone="muted">{position.quantity}주 · 평균 {formatPrice(position.averagePrice)} · 현재 {formatPrice(position.currentPrice)}</AppText>
      </Surface>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.sm },
  segmented: { flexDirection: 'row', padding: 4, borderRadius: 13 },
  segment: { flex: 1, minHeight: 42, borderRadius: 10, alignItems: 'center', justifyContent: 'center' },
});
