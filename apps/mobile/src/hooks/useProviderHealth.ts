import { SignalApiClient, type ApiEnvelope, type ProviderStatus } from '@signal/api-client';
import { useQuery } from '@tanstack/react-query';
import { mockDelay } from '@/data/mock';
import { getApiAccessToken } from '@/services/supabase';
import { useAppStore } from '@/store/useAppStore';

export interface ProviderHealth {
  source: 'api' | 'local-mock';
  state: 'CONNECTED' | 'DEGRADED' | 'DISCONNECTED';
  provider: 'mock';
  checkedAt: string;
}

const apiUrl = process.env.EXPO_PUBLIC_API_URL?.replace(/\/$/, '');

function readHealth(envelope: ApiEnvelope<ProviderStatus>): ProviderHealth {
  const data = envelope.data;
  if (data.provider !== 'mock' || !['CONNECTED', 'DEGRADED', 'DISCONNECTED'].includes(data.state)) {
    throw new Error('INVALID_PROVIDER_STATUS');
  }
  return {
    source: 'api',
    state: data.state,
    provider: data.provider,
    checkedAt: envelope.meta.generatedAt,
  };
}

export function useProviderHealth() {
  const connectionMode = useAppStore((state) => state.connectionMode);
  return useQuery({
    queryKey: ['provider-health', apiUrl ?? 'local-mock', connectionMode],
    queryFn: async (): Promise<ProviderHealth> => {
      if (connectionMode === 'offline') throw new Error('OFFLINE');
      if (connectionMode === 'error') throw new Error('PROVIDER_ERROR');
      if (apiUrl) {
        const accessToken = await getApiAccessToken();
        const apiClient = new SignalApiClient(apiUrl, fetch, accessToken ?? undefined);
        return readHealth(await apiClient.providerStatus());
      }
      return mockDelay(
        { source: 'local-mock', state: 'CONNECTED', provider: 'mock', checkedAt: new Date().toISOString() },
        connectionMode === 'delayed' ? 1_800 : 380,
      );
    },
    retry: false,
    staleTime: 20_000,
  });
}
