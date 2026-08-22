import React from 'react';
import { router } from 'expo-router';
import { StyleSheet, View } from 'react-native';
import { AppText, Banner, Button, Chip, Divider, ListRow, Metric, Screen, SectionTitle, Surface, spacing, useSignalTheme } from '@signal/ui';
import { useAppStore } from '@/store/useAppStore';
import { formatRate, formatWon } from '@/utils/format';
import { profitRate, unrealizedProfit } from '@/domain/portfolio';
import { connectedApiEnabled } from '@/services/connected-api';
import { useAuth } from '@/providers/AuthProvider';
import { supabaseConfigured } from '@/services/supabase';

export default function MyScreen() {
  const { colors } = useSignalTheme();
  const auth = useAuth();
  const nickname = useAppStore((state) => state.nickname);
  const trialMode = useAppStore((state) => state.trialMode);
  const profilePublic = useAppStore((state) => state.profilePublic);
  const positions = useAppStore((state) => state.positions);
  const open = positions.filter((item) => item.status !== 'CLOSED');
  const totalProfit = open.reduce((sum, item) => sum + unrealizedProfit(item), 0);
  const weightedRate = open.length ? open.reduce((sum, item) => sum + profitRate(item), 0) / open.length : 0;

  return (
    <Screen>
      <View style={styles.profile}><View style={[styles.avatar, { backgroundColor: colors.accent }]}><AppText variant="subtitle" tone="inverse">{nickname.slice(0, 1)}</AppText></View><View style={{ flex: 1, gap: 4 }}><View style={{ flexDirection: 'row', gap: spacing.xs }}><AppText variant="subtitle">{nickname}</AppText><Chip label={auth.session ? '로그인' : '체험'} /></View><AppText variant="caption" tone="muted">{auth.user?.email ?? `공식 랭킹 17위 · 프로필 ${profilePublic ? '공개' : '비공개'}`}</AppText></View><Button label="설정" kind="ghost" compact onPress={() => router.push('/settings')} /></View>
      {connectedApiEnabled && (!supabaseConfigured || auth.session) ? <Banner tone="accent" title="API 연결 모드" body="현재 로그인 토큰으로 전략·신호·포트폴리오를 맞춥니다." /> : supabaseConfigured && !auth.session ? <Banner tone="warning" title="로그인 필요" body="서버 데이터 연결 전 로그인하세요." action="로그인" onAction={() => router.push('/auth')} /> : trialMode ? <Banner tone="accent" title="로컬 체험 모드" body="데이터는 이 기기에만 저장됩니다. 앱 삭제 시 사라질 수 있어요." /> : null}
      <Surface style={{ gap: spacing.md }}>
        <View style={styles.row}><AppText variant="bodyStrong">전체 열린 포지션</AppText><Chip label="원장 3종 분리" /></View>
        <View style={styles.metrics}><Metric label="평가 손익" value={formatWon(totalProfit)} tone={totalProfit >= 0 ? 'positive' : 'negative'} /><Metric label="평균 수익률" value={formatRate(weightedRate)} tone={weightedRate >= 0 ? 'positive' : 'negative'} /><Metric label="보유" value={`${open.length}개`} /></View>
      </Surface>
      <SectionTitle title="포트폴리오" />
      <Surface style={{ paddingVertical: 0 }}>
        <ListRow title="실제 수동 보유" subtitle={`${open.filter((item) => item.kind === 'MANUAL_LIVE').length}개 · 사용자 랭킹 제외`} onPress={() => router.push({ pathname: '/holdings', params: { kind: 'MANUAL_LIVE' } })} />
        <Divider />
        <ListRow title="연습 페이퍼" subtitle={`${open.filter((item) => item.kind === 'SANDBOX_PAPER').length}개 · 직접 주문`} onPress={() => router.push({ pathname: '/holdings', params: { kind: 'SANDBOX_PAPER' } })} />
        <Divider />
        <ListRow title="공식 랭킹 페이퍼" subtitle="전략 잠김 · D+1 자동 가상 체결" onPress={() => router.push({ pathname: '/profile/[id]', params: { id: 'me' } })} />
        <Divider />
        <ListRow title="종료 포지션 기록" subtitle="전량매도 뒤 감시 종료" onPress={() => router.push('/history')} />
      </Surface>
      <SectionTitle title="도구" />
      <Surface style={{ paddingVertical: 0 }}>
        <ListRow title="종목 검색 · 관심 종목" onPress={() => router.push('/watchlist')} />
        <Divider />
        <ListRow title="데이터 공급자 상태" subtitle="Mock · 정상" onPress={() => router.push('/provider-status')} />
        <Divider />
        <ListRow title="알림 · 공개 · 개인정보 설정" onPress={() => router.push('/settings')} />
      </Surface>
      <SectionTitle title="공식 랭킹 트랙" />
      <Surface style={{ gap: spacing.sm }}><View style={styles.row}><AppText variant="bodyStrong">공식 랭킹 트랙</AppText><Chip label="잠김" tone="warning" /></View><AppText tone="muted">초기 10,000,000원 · 종목당 NAV 10% · 최대 10종목 · 추가매수 없음</AppText><Button label="내 공개 프로필 보기" kind="secondary" onPress={() => router.push({ pathname: '/profile/[id]', params: { id: 'me' } })} /></Surface>
    </Screen>
  );
}

const styles = StyleSheet.create({
  profile: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  avatar: { width: 52, height: 52, borderRadius: 18, alignItems: 'center', justifyContent: 'center' },
  row: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.sm },
  metrics: { flexDirection: 'row', gap: spacing.md, flexWrap: 'wrap' },
});
