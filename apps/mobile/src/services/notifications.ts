import * as Notifications from 'expo-notifications';
import { Platform } from 'react-native';

Notifications.setNotificationHandler({
  handleNotification: () => Promise.resolve({
    shouldPlaySound: false,
    shouldSetBadge: false,
    shouldShowBanner: true,
    shouldShowList: true,
  }),
});

export async function requestLocalNotificationPermission(): Promise<boolean> {
  if (Platform.OS === 'web') return false;
  if (Platform.OS === 'android') {
    await Notifications.setNotificationChannelAsync('signals', {
      name: '조건 신호',
      importance: Notifications.AndroidImportance.DEFAULT,
      lockscreenVisibility: Notifications.AndroidNotificationVisibility.PRIVATE,
    });
  }
  const current = await Notifications.getPermissionsAsync();
  if (current.granted) return true;
  const requested = await Notifications.requestPermissionsAsync();
  return requested.granted;
}

export async function sendLocalTestNotification(): Promise<boolean> {
  if (Platform.OS === 'web') return false;
  await Notifications.scheduleNotificationAsync({
    content: {
      title: '조건 신호 기록',
      body: '앱에서 상세 근거를 확인하세요.',
      data: { route: '/(tabs)/alerts' },
    },
    trigger: null,
  });
  return true;
}
