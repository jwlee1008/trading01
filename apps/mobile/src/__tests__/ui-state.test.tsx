import React from 'react';
import { fireEvent, render } from '@testing-library/react-native';
import { EmptyState, SignalThemeProvider } from '@signal/ui';

describe('accessible UI state', () => {
  it('renders empty state and invokes recovery action', async () => {
    const onAction = jest.fn();
    const view = await render(<SignalThemeProvider mode="light"><EmptyState title="신호 없음" body="전략을 만드세요." action="전략 만들기" onAction={onAction} /></SignalThemeProvider>);
    expect(view.getByText('신호 없음')).toBeOnTheScreen();
    await fireEvent.press(view.getByRole('button', { name: '전략 만들기' }));
    expect(onAction).toHaveBeenCalledTimes(1);
  });
});
