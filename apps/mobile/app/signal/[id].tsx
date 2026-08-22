import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { router, useLocalSearchParams } from 'expo-router';
import { StyleSheet, View } from 'react-native';
import { AppText, Banner, Button, Chip, Divider, EmptyState, ListRow, Metric, Screen, SectionTitle, Surface, spacing } from '@signal/ui';
import { PriceChange, TitleBlock } from '@/components/common';
import { universes } from '@/data/mock';
import { acknowledgeRemoteSignal, buildLocalSignalAdvice, requestRemoteSignalAdvice } from '@/services/connected-api';
import { useRemoteApiReady } from '@/hooks/useRemoteApiReady';
import { useAppStore } from '@/store/useAppStore';
import { formatDateTime, formatPrice } from '@/utils/format';
import type { SignalAdvice } from '@/domain/types';

export default function SignalDetailScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const signal = useAppStore((state) => state.signals.find((item) => item.id === id));
  const strategies = useAppStore((state) => state.strategies);
  const strategyHistory = useAppStore((state) => state.strategyHistory);
  const strategy = useMemo(
    () => strategies.find((item) => item.id === signal?.strategyId) ?? strategyHistory.find((item) => item.id === signal?.strategyId),
    [signal?.strategyId, strategies, strategyHistory],
  );
  const markRead = useAppStore((state) => state.markSignalRead);
  const remoteApiReady = useRemoteApiReady();
  const [ackError, setAckError] = useState<string | null>(null);
  const [advice, setAdvice] = useState<SignalAdvice | null>(null);
  const [adviceBusy, setAdviceBusy] = useState(false);
  const [adviceError, setAdviceError] = useState<string | null>(null);
  const acknowledge = useCallback(async () => {
    if (!id) return;
    if (!remoteApiReady) {
      markRead(id);
      return;
    }
    try {
      await acknowledgeRemoteSignal(id);
      markRead(id);
      setAckError(null);
    } catch (caught) {
      setAckError(caught instanceof Error ? caught.message : '신호 확인 처리에 실패했습니다.');
    }
  }, [id, markRead, remoteApiReady]);
  useEffect(() => { if (signal && !signal.read) void acknowledge(); }, [acknowledge, signal]);
  if (!signal) return <Screen><EmptyState title="신호를 찾지 못했어요" body="삭제됐거나 접근할 수 없는 신호입니다." action="홈" onAction={() => router.replace('/(tabs)')} /></Screen>;
  const explain = async () => {
    setAdviceBusy(true);
    setAdviceError(null);
    try {
      setAdvice(remoteApiReady ? await requestRemoteSignalAdvice(signal.id) : buildLocalSignalAdvice(signal));
    } catch (caught) {
      setAdviceError(caught instanceof Error ? caught.message : 'AI 설명을 생성하지 못했습니다.');
    } finally {
      setAdviceBusy(false);
    }
  };
  const universe = universes.find((item) => item.id === strategy?.universeId);
  const tradeParams = { signalId: signal.id, symbol: signal.symbol, name: signal.instrumentName, price: String(signal.closePrice), side: 'BUY' };
  return (
    <Screen>
      <TitleBlock eyebrow="사용자 설정 조건 충족 신호" title={signal.instrumentName} body={`${signal.symbol} · ${signal.candleClose} 완성 일봉`} />
      {ackError ? <Banner tone="negative" title="읽음 처리 실패" body={ackError} action="다시 시도" onAction={() => { void acknowledge(); }} /> : null}
      <Surface style={{ gap: spacing.md }}>
        <View style={styles.row}><PriceChange price={signal.closePrice} change={signal.changeRate} /><Chip label="일봉 확정" selected /></View>
        <Divider />
        <View style={styles.metrics}>{signal.values.map((item) => <Metric key={item.label} label={item.label} value={item.value} />)}</View>
      </Surface>
      {signal.delayed ? <Banner title="데이터 지연 표시" body="이 신호는 처리 지연 뒤 생성됐습니다. 기준 봉과 계산값을 확인하세요." /> : null}
      <SectionTitle title="충족 근거" />
      <Surface style={{ paddingVertical: 0 }}>{signal.reasons.map((reason, index) => <React.Fragment key={reason}><ListRow title={`${index + 1}. ${reason}`} subtitle="false → true 전환 확인" />{index < signal.reasons.length - 1 ? <Divider /> : null}</React.Fragment>)}</Surface>
      <SectionTitle title="고정된 맥락" />
      <Surface style={{ paddingVertical: 0 }}>
        <ListRow title="전략" subtitle={strategy ? `${strategy.name} v${strategy.version}` : '전략 정보 없음'} {...(strategy ? { onPress: () => router.push({ pathname: '/strategy/[id]', params: { id: strategy.id } }) } : {})} />
        <Divider />
        <ListRow title="종목 범위" subtitle={`${universe?.name ?? '-'} · ${universe?.version ?? '-'}`} />
        <Divider />
        <ListRow title="생성 시각" subtitle={formatDateTime(signal.createdAt)} />
      </Surface>
      <SectionTitle title="AI 신호 해석" />
      {adviceError ? <Banner tone="negative" title="설명 생성 실패" body={adviceError} action="다시 시도" onAction={() => { void explain(); }} /> : null}
      {advice ? (
        <Surface style={{ gap: spacing.md }}>
          <View style={styles.adviceHeader}>
            <AppText variant="subtitle" style={{ flex: 1 }}>{advice.summary}</AppText>
            <Chip label={advice.source === 'GEMINI' ? 'Gemini 생성' : '로컬 설명'} selected={advice.source === 'GEMINI'} />
          </View>
          <Divider />
          <AdviceList title="확인된 근거" items={advice.evidence} />
          <AdviceList title="주의할 위험" items={advice.risks} tone="warning" />
          <AdviceList title="결정 전 확인" items={advice.questionsToConsider} />
          <AppText variant="caption" tone="muted">기준 시각 {formatDateTime(advice.basedOn)} · {advice.model}</AppText>
          <AppText variant="caption" tone="muted">{advice.disclaimer}</AppText>
        </Surface>
      ) : (
        <Button label="AI로 신호 해석하기" kind="secondary" busy={adviceBusy} onPress={() => { void explain(); }} />
      )}
      {strategy?.locked ? (
        <Banner tone="accent" title="공식 랭킹 자동 가상 체결" body={`다음 체결 가능 거래일 공식 시가에 NAV 10% 한도로 1회 시도합니다. 사용자는 주문·가격을 고를 수 없습니다. 기준가 ${formatPrice(signal.closePrice)}`} />
      ) : (
        <>
          <Button label="실제 보유 등록" onPress={() => router.push({ pathname: '/trade', params: { ...tradeParams, mode: 'manual' } })} />
          <Button label="연습 페이퍼 주문" kind="secondary" onPress={() => router.push({ pathname: '/trade', params: { ...tradeParams, mode: 'paper' } })} />
        </>
      )}
      <AppText variant="caption" tone="muted" style={{ textAlign: 'center' }}>이 신호는 투자자문·매수 추천·수익 보장이 아닙니다.</AppText>
    </Screen>
  );
}

function AdviceList({ title, items, tone = 'default' }: { title: string; items: string[]; tone?: 'default' | 'warning' }) {
  return (
    <View style={{ gap: spacing.xs }}>
      <AppText variant="bodyStrong" tone={tone}>{title}</AppText>
      {items.map((item, index) => <AppText key={`${title}-${index}`} tone="muted">• {item}</AppText>)}
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  adviceHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start', gap: spacing.sm },
  metrics: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.md },
});
