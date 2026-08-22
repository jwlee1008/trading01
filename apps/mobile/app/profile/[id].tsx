import React from 'react';
import { useLocalSearchParams } from 'expo-router';
import { StyleSheet, View } from 'react-native';
import { AppText, Banner, Button, Divider, EmptyState, ListRow, Metric, Screen, SectionTitle, Surface, spacing, useSignalTheme } from '@signal/ui';
import { TitleBlock } from '@/components/common';
import { universes, userRanks } from '@/data/mock';
import { useAppStore } from '@/store/useAppStore';
import { formatRate } from '@/utils/format';

const curve = [10, 18, 15, 26, 35, 31, 48, 55, 52, 71, 78, 88];

export default function PublicProfileScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const profilePublic = useAppStore((state) => state.profilePublic);
  const nickname = useAppStore((state) => state.nickname);
  const ranked = userRanks.find((item) => item.id === id);
  const { colors } = useSignalTheme();
  if (!ranked) return <Screen><EmptyState title="프로필을 찾지 못했어요" body="공개를 철회했거나 존재하지 않는 사용자입니다." /></Screen>;
  const isMe = ranked.id === 'me';
  const displayName = isMe ? nickname : ranked.nickname;
  return (
    <Screen>
      <TitleBlock eyebrow={`통합 사용자 랭킹 ${ranked.rank}위`} title={displayName} body={`${universes.find((item) => item.id === ranked.universeId)?.name} · 공식 랭킹 페이퍼만`} />
      {isMe && !profilePublic ? <Banner title="비공개 프로필 미리보기" body="현재 신규 랭킹과 다른 사용자 공개 화면에서 제외되어 있습니다." /> : null}
      <Surface style={{ gap: spacing.md }}><View style={styles.metrics}><Metric label="3개월" value={formatRate(ranked.returnRate['3m'])} tone="positive" /><Metric label="6개월" value={formatRate(ranked.returnRate['6m'])} tone="positive" /><Metric label="전체" value={formatRate(ranked.returnRate.all)} tone="positive" /></View><Divider /><View style={styles.metrics}><Metric label="MDD" value={`${ranked.mdd}%`} tone="negative" /><Metric label="완료 거래" value={`${ranked.trades}회`} /><Metric label="운영" value={`${ranked.days}일`} /></View></Surface>
      <SectionTitle title="NAV 수익률 곡선" />
      <Surface style={{ gap: spacing.sm }} accessibilityLabel={`12개월 수익률 곡선. 시작 0, 종료 ${ranked.returnRate.all}%`}>
        <View style={styles.chart}>{curve.map((height, index) => <View key={index} style={[styles.bar, { height, backgroundColor: colors.accent }]} />)}</View>
        <View style={styles.axis}><AppText variant="caption" tone="muted">12개월 전</AppText><AppText variant="caption" tone="muted">현재</AppText></View>
      </Surface>
      <SectionTitle title="잠긴 전략" />
      <Surface style={{ paddingVertical: 0 }}><ListRow title={ranked.strategyName} subtitle="StrategyVersion · UniverseVersion · SellRuleVersion 고정" value="잠김" /><Divider /><ListRow title="포트폴리오 규칙" subtitle="초기 1천만원 · 종목당 10% · 최대 10종목" /><Divider /><ListRow title="비용 · 체결" subtitle="cost krx-v3 · fill open-v2 · 동일 기준" /></Surface>
      <SectionTitle title="완료된 페이퍼 체결" />
      <Surface style={{ paddingVertical: 0 }}><ListRow title="삼성전자 · 전량 종료" subtitle="BUY 74,300원 → SELL 78,500원 · 비용 반영" value="+5.42%" /><Divider /><ListRow title="SK하이닉스 · 전량 종료" subtitle="BUY 181,200원 → SELL 176,800원 · 비용 반영" value="-2.71%" /></Surface>
      <Banner tone="accent" title="미청산 포지션은 1거래일 지연 공개" body="MANUAL_LIVE 보유, 실제 투자금, 이메일, 계정 정보는 공개하지 않습니다." />
      {!isMe ? <View style={styles.actions}><Button label="사용자 차단" kind="ghost" compact onPress={() => undefined} /><Button label="성과 신고" kind="ghost" compact onPress={() => undefined} /></View> : null}
      <AppText variant="caption" tone="muted">시작 NAV 10,000,000원 · 마지막 갱신 2026.08.14 18:20 · 열린 손실도 NAV에 포함</AppText>
    </Screen>
  );
}

const styles = StyleSheet.create({
  metrics: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.md },
  chart: { height: 100, flexDirection: 'row', alignItems: 'flex-end', gap: 7, paddingTop: spacing.sm },
  bar: { flex: 1, minHeight: 4, borderRadius: 4 },
  axis: { flexDirection: 'row', justifyContent: 'space-between' },
  actions: { flexDirection: 'row', justifyContent: 'flex-end', gap: spacing.xs },
});
