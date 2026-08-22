import React, { useMemo, useState } from 'react';
import { router, useLocalSearchParams } from 'expo-router';
import { Alert, StyleSheet, View } from 'react-native';
import { AppText, Banner, Button, Chip, Divider, Field, Screen, SectionTitle, Surface, ToggleRow, spacing } from '@signal/ui';
import { Segmented, TitleBlock } from '@/components/common';
import { indicators } from '@/data/mock';
import type { IndicatorId, SellRule } from '@/domain/types';
import { saveRemoteSellRule } from '@/services/connected-api';
import { useRemoteApiReady } from '@/hooks/useRemoteApiReady';
import { useAppStore } from '@/store/useAppStore';
import { toNumber } from '@/utils/format';

export default function SellRuleScreen() {
  const { positionId } = useLocalSearchParams<{ positionId: string }>();
  const position = useAppStore((state) => state.positions.find((item) => item.id === positionId));
  const saveSellRule = useAppStore((state) => state.saveSellRule);
  const remoteApiReady = useRemoteApiReady();
  const existing = position?.sellRule;
  const [manualOnly, setManualOnly] = useState(existing?.manualOnly ?? false);
  const [stopOn, setStopOn] = useState(existing?.stopLossPercent != null);
  const [targetOn, setTargetOn] = useState(existing?.takeProfitPercent != null);
  const [daysOn, setDaysOn] = useState(existing?.maxHoldingDays != null);
  const [trailOn, setTrailOn] = useState(existing?.trailingStopPercent != null);
  const [stop, setStop] = useState(existing?.stopLossPercent?.toString() ?? '');
  const [target, setTarget] = useState(existing?.takeProfitPercent?.toString() ?? '');
  const [days, setDays] = useState(existing?.maxHoldingDays?.toString() ?? '');
  const [trail, setTrail] = useState(existing?.trailingStopPercent?.toString() ?? '');
  const [technical, setTechnical] = useState<IndicatorId[]>(existing?.technicalIds ?? []);
  const [technicalMode, setTechnicalMode] = useState<'ANY' | 'ALL'>(existing?.technicalMode ?? 'ANY');
  const [advanced, setAdvanced] = useState(Boolean(existing?.trailingStopPercent || existing?.technicalIds.length));
  const [saving, setSaving] = useState(false);
  const invalid = useMemo(() => {
    if (manualOnly) return null;
    if (![stopOn, targetOn, daysOn, trailOn, technical.length > 0].some(Boolean)) return '자동 규칙을 하나 이상 켜거나 수동 관리만 선택하세요.';
    const values = [{ on: stopOn, value: stop, name: '손절률' }, { on: targetOn, value: target, name: '목표수익률' }, { on: trailOn, value: trail, name: '추적 손절률' }, { on: daysOn, value: days, name: '최대 보유일' }];
    const bad = values.find((item) => item.on && (!Number.isFinite(toNumber(item.value)) || toNumber(item.value) <= 0));
    if (bad) return `${bad.name} 값을 직접 입력하세요.`;
    if ((stopOn && toNumber(stop) > 100) || (trailOn && toNumber(trail) > 100)) return '손절률은 100% 이하여야 합니다.';
    if (daysOn && !Number.isInteger(toNumber(days))) return '최대 보유일은 정수로 입력하세요.';
    return null;
  }, [days, daysOn, manualOnly, stop, stopOn, target, targetOn, technical.length, trail, trailOn]);
  if (!position) return <Screen><Banner tone="negative" title="포지션을 찾지 못했어요" /></Screen>;

  const toggleTechnical = (id: IndicatorId) => setTechnical((current) => current.includes(id) ? current.filter((item) => item !== id) : current.length < 3 ? [...current, id] : current);
  const save = async () => {
    if (invalid) return Alert.alert('저장할 수 없어요', invalid);
    const rule: SellRule = { version: (existing?.version ?? 0) + 1, manualOnly, stopLossPercent: !manualOnly && stopOn ? toNumber(stop) : null, takeProfitPercent: !manualOnly && targetOn ? toNumber(target) : null, trailingStopPercent: !manualOnly && trailOn ? toNumber(trail) : null, maxHoldingDays: !manualOnly && daysOn ? Math.floor(toNumber(days)) : null, technicalIds: manualOnly ? [] : technical, technicalMode };
    setSaving(true);
    try {
      if (remoteApiReady) await saveRemoteSellRule(position.id, rule);
      saveSellRule(position.id, rule);
      Alert.alert('새 규칙 버전을 저장했어요', '이 열린 포지션에 새 버전을 적용했습니다. 이전 버전 기록은 유지됩니다.');
      router.back();
    } catch (caught) {
      Alert.alert('규칙 저장 실패', caught instanceof Error ? caught.message : '잠시 뒤 다시 시도하세요.');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Screen>
      <TitleBlock eyebrow={`${position.instrumentName} · 규칙 v${(existing?.version ?? 0) + 1}`} title="언제 상태를 알릴까요?" body="일봉 마감 기준입니다. 알림은 자동 매도 주문이 아니며 수량을 바꾸지 않습니다." />
      <ToggleRow title="수동 관리만 사용" body="자동 매도 신호가 없다는 경고를 표시합니다." value={manualOnly} onValueChange={setManualOnly} />
      {manualOnly ? <Banner title="자동 매도 신호가 없습니다" body="시장과 포지션을 직접 확인해야 합니다. 공식 랭킹 전략에서는 이 선택을 쓸 수 없습니다." /> : (
        <>
          <SectionTitle title="초보 보호 규칙" />
          <Surface style={{ gap: spacing.md }}>
            <ToggleRow title="고정 손절률" body="조정 뒤 이동평균 매입단가 기준" value={stopOn} onValueChange={setStopOn} />
            {stopOn ? <Field label="손절률 (%)" value={stop} onChangeText={setStop} keyboardType="decimal-pad" placeholder="직접 입력" /> : null}
            <Divider />
            <ToggleRow title="고정 목표수익률" body="조정 뒤 이동평균 매입단가 기준" value={targetOn} onValueChange={setTargetOn} />
            {targetOn ? <Field label="목표수익률 (%)" value={target} onChangeText={setTarget} keyboardType="decimal-pad" placeholder="직접 입력" /> : null}
            <Divider />
            <ToggleRow title="최대 보유 거래일" body="휴장일을 빼고 첫 BUY부터 계산" value={daysOn} onValueChange={setDaysOn} />
            {daysOn ? <Field label="최대 보유일" value={days} onChangeText={setDays} keyboardType="number-pad" placeholder="직접 입력" /> : null}
          </Surface>
          <ToggleRow title="고급 규칙 펼치기" body="추적 손절 · 기술 지표 최대 3개" value={advanced} onValueChange={setAdvanced} />
          {advanced ? <Surface style={{ gap: spacing.md }}>
            <ToggleRow title="최고 종가 기준 추적 손절" body="추가 BUY 뒤에도 최고 종가는 초기화하지 않음" value={trailOn} onValueChange={setTrailOn} />
            {trailOn ? <Field label="최고 종가 대비 하락률 (%)" value={trail} onChangeText={setTrail} keyboardType="decimal-pad" placeholder="직접 입력" /> : null}
            <Divider />
            <View style={{ gap: spacing.xs }}><AppText variant="bodyStrong">기술 지표 {technical.length}/3</AppText><AppText variant="caption" tone="muted">기술 그룹 안에서만 ANY 또는 ALL을 씁니다.</AppText></View>
            <View style={styles.wrap}>{indicators.map((item) => <Chip key={item.id} label={item.name} selected={technical.includes(item.id)} onPress={() => toggleTechnical(item.id)} />)}</View>
            {technical.length ? <Segmented options={[{ value: 'ANY', label: '하나 이상' }, { value: 'ALL', label: '모두' }]} value={technicalMode} onChange={setTechnicalMode} /> : null}
          </Surface> : null}
        </>
      )}
      <Banner tone="accent" title="전체 판정은 OR" body="손절 OR 목표수익 OR 추적 손절 OR 최대 보유일 OR 기술 그룹입니다. 같은 봉에서 여러 조건이 맞으면 알림 하나에 근거를 합칩니다." />
      {invalid ? <AppText tone="negative">{invalid}</AppText> : null}
      <Button label={saving ? '저장 중…' : `규칙 v${(existing?.version ?? 0) + 1} 저장`} onPress={() => { void save(); }} disabled={Boolean(invalid) || saving} />
    </Screen>
  );
}

const styles = StyleSheet.create({ wrap: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs } });
