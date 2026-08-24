import React from 'react';
import { router } from 'expo-router';
import { StyleSheet, View } from 'react-native';
import { AppText, Banner, Button, Chip, Divider, EmptyState, ListRow, Metric, Screen, SectionTitle, Surface, spacing, useSignalTheme } from '@signal/ui';
import { useAppStore } from '@/store/useAppStore';
import { formatRate, formatWon } from '@/utils/format';
import { profitRate, unrealizedProfit } from '@/domain/portfolio';
import { connectedApiEnabled, endRemoteRankingTrack } from '@/services/connected-api';
import { Alert } from 'react-native';
import { useAuth } from '@/providers/AuthProvider';
import { supabaseConfigured } from '@/services/supabase';
import { useProviderHealth } from '@/hooks/useProviderHealth';

export default function MyScreen() {
  const { colors } = useSignalTheme();
  const auth = useAuth();
  const storedNickname = useAppStore((state) => state.nickname);
  const positions = useAppStore((state) => state.positions);
  const strategies = useAppStore((state) => state.strategies);
  const provider = useProviderHealth();
  const open = positions.filter((item) => item.status !== 'CLOSED');
  const ranked = open.filter((item) => item.kind === 'RANKED_PAPER');
  const rankingTrack = useAppStore((state) => state.rankingTrack);
  const setRankingTrack = useAppStore((state) => state.setRankingTrack);
  const nickname = storedNickname || String(auth.user?.user_metadata?.['nickname'] ?? '') || (auth.session ? '사용자' : '게스트');
  const totalProfit = open.reduce((sum, item) => sum + unrealizedProfit(item), 0);
  const weightedRate = open.length ? open.reduce((sum, item) => sum + profitRate(item), 0) / open.length : 0;

  return (
    <Screen>
      <View style={styles.profile}><View style={[styles.avatar, { backgroundColor: colors.accent }]}><AppText variant="subtitle" tone="inverse">{nickname.slice(0, 1)}</AppText></View><View style={{ flex: 1, gap: 4 }}><View style={{ flexDirection: 'row', gap: spacing.xs }}><AppText variant="subtitle">{nickname}</AppText><Chip label={auth.session ? '로그인' : '둘러보기'} /></View><AppText variant="caption" tone="muted">{auth.user?.email ?? '로그인하면 전략과 포트폴리오를 저장할 수 있습니다.'}</AppText></View><Button label="설정" kind="ghost" compact onPress={() => router.push('/settings')} /></View>
      {connectedApiEnabled && supabaseConfigured && auth.session ? <Banner tone="accent" title="서버 데이터 연결됨" body="현재 계정의 전략·신호·포트폴리오를 동기화합니다." /> : <Banner tone="warning" title="로그인 필요" body="둘러보기에서는 서버 데이터를 저장하거나 변경할 수 없습니다." action="로그인" onAction={() => router.push('/auth')} />}
      <Surface style={{ gap: spacing.md }}>
        <View style={styles.row}><AppText variant="bodyStrong">전체 열린 포지션</AppText><Chip label="원장 3종 분리" /></View>
        <View style={styles.metrics}><Metric label="평가 손익" value={formatWon(totalProfit)} tone={totalProfit >= 0 ? 'positive' : 'negative'} /><Metric label="평균 수익률" value={formatRate(weightedRate)} tone={weightedRate >= 0 ? 'positive' : 'negative'} /><Metric label="보유" value={`${open.length}개`} /></View>
      </Surface>
      <SectionTitle title="포트폴리오" />
      <Surface style={{ paddingVertical: 0 }}>
        <ListRow title="내 전략" subtitle={`${strategies.length}개 저장됨`} onPress={() => router.navigate('/(tabs)/create')} />
        <Divider />
        <ListRow title="실제 수동 보유" subtitle={`${open.filter((item) => item.kind === 'MANUAL_LIVE').length}개 · 사용자 랭킹 제외`} onPress={() => router.push({ pathname: '/holdings', params: { kind: 'MANUAL_LIVE' } })} />
        <Divider />
        <ListRow title="연습 페이퍼" subtitle={`${open.filter((item) => item.kind === 'SANDBOX_PAPER').length}개 · 직접 주문`} onPress={() => router.push({ pathname: '/holdings', params: { kind: 'SANDBOX_PAPER' } })} />
        <Divider />
        <ListRow title="공식 랭킹 페이퍼" subtitle={rankingTrack ? `${rankingTrack.strategyName} · ${ranked.length}개 보유 · D+1 가상 체결` : '활성 트랙 없음'} onPress={() => rankingTrack ? router.push({ pathname: '/profile/[id]', params: { id: 'me' } }) : router.navigate('/(tabs)/create')} />
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
      {rankingTrack ? <><SectionTitle title="공식 랭킹 트랙" /><Surface style={{ gap: spacing.sm }}><AppText variant="bodyStrong">{rankingTrack.strategyName}</AppText><View style={styles.metrics}><Metric label="누적 수익률" value={formatRate(rankingTrack.returnRate)} tone={rankingTrack.returnRate >= 0 ? 'positive' : 'negative'} /><Metric label="최대 낙폭" value={formatRate(rankingTrack.maxDrawdown)} tone="negative" /><Metric label="완료 매매" value={`${rankingTrack.tradeCount}건`} /></View><Button kind="danger" label="공식 랭킹 트랙 종료" onPress={() => Alert.alert('트랙 종료', '종료하면 이 트랙은 다시 시작할 수 없으며 재시작 제한이 적용됩니다.', [{ text: '취소', style: 'cancel' }, { text: '종료', style: 'destructive', onPress: () => { void endRemoteRankingTrack().then(() => setRankingTrack(null)).catch((error: unknown) => Alert.alert('종료 실패', error instanceof Error ? error.message : '잠시 뒤 다시 시도하세요.')); } }])} /></Surface></> : <><SectionTitle title="공식 랭킹 트랙" /><EmptyState title="활성 랭킹 트랙이 없습니다" body="확정 전략 상세에서 공식 랭킹을 시작할 수 있습니다." action="내 전략 보기" onAction={() => router.navigate('/(tabs)/create')} /></>}
    </Screen>
  );
}

const styles = StyleSheet.create({
  profile: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  avatar: { width: 52, height: 52, borderRadius: 18, alignItems: 'center', justifyContent: 'center' },
  row: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.sm },
  metrics: { flexDirection: 'row', gap: spacing.md, flexWrap: 'wrap' },
});
