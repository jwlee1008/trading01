import { useAuth } from '@/providers/AuthProvider';
import { connectedApiEnabled } from '@/services/connected-api';
import { supabaseConfigured } from '@/services/supabase';

export function useRemoteApiReady(): boolean {
  const { session } = useAuth();
  return connectedApiEnabled && supabaseConfigured && session !== null;
}
