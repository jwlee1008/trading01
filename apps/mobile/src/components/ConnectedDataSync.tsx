import { useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { connectedApiEnabled, loadRemoteSnapshot } from '@/services/connected-api';
import { useAuth } from '@/providers/AuthProvider';
import { supabaseConfigured } from '@/services/supabase';
import { useAppStore } from '@/store/useAppStore';

export function ConnectedDataSync() {
  const mode = useAppStore((state) => state.connectionMode);
  const applyRemoteSnapshot = useAppStore((state) => state.applyRemoteSnapshot);
  const { session } = useAuth();
  const userId = session?.user.id ?? null;
  const query = useQuery({
    queryKey: ['connected-api-snapshot', userId ?? (supabaseConfigured ? 'anonymous' : 'dev')],
    queryFn: () => loadRemoteSnapshot(),
    enabled: connectedApiEnabled && mode === 'online' && (!supabaseConfigured || userId !== null),
    refetchInterval: 5_000, retry: false,
  });
  useEffect(() => {
    if (query.data) applyRemoteSnapshot(query.data);
  }, [applyRemoteSnapshot, query.data]);
  return null;
}
