import React from 'react';
import MaterialCommunityIcons from '@expo/vector-icons/MaterialCommunityIcons';
import { Tabs } from 'expo-router';
import { useSignalTheme } from '@signal/ui';
import { appBrand } from '@signal/config';

const icons = {
  index: ['home-variant-outline', 'home-variant'],
  rankings: ['chart-box-outline', 'chart-box'],
  create: ['plus-circle-outline', 'plus-circle'],
  alerts: ['bell-outline', 'bell'],
  my: ['account-circle-outline', 'account-circle'],
} as const;

export default function TabLayout() {
  const { colors } = useSignalTheme();
  return (
    <Tabs
      screenOptions={({ route }) => ({
        headerStyle: { backgroundColor: colors.background },
        headerTitleStyle: { color: colors.text, fontWeight: '800' },
        headerShadowVisible: false,
        tabBarStyle: { backgroundColor: colors.surface, borderTopColor: colors.border, height: 70, paddingBottom: 8, paddingTop: 6 },
        tabBarActiveTintColor: colors.accent,
        tabBarInactiveTintColor: colors.textMuted,
        tabBarLabelStyle: { fontWeight: '700', fontSize: 11 },
        tabBarIcon: ({ color, size, focused }) => {
          const pair = icons[route.name as keyof typeof icons] ?? icons.index;
          return <MaterialCommunityIcons name={focused ? pair[1] : pair[0]} color={color} size={size} />;
        },
      })}
    >
      <Tabs.Screen name="index" options={{ title: '홈', headerTitle: appBrand.name }} />
      <Tabs.Screen name="rankings" options={{ title: '랭킹', headerTitle: '랭킹' }} />
      <Tabs.Screen name="create" options={{ title: '만들기', headerTitle: '전략 만들기' }} />
      <Tabs.Screen name="alerts" options={{ title: '알림', headerTitle: '알림' }} />
      <Tabs.Screen name="my" options={{ title: 'MY', headerTitle: 'MY' }} />
    </Tabs>
  );
}
