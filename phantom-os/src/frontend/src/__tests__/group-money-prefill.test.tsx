import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { GroupMoneySheet } from '../components/messenger/solana/GroupMoneySheet';
import { useMessengerStore } from '../stores/messengerStore';
import { uk } from '../i18n/messages/uk';
import { formatAmount, toUnits } from '../services/meetup/split';

describe('GroupMoneySheet prefill', () => {
  beforeEach(() => {
    useMessengerStore.setState({
      currentUser: { id: 'me', name: 'Я', handle: 'me', status: 'online' } as never,
      activeChatId: 'grp-1',
      chats: [
        {
          id: 'grp-1',
          name: 'Похід',
          type: 'group',
          members: [
            { id: 'me', name: 'Я', isMe: true },
            { id: 'peer-1', name: 'Олена' },
            { id: 'peer-2', name: 'Тарас' },
          ],
          messages: [],
          unreadCount: 0,
        } as never,
      ],
    });
  });

  afterEach(() => {
    cleanup();
  });

  it('seeds token and total on the request step and formats the footer button', () => {
    render(
      <GroupMoneySheet
        chatId="grp-1"
        prefill={{ token: 'SOL', total: '2.5' }}
        onClose={vi.fn()}
      />,
    );

    fireEvent.click(within(screen.getByTestId('group-money-request')).getByRole('button'));

    expect(screen.getByTestId('group-coin-SOL').getAttribute('aria-pressed')).toBe('true');
    expect((screen.getByTestId('group-request-total') as HTMLInputElement).value).toBe('2.5');

    const expectedFooter = uk['money.requestAmount'].replace(
      '{amount}',
      formatAmount(toUnits('2.5', 'SOL'), 'SOL'),
    ).replace(/\s+/g, ' ');
    expect(screen.getByText(expectedFooter)).toBeTruthy();
  });

  it('seeds both request and pay notes from prefill.note and keeps empty defaults without prefill', () => {
    render(
      <GroupMoneySheet
        chatId="grp-1"
        prefill={{ note: '150 грн' }}
        onClose={vi.fn()}
      />,
    );

    fireEvent.click(within(screen.getByTestId('group-money-request')).getByRole('button'));
    expect((screen.getByTestId('group-request-note') as HTMLInputElement).value).toBe('150 грн');

    cleanup();

    render(
      <GroupMoneySheet
        chatId="grp-1"
        prefill={{ note: '150 грн' }}
        onClose={vi.fn()}
      />,
    );

    fireEvent.click(within(screen.getByTestId('group-money-pay')).getByRole('button'));
    expect((screen.getByTestId('group-pay-note') as HTMLInputElement).value).toBe('150 грн');

    cleanup();

    render(<GroupMoneySheet chatId="grp-1" onClose={vi.fn()} />);
    fireEvent.click(within(screen.getByTestId('group-money-request')).getByRole('button'));
    expect(screen.getByTestId('group-coin-USDC').getAttribute('aria-pressed')).toBe('true');
    expect((screen.getByTestId('group-request-total') as HTMLInputElement).value).toBe('');
    expect((screen.getByTestId('group-request-note') as HTMLInputElement).value).toBe('');

    cleanup();

    render(<GroupMoneySheet chatId="grp-1" onClose={vi.fn()} />);
    fireEvent.click(within(screen.getByTestId('group-money-pay')).getByRole('button'));
    expect((screen.getByTestId('group-pay-note') as HTMLInputElement).value).toBe('');
  });
});
