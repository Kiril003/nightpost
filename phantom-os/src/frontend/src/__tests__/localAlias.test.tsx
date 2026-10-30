/**
 * Перейменований контакт — одне ім'я на всьому екрані (A52, 10.10): рядок і шапка
 * казали «Daniel», а підпис у групі й картка зустрічі — «Kyrylo».
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ChatArea } from '../components/messenger/ChatArea';
import { MeetupCard } from '../components/messenger/meetup/MeetupCard';
import { useMessengerStore } from '../stores/messengerStore';
import { localNameOf } from '../services/localName';
import type { Chat, Message } from '../types/messenger';
import { AT, ME, OLENA, installBridge, meetupMsg, removeBridge } from './meetupFixtures';

const noop = () => {};
const dm = (peer: string, title: string) =>
  ({ id: `dm-${peer}`, type: 'dm', title, peerNodeId: peer, avatar: '', unreadCount: 0, messages: [] }) as unknown as Chat;
const group = { id: 'g1', title: 'Friday pho', avatar: '', type: 'group', unreadCount: 0, messages: [] } as unknown as Chat;

let prev: ReturnType<typeof useMessengerStore.getState>;
beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});
beforeEach(() => {
  prev = useMessengerStore.getState();
});
afterEach(() => {
  cleanup();
  removeBridge();
  vi.useRealTimers();
  useMessengerStore.setState(prev, true);
});

describe('локальне ім\'я', () => {
  it('назва розмови з людиною перемагає ім\'я з її профілю', () => {
    expect(localNameOf([dm('p1', 'Daniel')], 'p1', 'Kyrylo')).toBe('Daniel');
    expect(localNameOf([dm('p2', 'Daniel')], 'p1', 'Kyrylo')).toBe('Kyrylo');
    expect(localNameOf([], null, 'Kyrylo')).toBe('Kyrylo');
  });

  it('підпис відправника в групі — перейменоване ім\'я', () => {
    useMessengerStore.setState({ chats: [dm('p1', 'Daniel'), group] });
    const msg = { id: 'a1', senderId: 'p1', senderName: 'Kyrylo', senderAvatar: '', timestamp: '16:40', type: 'text', text: 'who is in', isSelf: false, status: 'sent' } as unknown as Message;
    const { container } = render(
      <MemoryRouter>
        <ChatArea
          currentChat={group}
          messages={[msg]}
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
    const label = container.querySelector('[data-msg-sender]') as HTMLElement;
    expect(label.textContent).toBe('Daniel');
  });

  it('рядок людей на картці зустрічі — перейменоване ім\'я', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(AT - 2 * 3600_000));
    installBridge(() => undefined);
    const msg = meetupMsg({});
    useMessengerStore.setState({
      chats: [dm(OLENA, 'Lena'), { id: 'grp', type: 'group', title: 'Cafe X · 19:00', messages: [msg], unreadCount: 0 } as never],
      activeChatId: 'grp',
      currentUser: { ...useMessengerStore.getState().currentUser, id: ME, name: 'Я' },
    });
    render(<MeetupCard msg={msg} meId={ME} />);
    const people = screen.getByTestId('meetup-people');
    expect(within(people).getByText(/Lena/)).toBeTruthy();
    expect(people.textContent).not.toContain('Олена');
  });
});
