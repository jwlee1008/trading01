import React from 'react';
import { router } from 'expo-router';
import { Pressable, StyleSheet, View } from 'react-native';
import { AppText, Banner, Button, Chip, Divider, Screen, Surface, spacing, useSignalTheme } from '@signal/ui';
import { TitleBlock } from '@/components/common';
import { universes } from '@/data/mock';
import { useAppStore } from '@/store/useAppStore';

export default function UniverseScreen() {
  const { colors } = useSignalTheme();
  const selected = useAppStore((state) => state.selectedUniverseId);
  const setUniverse = useAppStore((state) => state.setUniverse);
  const choose = (id: (typeof universes)[number]['id']) => setUniverse(id);
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
      <Button label="이 범위 사용" onPress={() => router.replace('/(tabs)')} />
      <AppText variant="caption" tone="muted">포함 정책 v12: 보통주 포함 · 우선주/ETF/ETN/SPAC/관리종목 제외</AppText>
    </Screen>
  );
}

const styles = StyleSheet.create({
  row: { minHeight: 82, flexDirection: 'row', alignItems: 'center', gap: spacing.sm, paddingVertical: spacing.sm },
  radio: { width: 24, height: 24, borderRadius: 12, borderWidth: 2, alignItems: 'center', justifyContent: 'center' },
  dot: { width: 12, height: 12, borderRadius: 6 },
});
