import React from 'react';
import { Alert } from 'react-native';
import { fireEvent, render, waitFor } from '@testing-library/react-native';
import { SignalThemeProvider } from '@signal/ui';
import RankingDetailScreen from '../../app/ranking/[id]';
import StrategyDetailScreen from '../../app/strategy/[id]';
import { combinations } from '@/data/mock';
import { useAppStore } from '@/store/useAppStore';

let mockRouteId = 'combo-1';
const mockReplace = jest.fn();
const mockPush = jest.fn();

jest.mock('expo-router', () => ({
  router: { replace: mockReplace, push: mockPush },
  useLocalSearchParams: () => ({ id: mockRouteId }),
}));

jest.mock('@/services/connected-api', () => ({
  connectedApiEnabled: false,
  copyRemoteCombination: jest.fn(),
  remoteStrategyRuleLabel: jest.fn(() => 'Mock 조건'),
  reviseRemoteStrategy: jest.fn(),
}));

jest.mock('@/hooks/useRemoteApiReady', () => ({
  useRemoteApiReady: () => false,
}));

const renderScreen = (screen: React.ReactNode) => render(
  <SignalThemeProvider mode="light">{screen}</SignalThemeProvider>,
);

describe('ranking strategy copy detail flow', () => {
  beforeEach(() => {
    useAppStore.getState().resetDemo();
    mockRouteId = 'combo-1';
    mockReplace.mockReset();
    mockPush.mockReset();
    jest.spyOn(Alert, 'alert').mockImplementation(() => undefined);
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it('copies a ranked strategy and renders its detail without a snapshot loop', async () => {
    const combination = combinations.find((item) => item.id === mockRouteId);
    expect(combination).toBeDefined();

    const ranking = await renderScreen(<RankingDetailScreen />);
    await fireEvent.press(ranking.getByRole('button', { name: '내 전략으로 복사' }));

    await waitFor(() => expect(useAppStore.getState().strategies).toHaveLength(3));
    await waitFor(() => expect(Alert.alert).toHaveBeenCalledWith('내 전략으로 복사했어요', expect.any(String)));
    const copied = useAppStore.getState().strategies.find((item) => item.name === `${combination!.name} 복사본`);
    expect(copied).toMatchObject({
      name: `${combination!.name} 복사본`,
      locked: false,
      public: false,
    });

    await ranking.unmount();
    mockRouteId = copied!.id;
    const detail = await renderScreen(<StrategyDetailScreen />);
    expect(detail.getByText(`${combination!.name} 복사본`)).toBeOnTheScreen();
    expect(detail.getByRole('button', { name: '새 버전으로 수정' })).toBeOnTheScreen();
  });
});
