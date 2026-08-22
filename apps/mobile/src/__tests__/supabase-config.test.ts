import { readSupabasePublicConfig } from '@/services/supabase';

describe('Supabase public config', () => {
  it('accepts a complete public mobile config', () => {
    expect(readSupabasePublicConfig({
      EXPO_PUBLIC_SUPABASE_URL: ' http://127.0.0.1:54321/ ',
      EXPO_PUBLIC_SUPABASE_PUBLISHABLE_KEY: ' publishable-key ',
    })).toEqual({ url: 'http://127.0.0.1:54321', publishableKey: 'publishable-key' });
  });

  it('stays disabled for missing or unsafe config', () => {
    expect(readSupabasePublicConfig({ EXPO_PUBLIC_SUPABASE_URL: 'http://127.0.0.1:54321' })).toBeNull();
    expect(readSupabasePublicConfig({
      EXPO_PUBLIC_SUPABASE_URL: 'javascript:alert(1)',
      EXPO_PUBLIC_SUPABASE_PUBLISHABLE_KEY: 'publishable-key',
    })).toBeNull();
  });
});
