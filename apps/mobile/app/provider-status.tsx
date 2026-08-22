import React from 'react';
import { StyleSheet, View } from 'react-native';
import { AppText, Banner, Button, Chip, Divider, ListRow, Screen, SectionTitle, Surface, spacing } from '@signal/ui';
import { TitleBlock } from '@/components/common';
import { useProviderHealth } from '@/hooks/useProviderHealth';
import { useAppStore } from '@/store/useAppStore';

export default function ProviderStatusScreen() {
  const mode = useAppStore((state) => state.connectionMode);
  const setMode = useAppStore((state) => state.setConnectionMode);
  const provider = useProviderHealth();
  const healthy = mode === 'online' && provider.isSuccess && provider.data.state === 'CONNECTED';
  const source = provider.data?.source === 'api' ? 'Nest API' : '앱 로컬 Mock';
  return (
    <Screen>
      <TitleBlock eyebrow={`MARKET_DATA_PROVIDER=mock · ${source}`} title={provider.isLoading ? '상태 확인 중' : healthy ? '데이터 정상' : mode === 'offline' ? '오프라인' : mode === 'delayed' ? '데이터 지연' : '공급자 오류'} body="EXPO_PUBLIC_API_URL 설정 시 Nest API 상태를 조회합니다. 미설정 시 고정 seed 로컬 Mock을 씁니다. 실제 증권사·거래소 연결은 없습니다." />
      <Banner tone={healthy ? 'positive' : mode === 'error' || mode === 'offline' ? 'negative' : 'warning'} title={healthy ? '신호 계산 가능' : '신호 계산 중단'} body={healthy ? '8월 14일 완성 일봉 정규화와 결측 검사를 마쳤습니다.' : '오래되거나 누락된 데이터로 새 신호를 만들지 않습니다.'} />
      <Surface style={{ paddingVertical: 0 }}><ListRow title="종목 마스터" subtitle="Mock fixture · KRX 보통주 정책 v12" value="정상" /><Divider /><ListRow title="일봉 저장" subtitle="마지막 2026.08.14 15:30 KST" value={mode === 'delayed' ? '지연' : healthy ? '정상' : '중단'} /><Divider /><ListRow title="지표 캐시" subtitle="indicator-engine v2 · seed 20260815" value={healthy ? '정상' : '대기'} /><Divider /><ListRow title="푸시 outbox" subtitle="개발 기록만 · 원격 자격증명 없음" value="로컬" /></Surface>
      <SectionTitle title="상태 QA" />
      <AppText tone="muted">화면의 빈·로딩·오류·오프라인·지연 상태를 직접 확인할 수 있습니다.</AppText>
      <View style={styles.wrap}><Button label="정상" compact kind={mode === 'online' ? 'primary' : 'secondary'} onPress={() => setMode('online')} /><Button label="지연" compact kind={mode === 'delayed' ? 'primary' : 'secondary'} onPress={() => setMode('delayed')} /><Button label="오프라인" compact kind={mode === 'offline' ? 'primary' : 'secondary'} onPress={() => setMode('offline')} /><Button label="오류" compact kind={mode === 'error' ? 'primary' : 'secondary'} onPress={() => setMode('error')} /></View>
      <SectionTitle title="Mock 재현 항목" />
      <View style={styles.wrap}>{['급등락', '결측', '중복', '순서 역전', '연결 종료', '429', '토큰 만료'].map((label) => <Chip key={label} label={label} />)}</View>
      <AppText variant="caption" tone="muted">실제 공급자는 종목 검색, 구성 이력, 과거 캔들, 현재가/스트림, 상태, 누락 복구, rate limit 정규화 어댑터로 교체합니다.</AppText>
    </Screen>
  );
}

const styles = StyleSheet.create({ wrap: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs } });
