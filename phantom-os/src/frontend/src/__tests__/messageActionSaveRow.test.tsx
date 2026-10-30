import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MessageActionSheet, type MessageSheetHandlers } from '../components/messenger/feed/MessageActionSheet';
import { SAVED_NOTES_ID } from '../services/messengerApi';
import { tNow } from '../components/chat/tNow';
import type { Chat, Message } from '../types/messenger';

vi.mock('../services/livingPulse', () => ({
  listenLiving: () => () => {},
  useLiving: <T,>(f: (s: { pulse: null }) => T) => f({ pulse: null }),
}));

afterEach(() => {
  cleanup();
});

const baseMsg: Message = {
  id: 'msg_1',
  senderId: 'peer_1',
  senderName: 'Олена',
  senderAvatar: '',
  timestamp: '12:00',
  type: 'text',
  text: 'Нотатка для збереження',
  isSelf: false,
};

const dmChat: Chat = {
  id: 'chat_dm_1',
  title: 'Олена',
  avatar: '',
  type: 'dm',
  circle: 'friends',
  unreadCount: 0,
  messages: [baseMsg],
};

const makeHandlers = (): MessageSheetHandlers => ({
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
});

describe('MessageActionSheet save to Saved row', () => {
  it('renders saveNotes.action in more menu and calls on.save with message', () => {
    const on = makeHandlers();
    render(
      <MessageActionSheet
        msg={baseMsg}
        currentChat={dmChat}
        currentUserId="me"
        speaking={false}
        onClose={vi.fn()}
        on={on}
      />,
    );

    const moreBtn = document.body.querySelector<HTMLButtonElement>('[data-msg-more="closed"]');
    expect(moreBtn).not.toBeNull();
    fireEvent.click(moreBtn!);

    const saveBtn = screen.getByRole('button', { name: tNow('saveNotes.action') });
    fireEvent.click(saveBtn);

    expect(on.save).toHaveBeenCalledTimes(1);
    expect(on.save).toHaveBeenCalledWith(baseMsg);
  });

  it('hides save row when currentChat is SAVED_NOTES_ID', () => {
    const on = makeHandlers();
    const savedChat: Chat = { ...dmChat, id: SAVED_NOTES_ID };
    render(
      <MessageActionSheet
        msg={baseMsg}
        currentChat={savedChat}
        currentUserId="me"
        speaking={false}
        onClose={vi.fn()}
        on={on}
      />,
    );

    const moreBtn = document.body.querySelector<HTMLButtonElement>('[data-msg-more="closed"]');
    fireEvent.click(moreBtn!);

    expect(screen.queryByRole('button', { name: tNow('saveNotes.action') })).toBeNull();
  });

  it('hides save row when message is deleted', () => {
    const on = makeHandlers();
    const deletedMsg: Message = { ...baseMsg, isDeleted: true };
    render(
      <MessageActionSheet
        msg={deletedMsg}
        currentChat={dmChat}
        currentUserId="me"
        speaking={false}
        onClose={vi.fn()}
        on={on}
      />,
    );

    const moreBtn = document.body.querySelector<HTMLButtonElement>('[data-msg-more="closed"]');
    fireEvent.click(moreBtn!);

    expect(screen.queryByRole('button', { name: tNow('saveNotes.action') })).toBeNull();
  });
});
