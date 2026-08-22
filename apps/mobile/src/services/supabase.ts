import 'react-native-url-polyfill/auto';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { createClient, processLock, type SupabaseClient } from '@supabase/supabase-js';

export interface SupabasePublicConfig {
  url: string;
  publishableKey: string;
}

export function readSupabasePublicConfig(env: Record<string, string | undefined>): SupabasePublicConfig | null {
  const url = env['EXPO_PUBLIC_SUPABASE_URL']?.trim();
  const publishableKey = env['EXPO_PUBLIC_SUPABASE_PUBLISHABLE_KEY']?.trim();
  if (!url || !publishableKey) return null;
  try {
    const parsed = new URL(url);
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return null;
  } catch {
    return null;
  }
  return { url: url.replace(/\/$/, ''), publishableKey };
}

const publicConfig = readSupabasePublicConfig(process.env);

export const supabaseConfigured = publicConfig !== null;

export const supabase: SupabaseClient | null = publicConfig
  ? createClient(publicConfig.url, publicConfig.publishableKey, {
      auth: {
        storage: AsyncStorage,
        autoRefreshToken: true,
        persistSession: true,
        detectSessionInUrl: false,
        lock: processLock,
      },
    })
  : null;

export async function getApiAccessToken(): Promise<string | null> {
  if (!supabase) throw new Error('Supabase 공개 환경 변수가 설정되지 않았습니다.');
  const { data, error } = await supabase.auth.getSession();
  if (error) throw new Error('로그인 세션을 확인하지 못했습니다. 다시 로그인하세요.');
  return data.session?.access_token ?? null;
}

export async function refreshApiAccessToken(): Promise<string | null> {
  if (!supabase) throw new Error('Supabase 공개 환경 변수가 설정되지 않았습니다.');
  const { data, error } = await supabase.auth.refreshSession();
  if (error || !data.session) throw new Error('로그인 세션이 만료됐습니다. 다시 로그인하세요.');
  return data.session.access_token;
}
