import React from 'react';
import { router } from 'expo-router';
import { StyleSheet, View } from 'react-native';
import { AppText, Banner, Button, Chip, Divider, ListRow, Metric, Screen, SectionTitle, Surface, spacing, useSignalTheme } from '@signal/ui';
import { useAppStore } from '@/store/useAppStore';
import { formatWon } from '@/utils/format';
import { replayExecutions } from '@/domain/portfolio';
import { connectedApiEnabled } from '@/services/connected-api';
import { useAuth } from '@/providers/AuthProvider';
import { supabaseConfigured } from '@/services/supabase';
import { useProviderHealth } from '@/hooks/useProviderHealth';

export default function MyScreen() {
  const { colors } = useSignalTheme();
  const auth = useAuth();
  const storedNickname = useAppStore((state) => state.nickname);
  const positions = useAppStore((state) => state.positions);
  const strategies = useAppStore((state) => state.strategies);
  const profilePublic = useAppStore((state) => state.profilePublic);
  const provider = useProviderHealth();
  const open = positions.filter((item) => item.status !== 'CLOSED' && item.status !== 'ARCHIVED');
  const nickname = storedNickname || String(auth.user?.user_metadata?.['nickname'] ?? '') || (auth.session ? '사용자' : '게스트');
  const realizedProfit = positions.reduce((sum, item) => sum + (item.realizedProfit ?? replayExecutions(item.executions).realizedProfit), 0);

  return (
    <Screen>
      <View style={styles.profile}><View style={[styles.avatar, { backgroundColor: colors.accent }]}><AppText variant="subtitle" tone="inverse">{nickname.slice(0, 1)}</AppText></View><View style={{ flex: 1, gap: 4 }}><View style={{ flexDirection: 'row', gap: spacing.xs }}><AppText variant="subtitle">{nickname}</AppText><Chip label={auth.session ? '로그인' : '둘러보기'} /></View><AppText variant="caption" tone="muted">{auth.user?.email ?? '로그인하면 전략과 포트폴리오를 저장할 수 있습니다.'}</AppText></View><Button label="설정" kind="ghost" compact onPress={() => router.push('/settings')} /></View>
      {connectedApiEnabled && supabaseConfigured && auth.session ? <Banner tone="accent" title="서버 데이터 연결됨" body="현재 계정의 전략·신호·포트폴리오를 동기화합니다." /> : <Banner tone="warning" title="로그인 필요" body="둘러보기에서는 서버 데이터를 저장하거나 변경할 수 없습니다." action="로그인" onAction={() => router.push('/auth')} />}
      <Surface style={{ gap: spacing.md }}>
        <View style={styles.row}><AppText variant="bodyStrong">실제 매매 손익</AppText><Chip label={profilePublic ? '랭킹 공개' : '비공개'} selected={profilePublic} /></View>
        <View style={styles.metrics}><Metric label="실현 손익" value={formatWon(realizedProfit)} tone={realizedProfit >= 0 ? 'positive' : 'negative'} /><Metric label="보유" value={`${open.length}개`} /></View>
      </Surface>
      <SectionTitle title="포트폴리오" />
      <Surface style={{ paddingVertical: 0 }}>
        <ListRow title="내 전략" subtitle={`${strategies.length}개 저장됨`} onPress={() => router.navigate('/(tabs)/create')} />
        <Divider />
        <ListRow title="실제 매매 기록" subtitle={`${open.length}개 보유 · 공개 시 사용자 랭킹 반영`} onPress={() => router.push({ pathname: '/holdings', params: { kind: 'MANUAL_LIVE' } })} />
        <Divider />
        <ListRow title="종료 포지션 기록" subtitle="전량매도 뒤 감시 종료" onPress={() => router.push('/history')} />
      </Surface>
      <SectionTitle title="도구" />
      <Surface style={{ paddingVertical: 0 }}>
        <ListRow title="종목 검색 · 관심 종목" onPress={() => router.push('/watchlist')} />
        <Divider />
        <ListRow title="데이터 공급자 상태" subtitle={provider.data ? `${provider.data.provider} · ${provider.data.state}` : '상태 확인'} onPress={() => router.push('/provider-status')} />
        <Divider />
        <ListRow title="알림 · 공개 · 개인정보 설정" onPress={() => router.push('/settings')} />
      </Surface>
      <Banner title="사용자 랭킹 테스트 기준" body="공개 프로필이 실제 매매를 한 번이라도 입력하면 랭킹에 표시합니다. 매도 전 수익률은 0%입니다." />
    </Screen>
  );
}

const styles = StyleSheet.create({
  profile: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  avatar: { width: 52, height: 52, borderRadius: 18, alignItems: 'center', justifyContent: 'center' },
  row: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.sm },
  metrics: { flexDirection: 'row', gap: spacing.md, flexWrap: 'wrap' },
});
