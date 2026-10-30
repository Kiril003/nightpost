import { beforeEach, describe, expect, it } from 'vitest';
import { act, fireEvent, render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ChatArea } from '../components/messenger/ChatArea';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, Message } from '../types/messenger';

const GROUP_CHAT = {
  id: 'chat_group_busy',
  title: 'Вечеря',
  avatar: '',
  type: 'group',
  membersCount: 12,
  circle: 'all',
  pinned: false,
  unreadCount: 0,
  messages: [],
} as unknown as Chat;

const DM_CHAT = {
  id: 'chat_dm_peer',
  title: 'Оксана',
  avatar: '',
  type: 'dm',
  membersCount: 2,
  circle: 'all',
  pinned: false,
  unreadCount: 0,
  messages: [],
} as unknown as Chat;

const MESSAGES: Message[] = [
  {
    id: 'm1',
    senderId: 'p1',
    senderName: 'Петро',
    senderAvatar: '',
    timestamp: '14:30',
    sentAt: '2026-03-20T14:30:00Z',
    type: 'text',
    isSelf: false,
    text: 'ok',
  },
  {
    id: 'm2',
    senderId: 'p2',
    senderName: 'Іван',
    senderAvatar: '',
    timestamp: '14:31',
    sentAt: '2026-03-20T14:31:00Z',
    type: 'text',
    isSelf: false,
    text: 'lol',
  },
  {
    id: 'm3',
    senderId: 'p3',
    senderName: 'Марія',
    senderAvatar: '',
    timestamp: '14:31',
    sentAt: '2026-03-20T14:31:30Z',
    type: 'text',
    isSelf: false,
    text: 'ага',
  },
  {
    id: 'm4',
    senderId: 'p4',
    senderName: 'Оксана',
    senderAvatar: '',
    timestamp: '14:32',
    sentAt: '2026-03-20T14:32:00Z',
    type: 'text',
    isSelf: false,
    text: 'Table at 20:30?',
  },
  {
    id: 'm5',
    senderId: 'p1',
    senderName: 'Петро',
    senderAvatar: '',
    timestamp: '14:33',
    sentAt: '2026-03-20T14:33:00Z',
    type: 'text',
    isSelf: false,
    text: '€42 each',
  },
] as unknown as Message[];

const noop = () => {};

const renderChat = (chat: Chat, messages: Message[] = MESSAGES) =>
  render(
    <MemoryRouter>
      <ChatArea
        currentChat={chat}
        messages={messages}
        currentUserId="me"
        onOpenLocation={noop}
        onVotePoll={noop}
        onPayBillShare={noop}
        onAddReaction={noop}
        onReplyMessage={noop}
        selectedMessageIds={[]}
        onToggleSelectMessage={noop}
        isSelectionMode={false}
        isSearching={false}
        onCloseSearch={noop}
      />
    </MemoryRouter>,
  );

beforeEach(() => {
  localStorage.clear();
  useMessengerStore.setState({ chats: [] } as never);
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
});

describe('ChatArea signal fold у жвавій групі', () => {
  it('показує рядок згортки з лічильником, лишає важливі листи й розгортає за натиском', () => {
    const { container, getByTestId } = renderChat(GROUP_CHAT);
    const foldRow = getByTestId('fold-row');
    expect(foldRow.textContent).toContain('3');
    expect(container.querySelector('#message-m4')).not.toBeNull();
    expect(container.querySelector('#message-m5')).not.toBeNull();
    expect(container.querySelector('#message-m1')).toBeNull();
    expect(container.querySelector('#message-m2')).toBeNull();
    expect(container.querySelector('#message-m3')).toBeNull();

    fireEvent.click(getByTestId('fold-show'));
    expect(container.querySelector('[data-testid="fold-row"]')).toBeNull();
    expect(container.querySelector('#message-m1')?.textContent).toContain('ok');
    expect(container.querySelector('#message-m2')?.textContent).toContain('lol');
    expect(container.querySelector('#message-m3')?.textContent).toContain('ага');
  });

  it('не згортає ті самі листи в розмові 1:1', () => {
    const { container } = renderChat(DM_CHAT);
    expect(container.querySelector('[data-testid="fold-row"]')).toBeNull();
    expect(container.querySelector('#message-m1')).not.toBeNull();
    expect(container.querySelector('#message-m2')).not.toBeNull();
    expect(container.querySelector('#message-m3')).not.toBeNull();
  });

  it('не згортає групу, якщо fold:<id> вимкнено в localStorage', () => {
    localStorage.setItem(`fold:${GROUP_CHAT.id}`, 'off');
    const { container } = renderChat(GROUP_CHAT);
    expect(container.querySelector('[data-testid="fold-row"]')).toBeNull();
    expect(container.querySelector('#message-m1')).not.toBeNull();
  });

  it('розгортає серію, щойно автора першого листа додано в Близькі', () => {
    const { container } = renderChat(GROUP_CHAT);
    expect(container.querySelector('[data-testid="fold-row"]')).not.toBeNull();
    act(() => {
      useMessengerStore.setState({
        chats: [{ id: 'dm_p1', type: 'dm', title: 'Петро', peerNodeId: 'p1', circle: 'close', circles: ['close'], messages: [] }],
      } as never);
    });
    expect(container.querySelector('[data-testid="fold-row"]')).toBeNull();
    expect(container.querySelector('#message-m1')).not.toBeNull();
  });

  it('розгорнута серія не переходить в іншу розмову', () => {
    const { container, getByTestId, rerender } = renderChat(GROUP_CHAT);
    fireEvent.click(getByTestId('fold-show'));
    expect(container.querySelector('[data-testid="fold-row"]')).toBeNull();
    const other = { ...GROUP_CHAT, id: `${GROUP_CHAT.id}-2` } as Chat;
    rerender(
      <MemoryRouter>
        <ChatArea
          currentChat={other}
          messages={MESSAGES}
          currentUserId="me"
          onOpenLocation={noop}
          onVotePoll={noop}
          onPayBillShare={noop}
          onAddReaction={noop}
          onReplyMessage={noop}
          selectedMessageIds={[]}
          onToggleSelectMessage={noop}
          isSelectionMode={false}
          isSearching={false}
          onCloseSearch={noop}
        />
      </MemoryRouter>,
    );
    expect(container.querySelector('[data-testid="fold-row"]')).not.toBeNull();
  });
});
