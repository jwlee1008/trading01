import { useQuery } from '@tanstack/react-query';
import { connectedApiEnabled, loadRemoteUniverseVersions } from '@/services/connected-api';
import { universeIdFromKind } from '@/data/catalog';
import type { Universe } from '@/domain/types';

export function useUniverses() {
  return useQuery({
    queryKey: ['universe-versions'],
    enabled: connectedApiEnabled,
    queryFn: async (): Promise<Universe[]> => (await loadRemoteUniverseVersions()).map((item) => ({
      id: universeIdFromKind(item.kind), name: item.name || item.kind,
      count: item.memberCount, version: item.effectiveFrom || item.id.slice(0, 8), description: `${item.kind} · 확정 종목군`,
    })),
    retry: 2,
  });
}
