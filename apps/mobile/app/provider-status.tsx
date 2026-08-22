import React from 'react';
import { AppText, Banner, Divider, ErrorState, ListRow, LoadingState, Screen, Surface } from '@signal/ui';
import { TitleBlock } from '@/components/common';
import { useProviderHealth } from '@/hooks/useProviderHealth';
import { formatDateTime } from '@/utils/format';

export default function ProviderStatusScreen() {
  const query = useProviderHealth();
  if (query.isPending) return <Screen><LoadingState label="실제 공급자 상태 조회 중" /></Screen>;
  if (query.isError) return <Screen><ErrorState onRetry={() => void query.refetch()} /></Screen>;
  const data = query.data;
  const healthy = data.state === 'CONNECTED' && !data.delayed;
  return <Screen>
    <TitleBlock eyebrow={`MARKET_DATA_PROVIDER=${data.provider}`} title={healthy ? '데이터 정상' : data.state === 'DISCONNECTED' ? '공급자 연결 중단' : '데이터 점검 필요'} body="서버가 확인한 실제 시세 적재 상태입니다." />
    <Banner tone={healthy ? 'positive' : 'negative'} title={healthy ? '신호 계산 가능' : '새 신호 계산 중단'} body={healthy ? '최신 완료 일봉이 정상 상태입니다.' : '누락되거나 오래된 데이터로 새 신호를 만들지 않습니다.'} />
    <Surface style={{ paddingVertical: 0 }}><ListRow title="공급자" subtitle={data.provider} value={data.state} /><Divider /><ListRow title="마지막 완료 일봉" subtitle={data.lastCandleAt ? formatDateTime(data.lastCandleAt) : '적재된 일봉 없음'} value={data.delayed ? '지연' : '확정'} /></Surface>
    <AppText variant="caption" tone="muted">공급자 장애 시 임의 데이터로 대체하지 않고 복구될 때까지 fail-closed 상태를 유지합니다.</AppText>
  </Screen>;
}
