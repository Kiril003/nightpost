import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';

const pulseMock = vi.fn();

vi.mock('../services/messengerApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/messengerApi')>();
  return {
    ...actual,
    messengerApi: {
      ...actual.messengerApi,
      pulse: (...args: unknown[]) => pulseMock(...args),
      peerProfiles: vi.fn().mockResolvedValue([]),
    },
  };
});

import { MessageActionSheet, type MessageSheetHandlers } from '../components/messenger/feed/MessageActionSheet';
import { RemindersSheet } from '../components/messenger/today/RemindersSheet';
import { useLiving } from '../services/livingPulse';
import { disarmReminders, readLetterReminders, type LetterReminder } from '../services/letterReminders';
import type { Chat, Message } from '../types/messenger';

const handlers: MessageSheetHandlers = {
  reply: vi.fn(),
  copyText: vi.fn(),
  forward: vi.fn(),
  edit: vi.fn(),
  react: vi.fn(),
  openReactionPicker: vi.fn(),
  speak: vi.fn(),
  openCanvas: vi.fn(),
  toast: vi.fn(),
  translate: vi.fn(),
  summarize: vi.fn(),
  actionItems: vi.fn(),
  togglePin: vi.fn(),
  save: vi.fn(),
  select: vi.fn(),
  inspect: vi.fn(),
  remove: vi.fn(),
};

const incomingMsg: Message = {
  id: 'msg-1',
  senderId: 'peerA',
  senderName: 'Олена',
  senderAvatar: 'О',
  text: 'Принеси книгу',
  timestamp: '14:20',
  status: 'sent',
  isSelf: false,
  type: 'text',
};

const directChat: Chat = {
  id: 'peerA',
  title: 'Олена',
  type: 'direct',
  avatar: 'О',
  unreadCount: 0,
  circle: 'close',
  messages: [],
};

describe('nearSnooze UI', () => {
  beforeEach(() => {
    localStorage.clear();
    disarmReminders();
    pulseMock.mockReset();
    useLiving.setState({ pulse: null, faces: {} });
  });

  afterEach(() => {
    cleanup();
    disarmReminders();
    useLiving.setState({ pulse: null, faces: {} });
    localStorage.clear();
  });

  it('shows «Коли зустрінемось» disabled while pulse is null, then enables when peerA is in pulse.near and stores a near reminder on click', async () => {
    let resolvePulse!: (val: unknown) => void;
    pulseMock.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolvePulse = resolve;
        }),
    );

    render(
      <MessageActionSheet
        msg={incomingMsg}
        currentChat={directChat}
        currentUserId="me"
        speaking={false}
        onClose={vi.fn()}
        on={handlers}
      />,
    );

    expect(useLiving.getState().pulse).toBeNull();
    fireEvent.click(screen.getByText('Ще'));
    const initialLabel = screen.getByText('Коли зустрінемось');
    const initialBtn = initialLabel.closest('button') as HTMLButtonElement;
    expect(initialBtn).toBeTruthy();
    expect(initialBtn.disabled).toBe(true);

    fireEvent.click(initialBtn);
    expect(readLetterReminders()).toHaveLength(0);

    const nearPayload = {
      near: [{ peer_id: 'peerA', near: 'far', at_ms: Date.now(), updated_at_ms: Date.now() }],
    };
    pulseMock.mockResolvedValue(nearPayload);
    await act(async () => {
      resolvePulse(nearPayload);
      await Promise.resolve();
    });

    const enabledBtn = screen.getByText('Коли зустрінемось').closest('button') as HTMLButtonElement;
    expect(enabledBtn.disabled).toBe(false);
    fireEvent.click(enabledBtn);

    const stored = readLetterReminders();
    expect(stored).toHaveLength(1);
    expect(stored[0].trigger).toBe('near');
    expect(stored[0].peer).toBe('peerA');
  });

  it('keeps «Коли зустрінемось» disabled while pulse is pending/null and hides it when pulse.near is empty', async () => {
    let resolvePulse!: (val: unknown) => void;
    pulseMock.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolvePulse = resolve;
        }),
    );

    render(
      <MessageActionSheet
        msg={incomingMsg}
        currentChat={directChat}
        currentUserId="me"
        speaking={false}
        onClose={vi.fn()}
        on={handlers}
      />,
    );

    expect(useLiving.getState().pulse).toBeNull();
    fireEvent.click(screen.getByText('Ще'));
    const pendingBtn = screen.getByText('Коли зустрінемось').closest('button') as HTMLButtonElement;
    expect(pendingBtn.disabled).toBe(true);
    fireEvent.click(pendingBtn);
    expect(readLetterReminders()).toHaveLength(0);

    pulseMock.mockResolvedValue({ near: [] });
    await act(async () => {
      resolvePulse({ near: [] });
      await Promise.resolve();
    });
    expect(screen.queryByText('Коли зустрінемось')).toBeNull();
  });

  it('renders nearItems in RemindersSheet with «При зустрічі» and conditional «Не зустрілись» button', () => {
    const onDidntMeet = vi.fn();
    const firedItem: LetterReminder = {
      id: 'near_fired',
      trigger: 'near',
      peer: 'peerA',
      at: Date.now() + 86_400_000,
      who: 'Олена',
      text: 'Віддати ключі',
      chatId: 'peerA',
      firedDay: '2025-10-01',
    };

    const { rerender } = render(
      <RemindersSheet
        items={[]}
        nearItems={[firedItem]}
        onSnooze={vi.fn()}
        onCancel={vi.fn()}
        onDidntMeet={onDidntMeet}
        onCancelNear={vi.fn()}
        onClose={vi.fn()}
      />,
    );

    expect(screen.getByText('При зустрічі')).toBeTruthy();
    const didntBtn = screen.getByTestId('near-didnt-meet');
    fireEvent.click(didntBtn);
    expect(onDidntMeet).toHaveBeenCalledWith('near_fired');

    const unfiredItem: LetterReminder = {
      ...firedItem,
      id: 'near_unfired',
      firedDay: undefined,
    };

    rerender(
      <RemindersSheet
        items={[]}
        nearItems={[unfiredItem]}
        onSnooze={vi.fn()}
        onCancel={vi.fn()}
        onDidntMeet={onDidntMeet}
        onCancelNear={vi.fn()}
        onClose={vi.fn()}
      />,
    );

    expect(screen.getByText('При зустрічі')).toBeTruthy();
    expect(screen.queryByTestId('near-didnt-meet')).toBeNull();
  });
});
