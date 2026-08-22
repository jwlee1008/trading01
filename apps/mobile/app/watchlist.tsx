import React, { useMemo, useState } from 'react';
import { router } from 'expo-router';
import { Pressable, StyleSheet, View } from 'react-native';
import { AppText, Banner, Chip, EmptyState, Field, Screen, Surface, spacing } from '@signal/ui';
import { PriceChange, TitleBlock } from '@/components/common';
import { watchlist } from '@/data/mock';
import { useAppStore } from '@/store/useAppStore';

const moreInstruments = [
  { symbol: '207940', name: '삼성바이오로직스', price: 1045000, change: 0.67 },
  { symbol: '005380', name: '현대차', price: 244000, change: -1.21 },
  { symbol: '247540', name: '에코프로비엠', price: 178300, change: 2.35 },
  { symbol: '373220', name: 'LG에너지솔루션', price: 392500, change: -0.88 },
];

export default function WatchlistScreen() {
  const [query, setQuery] = useState('');
  const customSymbols = useAppStore((state) => state.customSymbols);
  const toggleWatchlist = useAppStore((state) => state.toggleWatchlist);
  const setUniverse = useAppStore((state) => state.setUniverse);
  const all = [...watchlist, ...moreInstruments];
  const shown = useMemo(() => all.filter((item) => !query || item.name.toLowerCase().includes(query.toLowerCase()) || item.symbol.includes(query)), [query]);
  return (
    <Screen>
      <TitleBlock title="종목 검색" body="관심 종목은 ‘내 종목 목록’ 전략 범위에 사용할 수 있어요." />
      <Field label="종목명 또는 6자리 코드" value={query} onChangeText={setQuery} placeholder="예: 삼성전자, 005930" autoCorrect={false} />
      <Banner tone="accent" title={`내 종목 ${customSymbols.length}개`} body="Mock 종목 목록입니다. 실제 종목 마스터 연결 전에는 전체 시장 검색을 지원하지 않습니다." />
      {shown.length === 0 ? <EmptyState title="검색 결과가 없어요" body="종목명이나 코드를 다시 확인하세요." /> : shown.map((item) => {
        const active = customSymbols.includes(item.symbol);
        return (
          <Surface key={item.symbol} style={styles.row}>
            <Pressable style={{ flex: 1 }} accessibilityRole="button" onPress={() => router.push({ pathname: '/trade', params: { mode: 'manual', side: 'BUY', symbol: item.symbol, name: item.name, price: String(item.price) } })}>
              <View style={{ gap: 3 }}><AppText variant="bodyStrong">{item.name}</AppText><AppText variant="caption" tone="muted">{item.symbol}</AppText></View>
            </Pressable>
            <PriceChange price={item.price} change={item.change} />
            <Chip label={active ? '★ 관심' : '☆ 추가'} selected={active} onPress={() => toggleWatchlist(item.symbol)} />
          </Surface>
        );
      })}
      <Pressable accessibilityRole="button" onPress={() => { setUniverse('custom'); router.push('/(tabs)/create'); }}><AppText tone="accent" variant="bodyStrong" style={{ textAlign: 'center' }}>이 목록으로 전략 만들기</AppText></Pressable>
    </Screen>
  );
}

const styles = StyleSheet.create({ row: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm } });
