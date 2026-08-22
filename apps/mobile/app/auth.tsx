import React, { useState } from 'react';
import { router, useLocalSearchParams } from 'expo-router';
import { StyleSheet, View } from 'react-native';
import { AppText, Banner, Button, Field, LoadingState, Screen, Surface, spacing, useSignalTheme } from '@signal/ui';
import { APP_NAME } from '@/data/catalog';
import { useAuth } from '@/providers/AuthProvider';
import { useAppStore } from '@/store/useAppStore';

type AuthMode = 'sign-in' | 'sign-up';

export default function AuthScreen() {
  const { origin } = useLocalSearchParams<{ origin?: string }>();
  const { colors } = useSignalTheme();
  const auth = useAuth();
  const hasSeenOnboarding = useAppStore((state) => state.hasSeenOnboarding);
  const completeOnboarding = useAppStore((state) => state.completeOnboarding);
  const [mode, setMode] = useState<AuthMode>('sign-in');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [nickname, setNickname] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  if (auth.loading) {
    return <View style={[styles.loading, { backgroundColor: colors.background }]}><LoadingState label="로그인 세션 확인 중" /></View>;
  }

  const finish = () => {
    completeOnboarding();
    if (origin === 'create' && router.canGoBack()) return router.back();
    router.replace(hasSeenOnboarding ? '/(tabs)/my' : { pathname: '/universe', params: { origin: 'setup' } });
  };
  const useTrial = () => {
    if (origin === 'create' && router.canGoBack()) return router.back();
    if (hasSeenOnboarding) return router.replace('/(tabs)/my');
    completeOnboarding();
    router.replace({ pathname: '/universe', params: { origin: 'setup' } });
  };
  const submit = async () => {
    setError(null);
    setMessage(null);
    const cleanEmail = email.trim();
    if (!cleanEmail.includes('@')) return setError('이메일 주소를 확인하세요.');
    if (password.length < 6) return setError('비밀번호는 6자 이상 입력하세요.');
    if (mode === 'sign-up' && nickname.trim().length < 2) return setError('닉네임은 2자 이상 입력하세요.');
    setBusy(true);
    try {
      if (mode === 'sign-in') {
        await auth.signIn(cleanEmail, password);
        finish();
      } else {
        const result = await auth.signUp(cleanEmail, password, nickname);
        if (result.needsEmailConfirmation) {
          setMode('sign-in');
          setPassword('');
          setMessage('확인 메일을 보냈습니다. 인증 뒤 로그인하세요.');
        } else {
          finish();
        }
      }
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : '인증 요청에 실패했습니다.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Screen contentContainerStyle={styles.content}>
      <View style={styles.brand}>
        <View style={[styles.mark, { backgroundColor: colors.accent }]}><AppText tone="inverse" variant="subtitle">S</AppText></View>
        <AppText variant="bodyStrong">{APP_NAME}</AppText>
      </View>
      <View style={styles.heading}>
        <AppText variant="hero">{mode === 'sign-in' ? '로그인' : '회원가입'}</AppText>
        <AppText tone="muted">전략·신호·포트폴리오를 계정에 안전하게 연결합니다.</AppText>
      </View>
      {!auth.configured ? <Banner tone="negative" title="Supabase 환경 변수 없음" body="로그인과 데이터 저장을 사용할 수 없습니다. 공개 URL과 publishable key를 설정하세요." /> : null}
      {auth.initializationError ? <Banner tone="negative" title="세션 확인 실패" body={auth.initializationError} /> : null}
      {message ? <Banner tone="positive" title="회원가입 접수" body={message} /> : null}
      {error ? <Banner tone="negative" title="인증 실패" body={error} /> : null}
      <Surface style={styles.form}>
        {mode === 'sign-up' ? <Field label="닉네임" value={nickname} onChangeText={setNickname} autoCapitalize="none" maxLength={16} editable={!busy && auth.configured} /> : null}
        <Field label="이메일" value={email} onChangeText={setEmail} autoCapitalize="none" autoCorrect={false} keyboardType="email-address" textContentType="emailAddress" editable={!busy && auth.configured} />
        <Field label="비밀번호" value={password} onChangeText={setPassword} secureTextEntry textContentType={mode === 'sign-in' ? 'password' : 'newPassword'} editable={!busy && auth.configured} />
        <Button label={mode === 'sign-in' ? '로그인' : '계정 만들기'} busy={busy} disabled={!auth.configured} onPress={() => { void submit(); }} />
        <Button
          label={mode === 'sign-in' ? '처음이면 회원가입' : '계정이 있으면 로그인'}
          kind="ghost"
          disabled={busy || !auth.configured}
          onPress={() => { setMode((value) => value === 'sign-in' ? 'sign-up' : 'sign-in'); setError(null); setMessage(null); }}
        />
      </Surface>
      <Button label={hasSeenOnboarding ? '내 화면으로 돌아가기' : '저장 없이 둘러보기'} kind="secondary" disabled={busy} onPress={useTrial} />
      <AppText variant="caption" tone="muted" style={styles.notice}>세션 저장과 갱신은 Supabase SDK가 맡습니다. service-role key는 앱에 넣지 않습니다.</AppText>
    </Screen>
  );
}

const styles = StyleSheet.create({
  loading: { flex: 1, justifyContent: 'center' },
  content: { minHeight: '100%', justifyContent: 'center', gap: spacing.lg, paddingVertical: 48 },
  brand: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  mark: { width: 36, height: 36, borderRadius: 12, alignItems: 'center', justifyContent: 'center' },
  heading: { gap: spacing.sm },
  form: { gap: spacing.md },
  notice: { textAlign: 'center' },
});
