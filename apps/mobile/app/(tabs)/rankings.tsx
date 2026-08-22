import React, { useMemo, useState } from 'react';
import { router } from 'expo-router';
import { Pressable, StyleSheet, View } from 'react-native';
import { AppText, Banner, Chip, Divider, ErrorState, LoadingState, Metric, Screen, Surface, spacing, useSignalTheme } from '@signal/ui';
import { Segmented } from '@/components/common';
import { combinations, indicators, universes, userRanks } from '@/data/mock';
import { useMockQuery } from '@/hooks/useMockQuery';
import { formatRate } from '@/utils/format';

type RankingType = 'combination' | 'indicator' | 'user';
type Period = '3m' | '6m' | '1y';

export default function RankingsScreen() {
  const { colors } = useSignalTheme();
  const [type, setType] = useState<RankingType>('combination');
  const [period, setPeriod] = useState<Period>('3m');
  const [universeFilter, setUniverseFilter] = useState<'all' | 'kospi200' | 'kosdaq150'>('all');
  const result = useMockQuery('rankings', true);
  const shownUsers = useMemo(() => userRanks.filter((item) => universeFilter === 'all' || item.universeId === universeFilter), [universeFilter]);

  return (
    <Screen>
      <Segmented options={[{ value: 'combination', label: '조합' }, { value: 'indicator', label: '지표 티어' }, { value: 'user', label: '사용자' }]} value={type} onChange={setType} />
      {type !== 'indicator' ? <Segmented options={[{ value: '3m', label: '3개월' }, { value: '6m', label: '6개월' }, { value: '1y', label: '1년' }]} value={period} onChange={setPeriod} /> : null}
      {result.isPending ? <LoadingState label="검증 데이터 집계 중" /> : null}
      {result.isError ? <ErrorState onRetry={() => void result.refetch()} /> : null}

      {result.data && type === 'combination' ? (
        <>
          <Banner tone="accent" title="신호 후 성과" body="완전한 매도 규칙이 없는 후보입니다. t+1 시가 진입 뒤 비용·벤치마크를 뺀 결과예요." />
          {combinations.map((item) => (
            <Pressable key={item.id} accessibilityRole="button" accessibilityLabel={`${item.rank}위 ${item.name} 상세`} onPress={() => router.push({ pathname: '/ranking/[id]', params: { id: item.id } })}>
              <Surface style={{ gap: spacing.md }}>
                <View style={styles.row}><View style={[styles.rank, { backgroundColor: colors.accentSoft }]}><AppText variant="subtitle" tone="accent">{item.rank}</AppText></View><View style={{ flex: 1, gap: 4 }}><AppText variant="bodyStrong">{item.name}</AppText><AppText variant="caption" tone="muted">{universes.find((universe) => universe.id === item.universeId)?.name} · 신호 {item.signalCount}건</AppText></View><AppText variant="subtitle" tone="positive">▲ {formatRate(item.excessReturn[period])}</AppText></View>
                <View style={styles.metrics}><Metric label="적중률" value={`${item.hitRate}%`} /><Metric label="MDD" value={`${item.mdd}%`} tone="negative" /><Metric label="안정성" value={`${item.stability}/100`} /></View>
                {item.signalCount < 30 ? <Chip label="데이터 부족 · 최소 30건" tone="warning" /> : <AppText variant="caption" tone="muted">{item.confidence}</AppText>}
              </Surface>
            </Pressable>
          ))}
        </>
      ) : null}

      {result.data && type === 'indicator' ? (
        <>
          <Banner title="미래 수익 예측이 아니에요" body="제거 기여도 50% · 기간/종목 안정성 30% · 중복 제거 출현 20%로 계산한 과거 견고성 등급입니다." />
          <Surface style={{ paddingVertical: 0 }}>
            {indicators.map((indicator, index) => (
              <React.Fragment key={indicator.id}>
                <Pressable accessibilityRole="button" onPress={() => router.push({ pathname: '/indicator/[id]', params: { id: indicator.id } })} style={styles.indicatorRow}>
                  <View style={[styles.tier, { backgroundColor: indicator.tier === 'S' ? colors.accent : colors.accentSoft }]}><AppText variant="subtitle" tone={indicator.tier === 'S' ? 'inverse' : 'accent'}>{indicator.tier}</AppText></View>
                  <View style={{ flex: 1, gap: 3 }}><AppText variant="bodyStrong">{indicator.name}</AppText><AppText variant="caption" tone="muted">{indicator.short}</AppText></View><AppText tone="muted">›</AppText>
                </Pressable>
                {index < indicators.length - 1 ? <Divider /> : null}
              </React.Fragment>
            ))}
          </Surface>
        </>
      ) : null}

      {result.data && type === 'user' ? (
        <>
          <Banner tone="accent" title="통합 순위 기준" body="전체 순위는 종목군을 보정하지 않은 페이퍼 포트폴리오 순수익률 기준입니다. 종목군별 투자 기회 차이가 성과에 영향을 줄 수 있습니다." />
          <View style={styles.chips}>{(['all', 'kospi200', 'kosdaq150'] as const).map((id) => <Chip key={id} label={id === 'all' ? '전체' : universes.find((item) => item.id === id)?.name ?? id} selected={universeFilter === id} onPress={() => setUniverseFilter(id)} />)}</View>
          {shownUsers.map((user) => (
            <Pressable key={user.id} accessibilityRole="button" onPress={() => user.public || user.id === 'me' ? router.push({ pathname: '/profile/[id]', params: { id: user.id } }) : undefined}>
              <Surface style={{ gap: spacing.sm }}>
                <View style={styles.row}><AppText variant="subtitle" tone={user.id === 'me' ? 'accent' : 'default'}>{user.rank}</AppText><View style={{ flex: 1, gap: 3 }}><AppText variant="bodyStrong">{user.nickname}{user.id === 'me' ? ' · 나' : ''}</AppText><AppText variant="caption" tone="muted">{universes.find((item) => item.id === user.universeId)?.name} · {user.days}일 운용</AppText></View><AppText variant="subtitle" tone={user.returnRate[period] >= 0 ? 'positive' : 'negative'}>{formatRate(user.returnRate[period])}</AppText></View>
                <View style={styles.metrics}><Metric label="MDD" value={`${user.mdd}%`} tone="negative" /><Metric label="거래" value={`${user.trades}회`} /><Metric label="갱신" value="18:20" /></View>
              </Surface>
            </Pressable>
          ))}
        </>
      ) : null}
      <AppText variant="caption" tone="muted" style={{ textAlign: 'center' }}>ranking-engine v1.4 · cost-model krx-v3 · 2026.08.14 갱신</AppText>
    </Screen>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  rank: { width: 36, height: 36, borderRadius: 12, alignItems: 'center', justifyContent: 'center' },
  metrics: { flexDirection: 'row', gap: spacing.md },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs },
  indicatorRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, minHeight: 74, paddingVertical: spacing.sm },
  tier: { width: 44, height: 44, borderRadius: 14, alignItems: 'center', justifyContent: 'center' },
});
