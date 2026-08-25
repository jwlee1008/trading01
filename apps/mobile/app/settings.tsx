import React, { useState } from 'react';
import { router } from 'expo-router';
import { Alert } from 'react-native';
import { useQueryClient } from '@tanstack/react-query';
import { AppText, Banner, Button, Divider, Field, ListRow, Screen, SectionTitle, Surface, ToggleRow, spacing } from '@signal/ui';
import { appBrand } from '@signal/config';
import { Segmented, TitleBlock } from '@/components/common';
import { connectedApiEnabled, deleteRemoteAccount, updateRemoteAlertSettings, updateRemoteProfileVisibility } from '@/services/connected-api';
import { requestLocalNotificationPermission, sendLocalTestNotification } from '@/services/notifications';
import { useAuth } from '@/providers/AuthProvider';
import { supabaseConfigured } from '@/services/supabase';
import { useAppStore } from '@/store/useAppStore';

export default function SettingsScreen() {
  const queryClient = useQueryClient();
  const auth = useAuth();
  const storeNickname = useAppStore((state) => state.nickname);
  const profilePublic = useAppStore((state) => state.profilePublic);
  const notifications = useAppStore((state) => state.notificationsEnabled);
  const quietHours = useAppStore((state) => state.quietHoursEnabled);
  const themeMode = useAppStore((state) => state.themeMode);
  const setProfilePublic = useAppStore((state) => state.setProfilePublic);
  const setNotifications = useAppStore((state) => state.setNotificationsEnabled);
  const setQuietHours = useAppStore((state) => state.setQuietHoursEnabled);
  const setThemeMode = useAppStore((state) => state.setThemeMode);
  const setNickname = useAppStore((state) => state.setNickname);
  const clearSessionData = useAppStore((state) => state.clearSessionData);
  const [nickname, setNicknameDraft] = useState(storeNickname);
  const [savingProfile, setSavingProfile] = useState(false);
  const [savingAlerts, setSavingAlerts] = useState(false);
  const [signingOut, setSigningOut] = useState(false);
  const remoteAccountConnected = connectedApiEnabled && (!supabaseConfigured || auth.session !== null);

  const saveProfile = async (next: { nickname: string; profilePublic: boolean }) => {
    if (!remoteAccountConnected) throw new Error('로그인이 필요합니다.');
    await updateRemoteProfileVisibility({ isPublic: next.profilePublic, nickname: next.nickname, discloseOpenPositions: false });
    setNickname(next.nickname);
    setProfilePublic(next.profilePublic);
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['connected-api-snapshot'] }),
      queryClient.invalidateQueries({ queryKey: ['rankings'] }),
    ]);
  };
  const saveNickname = async () => {
    const nextNickname = nickname.trim();
    if (nextNickname.length < 2) return Alert.alert('닉네임 확인', '닉네임은 2자 이상 입력하세요.');
    setSavingProfile(true);
    try {
      await saveProfile({ nickname: nextNickname, profilePublic });
      Alert.alert('닉네임을 저장했어요');
    } catch (caught) {
      Alert.alert('프로필 저장 실패', caught instanceof Error ? caught.message : '잠시 뒤 다시 시도하세요.');
    } finally {
      setSavingProfile(false);
    }
  };
  const changeProfilePublic = async (value: boolean) => {
    setSavingProfile(true);
    try {
      await saveProfile({ nickname: storeNickname, profilePublic: value });
    } catch (caught) {
      Alert.alert('공개 설정 실패', caught instanceof Error ? caught.message : '잠시 뒤 다시 시도하세요.');
    } finally {
      setSavingProfile(false);
    }
  };
  const saveAlerts = async (enabled: boolean, quietHoursEnabled: boolean) => {
    if (!remoteAccountConnected) throw new Error('로그인이 필요합니다.');
    await updateRemoteAlertSettings({ enabled, quietHoursEnabled, quietStart: '22:00', quietEnd: '07:00', showPriceOnLockScreen: false });
    setNotifications(enabled);
    setQuietHours(quietHoursEnabled);
  };
  const changeNotifications = async (enabled: boolean) => {
    setSavingAlerts(true);
    try {
      const granted = enabled ? await requestLocalNotificationPermission() : false;
      await saveAlerts(enabled && granted, quietHours);
      if (enabled && !granted) Alert.alert('알림 권한 필요', '기기 설정에서 알림 권한을 허용하세요. Web은 앱 안 기록만 지원합니다.');
    } catch (caught) {
      Alert.alert('알림 설정 실패', caught instanceof Error ? caught.message : '잠시 뒤 다시 시도하세요.');
    } finally {
      setSavingAlerts(false);
    }
  };
  const changeQuietHours = async (enabled: boolean) => {
    setSavingAlerts(true);
    try {
      await saveAlerts(notifications, enabled);
    } catch (caught) {
      Alert.alert('알림 설정 실패', caught instanceof Error ? caught.message : '잠시 뒤 다시 시도하세요.');
    } finally {
      setSavingAlerts(false);
    }
  };
  const testNotification = async () => {
    const sent = await sendLocalTestNotification();
    Alert.alert(sent ? '로컬 알림 생성' : 'Web 미지원', sent ? '민감한 가격·수량 없이 기기 알림을 만들었습니다.' : '원격 푸시 자격증명과 Web 알림은 연결하지 않았습니다.');
  };
  const signOut = async () => {
    setSigningOut(true);
    try {
      await auth.signOut();
      clearSessionData();
      router.replace('/onboarding');
    } catch (caught) {
      Alert.alert('로그아웃 실패', caught instanceof Error ? caught.message : '잠시 뒤 다시 시도하세요.');
    } finally {
      setSigningOut(false);
    }
  };
  const deleteAccount = () => Alert.alert(
    '계정 삭제',
    '서버 계정과 이 기기 세션 데이터를 삭제합니다. 되돌릴 수 없습니다.',
    [{ text: '취소', style: 'cancel' }, {
      text: '삭제', style: 'destructive', onPress: () => {
        void (async () => {
          try {
            if (!remoteAccountConnected) throw new Error('로그인이 필요합니다.');
            await deleteRemoteAccount();
            if (auth.session) await auth.signOut().catch(() => undefined);
            clearSessionData();
            router.replace('/onboarding');
          } catch (caught) {
            Alert.alert('계정 삭제 실패', caught instanceof Error ? caught.message : '잠시 뒤 다시 시도하세요.');
          }
        })();
      },
    }],
  );

  return (
    <Screen>
      <TitleBlock title="설정" body="MVP 모든 기능은 무료입니다. 결제 SDK와 페이월은 없습니다." />
      <SectionTitle title="화면" />
      <Segmented options={[{ value: 'system', label: '기기 설정' }, { value: 'light', label: '라이트' }, { value: 'dark', label: '다크' }]} value={themeMode} onChange={setThemeMode} />
      <SectionTitle title="프로필 · 공개" />
      <Surface style={{ gap: spacing.md }}>
        <Field label="닉네임" value={nickname} onChangeText={setNicknameDraft} maxLength={16} />
        <Button label={savingProfile ? '저장 중…' : '닉네임 저장'} kind="secondary" compact onPress={() => { void saveNickname(); }} disabled={savingProfile} />
        <Divider />
        <ToggleRow title="사용자 랭킹 프로필 공개" body="실제 매매 수익률과 매도 횟수를 공개합니다. 전략은 전략 상세에서 별도로 공개해야 합니다." value={profilePublic} onValueChange={(value) => { void changeProfilePublic(value); }} disabled={savingProfile} />
      </Surface>
      {!profilePublic
        ? <Banner title="현재 공개 동의 없음" body="내 매매 기록은 보존되지만 사용자 랭킹에는 나타나지 않습니다." />
        : <Banner tone="accent" title="사용자 랭킹 공개 중" body="공개 전략 목록에는 내 전략 상세에서 '공개로 전환'한 전략만 표시됩니다." />}

      <SectionTitle title="알림" />
      <Surface style={{ gap: spacing.sm }}><ToggleRow title="신호 알림" body="기기 권한을 확인합니다. 실제 원격 푸시 자격증명은 연결되지 않음" value={notifications} onValueChange={(value) => { void changeNotifications(value); }} disabled={savingAlerts} /><Divider /><ToggleRow title="방해 금지 시간" body="22:00~07:00 · 앱 기록은 계속 저장" value={quietHours} onValueChange={(value) => { void changeQuietHours(value); }} disabled={savingAlerts} /><Button label="민감 정보 없는 로컬 알림 테스트" kind="secondary" compact onPress={() => { void testNotification(); }} disabled={!notifications || savingAlerts} /></Surface>
      {!notifications ? <Banner tone="negative" title="알림 권한이 꺼져 있어요" body="신호 기록은 앱 안에 남지만 잠금 화면 알림은 받지 못합니다." /> : null}

      <SectionTitle title="데이터 · 개인정보" />
      <Surface style={{ paddingVertical: 0 }}><ListRow title="데이터 공급자 상태" subtitle="실제 수집 상태 확인" onPress={() => router.push('/provider-status')} /><Divider /><ListRow title="공개 데이터 범위" subtitle="수익률·매도 횟수 외 투자금·이메일·토큰 비공개" onPress={() => router.navigate('/(tabs)/rankings')} /><Divider /><ListRow title="개인정보 처리 · 고지" subtitle="정보·교육 목적 · 투자자문 아님" onPress={() => Alert.alert('필수 고지', '사용자 랭킹은 직접 작성한 매매 기록이며 증권사 인증 내역이 아닙니다. 백테스트와 기록 수익률은 미래 성과를 보장하지 않습니다.')} /></Surface>

      <SectionTitle title="계정" />
      {auth.session
        ? <Banner tone="positive" title="Supabase 로그인됨" body={`${auth.user?.email ?? '이메일 없음'} · API 요청에 현재 access token 사용`} />
        : auth.configured
          ? <Banner title="로그인되지 않음" body="서버 동기화 전 로그인하세요." />
          : <Banner tone="negative" title="Supabase 설정 필요" body="공개 URL과 publishable key를 설정해야 로그인할 수 있습니다." />}
      {auth.session
        ? <Button label="로그아웃" kind="secondary" busy={signingOut} onPress={() => { void signOut(); }} />
        : auth.configured
          ? <Button label="로그인 · 회원가입" kind="secondary" onPress={() => router.push('/auth')} />
          : null}
      <Banner title="삭제 전 확인" body="서버 계정과 이 기기 세션 데이터를 함께 삭제합니다." />
      <Button label="계정 · 세션 데이터 삭제" kind="danger" onPress={deleteAccount} disabled={!remoteAccountConnected} />
      <AppText variant="caption" tone="muted" style={{ textAlign: 'center' }}>{appBrand.name} 0.1.0 · entitlement: FREE_ALL</AppText>
    </Screen>
  );
}
