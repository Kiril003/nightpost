import React from 'react';
import { act, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MessageRow } from '../components/messenger/feed/MessageRow';
import * as feedActionsMod from '../components/messenger/feed/feedActions';
import type { FeedRow } from '../components/messenger/feed/feedModel';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, Message } from '../types/messenger';

const makeQueuedMsg = (id: string, text: string): Message => ({
  id,
  senderId: 'me',
  senderName: 'Я',
  senderAvatar: '',
  timestamp: '12:01',
  type: 'text',
  text,
  isSelf: true,
  status: 'queued',
});

const makeFeedRow = (msg: Message, isFirstInGroup: boolean, isLastInGroup: boolean): FeedRow => ({
  key: msg.id,
  msg,
  isSelf: true,
  isFirstInGroup,
  isLastInGroup,
  isNewSenderTurn: isFirstInGroup,
  showDateDivider: false,
  dateLabel: '',
  contourClass: 'rounded-2xl',
  density: 'short',
  densityClass: 'px-3 py-1.5',
  bareBubble: false,
  photoOnly: false,
  tail: isLastInGroup,
  cardBubble: false,
  cardView: false,
  pending: msg.status === 'queued',
  stored: false,
  failed: false,
  threadCount: 0,
});

const noop = () => {};
const actionsStub = new Proxy(
  {},
  {
    get: () => noop,
  },
);

const FeedWrap: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const Provider = (
    feedActionsMod as unknown as {
      FeedActionsContext?: React.Context<unknown>;
    }
  ).FeedActionsContext?.Provider;
  return Provider ? <Provider value={actionsStub}>{children}</Provider> : <>{children}</>;
};

describe('MessageRow outbox stack (motion-r2b-12)', () => {
  const originalChats = useMessengerStore.getState().chats;
  const originalMatchMedia = window.matchMedia;

  beforeEach(() => {
    const m1 = makeQueuedMsg('m1', 'Перший у черзі');
    const m2 = makeQueuedMsg('m2', 'Другий у черзі');
    const chat: Chat = {
      id: 'chat-stack',
      title: 'Олена',
      type: 'direct',
      unreadCount: 0,
      circle: 'personal',
      avatar: '',
      messages: [m1, m2],
    };
    useMessengerStore.setState({ chats: [chat] });
  });

  afterEach(() => {
    vi.useRealTimers();
    window.matchMedia = originalMatchMedia;
    useMessengerStore.setState({ chats: originalChats });
  });

  it('renders the second queued row stacked with 2px marginTop and opens on store ack', () => {
    vi.useFakeTimers();

    const chat = useMessengerStore.getState().chats[0];
    const row1 = makeFeedRow(chat.messages[0], true, false);
    const row2 = makeFeedRow(chat.messages[1], false, true);

    const { container } = render(
      <FeedWrap>
        <MessageRow
          row={row1}
          chatId="chat-stack"
          currentUserId="me"
          currentUserName="Я"
          groupish={false}
          direct
          phone={false}
          isSelectionMode={false}
          isSelected={false}
          isHighlighted={false}
          isSearchMatch={false}
          isActiveSearchMatch={false}
          fresh={false}
          expanded={false}
          copied={false}
        />
        <MessageRow
          row={row2}
          chatId="chat-stack"
          currentUserId="me"
          currentUserName="Я"
          groupish={false}
          direct
          phone={false}
          isSelectionMode={false}
          isSelected={false}
          isHighlighted={false}
          isSearchMatch={false}
          isActiveSearchMatch={false}
          fresh={false}
          expanded={false}
          copied={false}
        />
      </FeedWrap>,
    );

    const firstEl = container.querySelector<HTMLElement>('#message-m1')!;
    const secondEl = container.querySelector<HTMLElement>('#message-m2')!;

    expect(firstEl.getAttribute('data-state')).toBe('queued');
    expect(firstEl.hasAttribute('data-outbox-stacked')).toBe(false);
    expect(firstEl.querySelector('.msg-meta-row')).toBeNull();

    expect(secondEl.getAttribute('data-state')).toBe('queued');
    expect(secondEl.hasAttribute('data-outbox-stacked')).toBe(true);
    expect(window.getComputedStyle(secondEl).marginTop).toBe('2px');
    expect(secondEl.querySelector('.msg-meta-row')?.getAttribute('aria-label')).toBe('У черзі: 2');

    vi.advanceTimersByTime(60_000);
    expect(secondEl.getAttribute('data-state')).toBe('queued');
    expect(secondEl.hasAttribute('data-outbox-stacked')).toBe(true);
    expect(window.getComputedStyle(secondEl).marginTop).toBe('2px');

    act(() => {
      useMessengerStore.setState((s) => ({
        chats: s.chats.map((c) =>
          c.id === 'chat-stack'
            ? {
                ...c,
                messages: c.messages.map((m) => (m.id === 'm2' ? { ...m, status: 'sent' } : m)),
              }
            : c,
        ),
      }));
    });

    expect(secondEl.getAttribute('data-state')).toBe('open');
    expect(secondEl.hasAttribute('data-outbox-stacked')).toBe(false);
  });

  it('disables transition when prefers-reduced-motion is active', () => {
    window.matchMedia = vi.fn().mockImplementation((query: string) => ({
      matches: query === '(prefers-reduced-motion: reduce)',
      media: query,
      onchange: null,
      addListener: noop,
      removeListener: noop,
      addEventListener: noop,
      removeEventListener: noop,
      dispatchEvent: () => false,
    }));

    const chat = useMessengerStore.getState().chats[0];
    const row2 = makeFeedRow(chat.messages[1], false, true);

    const { container } = render(
      <FeedWrap>
        <MessageRow
          row={row2}
          chatId="chat-stack"
          currentUserId="me"
          currentUserName="Я"
          groupish={false}
          direct
          phone={false}
          isSelectionMode={false}
          isSelected={false}
          isHighlighted={false}
          isSearchMatch={false}
          isActiveSearchMatch={false}
          fresh={false}
          expanded={false}
          copied={false}
        />
      </FeedWrap>,
    );

    const secondEl = container.querySelector<HTMLElement>('#message-m2')!;
    expect(secondEl.style.transition).toBe('none');
  });
});
