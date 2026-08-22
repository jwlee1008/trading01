import React, { useMemo, useState } from 'react';
import { router, useLocalSearchParams } from 'expo-router';
import { Alert, StyleSheet, View } from 'react-native';
import { AppText, Banner, Button, Field, Screen, Surface, spacing } from '@signal/ui';
import { Segmented, TitleBlock } from '@/components/common';
import {
  remoteIdempotencyKey,
  submitRemoteManualExecution,
  submitRemotePaperOrder,
} from '@/services/connected-api';
import { useRemoteApiReady } from '@/hooks/useRemoteApiReady';
import { useAppStore } from '@/store/useAppStore';
import { formatPrice, toNumber } from '@/utils/format';

type Mode = 'manual' | 'paper';

export default function TradeScreen() {
  const remoteApiReady = useRemoteApiReady();
  const params = useLocalSearchParams<{ mode?: Mode; side?: 'BUY' | 'SELL'; signalId?: string; positionId?: string; symbol?: string; name?: string; price?: string }>();
  const position = useAppStore((state) => state.positions.find((item) => item.id === params.positionId));
  const registerManual = useAppStore((state) => state.registerManualHolding);
  const addManualBuy = useAppStore((state) => state.addManualBuy);
  const sellManual = useAppStore((state) => state.sellManual);
  const placePaperOrder = useAppStore((state) => state.placePaperOrder);
  const [mode, setMode] = useState<Mode>(params.mode ?? (position?.kind === 'SANDBOX_PAPER' ? 'paper' : 'manual'));
  const [side, setSide] = useState<'BUY' | 'SELL'>(params.side ?? 'BUY');
  const [symbol, setSymbol] = useState(params.symbol ?? position?.symbol ?? '');
  const [name, setName] = useState(params.name ?? position?.instrumentName ?? '');
  const [quantity, setQuantity] = useState(side === 'SELL' && position ? String(position.quantity) : '1');
  const [price, setPrice] = useState(params.price ?? (position ? String(position.currentPrice) : ''));
  const [date, setDate] = useState('2026-08-15');
  const [memo, setMemo] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const qty = toNumber(quantity);
  const unitPrice = toNumber(price);
  const error = useMemo(() => !/^\d{6}$/.test(symbol) ? '6자리 종목 코드가 필요해요.' : !name.trim() ? '종목명이 필요해요.' : !Number.isInteger(qty) || qty <= 0 ? '수량은 1 이상의 정수여야 해요.' : !Number.isFinite(unitPrice) || unitPrice <= 0 ? '단가는 0보다 커야 해요.' : side === 'SELL' && position && qty > position.quantity ? `보유 ${position.quantity}주를 초과했어요.` : null, [name, position, qty, side, symbol, unitPrice]);
  const total = Number.isFinite(qty * unitPrice) ? qty * unitPrice : 0;

  const submit = async () => {
    if (error) return Alert.alert('확인 필요', error);
    setSubmitting(true);
    try {
      if (mode === 'manual') {
        if (remoteApiReady) {
          await submitRemoteManualExecution({
            positionId: position?.id ?? null,
            symbol,
            side,
            price: unitPrice,
            quantity: qty,
            executedAt: `${date}T00:00:00.000Z`,
            signalId: params.signalId ?? null,
            memo,
            idempotencyKey: remoteIdempotencyKey('manual'),
          });
        }
        if (position && side === 'SELL') sellManual(position.id, qty, unitPrice);
        else if (position) addManualBuy(position.id, qty, unitPrice);
        else registerManual({ symbol, instrumentName: name.trim(), quantity: qty, price: unitPrice, boughtAt: date, memo, signalId: params.signalId ?? null });
        Alert.alert('체결을 기록했어요', side === 'SELL' && position && qty === position.quantity ? '전량매도되어 감시를 종료합니다.' : '실제 거래를 수동 원장에 기록했습니다.');
        router.replace(position ? { pathname: '/position/[id]', params: { id: position.id } } : '/holdings');
      } else {
        if (remoteApiReady) {
          await submitRemotePaperOrder({
            positionId: position?.id ?? null,
            symbol,
            side,
            quantity: qty,
            signalId: params.signalId ?? null,
            idempotencyKey: remoteIdempotencyKey('paper'),
          });
        }
        placePaperOrder({ side, symbol, instrumentName: name.trim(), quantity: qty, estimatedPrice: unitPrice, signalId: params.signalId ?? null, positionId: position?.id ?? null });
        Alert.alert('주문을 접수했어요', '다음 체결 가능 거래일 공식 시가에 Mock 처리합니다. 체결 전 취소할 수 있어요.');
        router.replace({ pathname: '/holdings', params: { kind: 'SANDBOX_PAPER' } });
      }
    } catch (reason) {
      Alert.alert('처리할 수 없어요', reason instanceof Error ? reason.message : '입력값을 확인하세요.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Screen>
      <TitleBlock eyebrow={side === 'BUY' ? 'BUY' : 'SELL'} title={mode === 'manual' ? '실제 체결 직접 등록' : '연습 페이퍼 주문'} body={mode === 'manual' ? '앱 밖 실제 거래만 적으세요. 과거 등록 알림은 소급 생성하지 않습니다.' : '다음 체결 가능 거래일 공식 시가에 전량 체결 또는 전량 미체결합니다.'} />
      {!position ? <Segmented options={[{ value: 'manual', label: '실제 수동' }, { value: 'paper', label: '연습 페이퍼' }]} value={mode} onChange={setMode} /> : null}
      {position ? <Segmented options={[{ value: 'BUY', label: '추가 매수' }, { value: 'SELL', label: '매도' }]} value={side} onChange={(value) => { setSide(value); if (value === 'SELL') setQuantity(String(position.quantity)); }} /> : null}
      <Surface style={{ gap: spacing.md }}>
        <View style={styles.two}><Field label="종목 코드" value={symbol} onChangeText={setSymbol} keyboardType="number-pad" maxLength={6} editable={!position} style={{ flex: 1 }} /><Field label="종목명" value={name} onChangeText={setName} editable={!position} style={{ flex: 1 }} /></View>
        <View style={styles.two}><Field label="수량 (정수)" value={quantity} onChangeText={setQuantity} keyboardType="number-pad" style={{ flex: 1 }} /><Field label={mode === 'manual' ? '실제 체결 단가' : '현재 추정 단가'} value={price} onChangeText={setPrice} keyboardType="number-pad" style={{ flex: 1 }} /></View>
        {mode === 'manual' ? <Field label="체결일 (YYYY-MM-DD)" value={date} onChangeText={setDate} /> : null}
        {mode === 'manual' ? <Field label="메모 (선택)" value={memo} onChangeText={setMemo} placeholder="증권사 체결 기록 등" /> : null}
      </Surface>
      <Surface style={{ gap: spacing.sm }}><View style={styles.row}><AppText tone="muted">예상 금액</AppText><AppText variant="subtitle">{formatPrice(total)}</AppText></View>{mode === 'paper' ? <><View style={styles.row}><AppText tone="muted">체결가</AppText><AppText>다음 공식 시가 ± slippage</AppText></View><View style={styles.row}><AppText tone="muted">비용</AppText><AppText>수수료 · 세금 · spread 반영</AppText></View></> : null}</Surface>
      {mode === 'manual' ? <Banner title="수동 기록은 사용자 랭킹에서 제외" body="실제 금액과 MANUAL_LIVE 정보는 공개 프로필에도 표시하지 않습니다." /> : <Banner tone="accent" title="연습 주문은 공식 랭킹에서 제외" body="체결 전 취소 가능 · 정수 수량 · 현금 부족/거래 불가 시 전체 거부" />}
      {error ? <AppText tone="negative">{error}</AppText> : null}
      <Button label={submitting ? '처리 중…' : mode === 'manual' ? `${side === 'BUY' ? '매수' : '매도'} 체결 등록` : `${side} 주문 확인`} onPress={() => void submit()} disabled={Boolean(error) || submitting} />
    </Screen>
  );
}

const styles = StyleSheet.create({ two: { flexDirection: 'row', gap: spacing.sm }, row: { flexDirection: 'row', justifyContent: 'space-between', gap: spacing.sm } });
