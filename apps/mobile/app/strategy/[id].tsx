import React, { useMemo, useState } from 'react';
import { router, useLocalSearchParams } from 'expo-router';
import { useQueryClient } from '@tanstack/react-query';
import { Alert, Platform, StyleSheet, View } from 'react-native';
import { AppText, Banner, Button, Chip, Divider, EmptyState, ListRow, Screen, SectionTitle, Surface, spacing } from '@signal/ui';
import { TitleBlock } from '@/components/common';
import { indicators } from '@/data/catalog';
import { useUniverses } from '@/hooks/useUniverses';
import { deleteRemoteStrategy, remoteStrategyRuleLabel, reviseRemoteStrategy } from '@/services/connected-api';
import { useRemoteApiReady } from '@/hooks/useRemoteApiReady';
import { useAppStore } from '@/store/useAppStore';

export default function StrategyDetailScreen() {
  const queryClient = useQueryClient();
  const { id, saved } = useLocalSearchParams<{ id: string; saved?: string }>();
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
  const upsertRemoteStrategy = useAppStore((state) => state.upsertRemoteStrategy);
  const removeRemoteStrategy = useAppStore((state) => state.removeRemoteStrategy);
  const remoteApiReady = useRemoteApiReady();
  const universes = useUniverses().data ?? [];
  const [savingVisibility, setSavingVisibility] = useState(false);
  const [deleting, setDeleting] = useState(false);
  if (!strategy) return <Screen><EmptyState title="전략을 찾지 못했어요" body="내 전략 목록으로 돌아가세요." action="전략 목록" onAction={() => router.replace('/(tabs)/create')} /></Screen>;
  const universe = universes.find((item) => item.id === strategy.universeId);
  const changeVisibility = async () => {
    if (!remoteApiReady) return Alert.alert('로그인이 필요합니다', '실제 전략 공개 설정은 로그인 후 변경할 수 있습니다.');
    if (!strategy.remoteStrategyId) return Alert.alert('동기화 대기', '서버 전략을 받은 뒤 다시 시도하세요.');
    setSavingVisibility(true);
    try {
      const revised = await reviseRemoteStrategy(strategy.remoteStrategyId, {
        name: strategy.name, universeId: strategy.universeId, indicatorIds: strategy.indicatorIds,
        conditionMode: strategy.conditionMode, public: !strategy.public,
      });
      upsertRemoteStrategy(revised);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['connected-api-snapshot'] }),
        queryClient.invalidateQueries({ queryKey: ['rankings'] }),
      ]);
      router.replace({ pathname: '/strategy/[id]', params: { id: revised.id } });
    } catch (caught) {
      Alert.alert('공개 설정 실패', caught instanceof Error ? caught.message : '잠시 뒤 다시 시도하세요.');
    } finally {
      setSavingVisibility(false);
    }
  };
  const deleteStrategy = async () => {
    if (!strategy.remoteStrategyId) return Alert.alert('동기화 대기', '서버 전략을 받은 뒤 다시 시도하세요.');
    setDeleting(true);
    try {
      await deleteRemoteStrategy(strategy.remoteStrategyId);
      removeRemoteStrategy(strategy.remoteStrategyId);
      await queryClient.invalidateQueries({ queryKey: ['connected-api-snapshot'] });
      router.replace('/(tabs)/create');
    } catch (caught) {
      Alert.alert('전략 삭제 실패', caught instanceof Error ? caught.message : '잠시 뒤 다시 시도하세요.');
    } finally {
      setDeleting(false);
    }
  };
  return (
    <Screen>
      {saved === '1' ? <Banner tone="positive" title="전략을 저장했습니다" body="이 전략은 다음 완성 일봉부터 조건 전환을 평가합니다. 새 신호가 없으면 홈에는 0건으로 표시됩니다." /> : null}
      <TitleBlock eyebrow={`전략 v${strategy.version} · 일봉`} title={strategy.name} body={`${universe?.name} · ${strategy.indicatorIds.length}개 지표를 ${strategy.conditionMode === 'ALL' ? '모두' : '하나 이상'} 충족`} />
      {historical ? <Banner title="이전 전략 버전" body="신호 근거 보존용 읽기 전용 버전입니다." /> : null}
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
          return <React.Fragment key={indicator.id}><ListRow title={`${index + 1}. ${indicator.name}`} subtitle={remoteStrategyRuleLabel(indicator.id)} value={indicator.tier} onPress={() => router.push({ pathname: '/indicator/[id]', params: { id: indicator.id } })} />{index < strategy.indicatorIds.length - 1 ? <Divider /> : null}</React.Fragment>;
        })}
      </Surface>
      {!historical ? <><Button label="새 버전으로 수정" kind="secondary" onPress={() => router.push({ pathname: '/(tabs)/create', params: { edit: strategy.id } })} /><Button label={savingVisibility ? '저장 중…' : strategy.public ? '비공개로 전환' : '공개로 전환'} kind="secondary" disabled={savingVisibility || deleting} onPress={() => confirmAction('새 전략 버전', `공개 설정을 바꾸면 v${strategy.version + 1}이 생성됩니다.`, changeVisibility)} /><Button label={deleting ? '삭제 중…' : '전략 삭제'} kind="danger" disabled={savingVisibility || deleting} onPress={() => confirmAction('전략 완전 삭제', '전략의 모든 버전과 생성된 신호가 함께 삭제됩니다. 이 작업은 되돌릴 수 없습니다.', deleteStrategy, true)} /></> : null}
      {history.length ? <><SectionTitle title="이전 버전" /><Surface style={{ paddingVertical: 0 }}>{history.map((version, index) => <React.Fragment key={`${version.id}-${version.version}`}><ListRow title={`v${version.version} · ${version.name}`} subtitle={`${version.indicatorIds.length}개 지표 · ${version.conditionMode} · 읽기 전용`} value={version.public ? '공개였음' : '비공개'} />{index < history.length - 1 ? <Divider /> : null}</React.Fragment>)}</Surface></> : null}
      <Banner tone="accent" title="신호는 자동 실주문이 아니에요" body="실제 체결이 발생하면 사용자가 매매 내역을 직접 등록합니다." />
    </Screen>
  );
}

const styles = StyleSheet.create({ row: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.sm }, wrap: { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'flex-end', gap: spacing.xs, flex: 1 } });

function confirmAction(title: string, message: string, action: () => Promise<void>, destructive = false) {
  if (Platform.OS === 'web') {
    const webWindow = globalThis as unknown as { confirm(message: string): boolean };
    if (webWindow.confirm(`${title}\n\n${message}`)) void action();
    return;
  }
  Alert.alert(title, message, [
    { text: '취소', style: 'cancel' },
    { text: destructive ? '삭제' : '확인', style: destructive ? 'destructive' : 'default', onPress: () => { void action(); } },
  ]);
}
