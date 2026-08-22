import { useQuery } from '@tanstack/react-query';
import { connectedApiEnabled, loadRemoteProviderHealth } from '@/services/connected-api';

export function useProviderHealth() {
  return useQuery({
    queryKey: ['provider-health'],
    queryFn: () => loadRemoteProviderHealth(),
    enabled: connectedApiEnabled,
    retry: 2,
    staleTime: 20_000,
    refetchInterval: 60_000,
  });
}
