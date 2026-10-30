import { afterEach, beforeAll, describe, expect, it } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import fs from 'node:fs';
import path from 'node:path';
import { ChatArea } from '../components/messenger/ChatArea';
import { ThreadView } from '../components/messenger/ThreadView';
import { useSpaceStore } from '../stores/spaceStore';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, Message } from '../types/messenger';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

const CHAT = {
  id: 'chat_dm',
  title: 'Оксана',
  avatar: '',
  type: 'dm',
  circle: 'all',
  pinned: false,
  unreadCount: 0,
  messages: [],
} as unknown as Chat;

const line = (over: Partial<Message> = {}): Message =>
  ({
    id: 'm1',
    senderId: 'peer',
    senderName: 'Оксана',
    senderAvatar: '',
    timestamp: '14:32',
    sentAt: '2026-09-18T11:32:00.000Z',
    type: 'text',
    text: 'Виїжджаємо за десять хвилин',
    isSelf: false,
    ...over,
  }) as unknown as Message;

const noop = () => {};

describe('edge stretch reachability', () => {
  it('stretches the ChatArea messages scroller firstElementChild when pulled down at top', () => {
    render(
      <MemoryRouter>
        <ChatArea
          currentChat={CHAT}
          messages={[line()]}
          currentUserId="me"
          onOpenLocation={noop}
          onVotePoll={noop}
          onPayBillShare={noop}
          onAddReaction={noop}
          onReplyMessage={noop}
          onEditMessage={noop}
          selectedMessageIds={[]}
          onToggleSelectMessage={noop}
          isSelectionMode={false}
          isSearching={false}
          onCloseSearch={noop}
        />
      </MemoryRouter>,
    );

    const scroller = screen.getByTestId('messages-scroller');
    Object.defineProperty(scroller, 'clientHeight', { value: 800, configurable: true, writable: true });
    Object.defineProperty(scroller, 'scrollHeight', { value: 800, configurable: true, writable: true });
    Object.defineProperty(scroller, 'scrollTop', { value: 0, configurable: true, writable: true });

    fireEvent.touchStart(scroller, { touches: [{ clientY: 100 }] });
    fireEvent.touchMove(scroller, { touches: [{ clientY: 300 }] });

    const firstChild = scroller.firstElementChild as HTMLElement | null;
    expect(firstChild).not.toBeNull();
    expect(firstChild!.style.transform).toContain('scaleY(');
  });

  it('mounts useEdgeStretch in MeScreen, MomentsFeed, and ThreadView', () => {
    const dir = path.resolve(__dirname, '../components/messenger');
    for (const file of ['MeScreen.tsx', 'MomentsFeed.tsx', 'ThreadView.tsx']) {
      const src = fs.readFileSync(path.join(dir, file), 'utf8');
      expect(src).toContain('useEdgeStretch(');
    }
  });
});

describe('edge stretch on the always-mounted ThreadView', () => {
  afterEach(() => {
    cleanup();
    useSpaceStore.getState().openThreadView(null);
  });

  it('stretches the thread feed after the thread is opened from a null view', () => {
    const root = line({ id: 'r1' });
    const chat = { ...CHAT, id: 'c1', messages: [root] } as unknown as Chat;
    useMessengerStore.setState({ chats: [chat], activeChatId: 'c1' });
    const { container } = render(<ThreadView />);
    expect(container.querySelector('.overflow-y-auto')).toBeNull();

    act(() => {
      useSpaceStore.getState().openThreadView({ chatId: 'c1', rootMessageId: 'r1' });
    });
    const feedEl = container.querySelector('.overflow-y-auto') as HTMLElement;
    expect(feedEl).not.toBeNull();
    Object.defineProperty(feedEl, 'clientHeight', { value: 800, configurable: true, writable: true });
    Object.defineProperty(feedEl, 'scrollHeight', { value: 800, configurable: true, writable: true });
    Object.defineProperty(feedEl, 'scrollTop', { value: 0, configurable: true, writable: true });

    fireEvent.touchStart(feedEl, { touches: [{ clientY: 100 }] });
    fireEvent.touchMove(feedEl, { touches: [{ clientY: 300 }] });

    expect((feedEl.firstElementChild as HTMLElement).style.transform).toContain('scaleY(');
  });
});
