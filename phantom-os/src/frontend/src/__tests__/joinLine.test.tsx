import React from 'react';
import { act, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MessageRow } from '../components/messenger/feed/MessageRow';
import type { FeedRow } from '../components/messenger/feed/feedModel';
import * as feedActionsMod from '../components/messenger/feed/feedActions';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, ChatMember, Message } from '../types/messenger';

(globalThis as Record<string, unknown>).__PHONE_BUILD__ ??= false;

const noop = () => {};
const dummyActions = new Proxy({}, { get: () => noop }) as never;

const FeedWrapper: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const mod = feedActionsMod as Record<string, unknown>;
  const ProviderComp = mod.FeedActionsProvider as
    | React.ComponentType<{ value: unknown; children: React.ReactNode }>
    | undefined;
  if (ProviderComp) {
    return <ProviderComp value={dummyActions}>{children}</ProviderComp>;
  }
  const Ctx = mod.FeedActionsContext as React.Context<unknown> | undefined;
  if (Ctx?.Provider) {
    return <Ctx.Provider value={dummyActions}>{children}</Ctx.Provider>;
  }
  return <>{children}</>;
};

const makeJoinRow = (targetId: string, overrides: Partial<Message> = {}): FeedRow =>
  ({
    msg: {
      id: `join-${targetId || 'none'}`,
      senderId: 'adder-1',
      senderName: 'Іван',
      senderAvatar: '',
      type: 'system',
      systemAct: 'add',
      systemActTarget: targetId,
      text: '',
      timestamp: '12:00',
      status: 'sent',
      ...overrides,
    },
    isSelf: false,
    isFirstInGroup: true,
    isLastInGroup: true,
    isNewSenderTurn: true,
    showDateDivider: false,
    dateLabel: '',
    bareBubble: false,
    photoOnly: false,
    cardBubble: false,
    cardView: false,
    pending: false,
    stored: false,
    failed: false,
    threadCount: 0,
    density: 'short',
    contourClass: '',
    densityClass: '',
  }) as FeedRow;

const makeDmChat = (peerId: string, title: string, overrides: Partial<Chat> = {}): Chat => ({
  id: `dm-${peerId}`,
  peerNodeId: peerId,
  title,
  avatar: '',
  type: 'direct',
  circle: 'all',
  unreadCount: 0,
  messages: [],
  ...overrides,
});

const makeMember = (id: string, name: string, overrides: Partial<ChatMember> = {}): ChatMember => ({
  id,
  name,
  handle: id,
  avatar: '',
  role: 'member',
  ...overrides,
});

const makeGroupChat = (members: ChatMember[], overrides: Partial<Chat> = {}): Chat => ({
  id: 'group-1',
  title: 'Група',
  avatar: '',
  type: 'group',
  circle: 'all',
  unreadCount: 0,
  members,
  messages: [],
  ...overrides,
});

const renderRow = (row: FeedRow) =>
  render(
    <FeedWrapper>
      <MessageRow
        row={row}
        chatId="group-1"
        currentUserId="me"
        currentUserName="Я"
        groupish={true}
        direct={false}
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
    </FeedWrapper>,
  );

describe('MessageRow join-line', () => {
  beforeEach(() => {
    localStorage.clear();
    useMessengerStore.setState({ chats: [] });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('renders join-line with close tag for a peer in Close using DM title', () => {
    useMessengerStore.setState({
      chats: [makeDmChat('anna', 'Анна', { circle: 'close' })],
    });
    const { getByTestId, container } = renderRow(makeJoinRow('anna'));

    const line = getByTestId('join-line');
    expect(line.textContent).toContain('Анна у групі');
    expect(line.textContent).not.toContain('Іван');

    const tag = container.querySelector('[data-join-tag="close"]');
    expect(tag).not.toBeNull();
    expect(tag?.textContent).toContain('у вашому Близькому');
  });

  it('renders join-line with verified tag when ChatMember.verified is true', () => {
    useMessengerStore.setState({
      chats: [makeGroupChat([makeMember('taras', 'Тарас', { verified: true })])],
    });
    const { getByTestId, container } = renderRow(makeJoinRow('taras'));

    const line = getByTestId('join-line');
    expect(line.textContent).toContain('Тарас у групі');

    const tag = container.querySelector('[data-join-tag="verified"]');
    expect(tag).not.toBeNull();
    expect(tag?.textContent).toContain('перевірено');
  });

  it('renders join-line with newKey tag when DM contactKeyChangedAt is within 30 days', () => {
    const recentIso = new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString();
    useMessengerStore.setState({
      chats: [makeDmChat('x9', 'Максим', { contactKeyChangedAt: recentIso })],
    });
    const { getByTestId, container } = renderRow(makeJoinRow('x9'));

    const line = getByTestId('join-line');
    expect(line.textContent).toContain('Максим у групі');

    const tag = container.querySelector('[data-join-tag="newKey"]');
    expect(tag).not.toBeNull();
    expect(tag?.textContent).toContain('новий ключ');
  });

  it('renders join-line without tag for an unverified group member and no join-line when target is missing', () => {
    useMessengerStore.setState({
      chats: [makeGroupChat([makeMember('stranger-1', 'Олег')])],
    });
    const { getByTestId, container, unmount } = renderRow(makeJoinRow('stranger-1'));

    const line = getByTestId('join-line');
    expect(line.textContent).toContain('Олег у групі');
    expect(container.querySelector('[data-join-tag]')).toBeNull();
    unmount();

    const missingTarget = renderRow(makeJoinRow('', { systemActTarget: undefined }));
    expect(missingTarget.queryByTestId('join-line')).toBeNull();
  });

  it('re-renders join-line when the joiner circle changes in useMessengerStore', () => {
    useMessengerStore.setState({
      chats: [makeDmChat('anna', 'Анна', { circle: 'all' })],
    });
    const { getByTestId, container } = renderRow(makeJoinRow('anna'));

    expect(getByTestId('join-line').textContent).toBe('Анна у групі');
    expect(container.querySelector('[data-join-tag]')).toBeNull();

    act(() => {
      useMessengerStore.setState({
        chats: [makeDmChat('anna', 'Анна', { circle: 'close' })],
      });
    });

    expect(getByTestId('join-line').textContent).toContain('Анна у групі');
    expect(getByTestId('join-line').textContent).toContain('у вашому Близькому');
    expect(container.querySelector('[data-join-tag="close"]')).not.toBeNull();
  });
});
