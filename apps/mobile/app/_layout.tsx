import React, { useState } from 'react';
import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SignalThemeProvider, palettes, useSignalTheme } from '@signal/ui';
import { ConnectedDataSync } from '@/components/ConnectedDataSync';
import { AuthProvider } from '@/providers/AuthProvider';
import { useAppStore } from '@/store/useAppStore';

function Navigation() {
  const { colors } = useSignalTheme();
  return (
    <>
      <StatusBar style={colors.background === palettes.dark.background ? 'light' : 'dark'} />
      <Stack
        screenOptions={{
          contentStyle: { backgroundColor: colors.background },
          headerStyle: { backgroundColor: colors.background },
          headerTintColor: colors.text,
          headerShadowVisible: false,
          headerBackButtonDisplayMode: 'minimal',
          headerTitleStyle: { fontWeight: '700' },
        }}
      >
        <Stack.Screen name="index" options={{ headerShown: false }} />
        <Stack.Screen name="onboarding" options={{ headerShown: false, gestureEnabled: false }} />
        <Stack.Screen name="auth" options={{ headerShown: false }} />
        <Stack.Screen name="(tabs)" options={{ headerShown: false }} />
        <Stack.Screen name="universe" options={{ title: '종목 범위' }} />
        <Stack.Screen name="watchlist" options={{ title: '종목 검색 · 관심 종목' }} />
        <Stack.Screen name="holdings" options={{ title: '보유 · 포지션' }} />
        <Stack.Screen name="history" options={{ title: '종료 포지션' }} />
        <Stack.Screen name="strategy/[id]" options={{ title: '전략 상세' }} />
        <Stack.Screen name="indicator/[id]" options={{ title: '지표 설명' }} />
        <Stack.Screen name="signal/[id]" options={{ title: '신호 상세' }} />
        <Stack.Screen name="position/[id]" options={{ title: '포지션 상세' }} />
        <Stack.Screen name="trade" options={{ title: '체결 기록' }} />
        <Stack.Screen name="sell-rule/[positionId]" options={{ title: '매도 규칙' }} />
        <Stack.Screen name="sell-signal/[id]" options={{ title: '매도 신호 상세' }} />
        <Stack.Screen name="profile/[id]" options={{ title: '공개 프로필' }} />
        <Stack.Screen name="settings" options={{ title: '설정' }} />
        <Stack.Screen name="provider-status" options={{ title: '데이터 상태' }} />
        <Stack.Screen name="test-top30" options={{ title: '테스트 TOP 30 설정' }} />
      </Stack>
    </>
  );
}

export default function RootLayout() {
  const themeMode = useAppStore((state) => state.themeMode);
  const [queryClient] = useState(() => new QueryClient({ defaultOptions: { queries: { retry: 1, staleTime: 20_000 } } }));
  return (
    <AuthProvider>
      <QueryClientProvider client={queryClient}>
        <SignalThemeProvider mode={themeMode}>
          <ConnectedDataSync />
          <Navigation />
        </SignalThemeProvider>
      </QueryClientProvider>
    </AuthProvider>
  );
}
