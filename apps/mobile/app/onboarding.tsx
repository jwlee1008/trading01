import React, { useState } from 'react';
import { router } from 'expo-router';
import { StyleSheet, View } from 'react-native';
import { AppText, Banner, Button, Chip, Screen, Surface, spacing, useSignalTheme } from '@signal/ui';
import { APP_NAME } from '@/data/catalog';
import { useAppStore } from '@/store/useAppStore';

const pages = [
  { eyebrow: '완성 일봉 기준', title: '내 조건을\n신호로 확인해요', body: '한국 주식 지표 조건을 만들고, 일봉 마감 뒤 조건 충족 근거를 한곳에서 확인합니다.', points: ['지표 최대 5개', 'KOSPI · KOSDAQ 범위', '실제 수동 · 연습 · 공식 랭킹 분리'] },
  { eyebrow: '원장 분리', title: '연습과 실제 기록을\n섞지 않아요', body: '실제 거래는 직접 등록합니다. 연습 주문은 다음 거래 가능일의 확정 시가 데이터로 처리됩니다.', points: ['자동 실주문 없음', '부분매도 뒤 잔여 수량 감시', '공식 랭킹은 잠긴 전략만 자동 가상 체결'] },
  { eyebrow: '필수 고지', title: '결정은 사용자에게\n성과 보장은 없어요', body: '신호와 랭킹은 정보·교육 목적 기계 산출물입니다. 투자자문이나 수익 보장이 아닙니다.', points: ['비용·지연·시장 상황 반영', '백테스트는 미래 성과를 보장하지 않음', '모든 MVP 기능 무료'] },
];

export default function OnboardingScreen() {
  const { colors } = useSignalTheme();
  const [page, setPage] = useState(0);
  const completeOnboarding = useAppStore((state) => state.completeOnboarding);
  const item = pages[page];
  if (!item) return null;

  const next = (trial: boolean) => {
    if (page < pages.length - 1) return setPage((value) => value + 1);
    if (!trial) return router.push('/auth');
    completeOnboarding();
    router.replace({ pathname: '/universe', params: { origin: 'setup' } });
  };

  return (
    <Screen contentContainerStyle={styles.content}>
      <View style={styles.brand}><View style={[styles.mark, { backgroundColor: colors.accent }]}><AppText tone="inverse" variant="subtitle">S</AppText></View><AppText variant="bodyStrong">{APP_NAME}</AppText></View>
      <View style={styles.hero}>
        <AppText variant="caption" tone="accent" style={{ fontWeight: '800' }}>{item.eyebrow}</AppText>
        <AppText variant="hero" style={{ lineHeight: 46 }}>{item.title}</AppText>
        <AppText tone="muted" style={{ fontSize: 17, lineHeight: 26 }}>{item.body}</AppText>
        <Surface style={{ gap: spacing.md, marginTop: spacing.md }}>
          {item.points.map((point) => <View key={point} style={styles.point}><Chip label="✓" selected /><AppText style={{ flex: 1 }}>{point}</AppText></View>)}
        </Surface>
        {page === 2 ? <Banner title="투자 책임을 확인해 주세요" body="실제 주문은 앱 밖에서 사용자 판단으로 진행합니다." /> : null}
      </View>
      <View style={{ gap: spacing.sm }}>
        <View style={styles.dots}>{pages.map((_, index) => <View key={index} style={[styles.dot, { backgroundColor: index === page ? colors.accent : colors.border }, index === page && styles.dotActive]} />)}</View>
        <Button label={page === 2 ? '둘러보기로 시작' : '다음'} onPress={() => next(true)} />
        {page === 2 ? <Button label="로그인 후 시작" kind="secondary" onPress={() => next(false)} /> : null}
        {page > 0 && page < 2 ? <Button label="이전" kind="ghost" onPress={() => setPage((value) => value - 1)} /> : null}
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: { minHeight: '100%', justifyContent: 'space-between', paddingTop: 64 },
  brand: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  mark: { width: 36, height: 36, borderRadius: 12, alignItems: 'center', justifyContent: 'center' },
  hero: { gap: spacing.md },
  point: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  dots: { flexDirection: 'row', alignSelf: 'center', gap: 6, marginBottom: spacing.sm },
  dot: { width: 7, height: 7, borderRadius: 4 },
  dotActive: { width: 22 },
});
