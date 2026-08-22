import { useQuery } from '@tanstack/react-query';
import { mockDelay } from '@/data/mock';
import { useAppStore } from '@/store/useAppStore';

export function useMockQuery<T>(key: string, value: T) {
  const connectionMode = useAppStore((state) => state.connectionMode);
  return useQuery({
    queryKey: ['mock', key, connectionMode],
    queryFn: async () => {
      if (connectionMode === 'offline') throw new Error('OFFLINE');
      if (connectionMode === 'error') throw new Error('PROVIDER_ERROR');
      return mockDelay(value, connectionMode === 'delayed' ? 1800 : 380);
    },
    retry: false,
    staleTime: 30_000,
  });
}
