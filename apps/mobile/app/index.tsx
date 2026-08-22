import React from 'react';
import { Redirect } from 'expo-router';
import { View } from 'react-native';
import { LoadingState, useSignalTheme } from '@signal/ui';
import { useAppStore } from '@/store/useAppStore';

export default function EntryScreen() {
  const hydrated = useAppStore((state) => state.hydrated);
  const hasSeenOnboarding = useAppStore((state) => state.hasSeenOnboarding);
  const { colors } = useSignalTheme();
  if (!hydrated) return <View style={{ flex: 1, backgroundColor: colors.background, justifyContent: 'center' }}><LoadingState label="내 설정 여는 중" /></View>;
  return <Redirect href={hasSeenOnboarding ? '/(tabs)' : '/onboarding'} />;
}
