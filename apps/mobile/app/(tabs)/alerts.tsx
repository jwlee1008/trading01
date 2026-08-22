import React, { useMemo, useState } from 'react';
import { router } from 'expo-router';
import { Pressable, StyleSheet, View } from 'react-native';
import { AppText, Banner, Button, Chip, EmptyState, Screen, Surface, spacing, useSignalTheme } from '@signal/ui';
import { Segmented } from '@/components/common';
import { useAppStore } from '@/store/useAppStore';
import { formatDateTime } from '@/utils/format';

export default function AlertsScreen() {
  const { colors } = useSignalTheme();
  const [filter, setFilter] = useState<'all' | 'unread'>('all');
  const alerts = useAppStore((state) => state.alerts);
  const markAlertRead = useAppStore((state) => state.markAlertRead);
  const markAllAlertsRead = useAppStore((state) => state.markAllAlertsRead);
  const shown = useMemo(() => filter === 'unread' ? alerts.filter((item) => !item.read) : alerts, [alerts, filter]);

  const open = (alert: (typeof alerts)[number]) => {
    markAlertRead(alert.id);
    if (alert.signalId) router.push({ pathname: '/signal/[id]', params: { id: alert.signalId } });
    else if (alert.positionId && alert.kind === 'SELL_SIGNAL') router.push({ pathname: '/sell-signal/[id]', params: { id: alert.positionId } });
    else if (alert.positionId) router.push({ pathname: '/position/[id]', params: { id: alert.positionId } });
  };

  return (
    <Screen>
      <View style={styles.row}><Segmented options={[{ value: 'all', label: '전체' }, { value: 'unread', label: `안 읽음 ${alerts.filter((item) => !item.read).length}` }]} value={filter} onChange={setFilter} /><Button label="모두 읽음" kind="ghost" compact onPress={markAllAlertsRead} /></View>
      <Banner tone="accent" title="잠금 화면은 정보를 줄여 표시해요" body="종목·가격 같은 민감값은 앱을 열어 확인합니다." />
      {shown.length === 0 ? <EmptyState symbol="✓" title="새 알림이 없어요" body="조건이 새로 충족되면 이곳에 기록됩니다." /> : shown.map((alert) => (
        <Pressable key={alert.id} accessibilityRole="button" onPress={() => open(alert)}>
          <Surface style={[styles.alert, !alert.read && styles.unread, !alert.read && { borderLeftColor: colors.accent }]}>
            <View style={styles.row}><Chip label={alert.kind === 'BUY_SIGNAL' ? '매수 조건' : alert.kind === 'SELL_SIGNAL' ? '매도 조건' : '시스템'} selected={!alert.read} tone={alert.delayed ? 'warning' : 'default'} /><AppText variant="caption" tone="muted">{formatDateTime(alert.createdAt)}</AppText></View>
            <AppText variant="bodyStrong">{alert.title}</AppText>
            <AppText tone="muted">{alert.body}</AppText>
            {alert.delayed ? <AppText variant="caption" tone="warning">데이터 지연 표시</AppText> : null}
          </Surface>
        </Pressable>
      ))}
    </Screen>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.sm },
  alert: { gap: spacing.sm },
  unread: { borderLeftWidth: 4 },
});
