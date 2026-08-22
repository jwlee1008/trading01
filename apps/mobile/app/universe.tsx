import React from 'react';
import { router, useLocalSearchParams } from 'expo-router';
import { Pressable, StyleSheet, View } from 'react-native';
import { AppText, Banner, Button, Chip, Divider, EmptyState, ErrorState, LoadingState, Screen, Surface, spacing, useSignalTheme } from '@signal/ui';
import { TitleBlock } from '@/components/common';
import { useUniverses } from '@/hooks/useUniverses';
import type { UniverseId } from '@/domain/types';
import { useAppStore } from '@/store/useAppStore';

export default function UniverseScreen() {
  const { origin } = useLocalSearchParams<{ origin?: string }>();
  const { colors } = useSignalTheme();
  const selected = useAppStore((state) => state.selectedUniverseId);
  const setUniverse = useAppStore((state) => state.setUniverse);
  const query = useUniverses();
  const universes = query.data ?? [];
  const choose = (id: UniverseId) => setUniverse(id);
  const finish = () => {
    if ((origin === 'create' || origin === 'home') && router.canGoBack()) router.back();
    else router.replace('/(tabs)');
  };
  if (query.isPending) return <Screen><LoadingState label="확정 종목군 조회 중" /></Screen>;
  if (query.isError) return <Screen><ErrorState onRetry={() => void query.refetch()} /></Screen>;
  if (universes.length === 0) return <Screen><EmptyState title="사용 가능한 종목군이 없습니다" body="실제 종목 마스터와 종목군 버전을 먼저 준비하세요." /></Screen>;
  return (
    <Screen>
      <TitleBlock title="어디서 신호를 찾을까요?" body="범위는 전략 버전에 고정됩니다. 나중에 바꾸면 새 버전과 랭킹 트랙을 만듭니다." />
      <Banner title="과거 구성 이력 사용" body="현재 지수 종목을 과거에 소급하지 않습니다. 열린 포지션은 범위에서 빠져도 청산까지 감시합니다." />
      <Surface style={{ paddingVertical: 0 }}>
        {universes.map((universe, index) => (
          <React.Fragment key={universe.id}>
            <Pressable accessibilityRole="radio" accessibilityState={{ checked: selected === universe.id }} onPress={() => choose(universe.id)} style={styles.row}>
              <View style={[styles.radio, { borderColor: selected === universe.id ? colors.accent : colors.border }]}>{selected === universe.id ? <View style={[styles.dot, { backgroundColor: colors.accent }]} /> : null}</View>
              <View style={{ flex: 1, gap: 4 }}><View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.xs }}><AppText variant="bodyStrong">{universe.name}</AppText><Chip label={universe.version} /></View><AppText variant="caption" tone="muted">{universe.description} · {universe.count.toLocaleString('ko-KR')}종목</AppText></View>
            </Pressable>
            {index < universes.length - 1 ? <Divider /> : null}
          </React.Fragment>
        ))}
      </Surface>
      {selected === 'custom' ? <Button label="내 종목 목록 편집" kind="secondary" onPress={() => router.push('/watchlist')} /> : null}
      <Button label={origin === 'create' ? '전략 만들기로 돌아가기' : '이 범위 사용'} onPress={finish} />
      <AppText variant="caption" tone="muted">확정된 종목군 버전과 구성 이력을 기준으로 평가합니다.</AppText>
    </Screen>
  );
}

const styles = StyleSheet.create({
  row: { minHeight: 82, flexDirection: 'row', alignItems: 'center', gap: spacing.sm, paddingVertical: spacing.sm },
  radio: { width: 24, height: 24, borderRadius: 12, borderWidth: 2, alignItems: 'center', justifyContent: 'center' },
  dot: { width: 12, height: 12, borderRadius: 6 },
});
