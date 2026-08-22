import React from 'react';
import { fireEvent, render, waitFor } from '@testing-library/react-native';
import { SignalThemeProvider } from '@signal/ui';
import AuthScreen from '../../app/auth';
import { useAppStore } from '@/store/useAppStore';

const mockSignIn = jest.fn(() => Promise.resolve());

jest.mock('expo-router', () => ({
  router: { replace: jest.fn() },
}));

jest.mock('@/providers/AuthProvider', () => ({
  useAuth: () => ({
    configured: true,
    loading: false,
    session: null,
    user: null,
    initializationError: null,
    signIn: mockSignIn,
    signUp: jest.fn(),
    signOut: jest.fn(),
  }),
}));

describe('Supabase auth screen', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    useAppStore.setState({ hasSeenOnboarding: false, trialMode: true });
  });

  it('signs in then finishes onboarding', async () => {
    const view = await render(<SignalThemeProvider mode="light"><AuthScreen /></SignalThemeProvider>);
    await fireEvent.changeText(view.getByLabelText('이메일'), 'user@example.com');
    await fireEvent.changeText(view.getByLabelText('비밀번호'), 'secret12');
    await fireEvent.press(view.getByRole('button', { name: '로그인' }));

    await waitFor(() => {
      expect(mockSignIn).toHaveBeenCalledWith('user@example.com', 'secret12');
      expect(jest.requireMock<{ router: { replace: jest.Mock } }>('expo-router').router.replace).toHaveBeenCalledWith('/universe');
    });
    expect(useAppStore.getState()).toMatchObject({ hasSeenOnboarding: true, trialMode: false });
  });
});
