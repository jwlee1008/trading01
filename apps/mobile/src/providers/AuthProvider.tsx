import React, { createContext, useContext, useEffect, useMemo, useState } from 'react';
import { AppState, Linking } from 'react-native';
import * as ExpoLinking from 'expo-linking';
import type { Session, User } from '@supabase/supabase-js';
import { applySupabaseRecoveryUrl, supabase, supabaseConfigured } from '@/services/supabase';
import { useAppStore } from '@/store/useAppStore';

interface SignUpResult {
  needsEmailConfirmation: boolean;
}

interface AuthContextValue {
  configured: boolean;
  loading: boolean;
  session: Session | null;
  user: User | null;
  initializationError: string | null;
  signIn: (email: string, password: string) => Promise<void>;
  signUp: (email: string, password: string, nickname: string) => Promise<SignUpResult>;
  signOut: () => Promise<void>;
  requestPasswordReset: (email: string) => Promise<void>;
  updatePassword: (password: string) => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

function requireSupabase() {
  if (!supabase) throw new Error('Supabase 공개 환경 변수를 먼저 설정하세요.');
  return supabase;
}

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [session, setSession] = useState<Session | null>(null);
  const [loading, setLoading] = useState(supabaseConfigured);
  const [initializationError, setInitializationError] = useState<string | null>(null);

  useEffect(() => {
    const client = supabase;
    if (!client) {
      setLoading(false);
      return;
    }
    let mounted = true;
    void client.auth.getSession().then(({ data, error }) => {
      if (!mounted) return;
      setSession(data.session);
      setInitializationError(error ? '저장된 로그인 세션을 열지 못했습니다.' : null);
      setLoading(false);
    });
    const { data: listener } = client.auth.onAuthStateChange((event, nextSession) => {
      if (!mounted) return;
      setSession(nextSession);
      setInitializationError(null);
      setLoading(false);
      if (event === 'SIGNED_OUT') useAppStore.getState().clearSessionData();
    });
    if (AppState.currentState === 'active') void client.auth.startAutoRefresh();
    const appState = AppState.addEventListener('change', (state) => {
      if (state === 'active') void client.auth.startAutoRefresh();
      else void client.auth.stopAutoRefresh();
    });
    const openRecoveryUrl = (url: string | null) => {
      if (url) void applySupabaseRecoveryUrl(url).catch(() => setInitializationError('비밀번호 재설정 링크를 열지 못했습니다.'));
    };
    void Linking.getInitialURL().then(openRecoveryUrl);
    const linking = Linking.addEventListener('url', ({ url }) => openRecoveryUrl(url));
    return () => {
      mounted = false;
      listener.subscription.unsubscribe();
      appState.remove();
      linking.remove();
      void client.auth.stopAutoRefresh();
    };
  }, []);

  const value = useMemo<AuthContextValue>(() => ({
    configured: supabaseConfigured,
    loading,
    session,
    user: session?.user ?? null,
    initializationError,
    signIn: async (email, password) => {
      const { error } = await requireSupabase().auth.signInWithPassword({ email: email.trim(), password });
      if (error) throw new Error(error.message);
    },
    signUp: async (email, password, nickname) => {
      const { data, error } = await requireSupabase().auth.signUp({
        email: email.trim(),
        password,
        options: { data: { nickname: nickname.trim() } },
      });
      if (error) throw new Error(error.message);
      return { needsEmailConfirmation: data.session === null };
    },
    signOut: async () => {
      const { error } = await requireSupabase().auth.signOut();
      if (error) throw new Error(error.message);
    },
    requestPasswordReset: async (email) => {
      const redirectTo = ExpoLinking.createURL('/auth', { queryParams: { mode: 'update-password' } });
      const { error } = await requireSupabase().auth.resetPasswordForEmail(email.trim(), { redirectTo });
      if (error) throw new Error(error.message);
    },
    updatePassword: async (password) => {
      const { error } = await requireSupabase().auth.updateUser({ password });
      if (error) throw new Error(error.message);
    },
  }), [initializationError, loading, session]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error('useAuth must be used inside AuthProvider');
  return value;
}
