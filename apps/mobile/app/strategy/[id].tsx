import React, { useMemo, useState } from 'react';
import { router, useLocalSearchParams } from 'expo-router';
import { Alert, StyleSheet, View } from 'react-native';
import { AppText, Banner, Button, Chip, Divider, EmptyState, ListRow, Screen, SectionTitle, Surface, spacing } from '@signal/ui';
import { TitleBlock } from '@/components/common';
import { indicators, universes } from '@/data/mock';
import { remoteStrategyRuleLabel, reviseRemoteStrategy } from '@/services/connected-api';
import { useRemoteApiReady } from '@/hooks/useRemoteApiReady';
import { useAppStore } from '@/store/useAppStore';

export default function StrategyDetailScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const strategies = useAppStore((state) => state.strategies);
  const strategyHistory = useAppStore((state) => state.strategyHistory);
  const strategy = useMemo(
    () => strategies.find((item) => item.id === id) ?? strategyHistory.find((item) => item.id === id),
    [id, strategies, strategyHistory],
  );
  const historical = useMemo(
    () => !strategies.some((item) => item.id === id) && strategyHistory.some((item) => item.id === id),
    [id, strategies, strategyHistory],
  );
  const history = useMemo(() => strategy?.remoteStrategyId
    ? strategyHistory.filter((item) => item.id !== id && item.remoteStrategyId === strategy.remoteStrategyId)
    : strategyHistory.filter((item) => item.id === id), [id, strategy, strategyHistory]);
  const togglePublic = useAppStore((state) => state.toggleStrategyPublic);
  const upsertRemoteStrategy = useAppStore((state) => state.upsertRemoteStrategy);
  const remoteApiReady = useRemoteApiReady();
  const [savingVisibility, setSavingVisibility] = useState(false);
  if (!strategy) return <Screen><EmptyState title="전략을 찾지 못했어요" body="내 전략 목록으로 돌아가세요." action="전략 목록" onAction={() => router.replace('/(tabs)/create')} /></Screen>;
  const universe = universes.find((item) => item.id === strategy.universeId);
  const changeVisibility = async () => {
    if (!remoteApiReady) {
      togglePublic(strategy.id);
      return;
    }
    if (!strategy.remoteStrategyId) return Alert.alert('동기화 대기', '서버 전략을 받은 뒤 다시 시도하세요.');
    setSavingVisibility(true);
    try {
      const revised = await reviseRemoteStrategy(strategy.remoteStrategyId, {
        name: strategy.name, universeId: strategy.universeId, indicatorIds: strategy.indicatorIds,
        conditionMode: strategy.conditionMode, public: !strategy.public,
      });
      upsertRemoteStrategy(revised);
      router.replace({ pathname: '/strategy/[id]', params: { id: revised.id } });
    } catch (caught) {
      Alert.alert('공개 설정 실패', caught instanceof Error ? caught.message : '잠시 뒤 다시 시도하세요.');
    } finally {
      setSavingVisibility(false);
    }
  };
  return (
    <Screen>
      <TitleBlock eyebrow={`전략 v${strategy.version} · 일봉`} title={strategy.name} body={`${universe?.name} · ${strategy.indicatorIds.length}개 지표를 ${strategy.conditionMode === 'ALL' ? '모두' : '하나 이상'} 충족`} />
      {historical ? <Banner title="이전 전략 버전" body="신호 근거 보존용 읽기 전용 버전입니다." /> : null}
      {strategy.locked ? <Banner title="공식 랭킹 트랙에서 잠김" body="신호 건너뛰기·취소·가격 수정이 불가합니다. 변경하려면 새 트랙과 자격 기간이 필요해요." /> : null}
      <Surface style={{ gap: spacing.sm }}>
        <View style={styles.row}><AppText variant="bodyStrong">상태</AppText><View style={styles.wrap}><Chip label={strategy.alertEnabled ? '알림 켬' : '알림 끔'} selected /><Chip label={strategy.public ? '공개' : '비공개'} /><Chip label={`${strategy.cooldownHours}시간 cooldown`} /></View></View>
        <Divider />
        <ListRow title="종목 범위 버전" subtitle={`${universe?.name} · ${universe?.version}`} />
        <ListRow title="조건 관계" subtitle={strategy.conditionMode === 'ALL' ? '모든 조건 충족 (AND)' : '하나 이상 충족 (OR)'} />
        <ListRow title="신호 생성" subtitle="완성 일봉 · false → true · 같은 봉 중복 방지" />
      </Surface>
      <SectionTitle title="지표 조건" />
      <Surface style={{ paddingVertical: 0 }}>
        {strategy.indicatorIds.map((indicatorId, index) => {
          const indicator = indicators.find((item) => item.id === indicatorId);
          if (!indicator) return null;
          return <React.Fragment key={indicator.id}><ListRow title={`${index + 1}. ${indicator.name}`} subtitle={remoteApiReady ? remoteStrategyRuleLabel(indicator.id) : indicator.defaultRule} value={indicator.tier} onPress={() => router.push({ pathname: '/indicator/[id]', params: { id: indicator.id } })} />{index < strategy.indicatorIds.length - 1 ? <Divider /> : null}</React.Fragment>;
        })}
      </Surface>
      {!strategy.locked && !historical ? <><Button label="새 버전으로 수정" onPress={() => router.push({ pathname: '/(tabs)/create', params: { edit: strategy.id } })} /><Button label={savingVisibility ? '저장 중…' : strategy.public ? '비공개로 전환' : '공개로 전환'} kind="secondary" disabled={savingVisibility} onPress={() => Alert.alert('새 전략 버전', `공개 설정을 바꾸면 v${strategy.version + 1}이 생성됩니다.`, [{ text: '취소', style: 'cancel' }, { text: '확인', onPress: () => { void changeVisibility(); } }])} /></> : null}
      {history.length ? <><SectionTitle title="이전 버전" /><Surface style={{ paddingVertical: 0 }}>{history.map((version, index) => <React.Fragment key={`${version.id}-${version.version}`}><ListRow title={`v${version.version} · ${version.name}`} subtitle={`${version.indicatorIds.length}개 지표 · ${version.conditionMode} · 읽기 전용`} value={version.public ? '공개였음' : '비공개'} />{index < history.length - 1 ? <Divider /> : null}</React.Fragment>)}</Surface></> : null}
      <Banner tone="accent" title="신호는 자동 실주문이 아니에요" body="실제·연습 모드에서는 신호 상세에서 보유 등록 또는 연습 주문을 직접 확인합니다." />
    </Screen>
  );
}

const styles = StyleSheet.create({ row: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.sm }, wrap: { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'flex-end', gap: spacing.xs, flex: 1 } });
