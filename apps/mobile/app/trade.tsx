import React, { useMemo, useState } from 'react';
import { router, useLocalSearchParams } from 'expo-router';
import { Alert, StyleSheet, View } from 'react-native';
import { useQueryClient } from '@tanstack/react-query';
import { AppText, Banner, Button, Field, Screen, Surface, spacing } from '@signal/ui';
import { Segmented, TitleBlock } from '@/components/common';
import {
  remoteIdempotencyKey,
  submitRemoteManualExecution,
} from '@/services/connected-api';
import { useRemoteApiReady } from '@/hooks/useRemoteApiReady';
import { useAppStore } from '@/store/useAppStore';
import { formatPrice, toNumber } from '@/utils/format';

export default function TradeScreen() {
  const queryClient = useQueryClient();
  const remoteApiReady = useRemoteApiReady();
  const params = useLocalSearchParams<{ side?: 'BUY' | 'SELL'; signalId?: string; positionId?: string; symbol?: string; name?: string; price?: string }>();
  const position = useAppStore((state) => state.positions.find((item) => item.id === params.positionId));
  const portfolioIds = useAppStore((state) => state.portfolioIds);
  const [side, setSide] = useState<'BUY' | 'SELL'>(params.side ?? 'BUY');
  const [symbol, setSymbol] = useState(params.symbol ?? position?.symbol ?? '');
  const [name, setName] = useState(params.name ?? position?.instrumentName ?? '');
  const [quantity, setQuantity] = useState(side === 'SELL' && position ? String(position.quantity) : '1');
  const [price, setPrice] = useState(params.price ?? (position ? String(position.currentPrice) : ''));
  const [date, setDate] = useState(new Date().toISOString().slice(0, 10));
  const [memo, setMemo] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const qty = toNumber(quantity);
  const unitPrice = toNumber(price);
  const error = useMemo(() => !/^\d{6}$/.test(symbol) ? '6자리 종목 코드가 필요해요.' : !name.trim() ? '종목명이 필요해요.' : !Number.isInteger(qty) || qty <= 0 ? '수량은 1 이상의 정수여야 해요.' : !Number.isFinite(unitPrice) || unitPrice <= 0 ? '단가는 0보다 커야 해요.' : side === 'SELL' && position && qty > position.quantity ? `보유 ${position.quantity}주를 초과했어요.` : null, [name, position, qty, side, symbol, unitPrice]);
  const total = Number.isFinite(qty * unitPrice) ? qty * unitPrice : 0;

  const submit = async () => {
    if (error) return Alert.alert('확인 필요', error);
    if (!remoteApiReady) return Alert.alert('로그인이 필요합니다', '실제 원장 작업은 로그인 후 서버에서 처리됩니다.');
    setSubmitting(true);
    try {
      const portfolioId = portfolioIds.MANUAL_LIVE;
      if (!portfolioId) throw new Error('실제 매매 포트폴리오를 준비하지 못했습니다. 잠시 후 다시 시도하세요.');
      await submitRemoteManualExecution({
        portfolioId, positionId: position?.id ?? null, symbol, side, price: unitPrice, quantity: qty,
        executedAt: `${date}T00:00:00.000Z`, signalId: params.signalId ?? null, memo,
        idempotencyKey: remoteIdempotencyKey('manual'),
      });
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['connected-api-snapshot'] }),
        queryClient.invalidateQueries({ queryKey: ['rankings'] }),
      ]);
      Alert.alert('체결을 기록했어요', side === 'SELL' && position && qty === position.quantity ? '전량매도되어 감시를 종료합니다.' : '실제 거래를 기록했습니다.');
      router.replace(position ? { pathname: '/position/[id]', params: { id: position.id } } : '/holdings');
    } catch (reason) {
      Alert.alert('처리할 수 없어요', reason instanceof Error ? reason.message : '입력값을 확인하세요.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Screen>
      <TitleBlock eyebrow={side === 'BUY' ? 'BUY' : 'SELL'} title="실제 체결 직접 등록" body="앱 밖에서 실제로 체결된 거래만 적으세요. 공개 설정 시 수익률 랭킹에 반영됩니다." />
      {position ? <Segmented options={[{ value: 'BUY', label: '추가 매수' }, { value: 'SELL', label: '매도' }]} value={side} onChange={(value) => { setSide(value); if (value === 'SELL') setQuantity(String(position.quantity)); }} /> : null}
      <Surface style={{ gap: spacing.md }}>
        <View style={styles.two}><Field label="종목 코드" value={symbol} onChangeText={setSymbol} keyboardType="number-pad" maxLength={6} editable={!position} style={{ flex: 1 }} /><Field label="종목명" value={name} onChangeText={setName} editable={!position} style={{ flex: 1 }} /></View>
        <View style={styles.two}><Field label="수량 (정수)" value={quantity} onChangeText={setQuantity} keyboardType="number-pad" style={{ flex: 1 }} /><Field label="실제 체결 단가" value={price} onChangeText={setPrice} keyboardType="number-pad" style={{ flex: 1 }} /></View>
        <Field label="체결일 (YYYY-MM-DD)" value={date} onChangeText={setDate} />
        <Field label="메모 (선택)" value={memo} onChangeText={setMemo} placeholder="증권사 체결 기록 등" />
      </Surface>
      <Surface style={{ gap: spacing.sm }}><View style={styles.row}><AppText tone="muted">체결 금액</AppText><AppText variant="subtitle">{formatPrice(total)}</AppText></View></Surface>
      <Banner title="직접 작성한 기록입니다" body="증권사 인증 내역이 아니며, 공개 프로필이면 이 기록부터 사용자 랭킹에 반영됩니다." />
      {error ? <AppText tone="negative">{error}</AppText> : null}
      <Button label={submitting ? '처리 중…' : `${side === 'BUY' ? '매수' : '매도'} 체결 등록`} onPress={() => void submit()} disabled={Boolean(error) || submitting || !remoteApiReady} />
    </Screen>
  );
}

const styles = StyleSheet.create({ two: { flexDirection: 'row', gap: spacing.sm }, row: { flexDirection: 'row', justifyContent: 'space-between', gap: spacing.sm } });
