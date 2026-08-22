import React from 'react';
import { router, useLocalSearchParams } from 'expo-router';
import { View } from 'react-native';
import { AppText, Banner, Button, Chip, Divider, EmptyState, ListRow, Screen, Surface, spacing } from '@signal/ui';
import { TitleBlock } from '@/components/common';
import { indicators } from '@/data/catalog';

export default function IndicatorDetailScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const indicator = indicators.find((item) => item.id === id);
  if (!indicator) return <Screen><EmptyState title="지표를 찾지 못했어요" body="지표 목록에서 다시 선택하세요." action="랭킹으로" onAction={() => router.replace('/(tabs)/rankings')} /></Screen>;
  return (
    <Screen>
      <TitleBlock eyebrow={`과거 견고성 ${indicator.tier} 티어`} title={indicator.name} body={indicator.short} />
      <Banner title="등급은 미래 수익 예측이 아니에요" body="검증 구간에서 지표 제거 기여도, 안정성, 중복 제거 출현을 합친 결과입니다." />
      <Surface style={{ gap: spacing.md }}>
        <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}><AppText variant="bodyStrong">초보 기본 조건</AppText><Chip label="교육용" selected /></View>
        <AppText variant="subtitle">{indicator.defaultRule}</AppText>
        <Divider />
        <ListRow title="계산식" subtitle={indicator.formula} />
        <ListRow title="최소 데이터" subtitle={`${indicator.minimumCandles}개 완성 봉 뒤 계산`} />
        <ListRow title="판정 시점" subtitle="일봉 종가 확정 뒤 false → true 전환" />
      </Surface>
      <Surface style={{ gap: spacing.sm }}><AppText variant="bodyStrong" tone="warning">흔한 오해 · 약점</AppText><AppText tone="muted">{indicator.caution}</AppText><AppText variant="caption" tone="muted">한 지표만으로 투자 결정을 내리지 마세요. 비용, 유동성, 데이터 지연도 결과를 바꿉니다.</AppText></Surface>
      <Button label="이 지표로 전략 만들기" onPress={() => router.navigate('/(tabs)/create')} />
    </Screen>
  );
}
