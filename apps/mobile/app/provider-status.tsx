import React, { useState } from 'react';
import { Alert } from 'react-native';
import { useQueryClient } from '@tanstack/react-query';
import { AppText, Banner, Button, Divider, ErrorState, ListRow, LoadingState, Screen, SectionTitle, Surface } from '@signal/ui';
import { TitleBlock } from '@/components/common';
import { useProviderHealth } from '@/hooks/useProviderHealth';
import { formatDateTime } from '@/utils/format';
import { createRemoteTestSignal, requestRemoteMarketDataRefresh } from '@/services/connected-api';
import { useAuth } from '@/providers/AuthProvider';

export default function ProviderStatusScreen() {
  const query = useProviderHealth();
  const auth = useAuth();
  const queryClient = useQueryClient();
  const [refreshing, setRefreshing] = useState(false);
  const [testing, setTesting] = useState(false);
  if (query.isPending) return <Screen><LoadingState label="실제 공급자 상태 조회 중" /></Screen>;
  if (query.isError) return <Screen><ErrorState onRetry={() => void query.refetch()} /></Screen>;
  const data = query.data;
  const activeCount = data.activeInstrumentCount ?? 0;
  const coveredCount = data.coveredInstrumentCount ?? 0;
  const refresh = async () => {
    if (!auth.session) return Alert.alert('로그인이 필요합니다', '데이터 최신화 요청은 로그인 후 사용할 수 있습니다.');
    setRefreshing(true);
    try {
      const result = await requestRemoteMarketDataRefresh();
      Alert.alert(result.alreadyQueued ? '이미 최신화 중입니다' : '최신화를 요청했습니다', 'Worker가 누락 종목을 순차 수집합니다. 화면의 커버리지는 작업 완료 후 갱신됩니다.');
      await query.refetch();
    } catch (caught) { Alert.alert('최신화 요청 실패', caught instanceof Error ? caught.message : '잠시 뒤 다시 시도하세요.'); }
    finally { setRefreshing(false); }
  };
  const createTestSignal = async () => {
    if (!auth.session) return Alert.alert('로그인이 필요합니다', '테스트 신호는 현재 계정에 생성됩니다.');
    setTesting(true);
    try {
      const result = await createRemoteTestSignal();
      Alert.alert('테스트 신호 준비 완료', `${result.symbol} · ${result.message}`);
      setTimeout(() => { void queryClient.invalidateQueries({ queryKey: ['connected-api-snapshot'] }); }, 2500);
    } catch (caught) { Alert.alert('테스트 신호 실패', caught instanceof Error ? caught.message : '잠시 뒤 다시 시도하세요.'); }
    finally { setTesting(false); }
  };
  const healthy = data.state === 'CONNECTED' && !data.delayed;
  return <Screen>
    <TitleBlock eyebrow={`MARKET_DATA_PROVIDER=${data.provider}`} title={healthy ? '데이터 정상' : data.state === 'DISCONNECTED' ? '공급자 연결 중단' : '데이터 점검 필요'} body="서버가 확인한 실제 시세 적재 상태입니다." />
    <Banner tone={healthy ? 'positive' : 'negative'} title={healthy ? '신호 계산 가능' : '새 신호 계산 중단'} body={healthy ? '최신 완료 일봉이 정상 상태입니다.' : '누락되거나 오래된 데이터로 새 신호를 만들지 않습니다.'} />
    <Surface style={{ paddingVertical: 0 }}><ListRow title="공급자" subtitle={data.provider} value={data.state} /><Divider /><ListRow title="마지막 완료 일봉" subtitle={data.lastCandleAt ? formatDateTime(data.lastCandleAt) : '적재된 일봉 없음'} value={data.delayed ? '지연' : '확정'} /><Divider /><ListRow title="완료 거래일 범위" subtitle={`적재 ${data.lastSession ?? '없음'} · 기대 ${data.expectedSession ?? '없음'}`} /><Divider /><ListRow title="최신 일봉 커버리지" subtitle={`${coveredCount.toLocaleString()} / ${activeCount.toLocaleString()} 종목`} value={coveredCount < activeCount ? '수집 중' : '완료'} /><Divider /><ListRow title="다음 전략 평가" subtitle={data.nextEvaluationAt ? formatDateTime(data.nextEvaluationAt) : '다음 거래 세션 미확정'} /></Surface>
    <Button label={refreshing ? '최신화 요청 중…' : '데이터 최신화'} disabled={refreshing} onPress={() => { void refresh(); }} />
    {__DEV__ ? <><SectionTitle title="개발 테스트" /><Banner tone="warning" title="랭킹에서 제외되는 로컬 fixture" body="TST001과 SMA(2) 테스트 전략을 만들고, 누를 때마다 false→true 일봉을 추가해 매수 신호 기능을 검증합니다." /><Button kind="secondary" label={testing ? '테스트 신호 준비 중…' : '테스트 매수 신호 만들기'} disabled={testing} onPress={() => { void createTestSignal(); }} /></> : null}
    <AppText variant="caption" tone="muted">공급자 장애 시 임의 데이터로 대체하지 않고 복구될 때까지 fail-closed 상태를 유지합니다.</AppText>
  </Screen>;
}
