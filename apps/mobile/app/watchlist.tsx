import React, { useMemo, useState } from 'react';
import { router } from 'expo-router';
import { Pressable, StyleSheet, View } from 'react-native';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AppText, Banner, Chip, EmptyState, ErrorState, Field, LoadingState, Screen, Surface, spacing } from '@signal/ui';
import { TitleBlock } from '@/components/common';
import { loadRemoteCatalog, loadRemoteWatchlist, setRemoteWatchlist, type RemoteInstrument } from '@/services/connected-api';
import { useRemoteApiReady } from '@/hooks/useRemoteApiReady';

export default function WatchlistScreen() {
  const [query, setQuery] = useState('');
  const remoteApiReady = useRemoteApiReady();
  const queryClient = useQueryClient();
  const catalog = useQuery({ queryKey: ['instrument-catalog'], queryFn: () => loadRemoteCatalog(), staleTime: 300_000 });
  const watchlist = useQuery({ queryKey: ['watchlist'], queryFn: () => loadRemoteWatchlist(), enabled: remoteApiReady });
  const mutation = useMutation({
    mutationFn: ({ symbol, enabled }: { symbol: string; enabled: boolean }) => setRemoteWatchlist(symbol, enabled),
    onSuccess: (items) => queryClient.setQueryData<RemoteInstrument[]>(['watchlist'], items),
  });
  const watched = new Set((watchlist.data ?? []).map((item) => item.symbol));
  const shown = useMemo(() => (catalog.data ?? []).filter((item) => !query.trim() || item.symbol.includes(query.trim()) || item.name.toLowerCase().includes(query.trim().toLowerCase())).slice(0, 100), [catalog.data, query]);
  if (catalog.isPending || (remoteApiReady && watchlist.isPending)) return <Screen><LoadingState label="실제 종목 마스터 조회 중" /></Screen>;
  if (catalog.isError || (remoteApiReady && watchlist.isError)) return <Screen><ErrorState onRetry={() => { void catalog.refetch(); if (remoteApiReady) void watchlist.refetch(); }} /></Screen>;
  return <Screen>
    <TitleBlock title="종목 검색" body="키움 종목 마스터에 등록된 실제 종목만 검색합니다." />
    <Field label="종목명 또는 6자리 코드" value={query} onChangeText={setQuery} placeholder="예: 삼성전자, 005930" autoCorrect={false} />
    <Banner tone={remoteApiReady ? 'accent' : 'warning'} title={remoteApiReady ? `관심 종목 ${watched.size}개` : '종목 검색은 가능하지만 저장하려면 로그인해야 합니다'} body={`종목 마스터 ${catalog.data.length.toLocaleString('ko-KR')}개 기준`} {...(!remoteApiReady ? { action: '로그인', onAction: () => router.push('/auth') } : {})} />
    {shown.length === 0 ? <EmptyState title="검색 결과가 없습니다" body="종목명이나 코드를 다시 확인하세요." /> : shown.map((item) => {
      const active = watched.has(item.symbol);
      return <Surface key={item.symbol} style={styles.row}><Pressable style={{ flex: 1 }} onPress={() => remoteApiReady ? router.push({ pathname: '/trade', params: { mode: 'manual', side: 'BUY', symbol: item.symbol, name: item.name } }) : router.push('/auth')}><View><AppText variant="bodyStrong">{item.name}</AppText><AppText variant="caption" tone="muted">{item.symbol} · {item.market}{item.tradeSuspended ? ' · 거래정지' : ''}</AppText></View></Pressable><Chip label={active ? '★ 관심' : remoteApiReady ? '☆ 추가' : '로그인'} selected={active} onPress={() => remoteApiReady ? mutation.mutate({ symbol: item.symbol, enabled: !active }) : router.push('/auth')} /></Surface>;
    })}
  </Screen>;
}

const styles = StyleSheet.create({ row: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm } });
