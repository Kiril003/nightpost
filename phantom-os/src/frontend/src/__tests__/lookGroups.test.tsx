/**
 * LOOK-2910, групи/канали/простори: мій допис у каналі — допис каналу (зліва,
 * без «моєї» бульбашки); обличчя групи в інфо — квадрат, як у списку; одна
 * вкладка — заголовок без риски; кількість учасників простору — тим самим словом,
 * що в групі; порожня розмова простору — «повідомлень», не «дописів».
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ChatArea } from '../components/messenger/ChatArea';
import { InfoTabs } from '../components/messenger/InfoView';
import { GroupProfileSheet, people } from '../components/messenger/GroupProfileSheet';
import { messengerApi } from '../services/messengerApi';
import { useMessengerStore } from '../stores/messengerStore';
import { tNow } from '../components/chat/tNow';
import type { Chat, Message } from '../types/messenger';

const chat = (extra: Partial<Chat> = {}): Chat =>
  ({ id: 'g1', title: 'Двір', type: 'group', circle: 'all', avatar: '', unreadCount: 0, pinned: false, messages: [], ...extra }) as unknown as Chat;
const channel = () => chat({ groupSettings: { kind: 'channel' } as Chat['groupSettings'], myRole: 'owner' });

const mine = (id: string, text: string): Message =>
  ({ id, senderId: 'me', senderName: 'Я', senderAvatar: '', timestamp: '12:25', type: 'text', text, isSelf: true, status: 'sent', sentAt: '2026-10-28T10:25:00Z' }) as Message;
const noop = () => {};
const feed = (c: Chat) =>
  render(
    <MemoryRouter>
      <ChatArea
        currentChat={c}
        messages={[mine('m1', 'Воду вимкнуть у четвер')]}
        currentUserId="me"
        onOpenLocation={noop} onVotePoll={noop} onPayBillShare={noop} onAddReaction={noop} onReplyMessage={noop}
        selectedMessageIds={[]} onToggleSelectMessage={noop} isSelectionMode={false} isSearching={false} onCloseSearch={noop}
      />
    </MemoryRouter>,
  );

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

let prev: ReturnType<typeof useMessengerStore.getState>;
beforeEach(() => {
  prev = useMessengerStore.getState();
  vi.spyOn(messengerApi, 'groupMembers').mockRejectedValue(new Error('down'));
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  useMessengerStore.setState(prev, true);
});

describe('мій допис у каналі', () => {
  it('стоїть зліва як допис каналу, без кольору моєї бульбашки', () => {
    const { container } = feed(channel());
    const row = container.querySelector('[data-post]')?.closest('.message-row') as HTMLElement;
    expect(row).toBeTruthy();
    expect(row.className).toMatch(/\bjustify-start\b/);
    expect(container.querySelector('.msg-bubble-self')).toBeNull();
    expect(container.querySelector('.msg-bubble-other')).toBeTruthy();
  });

  it('у звичайній групі мій лист лишається справа, моєю бульбашкою', () => {
    const { container } = feed(chat());
    expect(container.querySelector('.msg-bubble-self')).toBeTruthy();
  });
});

describe('інфо групи', () => {
  it('обличчя групи — квадрат із заокругленням, як у списку розмов', () => {
    const c = chat({ members: [{ id: 'me', name: 'Я', role: 'owner' }] as Chat['members'] });
    useMessengerStore.setState({ chats: [c] } as never);
    render(<GroupProfileSheet chat={c} onClose={noop} onSelectMember={noop} onTogglePinChat={noop} />);
    const face = screen.getByTestId('group-profile-photo').firstElementChild as HTMLElement;
    expect(face).toBeTruthy();
    expect(face.className).toMatch(/\brounded-card\b/);
    expect(face.className).not.toMatch(/\brounded-full\b/);
  });

  it('склад: дії — за «⋮», не за шевроном; ролі одним стовпцем; себе без «Ви · Ви»', () => {
    const c = chat({
      members: [
        { id: 'me', name: 'Я', role: 'owner', isMe: true },
        { id: 'p2', name: 'Оксана', role: 'admin', mayRemove: true },
      ] as Chat['members'],
    });
    useMessengerStore.setState({ chats: [c], currentUser: { id: 'me', name: '', avatar: '' } } as never);
    render(<GroupProfileSheet chat={c} onClose={noop} onSelectMember={noop} onTogglePinChat={noop} />);
    const list = screen.getByRole('button', { name: 'Дії над учасником' }).closest('ul') as HTMLElement;
    expect(list.querySelector('.lucide-chevron-down')).toBeNull();
    expect(screen.getByRole('button', { name: 'Дії над учасником' }).querySelector('.lucide-ellipsis-vertical, .lucide-more-vertical')).toBeTruthy();
    expect(list.querySelectorAll('[data-testid="member-trail-spacer"]')).toHaveLength(1);
    expect(within(list).getAllByText('Ви')).toHaveLength(1);
  });

  it('коло групи — рядок списку інфо, а не картка-поле', () => {
    const c = chat({ circle: 'friends', members: [{ id: 'me', name: 'Я', role: 'owner', isMe: true }] as Chat['members'] });
    useMessengerStore.setState({ chats: [c] } as never);
    render(<GroupProfileSheet chat={c} onClose={noop} onSelectMember={noop} onTogglePinChat={noop} />);
    const row = screen.getByTestId('circle-row');
    expect(row.closest('[data-testid="info-rows"]')).toBeTruthy();
    expect(row.textContent).toContain('Друзі');
    expect(row.textContent).toContain('Розділ');
    expect(row.textContent).not.toContain('Хто це');
  });

  it('одна вкладка — без риски перемикача; дві — з нею', () => {
    const { container, rerender } = render(<InfoTabs tabs={[{ key: 'members', label: 'Учасники', count: 4 }]} active="members" onChange={noop} />);
    expect(container.querySelector('[data-tab-mark]')).toBeNull();
    expect(screen.getByRole('tablist').className).not.toMatch(/\bborder-b\b/);
    rerender(
      <InfoTabs
        tabs={[{ key: 'posts', label: 'Дописи', count: 2 }, { key: 'members', label: 'Підписники', count: 4 }]}
        active="posts"
        onChange={noop}
      />,
    );
    expect(container.querySelectorAll('[data-tab-mark]')).toHaveLength(1);
    expect(screen.getByRole('tablist').className).toMatch(/\bborder-b\b/);
  });
});

describe('простір', () => {
  it('кількість учасників — тим самим словом, що в групі', () => {
    const t = tNow as unknown as Parameters<typeof people>[1];
    expect(people(2, t, 'uk')).toBe('учасники');
    expect(people(7, t, 'uk')).toBe('учасників');
  });

  it('порожня розмова простору — «повідомлень», канал — «дописів»', () => {
    expect(tNow('space.emptyChat')).toBe('Повідомлень ще немає');
    expect(tNow('space.emptyChannel')).toBe('Дописів ще немає');
  });

});
