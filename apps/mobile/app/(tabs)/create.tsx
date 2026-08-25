import React, { useMemo, useState } from 'react';
import { router, useLocalSearchParams } from 'expo-router';
import { useQueryClient } from '@tanstack/react-query';
import { Alert, Pressable, StyleSheet, View } from 'react-native';
import { AppText, Banner, Button, Chip, Divider, EmptyState, Field, Screen, SectionTitle, Surface, ToggleRow, spacing, useSignalTheme } from '@signal/ui';
import { Segmented, TitleBlock } from '@/components/common';
import { indicators } from '@/data/catalog';
import { useUniverses } from '@/hooks/useUniverses';
import type { IndicatorId } from '@/domain/types';
import { createRemoteStrategy, loadRemoteUniverseVersions, remoteStrategyRuleLabel, remoteUniverseKind, reviseRemoteStrategy } from '@/services/connected-api';
import { useRemoteApiReady } from '@/hooks/useRemoteApiReady';
import { useAppStore } from '@/store/useAppStore';

export default function CreateScreen() {
  const { colors } = useSignalTheme();
  const queryClient = useQueryClient();
  const remoteApiReady = useRemoteApiReady();
  const selectedUniverseId = useAppStore((state) => state.selectedUniverseId);
  const setUniverse = useAppStore((state) => state.setUniverse);
  const strategies = useAppStore((state) => state.strategies);
  const upsertRemoteStrategy = useAppStore((state) => state.upsertRemoteStrategy);
  const universeQuery = useUniverses();
  const universes = universeQuery.data ?? [];
  const params = useLocalSearchParams<{ edit?: string }>();
  const editing = strategies.find((item) => item.id === params.edit && !item.locked);
  const [name, setName] = useState(editing?.name ?? '내 일봉 전략');
  const [selected, setSelected] = useState<IndicatorId[]>(editing?.indicatorIds ?? ['rsi']);
  const [mode, setMode] = useState<'ALL' | 'ANY'>(editing?.conditionMode ?? 'ALL');
  const [advanced, setAdvanced] = useState(false);
  const [makePublic, setMakePublic] = useState(editing?.public ?? false);
  const [saving, setSaving] = useState(false);
  const [universeChecking, setUniverseChecking] = useState(false);
  const [universeNotice, setUniverseNotice] = useState<string | null>(null);
  React.useEffect(() => {
    if (!editing) return;
    setName(editing.name);
    setSelected(editing.indicatorIds);
    setMode(editing.conditionMode);
    setMakePublic(editing.public);
    setUniverse(editing.universeId);
  }, [editing, setUniverse]);
  React.useEffect(() => {
    if (!remoteApiReady || editing) return;
    let active = true;
    setUniverseChecking(true);
    void loadRemoteUniverseVersions().then((versions) => {
      if (!active) return;
      const availableKinds = new Set(versions.map((item) => item.kind));
      if (availableKinds.has(remoteUniverseKind(selectedUniverseId))) {
        setUniverseNotice(null);
        return;
      }
      const fallback = (['kospi', 'kosdaq', 'all'] as const).find((id) => availableKinds.has(remoteUniverseKind(id)));
      if (fallback) {
        setUniverse(fallback);
        setUniverseNotice('선택한 종목군 데이터가 아직 없어 현재 사용 가능한 범위로 변경했습니다.');
      } else {
        setUniverseNotice('저장 가능한 종목군 데이터가 없습니다. Worker 데이터 수집 상태를 확인하세요.');
      }
    }).catch((caught: unknown) => {
      if (active) setUniverseNotice(caught instanceof Error ? caught.message : '종목군 데이터를 확인하지 못했습니다.');
    }).finally(() => {
      if (active) setUniverseChecking(false);
    });
    return () => { active = false; };
  }, [editing, remoteApiReady, selectedUniverseId, setUniverse]);
  const selectedUniverse = universes.find((item) => item.id === selectedUniverseId);
  const error = useMemo(() => !name.trim() ? '전략 이름을 입력하세요.' : selected.length === 0 ? '지표를 1개 이상 선택하세요.' : null, [name, selected.length]);

  const toggleIndicator = (id: IndicatorId) => {
    setSelected((current) => {
      if (current.includes(id)) return current.filter((item) => item !== id);
      if (current.length >= 5) {
        Alert.alert('최대 5개', '개인 전략에는 지표를 최대 5개 넣을 수 있어요.');
        return current;
      }
      return [...current, id];
    });
  };

  const save = async () => {
    if (error) return Alert.alert('저장할 수 없어요', error);
    if (!remoteApiReady) return Alert.alert('로그인이 필요합니다', '실제 전략은 로그인 후 서버에 저장됩니다.');
    const input = { name: name.trim(), universeId: selectedUniverseId, indicatorIds: selected, conditionMode: mode, public: makePublic };
    setSaving(true);
    try {
      if (editing && !editing.remoteStrategyId) throw new Error('서버 전략 동기화를 기다린 뒤 다시 시도하세요.');
      const strategy = editing
        ? await reviseRemoteStrategy(editing.remoteStrategyId!, input)
        : await createRemoteStrategy(input);
      upsertRemoteStrategy(strategy);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['connected-api-snapshot'] }),
        queryClient.invalidateQueries({ queryKey: ['rankings'] }),
      ]);
      router.push({ pathname: '/strategy/[id]', params: { id: strategy.id, saved: '1' } });
    } catch (caught) {
      Alert.alert('전략 저장 실패', caught instanceof Error ? caught.message : '잠시 뒤 다시 시도하세요.');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Screen>
      <TitleBlock eyebrow="일봉 · 버전 고정" title={editing ? '새 전략 버전 만들기' : '내 조건 만들기'} body="완성된 일봉에서 false → true로 바뀐 날만 새 신호를 만듭니다." />
      {!remoteApiReady ? <Banner tone="warning" title="저장하려면 로그인이 필요합니다" body="종목 범위와 지표는 둘러볼 수 있으며, 로그인 후 실제 전략을 서버에 저장합니다." action="로그인" onAction={() => router.push({ pathname: '/auth', params: { origin: 'create' } })} /> : null}
      <Field label="전략 이름" value={name} onChangeText={setName} maxLength={30} {...(!name.trim() ? { error: '이름이 필요해요.' } : {})} />
      <SectionTitle title="1. 종목 범위" />
      {universeNotice ? <Banner tone="warning" title="종목 범위 확인" body={universeNotice} /> : null}
      <Pressable accessibilityRole="button" onPress={() => router.push({ pathname: '/universe', params: { origin: 'create' } })}>
        <Surface style={styles.row}><View style={{ flex: 1, gap: 4 }}><AppText variant="bodyStrong">{selectedUniverse?.name}</AppText><AppText variant="caption" tone="muted">{selectedUniverse?.count.toLocaleString('ko-KR')}종목 · {selectedUniverse?.version}</AppText></View><AppText tone="accent" variant="bodyStrong">바꾸기</AppText></Surface>
      </Pressable>
      <View style={styles.chips}>{universes.slice(0, 3).map((item) => <Chip key={item.id} label={item.name} selected={selectedUniverseId === item.id} onPress={() => setUniverse(item.id)} />)}</View>

      <SectionTitle title={`2. 지표 선택 ${selected.length}/5`} />
      <Surface style={{ paddingVertical: 0 }}>
        {indicators.map((indicator, index) => {
          const active = selected.includes(indicator.id);
          return (
            <React.Fragment key={indicator.id}>
              <Pressable accessibilityRole="checkbox" accessibilityState={{ checked: active }} onPress={() => toggleIndicator(indicator.id)} style={styles.indicator}>
                <View style={[styles.check, { borderColor: active ? colors.accent : colors.border }, active && { backgroundColor: colors.accent }]}><AppText tone={active ? 'inverse' : 'muted'}>{active ? '✓' : ''}</AppText></View>
                <View style={{ flex: 1, gap: 3 }}><AppText variant="bodyStrong">{indicator.name}</AppText><AppText variant="caption" tone="muted">{remoteApiReady ? remoteStrategyRuleLabel(indicator.id) : indicator.defaultRule}</AppText></View>
                <Pressable accessibilityRole="button" accessibilityLabel={`${indicator.name} 설명`} hitSlop={10} onPress={() => router.push({ pathname: '/indicator/[id]', params: { id: indicator.id } })}><AppText tone="accent">설명</AppText></Pressable>
              </Pressable>
              {index < indicators.length - 1 ? <Divider /> : null}
            </React.Fragment>
          );
        })}
      </Surface>

      <SectionTitle title="3. 조건 관계" />
      <Segmented options={[{ value: 'ALL', label: '모두 충족 (AND)' }, { value: 'ANY', label: '하나 이상 (OR)' }]} value={mode} onChange={setMode} />
      <ToggleRow title="고급 설정 펼치기" body="파라미터와 알림 cooldown 확인" value={advanced} onValueChange={setAdvanced} />
      {advanced ? <Surface style={{ gap: spacing.md }}><AppText variant="bodyStrong">고급 설정</AppText><AppText tone="muted">선택 지표별 파라미터는 검증 grid 기본값을 씁니다. 신호 cooldown은 종목·전략별 24시간입니다.</AppText><Banner title="파라미터 변경은 새 전략 버전을 만듭니다" /></Surface> : null}
      <ToggleRow title="전략 공개" body="공개하면 랭킹 프로필의 공개 전략 목록에 표시됩니다. 실제 보유 정보는 공개되지 않습니다." value={makePublic} onValueChange={setMakePublic} />
      <Button label={!remoteApiReady ? '로그인 후 전략 저장' : saving ? '저장 중…' : universeChecking || universeQuery.isPending ? '종목군 확인 중…' : editing ? `v${editing.version + 1}로 저장` : '전략 저장'} onPress={() => { void save(); }} disabled={Boolean(error) || saving || universeChecking || universeQuery.isPending || !remoteApiReady} />

      <SectionTitle title={`내 전략 ${strategies.length}개`} />
      {strategies.length === 0 ? <EmptyState title="아직 저장한 전략이 없습니다" body="위에서 종목 범위와 지표를 정한 뒤 첫 전략을 저장하세요." /> : strategies.map((strategy) => (
        <Pressable key={strategy.id} accessibilityRole="button" onPress={() => router.push({ pathname: '/strategy/[id]', params: { id: strategy.id } })}>
          <Surface style={styles.row}><View style={{ flex: 1, gap: 4 }}><AppText variant="bodyStrong">{strategy.name}</AppText><AppText variant="caption" tone="muted">v{strategy.version} · {strategy.indicatorIds.length}개 지표 · {strategy.conditionMode}</AppText></View><Chip label={strategy.locked ? '잠김' : strategy.public ? '공개' : '비공개'} tone={strategy.locked ? 'warning' : 'default'} /></Surface>
        </Pressable>
      ))}
    </Screen>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.sm },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs },
  indicator: { minHeight: 76, flexDirection: 'row', alignItems: 'center', gap: spacing.sm, paddingVertical: spacing.sm },
  check: { width: 26, height: 26, borderRadius: 8, borderWidth: 1, alignItems: 'center', justifyContent: 'center' },
});
